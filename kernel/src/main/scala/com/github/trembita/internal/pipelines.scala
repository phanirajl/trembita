package com.github.trembita.internal

import scala.language.higherKinds
import cats._
import cats.data.Nested
import cats.effect._
import cats.implicits._
import com.github.trembita._

import scala.collection.generic.CanBuildFrom
import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try

/**
  * A [[DataPipelineT]]
  * that was mapped
  *
  * @tparam A - type of source pipeline elements
  * @tparam B - type of pipeline elements after f application
  * @param f      - transformation function
  * @param source - a pipeline that f was applied on
  **/
protected[trembita] class MappingPipelineT[F[_], +A, B, Ex <: Execution](
  f: A => B,
  source: DataPipelineT[F, A, Ex]
)(F: Monad[F])
    extends DataPipelineT[F, B, Ex] {

  /** Each next map will compose [[f]] with some other map function */
  def map[C](f2: B => C)(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new MappingPipelineT[F, A, C, Ex](f2.compose(f), source)(F)

  /** Returns [[FlatMapPipelineT]] */
  def flatMap[C](
    f2: B => DataPipelineT[F, C, Ex]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](a => f2(f(a)), source)(F)

  /** Returns [[FlatMapPipelineT]] with filter function applied */
  def filter(p: B => Boolean)(implicit F: Monad[F]): DataPipelineT[F, B, Ex] =
    new FlatMapPipelineT[F, A, B, Ex](
      a => new StrictSource(Some(f(a)).filter(p).toIterator.pure[F], F),
      source
    )(F)

  /** Returns [[FlatMapPipelineT]] with [[PartialFunction]] applied */
  def collect[C](
    pf: PartialFunction[B, C]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](
      a => new StrictSource(Some(f(a)).collect(pf).toIterator.pure[F], F),
      source
    )(F)

  def mapM[C](f2: B => F[C])(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new MapMonadicPipelineT[F, A, C, Ex](f2.compose(f), source)(F)

  def mapG[C, G[_]](
    f2: B => G[C]
  )(implicit funcK: G ~> F): DataPipelineT[F, C, Ex] =
    new MapMonadicPipelineT[F, A, C, Ex](a => funcK(f2(f(a))), source)(F)

  def handleError[BB >: B](
    f2: Throwable => BB
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, BB, Ex] =
    new HandleErrorPipelineT[F, A, BB, Ex](f, f2, source)(F)

  def handleErrorWith[C >: B](
    f2: Throwable => DataPipelineT[F, C, Ex]
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex]({ a =>
      try {
        val b = List(f(a))
        new StrictSource(b.toIterator.pure[F], F)
      } catch {
        case e: Throwable => f2(e)
      }
    }, source)(F)

  //  def zip[C](that: DataPipeline[F, C, Ex]): DataPipeline[(B, C), F, T, Ex] =
  //    new MappingPipeline[(A, C), (B, C), F, T, Ex]({ case (a, b) => (f(a), b) }, source.zip(that))

  protected[trembita] def evalFunc[C >: B](Ex: Ex): F[Ex.Repr[C]] =
    F.map(source.evalFunc[A](Ex))(vs => Ex.Monad.map(vs)(f))
}

/**
  * A [[DataPipelineT]]
  * that was flatMapped
  *
  * @tparam A - type of source pipeline elements
  * @tparam B - type of pipeline elements after f application
  * @param f      - transformation function
  * @param source - a pipeline that f was applied on
  **/
protected[trembita] class FlatMapPipelineT[F[_], +A, B, Ex <: Execution](
  f: A => DataPipelineT[F, B, Ex],
  source: DataPipelineT[F, A, Ex]
)(F: Monad[F])
    extends DataPipelineT[F, B, Ex] {

  /** Each next map will compose [[f]] with some other map function */
  def map[C](f2: B => C)(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](f(_).map(f2), source)(F)

  /** Each next flatMap will compose [[f]] with some other map function */
  def flatMap[C](
    f2: B => DataPipelineT[F, C, Ex]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](f(_).flatMap(f2), source)(F)

  /** Filters the result of [[f]] application */
  def filter(
    p: B => Boolean
  )(implicit F: Monad[F]): FlatMapPipelineT[F, A, B, Ex] =
    new FlatMapPipelineT[F, A, B, Ex](f(_).filter(p), source)(F)

  /** Applies a [[PartialFunction]] to the result of [[f]] */
  def collect[C](
    pf: PartialFunction[B, C]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](f(_).collect(pf), source)(F)

  def mapM[C](f2: B => F[C])(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](f(_).mapM(f2), source)(F)

  def mapG[C, G[_]](
    f2: B => G[C]
  )(implicit funcK: G ~> F): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](f(_).mapG(f2), source)(F)

  def handleError[BB >: B](
    f2: Throwable => BB
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, BB, Ex] =
    new FlatMapPipelineT[F, A, BB, Ex]({ a =>
      f(a).handleError(f2)
    }, source)(F)

  def handleErrorWith[C >: B](
    f2: Throwable => DataPipelineT[F, C, Ex]
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex]({ a =>
      try f(a)
      catch {
        case e: Throwable => f2(e)
      }
    }, source)(F)

  protected[trembita] def evalFunc[C >: B](Ex: Ex): F[Ex.Repr[C]] =
    F.flatMap(source.evalFunc[A](Ex)) { vs =>
      val evaluated = Ex.Monad.map(vs)(f(_).evalFunc[B](Ex))

      val res =
        F.map(Ex.Traverse.sequence[F, Ex.Repr[B]](evaluated)(F))(
          Ex.Monad.flatten(_)
        )

      res.asInstanceOf[F[Ex.Repr[C]]]
    }
}

class CollectPipelineT[F[_], +A, B, Ex <: Execution](
  pf: PartialFunction[A, B],
  source: DataPipelineT[F, A, Ex]
)(F: Monad[F])
    extends DataPipelineT[F, B, Ex] {
  def map[C](f2: B => C)(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new CollectPipelineT[F, A, C, Ex](pf.andThen(f2), source)(F)

  /** Returns [[FlatMapPipelineT]] */
  def flatMap[C](
    f2: B => DataPipelineT[F, C, Ex]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, B, C, Ex](f2, this)(F)

  /** Returns [[FlatMapPipelineT]] with filter function applied */
  def filter(p: B => Boolean)(implicit F: Monad[F]): DataPipelineT[F, B, Ex] =
    new FlatMapPipelineT[F, A, B, Ex](
      a =>
        new StrictSource(List(a).collect(pf).filter(p).toIterator.pure[F], F),
      source
    )(F)

  /** Returns [[FlatMapPipelineT]] with [[PartialFunction]] applied */
  def collect[C](
    pf2: PartialFunction[B, C]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new CollectPipelineT[F, A, C, Ex](pf.andThen(pf2), source)(F)

  def mapM[C](f2: B => F[C])(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](
      a =>
        new StrictSource[F, C, Ex]({
          Some(a).collect(pf) match {
            case None    => F.pure[Iterator[C]](Iterator.empty)
            case Some(b) => f2(b).map(List(_).toIterator)
          }
        }, F),
      source
    )(F)

  def mapG[C, G[_]](
    f2: B => G[C]
  )(implicit funcK: G ~> F): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](
      a =>
        new StrictSource[F, C, Ex]({
          Some(a).collect(pf) match {
            case None    => F.pure[Iterator[C]](Iterator.empty)
            case Some(b) => F.map(funcK(f2(b)))(List(_).toIterator)
          }
        }, F),
      source
    )(F)

  def handleError[BB >: B](
    f2: Throwable => BB
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, BB, Ex] =
    new CollectPipelineT[F, A, BB, Ex]({
      case a if pf.isDefinedAt(a) =>
        try pf(a)
        catch {
          case e: Throwable => f2(e)
        }
    }, source)(F)

  def handleErrorWith[C >: B](
    f: Throwable => DataPipelineT[F, C, Ex]
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex]({ a =>
      try {
        val b = List(a).collect(pf)
        new StrictSource(b.toIterator.pure[F], F)
      } catch {
        case e: Throwable => f(e)
      }
    }, source)(F)

  protected[trembita] def evalFunc[C >: B](Ex: Ex): F[Ex.Repr[C]] =
    F.map(source.evalFunc[A](Ex))(Ex.collect(_)(pf))
}

protected[trembita] class HandleErrorPipelineT[F[_], +A, B, Ex <: Execution](
  f: A => B,
  fallback: Throwable => B,
  source: DataPipelineT[F, A, Ex]
)(F: MonadError[F, Throwable])
    extends DataPipelineT[F, B, Ex] {

  def map[C](f2: B => C)(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new MappingPipelineT[F, A, C, Ex]({ a =>
      val b = try f(a)
      catch {
        case e: Throwable => fallback(e)
      }
      f2(b)
    }, source)(F)

  def flatMap[C](
    f2: B => DataPipelineT[F, C, Ex]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, B, C, Ex](f2, this)(F)

  def filter(p: B => Boolean)(implicit F: Monad[F]): DataPipelineT[F, B, Ex] =
    new CollectPipelineT[F, B, B, Ex]({ case b if p(b) => b }, this)(F)

  def collect[C](
    pf: PartialFunction[B, C]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new CollectPipelineT[F, B, C, Ex](pf, this)(F)

  def mapM[C](f2: B => F[C])(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new MapMonadicPipelineT[F, A, C, Ex]({ a =>
      this.F.handleError(a.pure[F].map(f))(fallback).flatMap(f2)
    }, source)(F)

  def mapG[C, G[_]](
    f2: B => G[C]
  )(implicit funcK: G ~> F): DataPipelineT[F, C, Ex] =
    new MapMonadicPipelineT[F, A, C, Ex]({ a =>
      F.flatMap(F.handleError(F.map(F.pure(a))(f))(fallback))(b => funcK(f2(b)))
    }, source)(F)

  def handleError[BB >: B](
    f2: Throwable => BB
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, BB, Ex] =
    new HandleErrorPipelineT[F, A, BB, Ex]({ a =>
      try f(a)
      catch {
        case e: Throwable => fallback(e)
      }
    }, f2, source)(F)

  def handleErrorWith[C >: B](
    f2: Throwable => DataPipelineT[F, C, Ex]
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex]({ a =>
      try {
        val b = List(
          try f(a)
          catch {
            case e: Throwable => fallback(e)
          }
        )
        new StrictSource(b.toIterator.pure[F], F)
      } catch {
        case e: Throwable => f2(e)
      }
    }, source)(F)

  protected[trembita] def evalFunc[C >: B](Ex: Ex): F[Ex.Repr[C]] =
    F.map(
      source
        .evalFunc[A](Ex)
    )(Ex.Monad.map(_) { a =>
      try f(a)
      catch {
        case e: Throwable => fallback(e)
      }
    })
}

protected[trembita] class MapMonadicPipelineT[F[_], +A, B, Ex <: Execution](
  f: A => F[B],
  source: DataPipelineT[F, A, Ex]
)(F: Monad[F])
    extends DataPipelineT[F, B, Ex] {

  /** Each next map will compose [[f]] with some other map function */
  def map[C](f2: B => C)(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new MapMonadicPipelineT[F, A, C, Ex](a => f(a).map(f2), source)(F)

  /** Returns [[FlatMapPipelineT]] */
  def flatMap[C](
    f2: B => DataPipelineT[F, C, Ex]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, B, C, Ex](f2, this)(F)

  /** Returns [[FlatMapPipelineT]] with filter function applied */
  def filter(p: B => Boolean)(implicit F: Monad[F]): DataPipelineT[F, B, Ex] =
    new FlatMapPipelineT[F, A, B, Ex](
      a =>
        new StrictSource(f(a).map { b =>
          List(b).filter(p).toIterator
        }, F),
      source
    )(F)

  /** Returns [[FlatMapPipelineT]] with [[PartialFunction]] applied */
  def collect[C](
    pf: PartialFunction[B, C]
  )(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new FlatMapPipelineT[F, A, C, Ex](
      a =>
        new StrictSource(f(a).map { b =>
          List(b).collect(pf).toIterator
        }, F),
      source
    )(F)

  def mapM[C](f2: B => F[C])(implicit F: Monad[F]): DataPipelineT[F, C, Ex] =
    new MapMonadicPipelineT[F, A, C, Ex](a => f(a).flatMap(f2), source)(F)

  def mapG[C, G[_]](
    f2: B => G[C]
  )(implicit funcK: G ~> F): DataPipelineT[F, C, Ex] =
    new MapMonadicPipelineT[F, A, C, Ex](
      a => F.flatMap(f(a))(b => funcK(f2(b))),
      source
    )(F)

  def handleError[BB >: B](
    f2: Throwable => BB
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, BB, Ex] =
    new MapMonadicPipelineT[F, A, BB, Ex](
      a => f(a).asInstanceOf[F[BB]].handleError(f2),
      source
    )(F)

  def handleErrorWith[C >: B](
    f2: Throwable => DataPipelineT[F, C, Ex]
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, C, Ex] =
    new MapMonadicPipelineT[F, A, DataPipelineT[F, C, Ex], Ex]({ a =>
      f(a)
        .map { b =>
          new StrictSource[F, B, Ex](List(b).toIterator.pure[F], F)
            .asInstanceOf[DataPipelineT[F, C, Ex]]
        }
        .handleError(f2)
    }, source)(F).flatten

  protected[trembita] def evalFunc[C >: B](Ex: Ex): F[Ex.Repr[C]] =
    F.flatMap(source.evalFunc[A](Ex)) { vs =>
      val resultF: F[Ex.Repr[C]] = Ex.Traverse.sequence[F, C](Ex.Monad.map(vs) {
        a =>
          f(a).asInstanceOf[F[C]]
      })(F)
      resultF
    }
}

protected[trembita] class BridgePipelineT[F[_], +A, Ex1 <: Execution, Ex2 <: Execution](
  source: DataPipelineT[F, A, Ex1],
  ex2: Ex2
)(Ex1: Ex1, F: Monad[F])
    extends SeqSource[F, A, Ex2](F) {
  def handleError[B >: A](
    f: Throwable => B
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, B, Ex2] =
    new BridgePipelineT(source.handleError(f), ex2)(Ex1, F)

  def handleErrorWith[B >: A](
    f: Throwable => DataPipelineT[F, B, Ex2]
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, B, Ex2] =
    new BridgePipelineT(
      source.handleErrorWith[B](
        e => new BridgePipelineT[F, B, Ex2, Ex1](f(e), Ex1)(ex2, F)
      ),
      ex2
    )(Ex1, F)

  protected[trembita] def evalFunc[B >: A](Ex: Ex2): F[Ex.Repr[B]] =
    F.map(
      source
        .evalFunc[A](Ex1)
    )(vs => Ex.fromVector(Ex1.toVector(vs.asInstanceOf[Ex1.Repr[A]])))
}

/**
  * [[DataPipelineT]] subclass
  * with basic operations implemented:
  *
  * [[DataPipelineT.map]]      ~> [[MappingPipelineT]]
  * [[DataPipelineT.flatMap]]  ~> [[FlatMapPipelineT]]
  **/
protected[trembita] abstract class SeqSource[F[_], +A, Ex <: Execution](
  F: Monad[F]
) extends DataPipelineT[F, A, Ex] {
  def map[B](f: A => B)(implicit F: Monad[F]): DataPipelineT[F, B, Ex] =
    new MappingPipelineT[F, A, B, Ex](f, this)(F)

  def flatMap[B](
    f: A => DataPipelineT[F, B, Ex]
  )(implicit F: Monad[F]): DataPipelineT[F, B, Ex] =
    new FlatMapPipelineT[F, A, B, Ex](f, this)(F)

  def filter(p: A => Boolean)(implicit F: Monad[F]): DataPipelineT[F, A, Ex] =
    new CollectPipelineT[F, A, A, Ex]({ case a if p(a) => a }, this)(F)

  def collect[B](
    pf: PartialFunction[A, B]
  )(implicit F: Monad[F]): DataPipelineT[F, B, Ex] =
    new CollectPipelineT[F, A, B, Ex](pf, this)(F)

  def mapM[B](f: A => F[B])(implicit F: Monad[F]): DataPipelineT[F, B, Ex] =
    new MapMonadicPipelineT[F, A, B, Ex](f, this)(F)

  def mapG[B, G[_]](
    f2: A => G[B]
  )(implicit funcK: G ~> F): DataPipelineT[F, B, Ex] =
    new MapMonadicPipelineT[F, A, B, Ex](a => funcK(f2(a)), this)(F)
}

/**
  * Concrete implementation of [[DataPipelineT]]
  * wrapping by-name [[Iterable]]
  *
  * @tparam A - type of pipeline elements
  * @param iterF - not evaluated yet collection of [[A]]
  **/
protected[trembita] class StrictSource[F[_], +A, Ex <: Execution](
  iterF: => F[Iterator[A]],
  F: Monad[F]
) extends SeqSource[F, A, Ex](F) {
  def handleError[B >: A](
    f: Throwable => B
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, B, Ex] =
    new StrictSource[F, B, Ex](
      iterF.map { iterator =>
        new Iterator[B] {
          private var failed: Boolean = false

          def hasNext: Boolean = !failed && iterator.hasNext

          def next(): B =
            try iterator.next()
            catch {
              case e: Throwable =>
                failed = true
                f(e)
            }
        }
      },
      F
    )

  def handleErrorWith[B >: A](
    f: Throwable => DataPipelineT[F, B, Ex]
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, B, Ex] =
    new StrictSource[F, DataPipelineT[F, B, Ex], Ex](
      iterF.map { iterator =>
        new Iterator[DataPipelineT[F, B, Ex]] {
          private var failed: Boolean = false

          def hasNext: Boolean = !failed && iterator.hasNext

          def next(): DataPipelineT[F, B, Ex] =
            try {
              val res = iterator.next()
              new StrictSource(List(res).toIterator.pure[F], F)
            } catch {
              case e: Throwable =>
                failed = true
                f(e)
            }
        }
      },
      F
    ).flatten

  /**
    * Forces evaluation of [[DataPipelineT]]
    * collecting data into [[Iterable]]
    *
    * @return - collected data
    **/
  protected[trembita] def evalFunc[B >: A](Ex: Ex): F[Ex.Repr[B]] =
    F.map(iterF)(iter => Ex.fromVector(iter.toVector))
}

protected[trembita] class MemoizedPipelineT[F[_], +A, Ex <: Execution](
  vsF: F[Vector[A]],
  F: Monad[F]
) extends SeqSource[F, A, Ex](F) {
  def handleError[B >: A](f: Throwable => B)(
    implicit F: MonadError[F, Throwable]
  ): DataPipelineT[F, B, Ex] = this

  def handleErrorWith[B >: A](f: Throwable => DataPipelineT[F, B, Ex])(
    implicit F: MonadError[F, Throwable]
  ): DataPipelineT[F, B, Ex] = this

  protected[trembita] def evalFunc[B >: A](Ex: Ex): F[Ex.Repr[B]] =
    F.map(vsF)(Ex.fromVector(_))
}

/**
  * A [[DataPipelineT]] been sorted
  *
  * @tparam A - type of pipeline elements (requires [[Ordering]] and [[ClassTag]])
  * @param source - source pipeline to be sorted
  **/
protected[trembita] class SortedPipelineT[+A: Ordering, F[_], Ex <: Execution](
  source: DataPipelineT[F, A, Ex],
  ex: Ex,
  F: Monad[F]
) extends SeqSource[F, A, Ex](F) {
  def handleError[B >: A](
    f: Throwable => B
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, B, Ex] =
    new SortedPipelineT[A, F, Ex](
      source
        .handleError(f)
        .asInstanceOf[DataPipelineT[F, A, Ex]],
      ex,
      F
    )

  def handleErrorWith[B >: A](
    f: Throwable => DataPipelineT[F, B, Ex]
  )(implicit F: MonadError[F, Throwable]): DataPipelineT[F, B, Ex] =
    new SortedPipelineT[A, F, Ex](
      source
        .handleErrorWith(f)
        .asInstanceOf[DataPipelineT[F, A, Ex]],
      ex,
      F
    )

  protected[trembita] def evalFunc[B >: A](Ex: Ex): F[Ex.Repr[B]] =
    F.map(source.evalFunc[A](Ex)) { vs =>
      Ex.sorted(vs).asInstanceOf[Ex.Repr[B]]
    }
}
