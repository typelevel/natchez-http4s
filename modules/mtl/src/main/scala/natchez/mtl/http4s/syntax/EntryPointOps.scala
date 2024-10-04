// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.mtl.http4s.syntax

import cats.arrow.FunctionK
import cats.data.*
import cats.effect.*
import cats.mtl.Local
import cats.~>
import natchez.Span.SpanKind.Server
import natchez.http4s.DefaultValues.ExcludedHeaders
import natchez.{EntryPoint, Kernel, Span}
import org.http4s.{Http, HttpApp, HttpRoutes, Request}
import org.typelevel.ci.CIString

trait EntryPointOps[F[_]] { outer =>
  def self: EntryPoint[F]

  /**
   * Starts or continues a trace for each request handled by the passed `HttpApp[F]`
   * using the default [[HttpTracingOptions]].
   *
   * @param routes the `HttpApp[F]` that handles requests. Each invocation will occur in the scope of a new or continued `Trace[F]`
   * @return the wrapped `HttpApp[F]`
   */
  def liftApp(routes: HttpApp[F])
             (implicit F: MonadCancelThrow[F],
              L: Local[F, Span[F]],
             ): HttpApp[F] =
    liftApp(routes, HttpTracingOptions[F])

  /**
   * Starts or continues a trace for each request handled by the passed `HttpApp[F]`.
   *
   * @param routes the `HttpApp[F]` that handles requests. Each invocation will occur in the scope of a new or continued `Trace[F]`
   * @param options the [[HttpTracingOptions]] to apply to each request
   * @return the wrapped `HttpApp[F]`
   */
  def liftApp(routes: HttpApp[F],
              options: HttpTracingOptions[F])
             (implicit F: MonadCancelThrow[F],
              L: Local[F, Span[F]],
             ): HttpApp[F] =
    liftHttp(routes, options, FunctionK.id)

  /**
   * Starts or continues a trace for each request handled by the passed `HttpRoutes[F]`
   * using the default [[HttpTracingOptions]].
   *
   * When using this method with OpenTelemetry to add tracing to a subset of the routes
   * handled by your app, be careful to place the traced routes in the lowest priority
   * when combining them with untraced routes. If the `HttpRoutes[F]` wrapped by this
   * method are higher priority, traces will be emitted that contain no information,
   * because [[EntryPoint.continueOrElseRoot(name:String,kernel:natchez\.Kernel)*]]
   * will be invoked regardless of whether the routes passed to this method actually
   * handle the request or not.
   *
   * For example:
   * {{{
   *   def routesToBeTraced: HttpRoutes[F] = ???
   *   def untracedRoutes: HttpRoutes[F] = ???
   *
   *   untracedRoutes <+> entryPoint.liftRoutes(routesToBeTraced)
   * }}}
   *
   * will work as expected, but
   *
   * {{{
   *   entryPoint.liftRoutes(routesToBeTraced) <+> untracedRoutes
   * }}}
   *
   * will not, and the requests handled by `untracedRoutes` will in fact generate
   * empty traces.
   *
   * @param routes the `HttpRoutes[F]` that handles requests. Each invocation will occur in the scope of a new or continued `Trace[F]`
   * @return the wrapped `HttpRoutes[F]`
   */
  def liftRoutes(routes: HttpRoutes[F])
                (implicit F: MonadCancelThrow[F],
                 L: Local[F, Span[F]],
                ): HttpRoutes[F] =
    liftRoutes(routes, HttpTracingOptions[F])

  /**
   * Starts or continues a trace for each request handled by the passed `HttpRoutes[F]`.
   *
   * When using this method with OpenTelemetry to add tracing to a subset of the routes
   * handled by your app, be careful to place the traced routes in the lowest priority
   * when combining them with untraced routes. If the `HttpRoutes[F]` wrapped by this
   * method are higher priority, traces will be emitted that contain no information,
   * because [[EntryPoint.continueOrElseRoot(name:String,kernel:natchez\.Kernel)*]]
   * will be invoked regardless of whether the routes passed to this method actually
   * handle the request or not.
   *
   * For example:
   * {{{
   *   def routesToBeTraced: HttpRoutes[F] = ???
   *   def untracedRoutes: HttpRoutes[F] = ???
   *
   *   untracedRoutes <+> entryPoint.liftRoutes(routesToBeTraced)
   * }}}
   *
   * will work as expected, but
   *
   * {{{
   *   entryPoint.liftRoutes(routesToBeTraced) <+> untracedRoutes
   * }}}
   *
   * will not, and the requests handled by `untracedRoutes` will in fact generate
   * empty traces.
   *
   * @param routes the `HttpRoutes[F]` that handles requests. Each invocation will occur in the scope of a new or continued `Trace[F]`
   * @param options the [[HttpTracingOptions]] to apply to each request
   * @return the wrapped `HttpRoutes[F]`
   */
  def liftRoutes(routes: HttpRoutes[F],
                 options: HttpTracingOptions[F])
                (implicit F: MonadCancelThrow[F],
                 L: Local[F, Span[F]],
                ): HttpRoutes[F] =
    liftHttp(routes, options, OptionT.liftK)

  /**
   * Starts or continues a trace for each request handled by the passed `Http[G, F]`
   * using the default [[HttpTracingOptions]].
   *
   * @param routes the `Http[G, F]` that handles requests. Each invocation will occur in the scope of a new or continued `Trace[F]`
   * @return the wrapped `Http[G, F]`
   */
  def liftHttp[G[_]](routes: Http[G, F],
                     fk: F ~> G)
                    (implicit F: MonadCancelThrow[F],
                     G: MonadCancelThrow[G],
                     L: Local[G, Span[F]],
                    ): Http[G, F] =
    liftHttp(routes, HttpTracingOptions[F], fk)

  /**
   * Starts or continues a trace for each request handled by the passed `Http[G, F]`.
   *
   * @param routes the `Http[G, F]` that handles requests. Each invocation will occur in the scope of a new or continued `Trace[F]`
   * @param options the [[HttpTracingOptions]] to apply to each request
   * @return the wrapped `Http[G, F]`
   */
  def liftHttp[G[_]](routes: Http[G, F],
                     options: HttpTracingOptions[F],
                     fk: F ~> G)
                    (implicit F: MonadCancelThrow[F],
                     G: MonadCancelThrow[G],
                     L: Local[G, Span[F]],
                    ): Http[G, F] =
    Kleisli { req =>
      val kernelHeaders =
        req.headers.headers
          .collect {
            case header if options.isKernelHeader(header.name) => header.name -> header.value
          }
          .toMap

      self
        .continueOrElseRoot(options.spanName(req), Kernel(kernelHeaders), options.spanOptions)
        .mapK(fk)
        .use(Local[G, Span[F]].scope(routes.run(req)))
    }

}

trait ToEntryPointOps {
  implicit def toEntryPointOps[F[_]](ep: EntryPoint[F]): EntryPointOps[F] =
    new EntryPointOps[F] {
      val self: EntryPoint[F] = ep
    }
}

object entrypoint extends ToEntryPointOps

/**
 * A class holding the various options available to be set on traces started by [[EntryPointOps]].
 *
 * @param isKernelHeader a function to determine whether a given request header should be included in the `Trace[F]`'s `Kernel`
 * @param spanName a function to derive the name of the created span from the request being handled.
 *                 By default, this uses the request method and URI path, although strictly speaking this
 *                 is not compliant with the OpenTelemetry spec for any URIs with path variables. See Note 5 in the
 *                 [[https://opentelemetry.io/docs/specs/semconv/attributes-registry/http/#http-attributes Semantic Conventions for HTTP]].
 * @param spanOptions allows the caller to override the Natchez Span options. By default, this uses the default options with a `Server` span kind.
 */
class HttpTracingOptions[F[_]] private(val isKernelHeader: CIString => Boolean,
                                       val spanName: Request[F] => String,
                                       val spanOptions: Span.Options,
                                   ) {
  def withKernelHeaderDiscriminator(f: CIString => Boolean): HttpTracingOptions[F] =
    new HttpTracingOptions(f, spanName, spanOptions)
  def withSpanNameBuilder[G[_]](f: Request[G] => String): HttpTracingOptions[G] =
    new HttpTracingOptions(isKernelHeader, f, spanOptions)
  def withSpanOptions(options: Span.Options): HttpTracingOptions[F] =
    new HttpTracingOptions(isKernelHeader, spanName, options)
  def mapK[G[_]](fk: G ~> F): HttpTracingOptions[G] =
    this.withSpanNameBuilder(spanName.compose(_.mapK(fk)))
}

object HttpTracingOptions {
  /**
   * Returns the default options for [[EntryPointOps]] functions.
   *
   *   - `isKernelHeader` will exclude the headers defined in [[natchez.http4s.DefaultValues.ExcludedHeaders]]
   *   - `spanName` uses the request method and URI path, although strictly speaking this
   *                  is not compliant with the OpenTelemetry spec for any URIs with path variables. See Note 5 in the
   *                  [[https://opentelemetry.io/docs/specs/semconv/attributes-registry/http/#http-attributes Semantic Conventions for HTTP]].
   *   - `spanOptions` uses Natchez's default span options with a `Server` span kind
   *
   * @return the default options for [[EntryPointOps]] functions.
   */
  def apply[F[_]]: HttpTracingOptions[F] =
    new HttpTracingOptions[F](
      isKernelHeader = !ExcludedHeaders.contains(_),
      spanName = (req: Request[F]) => s"${req.method} ${req.uri.path}",
      spanOptions = Span.Options.Defaults.withSpanKind(Server),
  )
}
