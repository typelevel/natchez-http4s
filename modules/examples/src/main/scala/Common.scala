// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats._
import cats.effect._
import cats.syntax.all._
import io.jaegertracing.Configuration.ReporterConfiguration
import io.jaegertracing.Configuration.SamplerConfiguration
import natchez._
import natchez.jaeger.Jaeger
import org.http4s.dsl.Http4sDsl
import org.http4s.HttpRoutes

trait Common {

  // A dumb subroutine that does some tracing
  def greet[F[_]: Monad: Trace](input: String) =
    Trace[F].span("greet") {
      for {
        _ <- Trace[F].put("input" -> input)
      } yield s"Hello $input!\n"
    }

  // Our routes, in abstract F with a Trace constraint.
  def routes[F[_]: Defer: Trace](
    implicit ev: MonadError[F, Throwable]
  ): HttpRoutes[F] = {
    object dsl extends Http4sDsl[F]; import dsl._ // bleh
    HttpRoutes.of[F] {

      case GET -> Root / "hello" / name =>
        for {
          str <- greet[F](name)
          res <- Ok(str)
        } yield res

      case GET -> Root / "fail" =>
        ev.raiseError(new RuntimeException("💥 Boom!"))

    }
  }

  // A Jaeger entry point
  def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F](
      system    = "Http4sExample",
      uriPrefix = Some(new java.net.URI("http://localhost:16686")),
    ) { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
         .withReporter(ReporterConfiguration.fromEnv)
         .getTracer
      }
    }
}
