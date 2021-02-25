// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez.http4s.syntax

import natchez.Kernel
import org.http4s.Headers
import org.http4s.Header

trait KernelOps {

  def self: Kernel

  def toHttp4sHeaders: Headers =
    Headers.of(self.toHeaders.map { case (k, v) => Header(k, v) } .toSeq :_*)

}

trait ToKernelOps {
  implicit def toKernelOps(kernel: Kernel): KernelOps =
    new KernelOps {
      val self = kernel
    }
}

trait KernelCompanionOps {

  def self: Kernel.type

  def fromHttp4sHeaders(headers: Headers): Kernel =
    Kernel(headers.toList.map { h => h.name.toString() -> h.value } .toMap)

}

trait ToKernelCompanionOps {
  implicit def toKernelCompanionOps(kernelCompanion: Kernel.type): KernelCompanionOps =
    new KernelCompanionOps {
      val self = kernelCompanion
    }
}

object kernel extends ToKernelOps with ToKernelCompanionOps
