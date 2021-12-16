// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s.syntax

import cats.~>
import cats.data.{ Kleisli, OptionT }
import cats.data.Kleisli.applyK
import cats.effect.MonadCancel
import cats.implicits._
import natchez.{ EntryPoint, Kernel, Span }
import org.http4s.HttpRoutes
import cats.effect.Resource
import natchez.TraceValue
import cats.Monad
import org.http4s.server.websocket.WebSocketBuilder2

trait EntryPointOps[F[_]] { outer =>

  def self: EntryPoint[F]

  /**
   * Given an entry point and HTTP Routes in Kleisli[F, Span[F], *] return routes in F. A new span
   * is created with the URI path as the name, either as a continuation of the incoming trace, if
   * any, or as a new root.
   */
  def liftT(routes: HttpRoutes[Kleisli[F, Span[F], *]])(
    implicit ev: MonadCancel[F, Throwable]
  ): HttpRoutes[F] =
    Kleisli { req =>
      val kernel = Kernel(req.headers.headers.map(h => (h.name.toString -> h.value)).toMap)
      val spanR  = self.continueOrElseRoot(req.uri.path.toString, kernel)
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
   */
  def liftR(routes: Resource[Kleisli[F, Span[F], *], HttpRoutes[Kleisli[F, Span[F], *]]])(
    implicit ev: MonadCancel[F, Throwable]
  ): Resource[F, HttpRoutes[F]] =
    routes.map(liftT).mapK(lower)

  /**
   * Given an entry point and a function from `WebSocketBuilder2` to HTTP Routes in
   * Kleisli[F, Span[F], *] return a function from `WebSocketBuilder2` to routes in F. A new span
   * is created with the URI path as the name, either as a continuation of the incoming trace, if
   * any, or as a new root.
   */
  def wsLiftT(
    routes: WebSocketBuilder2[Kleisli[F, Span[F], *]] => HttpRoutes[Kleisli[F, Span[F], *]]
  )(implicit ev: MonadCancel[F, Throwable]): WebSocketBuilder2[F] => HttpRoutes[F] = wsb =>
    liftT(routes(wsb.imapK(lift)(lower)))

  /**
   * Lift a `WebSocketBuilder2 => HttpRoutes`-yielding resource that consumes `Span`s into the bare
   * effect. We do this by ignoring any tracing that happens during allocation and freeing of the
   * `HttpRoutes` resource. The reasoning is that such a resource typically lives for the lifetime
   * of the application and it's of little use to keep a span open that long.
   */
  def wsLiftR(
    routes: Resource[Kleisli[F, Span[F], *], WebSocketBuilder2[Kleisli[F, Span[F], *]] => HttpRoutes[Kleisli[F, Span[F], *]]]
  )(implicit ev: MonadCancel[F, Throwable]): Resource[F, WebSocketBuilder2[F] => HttpRoutes[F]] =
    routes.map(wsLiftT).mapK(lower)

  private val lift: F ~> Kleisli[F, Span[F], *] =
    Kleisli.liftK

  private def lower(implicit ev: Monad[F]): Kleisli[F, Span[F], *] ~> F =
    new (Kleisli[F, Span[F], *] ~> F) {
      def apply[A](fa: Kleisli[F, Span[F], A]) =
        fa.run(dummySpan)
    }

  private def dummySpan(implicit ev: Monad[F]): Span[F] =
    new Span[F] {
      val kernel = Kernel(Map.empty).pure[F]
      def put(fields: (String, TraceValue)*) = Monad[F].unit
      def span(name: String) = Monad[Resource[F, *]].pure(this)
      def traceId = Monad[F].pure(None)
      def traceUri = Monad[F].pure(None)
      def spanId = Monad[F].pure(None)
    }

}

trait ToEntryPointOps {

  implicit def toEntryPointOps[F[_]](ep: EntryPoint[F]): EntryPointOps[F] =
    new EntryPointOps[F] {
      val self = ep
    }

}

object entrypoint extends ToEntryPointOps