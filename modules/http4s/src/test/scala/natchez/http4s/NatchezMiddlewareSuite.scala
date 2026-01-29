// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s

import cats.Monad
import cats.data.{Chain, Kleisli}
import cats.effect.{IO, MonadCancelThrow, Resource}
import cats.syntax.all.*
import io.opentelemetry.semconv.{HttpAttributes, ServerAttributes, UrlAttributes}
import munit.ScalaCheckEffectSuite
import natchez.*
import natchez.Span.SpanKind
import natchez.TraceValue.{NumberValue, StringValue}
import natchez.http4s.syntax.entrypoint.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.request.*
import org.http4s.headers.*
import org.http4s.syntax.literals.*
import org.scalacheck.effect.PropF
import org.typelevel.ci.*

class NatchezMiddlewareSuite
  extends InMemorySuite
    with ScalaCheckEffectSuite {

  test("do not leak security and payload headers to the client request") {
    val headers = Headers(
      // security
      Authorization(BasicCredentials("username", "password")),
      Cookie(RequestCookie("key", "value")),
      `Set-Cookie`(ResponseCookie("key", "value")),
      // payload
      `Content-Type`(MediaType.`text/event-stream`),
      `Content-Length`(42L),
      `Content-Range`(1L),
      Header.Raw(ci"Trailer", "Expires"),
      `Transfer-Encoding`(TransferCoding.identity),
      // custom conflicting (client uses this header)
      Header.Raw(CustomHeaderName, "external"),
      // custom non-conflicting
      Header.Raw(CorrelationIdName, "id-123")
    )

    val request = Request[IO](
      method = Method.GET,
      uri = uri"/hello/some-name",
      headers = headers
    )

    val expectedHeaders = Headers(
      Header.Raw(CorrelationIdName, "id-123"),
      Header.Raw(CustomHeaderName, "internal")
    )

    for {
      ref      <- IO.ref(Chain.empty[(Lineage, NatchezCommand)])
      ep       <- IO.pure(new InMemory.EntryPoint(ref))
      routes   <- IO.pure(ep.liftT(httpRoutes[Kleisli[IO, natchez.Span[IO], *]](None, useOpenTelemetrySemanticConventions = false)))
      response <- routes.orNotFound.run(request)
    } yield {
      assertEquals(response.status.code, 200)
      assertEquals(response.headers, expectedHeaders)
    }
  }

  test("generate proper tracing history") {
    PropF.forAllF { (userSpecifiedTags: List[(String, TraceValue)],
                     maybeSpanOptions: Option[Span.Options],
                     maybeUrlScheme: Option[Uri.Scheme],
                     maybeUriAuthority: Option[Uri.Authority],
                     maybeHostHeader: Option[headers.Host],
                     useOpenTelemetrySemanticConventions: Boolean,
                    ) =>
      val request = maybeHostHeader.foldl(Request[IO](
        method = Method.GET,
        uri = uri"/hello/some-name".copy(authority = maybeUriAuthority, scheme = maybeUrlScheme),
        headers = Headers(
          Header.Raw(CustomHeaderName, "external"),
          Header.Raw(CorrelationIdName, "id-123"),
        )
      ))(_.putHeaders(_))

      val expectedHistory = {
        val requestKernel = Kernel(
          Map(CustomHeaderName -> "external", CorrelationIdName -> "id-123") ++ maybeHostHeader.map { header =>
            headers.Host.headerInstance.name -> headers.Host.headerInstance.value(header)
          }.toMap
        )

        val requestTagsFromClientMiddleware =
          if (useOpenTelemetrySemanticConventions) List(
            UrlAttributes.URL_FULL.getKey -> StringValue(request.uri.withPath(Root / "some-name").renderString),
            HttpAttributes.HTTP_REQUEST_METHOD.getKey -> StringValue("GET"),
          )
          else List(
            "client.http.uri" -> StringValue(request.uri.withPath(Root / "some-name").renderString),
            "client.http.method" -> StringValue("GET")
          )

        val responseTagsFromClientMiddleware =
          if (useOpenTelemetrySemanticConventions) List(
            HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey -> NumberValue(200)
          )
          else List(
            "client.http.status_code" -> NumberValue(200)
          )

        val serverAddressTag: Option[(String, TraceValue)] =
          maybeUriAuthority.map(_.host.renderString)
            .orElse(maybeHostHeader.map(_.host))
            .map(host => ServerAttributes.SERVER_ADDRESS.getKey -> StringValue(host))

        val serverPortTag: Option[(String, TraceValue)] =
          maybeUriAuthority.flatMap(_.port)
            .orElse(maybeHostHeader.flatMap(_.port))
            .map(port => ServerAttributes.SERVER_PORT.getKey -> NumberValue(port))

        val urlSchemeTag: Option[(String, TraceValue)] =
          maybeUrlScheme.map(scheme => UrlAttributes.URL_SCHEME.getKey -> StringValue(scheme.value))

        val requestTagsFromServerMiddleware =
          if (useOpenTelemetrySemanticConventions) List(
            HttpAttributes.HTTP_REQUEST_METHOD.getKey -> StringValue("GET"),
            UrlAttributes.URL_FULL.getKey -> StringValue(request.uri.renderString),
          )
          else List(
            "http.method" -> StringValue("GET"),
            "http.url" -> StringValue(request.uri.renderString)
          )

        val responseTagsFromServerMiddleware =
          if (useOpenTelemetrySemanticConventions) List(
            HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey -> NumberValue(200)
          )
          else List(
            "http.status_code" -> StringValue("200")
          )

        val spanOptions = maybeSpanOptions.getOrElse(Span.Options.Defaults.withSpanKind(SpanKind.Client))
        val kernel = maybeSpanOptions.flatMap(_.parentKernel)

        val clientSpanName = if (useOpenTelemetrySemanticConventions) request.method.name else "http4s-client-request"

        List(
          List(
            (Lineage.Root, NatchezCommand.CreateRootSpan("/hello/some-name", requestKernel, Span.Options.Defaults)),
            (Lineage.Root("/hello/some-name"), NatchezCommand.CreateSpan("call-proxy", None, Span.Options.Defaults)),
            (Lineage.Root("/hello/some-name") / "call-proxy", NatchezCommand.CreateSpan(clientSpanName, kernel, spanOptions)),
            (Lineage.Root("/hello/some-name") / "call-proxy" / clientSpanName, NatchezCommand.AskKernel(requestKernel)),
            (Lineage.Root("/hello/some-name") / "call-proxy" / clientSpanName, NatchezCommand.Put(requestTagsFromClientMiddleware)),
          ),
          List(serverAddressTag, serverPortTag, urlSchemeTag).flatMap(
            _.map(tag => (Lineage.Root("/hello/some-name") / "call-proxy" / clientSpanName, NatchezCommand.Put(List(tag))))
          ),
          List(
            (Lineage.Root("/hello/some-name") / "call-proxy" / clientSpanName, NatchezCommand.Put(userSpecifiedTags)),
            (Lineage.Root("/hello/some-name") / "call-proxy" / clientSpanName, NatchezCommand.Put(responseTagsFromClientMiddleware)),
            (Lineage.Root("/hello/some-name") / "call-proxy", NatchezCommand.ReleaseSpan(clientSpanName)),
            (Lineage.Root("/hello/some-name"), NatchezCommand.ReleaseSpan("call-proxy")),
            (Lineage.Root("/hello/some-name"), NatchezCommand.Put(requestTagsFromServerMiddleware)),
            (Lineage.Root("/hello/some-name"), NatchezCommand.Put(responseTagsFromServerMiddleware)),
            (Lineage.Root, NatchezCommand.ReleaseRootSpan("/hello/some-name")),
          )
        )
          .flatten
      }

      for {
        ep <- InMemory.EntryPoint.create[IO]
        routes <- IO.pure(ep.liftT(httpRoutes[Kleisli[IO, natchez.Span[IO], *]](maybeSpanOptions, useOpenTelemetrySemanticConventions, userSpecifiedTags *)))
        _ <- routes.orNotFound.run(request)
        history <- ep.ref.get
      } yield assertEquals(history.toList, expectedHistory)
    }
  }

  private def httpRoutes[F[_]: MonadCancelThrow: Trace](maybeSpanOptions: Option[Span.Options],
                                                        useOpenTelemetrySemanticConventions: Boolean,
                                                        additionalAttributes: (String, TraceValue)*): HttpRoutes[F] = {
    val client = maybeSpanOptions match {
      case Some(spanOptions) =>
        NatchezMiddleware.clientWithAttributes(echoHeadersClient[F], spanOptions, useOpenTelemetrySemanticConventions)(additionalAttributes *)
      case None =>
        NatchezMiddleware.clientWithAttributes(echoHeadersClient[F], useOpenTelemetrySemanticConventions)(additionalAttributes *)
    }

    val server = NatchezMiddleware.server(proxyRoutes(client), useOpenTelemetrySemanticConventions)
    server
  }

  private def echoHeadersClient[F[_]: MonadCancelThrow]: Client[F] =
    Client[F] { request =>
      Resource.pure(Response[F](headers = request.headers))
    }

  private def proxyRoutes[F[_]: Monad: Trace](client: Client[F]): HttpRoutes[F] = {
    HttpRoutes.of[F] {
      case incomingRequest @ GET -> Root / "hello" / name =>
        val request = incomingRequest.headers.get[headers.Host].foldl(Request[F](
          method = Method.GET,
          uri = incomingRequest.uri.withPath(Root / name),
          headers = Headers(Header.Raw(CustomHeaderName, "internal"))
        ))(_.putHeaders(_))

        Trace[F].span("call-proxy") {
          client.toHttpApp.run(request)
        }
    }
  }

}
