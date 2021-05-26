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
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port

object Http4sExampleStreamed extends IOApp with Common {

  def stream[F[_]: Async]: Stream[F, Nothing] = {
    for {
      ep          <- Stream.resource(entryPoint[F])
      finalRoutes  = ep.liftT(NatchezMiddleware.server(routes))
      finalHttpApp = Logger.httpRoutes(true, true)(finalRoutes).orNotFound
      host        <- Stream.eval(Host.fromString("0.0.0.0").liftTo[F](new Throwable("invalid host")))
      port        <- Stream.eval(Port.fromInt(8080).liftTo[F](new Throwable("invalid port")))
      exitCode    <- Stream.resource(
        EmberServerBuilder.default[F]
          .withHost(host)
          .withPort(port)
          .withHttpApp(finalHttpApp)
          .build >>
        Resource.eval(Sync[F].delay(StdIn.readLine("Server is running... Press ENTER key to stop")))
      )
    } yield exitCode
  }.drain

  def run(args: List[String]): IO[ExitCode] =
    stream[IO].compile.drain.as(ExitCode.Success)

}
