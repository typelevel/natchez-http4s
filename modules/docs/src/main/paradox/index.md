# Natchez-Http4s

This is a support library for using [Natchez]() with [Http4s](). It provides the following things:

1. A mechanism to discharge a `Trace[F]` constraint on `HttpRoutes[F]`, which constructs the required ambient span from incoming headers when possible, otherwise creating a root span.
1. A server middleware which adds standard request/response header information to the top-level span associated with a request, as well as extended fields for failure cases.
1. A client middleware which creates a span around outgoing requests and sends that span's kernel in outgoing headers.

See below, then check out the `examples/` module in the repo for a working example with a real tracing back-end.

## Tracing HttpRoutes

Here is the basic pattern.

- Construct `HttpRoutes` in abstract `F` with a `Trace` constraint.
- Lift it into our `EntryPoint`'s `F`, which has _no_ `Trace` constraint.

```scala mdoc
// Nothing new here
import cats.effect.Bracket
import natchez.{ EntryPoint, Trace }
import org.http4s.HttpRoutes

// This import provides `liftT`, used below.
import natchez.http4s.implicits._

// Our routes constructor is parametric in its effect, which has (at least) a
// `Trace` constraint. This means all our handlers will be in the same F and
// will have tracing available.
def mkTracedRoutes[F[_]: Trace]: HttpRoutes[F] =
  ???

// Given an EntryPoint in F, with (at least) `Bracket[F, Throwable]` but
// WITHOUT a Trace constraint, we can lift `mkTracedRoutes` into F.
def mkRoutes[F[_]](ep: EntryPoint[F])(
  implicit ev: Bracket[F, Throwable]
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
def mkRoutesResource[F[_]: Defer](ep: EntryPoint[F])(
  implicit ev: Bracket[F, Throwable]
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
  implicit ev: Bracket[F, Throwable]
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

## Limitations

Because we're instantiating `F` to `Kleisl[F, Span[F], *]` in our `HttpRoutes`, any constraints we place on `F` must be fulfillable by `Kleisli`. Things like `Monad` and `Deferred` and so on are fine, but effects that require the ability to extract a value (specifically `Effect` and `ConcurrentEffect`) are unavailable and will lead to a type error. So be aware of this constraint.

