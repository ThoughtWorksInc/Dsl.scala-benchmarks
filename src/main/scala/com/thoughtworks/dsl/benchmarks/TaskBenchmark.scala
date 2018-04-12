package com.thoughtworks.dsl

import com.thoughtworks.dsl.Dsl.!!

import scala.util.{Success, Try}
import com.thoughtworks.dsl.task._
import com.thoughtworks.dsl.keywords.Shift.implicitShift
import monix.execution.{Cancelable, Scheduler}
import org.openjdk.jmh.annotations._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, SyncVar}
import scala.util.control.NoStackTrace

object benchmarks {

  private def blockingExecuteMonix[A](task: _root_.monix.eval.Task[A])(
      implicit executionContext: ExecutionContext): A = {
    val syncVar = new SyncVar[Try[A]]
    task.runOnComplete(syncVar.put)(Scheduler(executionContext))
    syncVar.take.get
  }

  private def blockingAwaitMonix[A](task: _root_.monix.eval.Task[A]): A = {
    val syncVar = new SyncVar[Try[A]]
    task.runOnComplete(syncVar.put)(Scheduler.trampoline())
    syncVar.take.get
  }

  final class IntException(val n: Int) extends Exception with NoStackTrace

  @State(Scope.Benchmark)
  @Warmup(iterations = 5)
  @Measurement(iterations = 5)
  @Fork(1)
  abstract class BenchmarkState {

    @Param(Array("100", "1000", "10000"))
    var totalLoops: Int = _

    lazy val expectedResult = (0 until totalLoops).sum

  }

  abstract class NonTailRecursion extends BenchmarkState {

    @Benchmark
    def dsl(): Unit = {
      def loop(i: Int = 0): task.Task[Int] = _ {
        if (i < totalLoops) {
          !loop(i + 1) + i
        } else {
          0
        }
      }

      val result = Task.blockingAwait(loop())
      assert(result == expectedResult)
    }

    @Benchmark
    def monix(): Unit = {

      def loop(i: Int = 0): _root_.monix.eval.Task[Int] = {
        if (i < totalLoops) {
          loop(i + 1).map(_ + i)
        } else {
          _root_.monix.eval.Task(0)
        }
      }

      val result = blockingAwaitMonix(loop())
      assert(result == expectedResult)
    }
  }

  @Threads(value = Threads.MAX)
  class MultiThreadNonTailRecursion extends AsyncTask

  @Threads(value = 1)
  class SingleThreadNonTailRecursion extends AsyncTask

  abstract class TailRecursion extends BenchmarkState {

    @Benchmark
    def dsl(): Unit = {
      def loop(i: Int = 0, accumulator: Int = 0): task.Task[Int] = _ {
        if (i < totalLoops) {
          !loop(i + 1, accumulator + i)
        } else {
          accumulator
        }
      }

      val result = Task.blockingAwait(loop())
      assert(result == expectedResult)
    }

    @Benchmark
    def monix(): Unit = {
      def loop(i: Int = 0, accumulator: Int = 0): _root_.monix.eval.Task[Int] = {
        if (i < totalLoops) {
          _root_.monix.eval.Task.suspend(
            loop(i + 1, accumulator + i)
          )
        } else {
          _root_.monix.eval.Task(accumulator)
        }
      }

      val result = blockingAwaitMonix(loop())
      assert(result == expectedResult)

    }

  }

  @Threads(value = Threads.MAX)
  class MultiThreadTailCall extends AsyncTask

  @Threads(value = 1)
  class SingleThreadTailCall extends AsyncTask

  abstract class ExceptionHandling extends BenchmarkState {

    private def error(i: Int): Unit = {
      throw new IntException(i)
    }

    @Benchmark
    def dsl(): Unit = {
      def throwing(i: Int): task.Task[Unit] = _ {
        error(i)
      }
      val tasks: Seq[task.Task[Unit]] = (0 until totalLoops).map(throwing)

      def loop(i: Int = 0, accumulator: Int = 0): task.Task[Int] = _ {
        if (i < totalLoops) {
          val n = try {
            !tasks(i)
            i
          } catch {
            case e: IntException =>
              e.n
          }
          !loop(i + 1, accumulator + n)
        } else {
          accumulator
        }
      }

      val result = Task.blockingAwait(loop())
      assert(result == expectedResult)
    }

    @Benchmark
    def monix(): Unit = {
      def throwing(i: Int): _root_.monix.eval.Task[Unit] = _root_.monix.eval.Task {
        error(i)
      }

      val tasks: Seq[_root_.monix.eval.Task[Unit]] = (0 until totalLoops).map(throwing)

      def loop(i: Int = 0, accumulator: Int = 0): _root_.monix.eval.Task[Int] = {
        if (i < totalLoops) {
          tasks(i)
            .map(Function.const(i))
            .onErrorRecover {
              case e: IntException =>
                e.n
            }
            .flatMap { n =>
              loop(i + 1, accumulator + n)
            }
        } else {
          _root_.monix.eval.Task(accumulator)
        }
      }

      val result = blockingAwaitMonix(loop())
      assert(result == expectedResult)
    }
  }
  @Threads(value = Threads.MAX)
  class MultiThreadExceptionHandling extends AsyncTask

  @Threads(value = 1)
  class SingleThreadExceptionHandling extends AsyncTask

  abstract class AsyncTask extends BenchmarkState {
    @Benchmark
    def dsl(): Unit = {
      import scala.concurrent.ExecutionContext.Implicits.global
      def loop(i: Int = 0, accumulator: Int = 0): task.Task[Int] = _ {
        if (i < totalLoops) {
          !Task.switchExecutionContext(global)
          !loop(i + 1, accumulator + i)
        } else {
          !Task.switchExecutionContext(global)
          accumulator
        }
      }

      val result = Task.blockingAwait(loop())
      assert(result == expectedResult)
    }
    @Benchmark
    def monix(): Unit = {
      def loop(i: Int = 0, accumulator: Int = 0): _root_.monix.eval.Task[Int] = _root_.monix.eval.Task.async[Int] {
        (scheduler, callback) =>
          if (i < totalLoops) {
            loop(i + 1, accumulator + i).runAsync(callback)(scheduler)
          } else {
            _root_.monix.eval.Task(accumulator).runAsync(callback)(scheduler)
          }
      }
      import scala.concurrent.ExecutionContext.Implicits.global
      val result = blockingExecuteMonix(loop())
      assert(result == expectedResult)
    }
  }

  @Threads(value = Threads.MAX)
  class MultiThreadAsyncTask extends AsyncTask

  @Threads(value = 1)
  class SingleThreadAsyncTask extends AsyncTask

}
