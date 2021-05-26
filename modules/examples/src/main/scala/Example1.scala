// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example


import cats.effect._
import cats.syntax.all._
import com.comcast.ip4s.Port
import natchez.http4s.implicits._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.implicits._
import natchez.http4s.NatchezMiddleware

/**
 * Start up Jaeger thus:
 *
 *  docker run -d --name jaeger \
 *    -e COLLECTOR_ZIPKIN_HTTP_PORT=9411 \
 *    -p 5775:5775/udp \
 *    -p 6831:6831/udp \
 *    -p 6832:6832/udp \
 *    -p 5778:5778 \
 *    -p 16686:16686 \
 *    -p 14268:14268 \
 *    -p 9411:9411 \
 *    jaegertracing/all-in-one:1.8
 *
 * Run this example and do some requests. Go to http://localhost:16686 and select `Http4sExample`
 * and search for traces.
*/
object Http4sExample extends IOApp with Common {

  // Our main app resource
  def server[F[_]: Async]: Resource[F, Server] =
    for {
      ep <- entryPoint[F]
      ap  = ep.liftT(NatchezMiddleware.server(routes)).orNotFound // liftT discharges the Trace constraint
      p  <- Resource.eval(Port.fromInt(8080).liftTo[F](new Throwable("invalid port")))
      sv <- EmberServerBuilder.default[F].withPort(p).withHttpApp(ap).build
    } yield sv

  // Done!
  def run(args: List[String]): IO[ExitCode] =
    server[IO].use(_ => IO.never)

}
