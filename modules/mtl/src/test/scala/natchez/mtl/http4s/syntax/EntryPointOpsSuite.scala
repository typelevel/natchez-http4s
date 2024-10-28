// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.mtl.http4s.syntax

import cats.arrow.FunctionK
import cats.effect.{IO, IOLocal, MonadCancelThrow}
import cats.mtl.Local
import cats.syntax.all.*
import cats.Applicative
import munit.{Location, ScalaCheckEffectSuite, TestOptions}
import natchez.Span.Options.Defaults
import natchez.Span.Options.SpanCreationPolicy.{Coalesce, Default, Suppress}
import natchez.Span.SpanKind.Server
import natchez.http4s.InMemorySuite
import natchez.http4s.syntax.kernel.*
import natchez.{EntryPoint, InMemory, Kernel, Span, Trace}
import natchez.mtl.*
import org.http4s.dsl.Http4sDsl
import org.http4s.syntax.all.*
import org.http4s.{Header, Headers, HttpApp, HttpRoutes, Method, Request}
import org.scalacheck.effect.PropF

class EntryPointOpsSuite
  extends InMemorySuite
    with ScalaCheckEffectSuite {

  testLift("liftRoutes uses the kernel from the request to continue or create a new trace") { implicit local: Local[IO, Span[IO]] =>
    _.liftRoutes(httpRoutes[IO], _).orNotFound
  }

  testLift("liftApp uses the kernel from the request to continue or create a new trace") { implicit local: Local[IO, Span[IO]] =>
    _.liftApp(httpRoutes[IO].orNotFound, _)
  }

  testLift("liftHttp uses the kernel from the request to continue or create a new trace") { implicit local: Local[IO, Span[IO]] =>
    _.liftHttp(httpRoutes[IO].orNotFound, _, FunctionK.id)
  }

  private def testLift(options: TestOptions)
                      (body: Local[IO, Span[IO]] => (EntryPoint[IO], HttpTracingOptions[IO]) => HttpApp[IO])
                      (implicit loc: Location): Unit =
    test(options) {
      PropF.forAllNoShrinkF { (kernel: Kernel, maybeSpanOptions: Option[Span.Options]) =>
        val request = Request[IO](
          method = Method.GET,
          uri = uri"/hello/some-name",
          headers = Headers(
            Header.Raw(CustomHeaderName, "external"),
            Header.Raw(CorrelationIdName, "id-123")
          ) ++ kernel.toHttp4sHeaders
        )

        val expectedHistory = {
          val requestKernel = Kernel(
            Map(CustomHeaderName -> "external", CorrelationIdName -> "id-123")
          ) |+| kernel

          maybeSpanOptions.foldl {
            List(
              (Lineage.Root, NatchezCommand.CreateRootSpan("GET /hello/some-name", requestKernel, maybeSpanOptions.getOrElse(Defaults.withSpanKind(Server)))),
              (Lineage.Root("GET /hello/some-name"), NatchezCommand.CreateSpan("trace request handling", None, Span.Options.Defaults)),
              (Lineage.Root("GET /hello/some-name"), NatchezCommand.ReleaseSpan("trace request handling")),
              (Lineage.Root, NatchezCommand.ReleaseRootSpan("GET /hello/some-name"))
            )
          } { (acc, options) =>
            if (options.spanCreationPolicy == Default) acc
            else
              acc.filter {
                case (Lineage.Root("root"), _) => options.spanCreationPolicy == Suppress || options.spanCreationPolicy == Coalesce
                case _ => false
              }
          }
        }

        InMemory.EntryPoint.create[IO]
          .flatMap { ep =>
            IOLocal(Span.noop[IO])
              .map(EntryPointOpsSuite.catsMtlEffectLocalForIO(_))
              .flatMap {
                body(_)(ep, maybeSpanOptions.foldl(HttpTracingOptions[IO])(_ withSpanOptions _))
                  .run(request)
              }
              .flatMap(_ => ep.ref.get)
              .map { history =>
                assertEquals(history.toList, expectedHistory)
              }
          }
      }
    }

  private def httpRoutes[F[_] : MonadCancelThrow : Trace]: HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {
      case GET -> Root / "hello" / "some-name" =>
        Trace[F].span("trace request handling")(Ok())
    }
  }

}

object EntryPointOpsSuite {
  // TODO should this be added to natchez-mtl until https://github.com/typelevel/cats-effect/issues/3385 is merged?
  def catsMtlEffectLocalForIO[E](implicit ioLocal: IOLocal[E]): Local[IO, E] =
    new Local[IO, E] {
      override def local[A](fa: IO[A])(f: E => E): IO[A] =
        ioLocal.get.flatMap { initial =>
          ioLocal.set(f(initial)) >> fa.guarantee(ioLocal.set(initial))
        }

      override def applicative: Applicative[IO] = IO.asyncForIO

      override def ask[E2 >: E]: IO[E2] = ioLocal.get
    }
}
