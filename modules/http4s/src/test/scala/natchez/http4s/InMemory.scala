// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez
package http4s

import cats.data.Kleisli
import cats.effect.{IO, MonadCancelThrow}
import munit.CatsEffectSuite
import org.typelevel.ci.*

trait InMemorySuite
  extends CatsEffectSuite
    with Arbitraries {
  type Lineage = InMemory.Lineage
  val Lineage: InMemory.Lineage.type = InMemory.Lineage
  type NatchezCommand = InMemory.NatchezCommand
  val NatchezCommand: InMemory.NatchezCommand.type = InMemory.NatchezCommand

  trait TraceTest {
    def program[F[_]: MonadCancelThrow: Trace]: F[Unit]
    def expectedHistory: List[(Lineage, NatchezCommand)]
  }

  def traceTest(name: String, tt: TraceTest): Unit = {
    test(s"$name - Kleisli")(
      testTraceKleisli(tt.program[Kleisli[IO, Span[IO], *]](implicitly, _), tt.expectedHistory)
    )
    test(s"$name - IOLocal")(testTraceIoLocal(tt.program[IO](implicitly, _), tt.expectedHistory))
  }

  def testTraceKleisli(
      traceProgram: Trace[Kleisli[IO, Span[IO], *]] => Kleisli[IO, Span[IO], Unit],
      expectedHistory: List[(Lineage, NatchezCommand)]
  ): IO[Unit] = testTrace[Kleisli[IO, Span[IO], *]](
    traceProgram,
    root => IO.pure(Trace[Kleisli[IO, Span[IO], *]] -> (k => k.run(root))),
    expectedHistory
  )

  def testTraceIoLocal(
      traceProgram: Trace[IO] => IO[Unit],
      expectedHistory: List[(Lineage, NatchezCommand)]
  ): IO[Unit] = testTrace[IO](traceProgram, Trace.ioTrace(_).map(_ -> identity), expectedHistory)

  def testTrace[F[_]](
      traceProgram: Trace[F] => F[Unit],
      makeTraceAndResolver: Span[IO] => IO[(Trace[F], F[Unit] => IO[Unit])],
      expectedHistory: List[(Lineage, NatchezCommand)]
  ): IO[Unit] =
    InMemory.EntryPoint.create[IO].flatMap { ep =>
      val traced = ep.root("root").use { r =>
        makeTraceAndResolver(r).flatMap { case (traceInstance, resolve) =>
          resolve(traceProgram(traceInstance))
        }
      }
      traced *> ep.ref.get.map { history =>
        assertEquals(history.toList, expectedHistory)
      }
    }

  val CustomHeaderName = ci"X-Custom-Header"
  val CorrelationIdName = ci"X-Correlation-Id"

}
