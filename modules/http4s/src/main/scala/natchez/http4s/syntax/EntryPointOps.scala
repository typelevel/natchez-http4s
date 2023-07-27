// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s.syntax

import cats.~>
import cats.data.{ Kleisli, OptionT }
import cats.effect.MonadCancel
import org.http4s.HttpRoutes
import cats.effect.Resource
import org.http4s.server.websocket.WebSocketBuilder2
import natchez.EntryPoint
import natchez.Span

trait EntryPointOps[F[_]] { outer =>

  def self: EntryPoint[F]

  /**
   * Given an entry point and HTTP Routes in Kleisli[F, Span[F], *] return routes in F. 
   * A span that is injected is a RootsSpan (smilarly to Trace.ioTraceForEntryPoint)
   *
   */
  def liftT(
    routes: HttpRoutes[Kleisli[F, Span[F], *]],
  )(implicit ev: MonadCancel[F, Throwable]): HttpRoutes[F] =
    Kleisli { req =>
      val root = Span.makeRoots(self)
      OptionT(routes.run(req.mapK(Kleisli.liftK)).mapK(Kleisli.applyK(root)).map(_.mapK(Kleisli.applyK(root))).value)
    }


  /**
   * Lift an `HttpRoutes`-yielding resource that consumes `Span`s into the bare effect. We do this
   * by ignoring any tracing that happens during allocation and freeing of the `HttpRoutes`
   * resource. The reasoning is that such a resource typically lives for the lifetime of the
   * application and it's of little use to keep a span open that long.
   *
   */
  def liftR(
    routes: Resource[Kleisli[F, Span[F], *], HttpRoutes[Kleisli[F, Span[F], *]]],
  )(implicit ev: MonadCancel[F, Throwable]): Resource[F, HttpRoutes[F]] =
    routes.map(liftT).mapK(Span.dropTracing)

  /**
   * Given an entry point and a function from `WebSocketBuilder2` to HTTP Routes in
   * Kleisli[F, Span[F], *] return a function from `WebSocketBuilder2` to routes in F.    
   */
  def wsLiftT(
    routes: WebSocketBuilder2[Kleisli[F, Span[F], *]] => HttpRoutes[Kleisli[F, Span[F], *]]
   )(implicit ev: MonadCancel[F, Throwable]): WebSocketBuilder2[F] => HttpRoutes[F] = wsb =>
    liftT(routes(wsb.imapK(lift)(Span.dropTracing)))

  /**
   * Lift a `WebSocketBuilder2 => HttpRoutes`-yielding resource that consumes `Span`s into the bare
   * effect. We do this by ignoring any tracing that happens during allocation and freeing of the
   * `HttpRoutes` resource. The reasoning is that such a resource typically lives for the lifetime
   * of the application and it's of little use to keep a span open that long.
   */
  def wsLiftR(
    routes: Resource[Kleisli[F, Span[F], *], WebSocketBuilder2[Kleisli[F, Span[F], *]] => HttpRoutes[Kleisli[F, Span[F], *]]]
  )(implicit ev: MonadCancel[F, Throwable]): Resource[F, WebSocketBuilder2[F] => HttpRoutes[F]] =
    routes.map(wsLiftT).mapK(Span.dropTracing)

  private val lift: F ~> Kleisli[F, Span[F], *] =
    Kleisli.liftK
}

trait ToEntryPointOps {

  implicit def toEntryPointOps[F[_]](ep: EntryPoint[F]): EntryPointOps[F] =
    new EntryPointOps[F] {
      val self = ep
    }

}

object entrypoint extends ToEntryPointOps
