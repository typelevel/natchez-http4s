// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s

import cats.data.{ Kleisli, OptionT }
import cats.effect.MonadCancel
import cats.implicits._
import org.http4s.HttpRoutes
import natchez.Trace
import natchez.Tags
import scala.util.control.NonFatal
import org.http4s.Response
import org.http4s.client.Client
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import cats.effect.Resource

object NatchezMiddleware {
  import syntax.kernel._

  @deprecated("Use NatchezMiddleware.server(routes)", "0.0.3")
  def apply[F[_]: MonadCancel[*[_], Throwable]: Trace](routes: HttpRoutes[F]): HttpRoutes[F] =
    server(routes)

  /**
   * A middleware that adds the following standard fields to the current span:
   *
   * - "http.method"      -> "GET", "PUT", etc.
   * - "http.url"         -> request URI (not URL)
   * - "http.status_code" -> "200", "403", etc. // why is this a string?
   * - "error"            -> true // only present in case of error
   *
   * In addition the following non-standard fields are added in case of error:
   *
   * - "error.message"    -> Exception message
   * - "error.stacktrace" -> Exception stack trace as a multi-line string
   */
  def server[F[_]: MonadCancel[*[_], Throwable]: Trace](routes: HttpRoutes[F]): HttpRoutes[F] =
    Kleisli { req =>

      val addRequestFields: F[Unit] =
        Trace[F].put(
          Tags.http.method(req.method.name),
          Tags.http.url(req.uri.renderString),
        )

      def addResponseFields(res: Response[F]): F[Unit] =
        Trace[F].put(
          Tags.http.status_code(res.status.code.toString)
        )

      def addErrorFields(e: Throwable): F[Unit] =
        Trace[F].put(
          Tags.error(true),
          "error.message"    -> e.getMessage(),
          "error.stacktrace" -> {
            val baos = new ByteArrayOutputStream
            val fs   = new AnsiFilterStream(baos)
            val ps   = new PrintStream(fs, true, "UTF-8")
            e.printStackTrace(ps)
            ps.close
            fs.close
            baos.close
            new String(baos.toByteArray, "UTF-8")
          }
        )

      OptionT {
        routes(req).onError {
          case NonFatal(e)   => OptionT.liftF(addRequestFields *> addErrorFields(e))
        } .value.flatMap {
          case Some(handler) => addRequestFields *> addResponseFields(handler).as(handler.some)
          case None          => Option.empty[Response[F]].pure[F]
        }
      }
    }

  /**
   * A middleware that adds the current span's kernel to outgoing requests, performs requests in
   * a span called `http4s-client-request`, and adds the following fields to that span.
   *
   * - "client.http.method"      -> "GET", "PUT", etc.
   * - "client.http.uri"         -> request URI
   * - "client.http.status_code" -> "200", "403", etc. // why is this a string?
   *
   */
  def client[F[_]: MonadCancel[*[_], Throwable]: Trace](
    client: Client[F],
  ): Client[F] =
    Client { req =>
      Resource {
        Trace[F].span("http4s-client-request") {
          for {
            knl  <- Trace[F].kernel
            _    <- Trace[F].put(
                      "client.http.uri"    -> req.uri.toString(),
                      "client.http.method" -> req.method.toString
                    )
            reqʹ = req.putHeaders(knl.toHttp4sHeaders.headers)
            rsrc <- client.run(reqʹ).allocated
            _    <- Trace[F].put("client.http.status_code" -> rsrc._1.status.code.toString())
          } yield rsrc
        }
      }
    }

}
