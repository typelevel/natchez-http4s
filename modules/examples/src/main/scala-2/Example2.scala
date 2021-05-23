// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats.effect._
import natchez.http4s.NatchezMiddleware
import natchez.http4s.implicits._
import cats.syntax.all._
import fs2.Stream
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.middleware.Logger
import scala.io.StdIn

object Http4sExampleStreamed extends IOApp with Common {

  def stream[F[_]: Concurrent: Timer: ContextShift]: Stream[F, Nothing] = {
    for {
      ep <- Stream.resource(entryPoint[F])
      finalRoutes = ep.liftT(NatchezMiddleware.server(routes))
      finalHttpApp = Logger.httpRoutes(true, true)(finalRoutes).orNotFound

      exitCode <- Stream.resource(
        EmberServerBuilder.default[F]
          .withHost("0.0.0.0")
          .withPort(8080)
          .withHttpApp(finalHttpApp)
          .build >>
        Resource.liftF(Sync[F].delay(StdIn.readLine("Server is running... Press ENTER key to stop")))
      )
    } yield exitCode
  }.drain

  def run(args: List[String]): IO[ExitCode] =
    stream[IO].compile.drain.as(ExitCode.Success)

}
