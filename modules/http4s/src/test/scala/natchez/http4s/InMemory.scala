// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s

import java.net.URI

import cats.data.Chain
import cats.effect.{ IO, Ref, Resource }
import natchez.Kernel

object InMemory {

  class Span(
    lineage : Lineage,
    k       : Kernel,
    ref     : Ref[IO, Chain[(Lineage, NatchezCommand)]]
  ) extends natchez.Span[IO] {

    def put(fields: (String, natchez.TraceValue)*): IO[Unit] =
      ref.update(_.append(lineage -> NatchezCommand.Put(fields.toList)))

    def kernel: IO[Kernel] =
      ref.update(_.append(lineage -> NatchezCommand.AskKernel(k))).as(k)

    def span(name: String): Resource[IO, natchez.Span[IO]] = {
      val acquire = ref
        .update(_.append(lineage -> NatchezCommand.CreateSpan(name)))
        .as(new Span(lineage / name, k, ref))

      val release = ref.update(_.append(lineage -> NatchezCommand.ReleaseSpan(name)))

      Resource.make(acquire)(_ => release)
    }

    def traceId: IO[Option[String]] =
      ref.update(_.append(lineage -> NatchezCommand.AskTraceId)).as(None)

    def spanId: IO[Option[String]] =
      ref.update(_.append(lineage -> NatchezCommand.AskSpanId)).as(None)

    def traceUri: IO[Option[URI]] =
      ref.update(_.append(lineage -> NatchezCommand.AskTraceUri)).as(None)
  }

  class EntryPoint(ref: Ref[IO, Chain[(Lineage, NatchezCommand)]]) extends natchez.EntryPoint[IO] {

    def root(name: String): Resource[IO, Span] =
      newSpan(name, Kernel(Map.empty))

    def continue(name: String, kernel: Kernel): Resource[IO, Span] =
      newSpan(name, kernel)

    def continueOrElseRoot(name: String, kernel: Kernel): Resource[IO, Span] =
      newSpan(name, kernel)

    private def newSpan(name: String, kernel: Kernel): Resource[IO, Span] = {
      val acquire = ref
        .update(_.append(Lineage.Root -> NatchezCommand.CreateRootSpan(name, kernel)))
        .as(new Span(Lineage.Root, kernel, ref))

      val release = ref.update(_.append(Lineage.Root -> NatchezCommand.ReleaseRootSpan(name)))

      Resource.make(acquire)(_ => release)
    }
  }

  sealed trait Lineage {
    def /(name: String): Lineage.Child = Lineage.Child(name, this)
  }
  object Lineage {
    case object Root                                      extends Lineage
    final case class Child(name: String, parent: Lineage) extends Lineage
  }

  sealed trait NatchezCommand
  object NatchezCommand {
    final case class AskKernel(kernel: Kernel)                       extends NatchezCommand
    final case object AskSpanId                                      extends NatchezCommand
    final case object AskTraceId                                     extends NatchezCommand
    final case object AskTraceUri                                    extends NatchezCommand
    final case class Put(fields: List[(String, natchez.TraceValue)]) extends NatchezCommand
    final case class CreateSpan(name: String)                        extends NatchezCommand
    final case class ReleaseSpan(name: String)                       extends NatchezCommand
    // entry point
    final case class CreateRootSpan(name: String, kernel: Kernel)    extends NatchezCommand
    final case class ReleaseRootSpan(name: String)                   extends NatchezCommand
  }

}

