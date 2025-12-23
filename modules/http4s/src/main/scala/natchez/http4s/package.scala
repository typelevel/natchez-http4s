// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez

import org.http4s.{Method, Uri, headers}

package object http4s {
  object implicits
    extends syntax.ToEntryPointOps
       with syntax.ToKernelOps
       with syntax.ToKernelCompanionOps {
    implicit lazy val traceableValueUri: TraceableValue[Uri] = TraceableValue[String].contramap(_.renderString)
    implicit lazy val traceableValueHost: TraceableValue[Uri.Host] = TraceableValue[String].contramap(_.renderString)
    implicit lazy val traceableValueMethod: TraceableValue[Method] = TraceableValue[String].contramap(_.renderString)
    implicit lazy val traceableValueScheme: TraceableValue[Uri.Scheme] = TraceableValue[String].contramap(_.value)
    implicit lazy val traceableValueHostHeader: TraceableValue[headers.Host] = TraceableValue[String].contramap(_.host)
  }
}
