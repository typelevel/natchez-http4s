// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez

package object http4s {
  object implicits
    extends syntax.ToEntryPointOps
       with syntax.ToKernelOps
       with syntax.ToKernelCompanionOps
}

