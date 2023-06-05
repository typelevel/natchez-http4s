// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s

import cats.data.{ Kleisli, OptionT }
import cats.syntax.all._
import cats.effect.{MonadCancel, Outcome}
import cats.effect.syntax.all._
import Outcome._
import org.http4s.HttpRoutes
import natchez.{Trace, TraceValue, Tags}
import org.http4s.Response
import org.http4s.client.Client
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import cats.effect.Resource

object NatchezMiddleware {
  import syntax.kernel._

  @deprecated("Use NatchezMiddleware.server(routes)", "0.0.3")
  def apply[F[_]: Trace](routes: HttpRoutes[F])(
    implicit ev: MonadCancel[F, Throwable]
  ): HttpRoutes[F] =
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
   * - "cancelled"        -> true // only present in case of cancellation
   */
  def server[F[_]: Trace](routes: HttpRoutes[F])(
    implicit ev: MonadCancel[F, Throwable]
  ): HttpRoutes[F] =
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

      routes(req).guaranteeCase {
        case Canceled() => OptionT.liftF(addRequestFields *> Trace[F].put(("cancelled", TraceValue.BooleanValue(true)), Tags.error(true)))
        case Errored(e) => OptionT.liftF(addRequestFields *> addErrorFields(e))
        case Succeeded(fa) => OptionT.liftF {
          fa.value.flatMap {
            case Some(resp) => addRequestFields *> addResponseFields(resp)
            case None => MonadCancel[F].unit
          }
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
  def client[F[_]: Trace](client: Client[F])(
    implicit ev: MonadCancel[F, Throwable]
  ): Client[F] =
    Client { req =>
      Resource.applyFull {poll =>
        Trace[F].span("http4s-client-request") {
          for {
            knl  <- Trace[F].kernel
            _    <- Trace[F].put(
                      "client.http.uri"    -> req.uri.toString(),
                      "client.http.method" -> req.method.toString
                    )
            reqʹ = req.withHeaders(knl.toHttp4sHeaders ++ req.headers) // prioritize request headers over kernel ones
            rsrc <- poll(client.run(reqʹ).allocatedCase)
            _    <- Trace[F].put("client.http.status_code" -> rsrc._1.status.code.toString())
          } yield rsrc
        }
      }
    }

}
