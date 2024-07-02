// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s

import cats.Monad
import cats.data.{ Chain, Kleisli }
import cats.effect.{ IO, MonadCancelThrow, Resource }
import natchez.{Kernel, Span, Trace}
import natchez.TraceValue.StringValue
import natchez.http4s.syntax.entrypoint._
import org.http4s._
import org.http4s.headers._
import org.http4s.client.Client
import org.http4s.dsl.request._
import org.http4s.syntax.literals._
import org.typelevel.ci._

class NatchezMiddlewareSuite extends InMemorySuite {

  private val CustomHeaderName = ci"X-Custom-Header"
  private val CorrelationIdName = ci"X-Correlation-Id"

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
      routes   <- IO.pure(ep.liftT(httpRoutes[Kleisli[IO, natchez.Span[IO], *]]))
      response <- routes.orNotFound.run(request)
    } yield {
      assertEquals(response.status.code, 200)
      assertEquals(response.headers, expectedHeaders)
    }
  }

  test("generate proper tracing history") {
    val request = Request[IO](
      method = Method.GET,
      uri = uri"/hello/some-name",
      headers = Headers(
        Header.Raw(CustomHeaderName, "external"),
        Header.Raw(CorrelationIdName, "id-123")
      )
    )

    val expectedHistory = {
      val requestKernel = Kernel(
        Map(CustomHeaderName -> "external", CorrelationIdName -> "id-123")
      )

      val clientRequestTags = List(
        "client.http.uri" -> StringValue("/some-name"),
        "client.http.method" -> StringValue("GET")
      )

      val clientResponseTags = List(
        "client.http.status_code" -> StringValue("200")
      )

      val requestTags = List(
        "http.method" -> StringValue("GET"),
        "http.url" -> StringValue("/hello/some-name")
      )

      val responseTags = List(
        "http.status_code" -> StringValue("200")
      )

      List(
        (Lineage.Root,                                          NatchezCommand.CreateRootSpan("/hello/some-name", requestKernel, Span.Options.Defaults)),
        (Lineage.Root,                                          NatchezCommand.Put(requestTags)),
        (Lineage.Root,                                          NatchezCommand.CreateSpan("call-proxy", None, Span.Options.Defaults)),
        (Lineage.Root / "call-proxy",                           NatchezCommand.CreateSpan("http4s-client-request", None, Span.Options.Defaults)),
        (Lineage.Root / "call-proxy" / "http4s-client-request", NatchezCommand.AskKernel(requestKernel)),
        (Lineage.Root / "call-proxy" / "http4s-client-request", NatchezCommand.Put(clientRequestTags)),
        (Lineage.Root / "call-proxy" / "http4s-client-request", NatchezCommand.Put(clientResponseTags)),
        (Lineage.Root / "call-proxy",                           NatchezCommand.ReleaseSpan("http4s-client-request")),
        (Lineage.Root,                                          NatchezCommand.ReleaseSpan("call-proxy")),
        (Lineage.Root,                                          NatchezCommand.Put(responseTags)),
        (Lineage.Root,                                          NatchezCommand.ReleaseRootSpan("/hello/some-name"))
      )
    }

    for {
      ep       <- InMemory.EntryPoint.create
      routes   <- IO.pure(ep.liftT(httpRoutes[Kleisli[IO, natchez.Span[IO], *]]))
      _        <- routes.orNotFound.run(request)
      history  <- ep.ref.get
    } yield assertEquals(history.toList, expectedHistory)
  }

  private def httpRoutes[F[_]: MonadCancelThrow: Trace]: HttpRoutes[F] = {
    val client = NatchezMiddleware.client(echoHeadersClient[F])
    val server = NatchezMiddleware.server(proxyRoutes(client))
    server
  }

  private def echoHeadersClient[F[_]: MonadCancelThrow]: Client[F] =
    Client[F] { request =>
      Resource.pure(Response[F](headers = request.headers))
    }

  private def proxyRoutes[F[_]: Monad: Trace](client: Client[F]): HttpRoutes[F] = {
    HttpRoutes.of[F] {
      case GET -> Root / "hello" / name =>
        val request = Request[F](
          method = Method.GET,
          uri = Uri(path = Root / name),
          headers = Headers(Header.Raw(CustomHeaderName, "internal"))
        )

        Trace[F].span("call-proxy") {
          client.toHttpApp.run(request)
        }
    }
  }

}
