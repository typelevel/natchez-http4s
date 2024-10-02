// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez
package http4s

import cats.data.{Chain, Kleisli}
import cats.effect.{IO, MonadCancelThrow}
import munit.CatsEffectSuite
import natchez.Span.Options.SpanCreationPolicy
import natchez.Span.SpanKind
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary
import org.typelevel.ci.*

trait InMemorySuite extends CatsEffectSuite {
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

  // TODO remove if https://github.com/typelevel/natchez/pull/1071 is merged
  implicit val arbTraceValue: Arbitrary[TraceValue] = Arbitrary {
    Gen.oneOf(
      arbitrary[String].map(TraceValue.StringValue(_)),
      arbitrary[Boolean].map(TraceValue.BooleanValue(_)),
      arbitrary[Number].map(TraceValue.NumberValue(_)),
    )
  }

  // TODO remove if https://github.com/typelevel/natchez/pull/1071 is merged
  implicit val arbAttribute: Arbitrary[(String, TraceValue)] = Arbitrary {
    for {
      key <- arbitrary[String]
      value <- arbitrary[TraceValue]
    } yield key -> value
  }

  // TODO remove if https://github.com/typelevel/natchez/pull/1071 is merged
  implicit val arbCIString: Arbitrary[CIString] = Arbitrary {
    Gen.alphaLowerStr.map(CIString(_))
  }

  // TODO remove if https://github.com/typelevel/natchez/pull/1071 is merged
  implicit val arbKernel: Arbitrary[Kernel] = Arbitrary {
    arbitrary[Map[CIString, String]].map(Kernel(_))
  }

  // TODO remove if https://github.com/typelevel/natchez/pull/1071 is merged
  implicit val arbSpanCreationPolicy: Arbitrary[SpanCreationPolicy] = Arbitrary {
    Gen.oneOf(SpanCreationPolicy.Default, SpanCreationPolicy.Coalesce, SpanCreationPolicy.Suppress)
  }

  // TODO remove if https://github.com/typelevel/natchez/pull/1071 is merged
  implicit val arbSpanKind: Arbitrary[SpanKind] = Arbitrary {
    Gen.oneOf(
      SpanKind.Internal,
      SpanKind.Client,
      SpanKind.Server,
      SpanKind.Producer,
      SpanKind.Consumer,
    )
  }

  // TODO remove if https://github.com/typelevel/natchez/pull/1071 is merged
  implicit val arbSpanOptions: Arbitrary[Span.Options] = Arbitrary {
    for {
      parentKernel <- arbitrary[Option[Kernel]]
      spanCreationPolicy <- arbitrary[SpanCreationPolicy]
      spanKind <- arbitrary[SpanKind]
      links <- arbitrary[List[Kernel]].map(Chain.fromSeq)
    } yield {
      links.foldLeft {
        parentKernel.foldLeft {
          Span
            .Options
            .Defaults
            .withSpanKind(spanKind)
            .withSpanCreationPolicy(spanCreationPolicy)
        }(_.withParentKernel(_))
      }(_.withLink(_))
    }
  }

  val CustomHeaderName = ci"X-Custom-Header"
  val CorrelationIdName = ci"X-Correlation-Id"

}
