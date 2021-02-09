// Copyright (c) 2021 by Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s

import cats.data.{ Kleisli, OptionT }
import cats.effect.Bracket
import cats.implicits._
import org.http4s.HttpRoutes
import natchez.Trace
import natchez.Tags
import scala.util.control.NonFatal
import org.http4s.Response
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import cats.MonadError

  /**
   * A middleware that adds the following standard fields to the current span:
   *
   * - "http.method"      -> "GET", "PUT", etc.
   * - "http.url"         -> request URI (not URL)
   * - "http.status_code" -> "200", "403", etc. // why is this a string*
   * - "error"            -> true // only present in case of error
   *
   * In addition the following non-standard fields are added in case of error:
   *
   * - "error.message"    -> Exception message
   * - "error.stacktrace" -> Exception stack trace as a multi-line string
   */
  object NatchezMiddleware {

  def apply[F[_]: Bracket[*[_], Throwable]: Trace](routes: HttpRoutes[F]): HttpRoutes[F] =
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
          "error.message"    -> {
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

}

object X {

  def foo[F[_], A, B](f: A => B, g: B => A)(
    implicit ev: MonadError[F, A]
  ): MonadError[F, B] =
    new MonadError[F, B] {
      def pure[T](x: T): F[T] = ev.pure(x)
      def raiseError[T](e: B): F[T] = ev.raiseError(g(e))
      def handleErrorWith[T](fa: F[T])(h: B => F[T]): F[T] = ev.handleErrorWith(fa)(b => h(f(b)))
      def flatMap[T, U](fa: F[T])(h: T => F[U]): F[U] = ev.flatMap(fa)(h)
      def tailRecM[T, U](a: T)(h: T => F[Either[T,U]]): F[U] = ev.tailRecM(a)(h)
    }

}
