# Natchez-Http4s

This is a support library for using [Natchez]() with [Http4s](). It provides the following things:

1. A mechanism to discharge a `Trace[F]` constraint on `HttpRoutes[F]`, which constructs the required ambient span from incoming headers when possible, otherwise creating a root span.
1. A server middleware which adds standard request/response header information to the top-level span associated with a request, as well as extended fields for failure cases.
1. A client middleware which creates a span around outgoing requests and sends that span's kernel in outgoing headers.

Below are the available version series (see [releases](https://github.com/tpolecat/natchez-http4s/releases) for exact version numbers). You are strongly encouraged to use the **Active** series. Older series will receive bug fixes when necessary but are not actively maintained.

| Series    | Status     | 2.12 | 2.13 | 3.0 | Http4s | Cats-Effect |
|:---------:|------------|:----:|:----:|:---:|:------:|:-----------:|
| **0.1.x** | **Active** | ✅   | ✅   | ✅   | 0.23.x | **3.x**     |
| 0.0.x     | EOL        | ✅   | ✅   | ✅   | 0.22.x | 2.x         |

See below, then check out the `examples/` module in the repo for a working example with a real tracing back-end.

## Import

@@dependency[sbt,Maven,Gradle] {
  group="$org$"
  artifact="$core-dep$"
  version="$version$"
}

## Tracing HttpRoutes

Here is the basic pattern for HTTP applications **without WebSockets**. For the WebSocket variation see below.

- Construct `HttpRoutes` in abstract `F` with a `Trace` constraint.
- Lift it into our `EntryPoint`'s `F`, which has _no_ `Trace` constraint.

```scala mdoc
// Nothing new here
import cats.effect.MonadCancel
import natchez.{ EntryPoint, Trace }
import org.http4s.HttpRoutes

// This import provides `liftT`, used below.
import natchez.http4s.implicits._

// Our routes constructor is parametric in its effect, which has (at least) a
// `Trace` constraint. This means all our handlers will be in the same F and
// will have tracing available.
def mkTracedRoutes[F[_]: Trace]: HttpRoutes[F] =
  ???

// Given an EntryPoint in F, with (at least) `MonadCancel[F, Throwable]` but
// WITHOUT a Trace constraint, we can lift `mkTracedRoutes` into F.
def mkRoutes[F[_]](ep: EntryPoint[F])(
  implicit ev: MonadCancel[F, Throwable]
): HttpRoutes[F] =
  ep.liftT(mkTracedRoutes)
```

The trick here is that `liftT` takes an `HttpRoutes[Kleisl[F, Span[F], *]]`, but this type is usually inferred so we never know or care that we're using `Kleisli`. If you do need to provide an explicit type argument, that's what it is.


We also provide `liftR`, which is analogous to `liftT` but works for `Resource[F, HttpRoutes[F]]`.

```scala mdoc
import cats.Defer
import cats.effect.Resource

def mkTracedRoutesResource[F[_]: Trace]: Resource[F, HttpRoutes[F]] =
  ???

// Note that liftR also requires Defer[F]
def mkRoutesResource[F[_]](ep: EntryPoint[F])(
  implicit ev: MonadCancel[F, Throwable]
): Resource[F, HttpRoutes[F]] =
  ep.liftR(mkTracedRoutesResource)
```

## Server Middleware

`NatchezMiddleware.server(<routes>)` adds the following "standard" fields to the top-level span associated with each request.

| Field              | Type       | Description                           |
|--------------------|------------|---------------------------------------|
| `http.method`      | String     | `"GET"`, `"PUT"`, etc.                |
| `http.url`         | String     | request URI                           |
| `http.status_code` | String (!) | `"200"`, `"403"`, etc.                |
| `error`            | Boolean    | `true`, only present in case of error |

In addition, the following Natchez-only fields are added.

| Field              | Type   | Description                                  |
|--------------------|--------|----------------------------------------------|
| `error.message`    | String | Exception message                            |
| `error.stacktrace` | String | Exception stack trace as a multi-line string |

Usage is straightforward.

```scala mdoc
import natchez.http4s.NatchezMiddleware

def mkRoutes2[F[_]](ep: EntryPoint[F])(
  implicit ev: MonadCancel[F, Throwable]
): HttpRoutes[F] =
  ep.liftT(NatchezMiddleware.server(mkTracedRoutes)) // type arguments are inferred as above
```

## Client Middleware

`NatchezMiddleware.client(<client>)` adds a span called `http4s-client-request` around outgoing requests,
with the following fields.

| Field                     | Type       | Description            |
|---------------------------|------------|------------------------|
| `client.http.method`      | String     | `"GET"`, `"PUT"`, etc. |
| `client.http.url`         | String     | request URI            |
| `client.http.status_code` | String (!) | `"200"`, `"403"`, etc. |

## Tracing HttpRoutes with WebSockets

The basic pattern for HTTP applications with WebSockets is analogous to the HTTP-only case above, but instead of `HttpRoutes` we are lifting functions `WebSocketBuilder2 => HttpRoutes`, which is one `map` away from what your pass to your server builder's `withHttpWebSocketApp` method.

- Construct a `WebSocketBuilder2 => HttpRoutes` in abstract `F` with a `Trace` constraint.
- Lift it into our `EntryPoint`'s `F`, which has _no_ `Trace` constraint.

```scala mdoc:reset
// Nothing new here
import cats.effect.MonadCancel
import natchez.{ EntryPoint, Trace }
import org.http4s.HttpRoutes
import org.http4s.server.websocket.WebSocketBuilder2

// This import provides `wsLiftT`, used below.
import natchez.http4s.implicits._

// Our routes constructor is parametric in its effect, which has (at least) a
// `Trace` constraint. This means all our handlers will be in the same F and
// will have tracing available.
def mkTracedRoutes[F[_]: Trace]: WebSocketBuilder2[F] => HttpRoutes[F] =
  ???

// Given an EntryPoint in F, with (at least) `MonadCancel[F, Throwable]` but
// WITHOUT a Trace constraint, we can lift `mkTracedRoutes` into F.
def mkRoutes[F[_]](ep: EntryPoint[F])(
  implicit ev: MonadCancel[F, Throwable]
): WebSocketBuilder2[F] => HttpRoutes[F] =
  ep.wsLiftT(mkTracedRoutes)
```

And analogously with `wsLiftR` for the `Resource` case:

```scala mdoc
import cats.Defer
import cats.effect.Resource

def mkTracedRoutesResource[F[_]: Trace]: Resource[F, WebSocketBuilder2[F] => HttpRoutes[F]] =
  ???

// Note that liftR also requires Defer[F]
def mkRoutesResource[F[_]](ep: EntryPoint[F])(
  implicit ev: MonadCancel[F, Throwable]
): Resource[F, WebSocketBuilder2[F] => HttpRoutes[F]] =
  ep.wsLiftR(mkTracedRoutesResource)
```

As with the HTTP-only case above, the trick here is that `wsLiftT/R` will infer `F` as `Kleisli` and you won't normally need to mention it in your code.

## Limitations

Because we're instantiating `F` to `Kleisl[F, Span[F], *]` in our `HttpRoutes`, any constraints we place on `F` must be fulfillable by `Kleisli`. Things like `Monad` and `Deferred` and so on are fine, but effects that require the ability to extract a value (specifically `Effect` and `ConcurrentEffect`) are unavailable and will lead to a type error. So be aware of this constraint.

