// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez
package http4s

import org.http4s.headers.*
import org.typelevel.ci.*

private[natchez] object DefaultValues {
  private[natchez] val ExcludedHeaders: Set[CIString] = {
    val payload = Set(
      `Content-Length`.name,
      ci"Content-Type",
      `Content-Range`.name,
      ci"Trailer",
      `Transfer-Encoding`.name,
    )

    val security = Set(
      Authorization.name,
      Cookie.name,
      `Set-Cookie`.name,
    )

    payload ++ security
  }
}
