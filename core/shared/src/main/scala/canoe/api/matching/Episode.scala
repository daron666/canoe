package canoe.api.matching

import canoe.api.matching.Episode._
import cats.instances.list._
import cats.syntax.all._
import cats.{~>, ApplicativeError, MonadError, StackSafeMonad}
import fs2.{Pipe, Pull, Stream}

/**
  * Type which represents a description of sequence of elements.
  *
  * Such description can be applied to the actual sequence/stream of elements
  * to find the subsequences which match the description.
  *
  * For partially matched subsequence (such that at least first element was matched)
  * one of 4 possible results is yielded:
  *  - Matched(o)   - when the whole episode was matched as a prefix subsequence of input sequence.
  *                   Contains the result value of type `O`.
  *  - Missed(i)    - when the episode was started (partially matched) but was not fully matched.
  *                   Contains the first element of type `I` which didn't match the description.
  *  - Cancelled(i) - when the episode was cancelled by specific input.
  *                   Contains the input element of type `I` which caused cancellation.
  *  - Failed(e)    - when evaluated program resulted into failure.
  *                   Contains the error (Throwable) which was raised during the evaluation.
  *
  * `Episode` forms a monad in `O` with `pure` and `flatMap`.
  *
  * @tparam F Effect type
  * @tparam I Input elements type
  * @tparam O Result value type
  */
private[api] sealed trait Episode[F[_], -I, +O] {

  /**
    * Pipe which produces a singleton stream if the elements of input stream 
    * match the description of this episode and empty stream otherwise.
    * Fails on the first unhandled error result.
    */
  def matching(implicit F: ApplicativeError[F, Throwable]): Pipe[F, I, O] =
    open(this, _, Nil).flatMap {
      case (Matched(o), _) => Stream(o)
      case (Failed(e), _)  => Stream.raiseError[F](e)
      case _               => Stream.empty
    }

  def flatMap[I2 <: I, O2](fn: O => Episode[F, I2, O2]): Episode[F, I2, O2] =
    Bind(this, fn)

  def map[O2](fn: O => O2): Episode[F, I, O2] = flatMap(fn.andThen(Pure(_)))

  def mapK[G[_]](fn: F ~> G): Episode[G, I, O] = translate(this, fn)
}

object Episode {

  private[api] final case class Pure[F[_], A](a: A) extends Episode[F, Any, A]

  private[api] final case class Next[F[_], A](p: A => Boolean) extends Episode[F, A, A]

  private[api] final case class First[F[_], A](p: A => Boolean) extends Episode[F, A, A]

  private[api] final case class Eval[F[_], A](fa: F[A]) extends Episode[F, Any, A]

  private[api] final case class RaiseError[F[_]](e: Throwable) extends Episode[F, Any, Nothing]

  private[api] final case class Protected[F[_], I, O1, O2 >: O1](episode: Episode[F, I, O1],
                                                                 onError: Throwable => Episode[F, I, O2])
      extends Episode[F, I, O2]

  private[api] final case class Bind[F[_], I, O1, O2](episode: Episode[F, I, O1], fn: O1 => Episode[F, I, O2])
      extends Episode[F, I, O2]

  private[api] final case class Cancellable[F[_], I, O](episode: Episode[F, I, O],
                                                        cancelOn: I => Boolean,
                                                        finalizer: Option[I => F[Unit]])
      extends Episode[F, I, O]

  private[api] final case class Tolerate[F[_], I, O](episode: Episode[F, I, O], limit: Option[Int], fn: I => F[Unit])
      extends Episode[F, I, O]

  private[api] implicit def monadErrorInstance[F[_], I]: MonadError[Episode[F, I, *], Throwable] =
    new MonadError[Episode[F, I, *], Throwable] with StackSafeMonad[Episode[F, I, *]] {

      def pure[A](a: A): Episode[F, I, A] = Pure(a)

      def flatMap[A, B](fa: Episode[F, I, A])(f: A => Episode[F, I, B]): Episode[F, I, B] =
        fa.flatMap(f)

      def handleErrorWith[A](fa: Episode[F, I, A])(f: Throwable => Episode[F, I, A]): Episode[F, I, A] =
        Protected(fa, f)

      def raiseError[A](e: Throwable): Episode[F, I, A] =
        RaiseError(e)
    }

  private sealed trait Result[+I, +O] {
    def map[O1](f: O => O1): Result[I, O1] = this match {
      case Matched(o)          => Matched(f(o))
      case same @ Missed(_)    => same
      case same @ Cancelled(_) => same
      case same @ Failed(_)    => same
    }
  }

  private final case class Matched[O](o: O) extends Result[Nothing, O]
  private final case class Missed[I](elem: I) extends Result[I, Nothing]
  private final case class Cancelled[I](elem: I) extends Result[I, Nothing]
  private final case class Failed(e: Throwable) extends Result[Nothing, Nothing]

  private def open[F[_], I, O](
    episode: Episode[F, I, O],
    input: Stream[F, I],
    cancelTokens: List[(I => Boolean, Option[I => F[Unit]])]
  ): Stream[F, (Result[I, O], Stream[F, I])] =
    episode match {
      case First(p) =>
        def go(in: Stream[F, I]): Pull[F, (Result[I, O], Stream[F, I]), Unit] =
          in.pull.uncons1.attempt.flatMap {
            case Left(e)                          => Pull.output1(Failed(e) -> Stream.empty)
            case Right(Some((a, rest))) if (p(a)) => Pull.output1(Matched(a.asInstanceOf[O]) -> rest)
            case _                                => Pull.done
          }

        go(input).stream

      case Next(p) =>
        def go(in: Stream[F, I]): Pull[F, (Result[I, O], Stream[F, I]), Unit] =
          in.pull.uncons1.attempt.flatMap {
            case Left(e) => Pull.output1(Failed(e) -> Stream.empty)
            case Right(Some((a, rest))) =>
              cancelTokens.collect { case (p, f) if p(a) => f } match {
                case Nil =>
                  if (p(a)) Pull.output1(Matched(a.asInstanceOf[O]) -> rest)
                  else Pull.output1(Missed(a) -> rest)

                case nonEmpty =>
                  nonEmpty
                    .collect { case Some(f) => f }
                    .traverse(f => Pull.eval(f(a))) >>
                    Pull.output1(Cancelled(a) -> rest)
              }

            case Right(None) => Pull.done
          }

        go(input).stream

      case Pure(a) =>
        Stream(a).covary[F].map(Matched(_) -> input)

      case Eval(fa) =>
        Stream
          .eval(fa)
          .map(Matched(_) -> input)
          .handleErrorWith(e => Stream(Failed(e) -> input))

      case RaiseError(e) =>
        Stream(Failed(e) -> input)

      case Protected(episode, onError) =>
        open(episode, input, cancelTokens).flatMap {
          case (Failed(e), rest) => open(onError(e), rest, cancelTokens)
          case other             => Stream(other)
        }

      case Cancellable(episode, p, f) =>
        open(episode, input, (p, f) :: cancelTokens)

      case Tolerate(episode, limit, fn) =>
        limit match {
          case Some(n) if n <= 0 =>
            open(episode, input, cancelTokens)

          case _ =>
            open(episode, input, cancelTokens).flatMap {
              case (Missed(m), rest) =>
                Stream.eval(fn(m)) >> open(
                  Tolerate(episode, limit.map(_ - 1), fn),
                  rest,
                  cancelTokens
                )

              case res => Stream(res)
            }
        }

      case Bind(prev, fn) =>
        Stream(prev)
          .flatMap(ep => open(ep, input, cancelTokens))
          .flatMap {
            // Have to explicitly handle all not matched cases in order to satisfy compile
            case (Matched(a), rest)   => open(fn(a), rest, cancelTokens)
            case (Missed(m), rest)    => Stream(Missed(m) -> rest)
            case (Cancelled(m), rest) => Stream(Cancelled(m) -> rest)
            case (Failed(e), rest)    => Stream(Failed(e) -> rest)
          }
    }

  private def translate[F[_], G[_], I, O](episode: Episode[F, I, O], f: F ~> G): Episode[G, I, O] =
    episode match {
      case Pure(a)                    => Pure(a)
      case Next(p)                    => Next(p).asInstanceOf[Episode[G, I, O]]
      case First(p)                   => First(p).asInstanceOf[Episode[G, I, O]]
      case Eval(fa)                   => Eval(f(fa))
      case RaiseError(e)              => RaiseError(e)
      case Protected(ep, onError)     => Protected(ep.mapK(f), onError.andThen(_.mapK(f)))
      case Bind(ep, fn)               => Bind(ep.mapK(f), fn.andThen(_.mapK(f)))
      case Tolerate(ep, limit, fn)    => Tolerate(ep.mapK(f), limit, fn.andThen(f(_)))
      case Cancellable(ep, canc, fin) => Cancellable(ep.mapK(f), canc, fin.map(_.andThen(f(_))))
    }
}
