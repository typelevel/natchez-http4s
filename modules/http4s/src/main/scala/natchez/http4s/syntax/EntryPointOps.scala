// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s.syntax

import cats.~>
import cats.data.{Kleisli, OptionT}
import cats.data.Kleisli.applyK
import cats.effect.MonadCancel
import natchez.{EntryPoint, Kernel, Span}
import org.http4s.HttpRoutes
import cats.effect.Resource
import natchez.http4s.DefaultValues
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString

/**
 * @define excludedHeaders
 *         All headers except security (Authorization, Cookie, Set-Cookie)
 *         and payload (Content-Length, ContentType, Content-Range, Trailer, Transfer-Encoding)
 *         are passed to Kernel by default.
 *
 * @define isKernelHeader should an HTTP header be passed to Kernel or not
 *
 * @define spanName compute the span name from the request
 *
 * @define spanOptions options used in span creation
 */
trait EntryPointOps[F[_]] { outer =>

  def self: EntryPoint[F]

  /**
   * Given an entry point and HTTP Routes in Kleisli[F, Span[F], *] return routes in F. A new span
   * is created with by default the URI path as the name, either as a continuation of the incoming trace, if
   * any, or as a new root.
   *
   * @note $excludedHeaders
   *
   * @param isKernelHeader $isKernelHeader
   * @param spanName $spanName
   * @param spanOptions $spanOptions
   */
  def liftT(
    routes: HttpRoutes[Kleisli[F, Span[F], *]],
    isKernelHeader: CIString => Boolean = name => !EntryPointOps.ExcludedHeaders.contains(name),
    spanName: org.http4s.Request[F] => String = _.uri.path.toString,
    spanOptions: Span.Options = Span.Options.Defaults,
  )(implicit ev: MonadCancel[F, Throwable]): HttpRoutes[F] =
    Kleisli { req =>
      val kernelHeaders = req.headers.headers
        .collect {
          case header if isKernelHeader(header.name) => header.name -> header.value
        }
        .toMap

      val kernel = Kernel(kernelHeaders)
      val spanR  = self.continueOrElseRoot(spanName(req), kernel, spanOptions)
      OptionT {
        spanR.use { span =>
          routes.run(req.mapK(lift)).mapK(applyK(span)).map(_.mapK(applyK(span))).value
        }
      }
    }

  /**
   * Lift an `HttpRoutes`-yielding resource that consumes `Span`s into the bare effect. We do this
   * by ignoring any tracing that happens during allocation and freeing of the `HttpRoutes`
   * resource. The reasoning is that such a resource typically lives for the lifetime of the
   * application and it's of little use to keep a span open that long.
   *
   * @note $excludedHeaders
   *
   * @param isKernelHeader $isKernelHeader
   */
  def liftR(
    routes: Resource[Kleisli[F, Span[F], *], HttpRoutes[Kleisli[F, Span[F], *]]],
    isKernelHeader: CIString => Boolean = name => !EntryPointOps.ExcludedHeaders.contains(name)
  )(implicit ev: MonadCancel[F, Throwable]): Resource[F, HttpRoutes[F]] =
    routes.map(liftT(_, isKernelHeader)).mapK(Span.dropTracing)

  /**
   * Given an entry point and a function from `WebSocketBuilder2` to HTTP Routes in
   * Kleisli[F, Span[F], *] return a function from `WebSocketBuilder2` to routes in F. A new span
   * is created with the URI path as the name, either as a continuation of the incoming trace, if
   * any, or as a new root.
   *
   * @note $excludedHeaders
   *
   * @param isKernelHeader $isKernelHeader
   */
  def wsLiftT(
    routes: WebSocketBuilder2[Kleisli[F, Span[F], *]] => HttpRoutes[Kleisli[F, Span[F], *]],
    isKernelHeader: CIString => Boolean = name => !EntryPointOps.ExcludedHeaders.contains(name),
    spanName: org.http4s.Request[F] => String = _.uri.path.toString,
    spanOptions: Span.Options = Span.Options.Defaults
   )(implicit ev: MonadCancel[F, Throwable]): WebSocketBuilder2[F] => HttpRoutes[F] = wsb =>
    liftT(routes(wsb.imapK(lift)(Span.dropTracing)), isKernelHeader, spanName, spanOptions)

  /**
   * Lift a `WebSocketBuilder2 => HttpRoutes`-yielding resource that consumes `Span`s into the bare
   * effect. We do this by ignoring any tracing that happens during allocation and freeing of the
   * `HttpRoutes` resource. The reasoning is that such a resource typically lives for the lifetime
   * of the application and it's of little use to keep a span open that long.
   *
   * @note $excludedHeaders
   *
   * @param isKernelHeader $isKernelHeader
   */
  def wsLiftR(
    routes: Resource[Kleisli[F, Span[F], *], WebSocketBuilder2[Kleisli[F, Span[F], *]] => HttpRoutes[Kleisli[F, Span[F], *]]],
    isKernelHeader: CIString => Boolean = name => !EntryPointOps.ExcludedHeaders.contains(name),
    spanName: org.http4s.Request[F] => String = _.uri.path.toString,
    spanOptions: Span.Options = Span.Options.Defaults
  )(implicit ev: MonadCancel[F, Throwable]): Resource[F, WebSocketBuilder2[F] => HttpRoutes[F]] =
    routes.map(wsLiftT(_, isKernelHeader, spanName, spanOptions)).mapK(Span.dropTracing)

  private val lift: F ~> Kleisli[F, Span[F], *] =
    Kleisli.liftK
}

object EntryPointOps {
  val ExcludedHeaders: Set[CIString] = DefaultValues.ExcludedHeaders
}

trait ToEntryPointOps {

  implicit def toEntryPointOps[F[_]](ep: EntryPoint[F]): EntryPointOps[F] =
    new EntryPointOps[F] {
      val self = ep
    }

}

object entrypoint extends ToEntryPointOps
