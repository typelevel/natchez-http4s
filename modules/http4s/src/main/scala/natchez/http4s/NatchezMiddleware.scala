// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s

import cats.Applicative
import cats.data.{Ior, Kleisli, OptionT}
import cats.effect.Outcome.*
import cats.effect.syntax.all.*
import cats.effect.{MonadCancel, MonadCancelThrow, Resource}
import cats.syntax.all.*
import natchez.Span.Options.Defaults
import natchez.Span.SpanKind
import natchez.http4s.implicits.*
import natchez.*
import org.http4s.client.Client
import org.http4s.headers.Host
import org.http4s.{HttpRoutes, Request, Response, Uri, headers}

import java.io.{ByteArrayOutputStream, PrintStream}

object NatchezMiddleware {
  import syntax.kernel.*

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
        ) >> Option(e.getMessage).traverse_(m => Trace[F].put("error.message" -> m))

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
  def client[F[_] : Trace : MonadCancelThrow](client: Client[F]): Client[F] =
    NatchezMiddleware.client(client, useOpenTelemetrySemanticConventions = false)

  def client[F[_] : Trace : MonadCancelThrow](client: Client[F],
                                              useOpenTelemetrySemanticConventions: Boolean,
                                             ): Client[F] =
    NatchezMiddleware.client(client, _ => Seq.empty[(String, TraceValue)].pure[F], useOpenTelemetrySemanticConventions)

  /**
   * A middleware that adds the current span's kernel to outgoing requests, performs requests in
   * a span called `http4s-client-request`, and adds the following fields to that span.
   *
   * - "client.http.method"      -> "GET", "PUT", etc.
   * - "client.http.uri"         -> request URI
   * - "client.http.status_code" -> "200", "403", etc. // why is this a string?
   *
   * @param client the `Client[F]` to be enhanced
   * @param additionalAttributes additional attributes to be added to the span
   * @tparam F An effect with instances of `Trace[F]` and `MonadCancelThrow[F]`
   * @return the enhanced `Client[F]`
   */
  def clientWithAttributes[F[_] : Trace : MonadCancelThrow](client: Client[F])
                                                           (additionalAttributes: (String, TraceValue)*): Client[F] =
    NatchezMiddleware.clientWithAttributes(client, useOpenTelemetrySemanticConventions = false)(additionalAttributes *)

  def clientWithAttributes[F[_] : Trace : MonadCancelThrow](client: Client[F],
                                                            useOpenTelemetrySemanticConventions: Boolean,
                                                           )
                                                           (additionalAttributes: (String, TraceValue)*): Client[F] =
    NatchezMiddleware.client(client, (_: Request[F]) => additionalAttributes.pure[F], useOpenTelemetrySemanticConventions)

  /**
   * A middleware that adds the current span's kernel to outgoing requests, performs requests in
   * a span called `http4s-client-request`, and adds the following fields to that span.
   *
   * - "client.http.method"      -> "GET", "PUT", etc.
   * - "client.http.uri"         -> request URI
   * - "client.http.status_code" -> "200", "403", etc. // why is this a string?
   *
   * @param client the `Client[F]` to be enhanced
   * @param additionalAttributes additional attributes to be added to the span
   * @tparam F An effect with instances of `Trace[F]` and `MonadCancelThrow[F]`
   * @return the enhanced `Client[F]`
   */
  def clientWithAttributes[F[_] : Trace : MonadCancelThrow](client: Client[F],
                                                            spanOptions: Span.Options)
                                                           (additionalAttributes: (String, TraceValue)*): Client[F] =
    NatchezMiddleware.clientWithAttributes(client, spanOptions, useOpenTelemetrySemanticConventions = false)(additionalAttributes *)

  def clientWithAttributes[F[_] : Trace : MonadCancelThrow](client: Client[F],
                                                            spanOptions: Span.Options,
                                                            useOpenTelemetrySemanticConventions: Boolean,
                                                           )
                                                           (additionalAttributes: (String, TraceValue)*): Client[F] =
    NatchezMiddleware.client(client, spanOptions, (_: Request[F]) => additionalAttributes.pure[F], useOpenTelemetrySemanticConventions)

  /**
   * A middleware that adds the current span's kernel to outgoing requests, performs requests in
   * a span called `http4s-client-request`, and adds the following fields to that span.
   *
   * - "client.http.method"      -> "GET", "PUT", etc.
   * - "client.http.uri"         -> request URI
   * - "client.http.status_code" -> "200", "403", etc. // why is this a string?
   *
   * @param client the `Client[F]` to be enhanced
   * @param additionalAttributesF a function that takes the `Request[F]` and returns any additional attributes to be added to the span
   * @tparam F An effect with instances of `Trace[F]` and `MonadCancelThrow[F]`
   * @return the enhanced `Client[F]`
   */
  def client[F[_] : Trace : MonadCancelThrow](client: Client[F],
                                              additionalAttributesF: Request[F] => F[Seq[(String, TraceValue)]],
                                             ): Client[F] =
    NatchezMiddleware.client(client, additionalAttributesF, useOpenTelemetrySemanticConventions = false)

  def client[F[_] : Trace : MonadCancelThrow](client: Client[F],
                                              additionalAttributesF: Request[F] => F[Seq[(String, TraceValue)]],
                                              useOpenTelemetrySemanticConventions: Boolean,
                                             ): Client[F] =
    NatchezMiddleware.client(client, Defaults.withSpanKind(SpanKind.Client), additionalAttributesF, useOpenTelemetrySemanticConventions)

  /**
   * A middleware that adds the current span's kernel to outgoing requests, performs requests in
   * a span called `http4s-client-request`, and adds the following fields to that span.
   *
   * - "client.http.method"      -> "GET", "PUT", etc.
   * - "client.http.uri"         -> request URI
   * - "client.http.status_code" -> "200", "403", etc. // why is this a string?
   *
   * @param client the `Client[F]` to be enhanced
   * @param additionalAttributesF a function that takes the `Request[F]` and returns any additional attributes to be added to the span
   * @tparam F An effect with instances of `Trace[F]` and `MonadCancelThrow[F]`
   * @return the enhanced `Client[F]`
   */
  def client[F[_] : Trace : MonadCancelThrow](client: Client[F],
                                              spanOptions: Span.Options,
                                              additionalAttributesF: Request[F] => F[Seq[(String, TraceValue)]],
                                             ): Client[F] =
    NatchezMiddleware.client(client, spanOptions, additionalAttributesF, useOpenTelemetrySemanticConventions = false)

  def client[F[_] : Trace : MonadCancelThrow](client: Client[F],
                                              spanOptions: Span.Options,
                                              additionalAttributesF: Request[F] => F[Seq[(String, TraceValue)]],
                                              useOpenTelemetrySemanticConventions: Boolean,
                                             ): Client[F] =
    Client { req =>
      Resource.applyFull {poll =>
        Trace[F].span(spanName(req, useOpenTelemetrySemanticConventions), spanOptions) {
          for {
            knl  <- Trace[F].kernel
            _    <- Trace[F].put(
                      httpUrlKey(useOpenTelemetrySemanticConventions)    -> req.uri,
                      httpMethodKey(useOpenTelemetrySemanticConventions) -> req.method,
                    )
            _ <- addAttributeFromUriAuthorityOrHeader("server.address")(fromUri = _.host.some, fromHeader = _.host.some)(req)
            _ <- addAttributeFromUriAuthorityOrHeader("server.port")(fromUri = _.port, fromHeader = _.port)(req)
            _ <- req.uri.scheme.asAttribute[F]("url.scheme")
            additionalAttributes <- additionalAttributesF(req)
            _ <- Trace[F].put(additionalAttributes *)
            reqʹ = req.withHeaders(knl.toHttp4sHeaders ++ req.headers) // prioritize request headers over kernel ones
            rsrc <- poll(client.run(reqʹ).allocatedCase)
            _    <- Trace[F].put(httpStatusCodeKey(useOpenTelemetrySemanticConventions) -> rsrc._1.status.code)
          } yield rsrc
        }
      }
    }

  private def addAttributeFromUriAuthorityOrHeader[F[_] : Applicative : Trace, A: TraceableValue, B: TraceableValue](key: String)
                                                                                                                    (fromUri: Uri.Authority => Option[A],
                                                                                                                     fromHeader: headers.Host => Option[B])
                                                                                                                    (req: Request[F]): F[Unit] =
    Ior.fromOptions(req.headers.get[Host].flatMap(fromHeader), req.uri.authority.flatMap(fromUri))
      .map(_.toEither)
      .asAttribute(key)

  private implicit class OptionTraceOps[A](val maybeA: Option[A]) extends AnyVal {
    def asAttribute[F[_] : Applicative : Trace](key: String)(implicit T: TraceableValue[A]): F[Unit] =
      maybeA.traverse_(a => Trace[F].put(key -> a))
  }

  /**
   * See https://opentelemetry.io/docs/specs/semconv/http/http-spans/#http-client-span and
   * https://opentelemetry.io/docs/specs/semconv/registry/attributes/url/#url-full
   */
  private def httpUrlKey(useOpenTelemetrySemanticConventions: Boolean): String =
    if (useOpenTelemetrySemanticConventions) "url.full" else "client.http.uri"

  /**
   * See https://opentelemetry.io/docs/specs/semconv/http/http-spans/#http-client-span and
   * https://opentelemetry.io/docs/specs/semconv/registry/attributes/http/#http-request-method
   */
  private def httpMethodKey(useOpenTelemetrySemanticConventions: Boolean): String =
    if (useOpenTelemetrySemanticConventions) "http.request.method" else "client.http.method"

  /**
   * See https://opentelemetry.io/docs/specs/semconv/http/http-spans/#http-client-span and
   * https://opentelemetry.io/docs/specs/semconv/registry/attributes/http/#http-response-status-code
   */
  private def httpStatusCodeKey(useOpenTelemetrySemanticConventions: Boolean): String =
    if (useOpenTelemetrySemanticConventions) "http.response.status_code" else "client.http.status_code"

  /**
   * See https://opentelemetry.io/docs/specs/semconv/http/http-spans/#name
   */
  private def spanName[F[_]](request: Request[F],
                             useOpenTelemetrySemanticConventions: Boolean): String =
    if (useOpenTelemetrySemanticConventions) request.method.name else "http4s-client-request"

}
