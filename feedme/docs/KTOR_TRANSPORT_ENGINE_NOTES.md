# Ktor 3.5.2 transport engine audit

Inspected 2026-09-13 against the pinned **3.5.2** source and public Maven metadata. These are configuration recipes and source findings, not a claim that an iOS runtime or all recipes have been compiled or exercised. The transport implementation and its verification report determine the completed test evidence.

## Decisions that affect the factory

Use an explicitly constructed, privately owned client. The JVM/Android factory uses **OkHttp**, with explicit direct proxy selection, one-shot bodies, and a network interceptor that rejects a second exchange before sending request bytes. CIO 3.5.2 has no verified per-client direct-connection override: `proxy = null` consults the JVM global `ProxySelector`; `Proxy.NO_PROXY` is rejected as an unknown proxy type. A production factory must not temporarily change the global selector. Darwin can have cookies, cache, and credential lookup explicitly disabled, but the stock engine has an unbounded internal response-chunk queue and no exposed switch that disables every NSURLSession retry. These limits must remain visible while native verification is pending.

## Published modules and compiler baseline

The public Gradle module metadata was fetched successfully for all three engine coordinates at version 3.5.2:

| Gradle coordinate | Relevant published target module | Intended source set |
| --- | --- | --- |
| `io.ktor:ktor-client-core:3.5.2` | Resolved for each platform | `commonMain` |
| `io.ktor:ktor-client-okhttp:3.5.2` | `ktor-client-okhttp-jvm` | JVM and Android JVM source sets |
| `io.ktor:ktor-client-cio:3.5.2` | `ktor-client-cio-jvm`, `ktor-client-cio-iosarm64`, `ktor-client-cio-iossimulatorarm64` | Optional engine comparison/test targets |
| `io.ktor:ktor-client-darwin:3.5.2` | `ktor-client-darwin-iosarm64`, `ktor-client-darwin-iossimulatorarm64` | `iosMain` |

OkHttp's Ktor module publishes a JVM variant, not a distinct Android AAR. Android consumes the JVM library. Darwin's metadata requires Kotlin stdlib 2.3.21 and coroutines 1.11.0. Ktor's 3.5.2 version catalog pins Kotlin 2.3.21 and OkHttp 5.3.2. This matches the project's Kotlin pin; no compiler upgrade is required merely to add these clients. Variant availability is not native compilation or runtime evidence.

Sources: [CIO metadata](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-cio/3.5.2/ktor-client-cio-3.5.2.module), [OkHttp metadata](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-okhttp/3.5.2/ktor-client-okhttp-3.5.2.module), [Darwin metadata](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-darwin/3.5.2/ktor-client-darwin-3.5.2.module), [Ktor version catalog](https://github.com/ktorio/ktor/blob/3.5.2/gradle/libs.versions.toml), [official engine support](https://ktor.io/docs/client-engines.html).

## Common client policy

The factory's configuration must set `followRedirects = false` and `expectSuccess = false`; install `HttpTimeout` with positive bounded request, connect, and socket values. Do not install `HttpRequestRetry`, `HttpCookies`, `HttpCache`, `Auth`, `Logging`, or unreviewed interceptors. Build request URLs from a validated origin and catalog-owned paths. Do not accept an arbitrary URL, request builder, engine, or configurator at the production adapter boundary.

Validate the original origin text before normalization: reject user-info (including empty user-info), query or fragment markers (including empty `?`/`#`), whitespace/control characters, backslashes, unsupported schemes, unexpected paths, and ports outside the supported range. Production is HTTPS; a separate explicit local-test policy may permit exact loopback hosts and HTTP. Hostnames that only resolve to loopback are not equivalent to a literal permitted loopback address. The URL must be fixed before attaching bearer credentials.

Allow only intended request headers and catalogued operations. Response `Location`, cookie, challenge, and cache headers must never initiate a second request. Diagnostic results should contain stable local failure categories, not upstream body text, URL text, bearer values, exception messages, or `HttpResponse.toString()`. Omitting the Logging plugin is necessary but does not override a host application's global SLF4J/CFNetwork diagnostic logging configuration; audit that configuration before production use.

The common executor must explicitly send `Accept-Encoding: identity` and reject nonidentity response encodings before parsing. OkHttp otherwise adds `Accept-Encoding: gzip` and transparently decompresses, also stripping the response's encoding and length headers. Its bridge enables this behavior only when it adds that request header itself, so an explicit identity header disables this transparent path. Native NSURLSession compression behavior remains part of the native runtime verification gate. [OkHttp bridge implementation](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/BridgeInterceptor.kt).

Ktor only installs its redirect plugin when `followRedirects` is true. Error-response validation can save/read a body when `expectSuccess` is enabled, so use explicit bounded response handling. [Client source](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/common/src/io/ktor/client/HttpClient.kt).

## Bounded response consumption and ownership

Use the callback overload:

```kotlin
client.prepareRequest {
    // Policy-approved method, URL, headers, and serialized bounded request bytes.
}.execute { response ->
    val channel = response.bodyAsChannel()
    // Consume and validate inside this block only.
}
```

`execute { ... }` skips Ktor's saved-body buffering and cleans up the response in `finally`, including cancellation of its raw channel. `execute()` without the callback fully saves the response body. A `client.get(...)` followed by a length check is therefore not an allocation bound. Do not let the response or channel escape the callback. [HttpStatement source](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/common/src/io/ktor/client/statement/HttpStatement.kt).

A common-code reader can allocate a fixed `ByteArray(maxBytes + 1)`, with a small validated maximum below `Int.MAX_VALUE`. Repeatedly call `readAvailable(buffer, offset, buffer.size - offset)`. Reaching `maxBytes + 1` rejects the response immediately. At EOF, check `channel.closedCause` and propagate it before accepting bytes; do not silently accept a valid JSON prefix from a failed connection. Read failures and cancellation must still run the statement cleanup. Only decode UTF-8 and parse JSON after the byte bound passes. A content-length check is an early rejection, never the sole limit; chunked, missing-length, lying-length, and truncated responses require the stream limit. This bounds the adapter's retained response, not all OS/engine buffers. [ByteReadChannel operations](https://github.com/ktorio/ktor/blob/3.5.2/ktor-io/common/src/io/ktor/utils/io/ByteReadChannelOperations.kt).

`HttpClient(OkHttp) { ... }` owns its engine. `HttpClient(existingEngine) { ... }` leaves engine ownership with the caller. `client.close()` begins graceful asynchronous closure; tests that require deterministic shutdown may subsequently join `client.coroutineContext.job`. Cancel an active request for immediate cancellation rather than assuming `close()` synchronously cancels it. [Ownership and close implementation](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/common/src/io/ktor/client/HttpClient.kt).

## JVM / Android OkHttp recipe

The implemented factories are `shared/transport/src/jvmMain/kotlin/com/feedme/transport/PlatformHttpClient.kt` and the identical `androidMain` source. Both expose `internal actual fun platformHttpClient(): HttpClient`, with 15-second connection and 30-second overall/socket deadlines. The following excerpt explains the body protection; consult those files for the additional network-exchange guard and private connection pool.

```kotlin
engine {
    config {
        followRedirects(false)
        followSslRedirects(false)
        retryOnConnectionFailure(false)
        proxy(java.net.Proxy.NO_PROXY)
        socketFactory(DirectSocketFactory()) // See the complete factory implementation.
        cookieJar(okhttp3.CookieJar.NO_COOKIES)
        cache(null)
        authenticator(okhttp3.Authenticator.NONE)
        proxyAuthenticator(okhttp3.Authenticator.NONE)
        // Do not replace the default TLS socket factory or hostname verifier.
    }
    addInterceptor { chain ->
        val request = chain.request()
        val originalBody = request.body
        val guardedRequest = if (originalBody == null) {
            request
        } else {
            request.newBuilder().method(request.method, object : okhttp3.RequestBody() {
                override fun contentType() = originalBody.contentType()
                override fun contentLength() = originalBody.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: okio.BufferedSink) = originalBody.writeTo(sink)
            }).build()
        }
        chain.proceed(guardedRequest)
    }
}
```

This body wrapper assumes the adapter only emits finite, non-duplex request bodies. It marks every body as one-shot, including a zero-length write body. The factory's application interceptor also attaches a fresh private `NetworkAttempt` tag. A network interceptor atomically permits only the first exchange for that tag; a second exchange throws a fixed-message exception before `chain.proceed`. The guard therefore also covers bodyless GET/DELETE requests. A blocked bodyless 503 follow-up produces a local transport failure; a one-shot write returns the original 503 response. Neither sends a second HTTP request.

Ktor's OkHttp defaults already disable both kinds of redirects but **enable** `retryOnConnectionFailure`. Its `clientCacheSize` caches engine client instances, not HTTP responses; it is unrelated to disabling response caching. Do not disable that cache casually, because engine shutdown iterates cached instances to clean up pools and dispatchers. [Ktor OkHttp config](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpConfig.kt), [engine implementation](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpEngine.kt).

OkHttp 5.3.2 can generate a follow-up for `503` with `Retry-After: 0` even when connection retries are disabled. It can also repeat a misdirected `421` request on a coalesced HTTP/2 connection. The follow-up loop checks the body's `isOneShot()` and returns the original response instead of replaying that body. Connection failure and `408` recovery separately respect `retryOnConnectionFailure(false)`. The additional per-call network guard covers bodyless follow-ups. [Pinned OkHttp retry and follow-up implementation](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt).

OkHttp uses the system proxy selector if a proxy is not set. Explicit `Proxy.NO_PROXY` bypasses that selection. Its stock TLS setup selects a platform trust manager and its hostname verifier verifies hostnames; leave both enabled. `CookieJar.NO_COOKIES`, no cache, and `Authenticator.NONE` are defaults, but the explicit recipe documents and fixes the intended behavior. [Pinned OkHttp client implementation](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/OkHttpClient.kt).

A real JVM proxy test exposed another layer: the default Java socket's SOCKS implementation calls the global selector with a `socket://host:port` URI, even when OkHttp selected a direct route. The factories additionally use a private `SocketFactory` that constructs `Socket(Proxy.NO_PROXY)`. This prevents the lower socket layer from consulting the ambient selector without changing the TLS socket factory or verifier. The other `SocketFactory` overloads preserve direct construction and close the socket on connection failure. [OpenJDK SOCKS lookup](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/net/SocksSocketImpl.java), [Socket's explicit proxy constructor](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/net/Socket.java), [OkHttp socket creation](https://github.com/square/okhttp/blob/parent-5.3.2/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/ConnectPlan.kt).

## CIO findings

With `pipelining = false`, the dedicated-request path performs one request and does not have general post-write recovery. Keep `endpoint { connectAttempts = 1 }`; that option controls connection attempts, not HTTP-request retry. Do not permit the `Expect` header: CIO has a special `100-continue`/`417` path that rewrites the request. Its stock request timeout is 15 seconds, connect timeout 5 seconds, and socket timeout infinite, so explicitly configure all limits. Preserve stock HTTPS trust and hostname verification. [CIO endpoint config](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-cio/common/src/io/ktor/client/engine/cio/CIOEngineConfig.kt), [request path](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-cio/common/src/io/ktor/client/engine/cio/Endpoint.kt).

CIO's null proxy falls back to `lookupGlobalProxy(url)`, implemented on JVM by the process-wide `ProxySelector`. A `DIRECT` Java proxy maps to `ProxyType.UNKNOWN`, which CIO rejects at construction. This is why the strict direct-only JVM factory should use OkHttp. Test code may install a temporary selector and restore it in `finally`, but that process-wide mutation is unsuitable as a per-client production workaround. [CIO selection](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-cio/common/src/io/ktor/client/engine/cio/CIOEngine.kt), [JVM lookup](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-cio/jvm/src/io/ktor/client/engine/cio/Proxy.jvm.kt), [proxy type mapping](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-core/jvm/src/io/ktor/client/engine/ProxyConfigJvm.kt).

## Darwin recipe and limits

Use the Ktor-owned session and its stock redirect delegate. The following configuration uses Objective-C setters to avoid ambiguity around generated Kotlin property casing. Native compilation is required to confirm the SDK declarations in the actual build environment.

```kotlin
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)
fun makeDarwinClient() = HttpClient(Darwin) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    engine {
        configureSession {
            setHTTPCookieStorage(null)
            setHTTPShouldSetCookies(false)
            setURLCache(null)
            setURLCredentialStorage(null)
            setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            setConnectionProxyDictionary(emptyMap<Any?, Any?>())
            setTimeoutIntervalForRequest(30.0)
            setTimeoutIntervalForResource(30.0)
        }
        configureRequest {
            setHTTPShouldHandleCookies(false)
            setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        }
        handleChallenge { _, _, challenge, completion ->
            if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
            } else {
                completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }
}
```

Imports for this recipe are `io.ktor.client.HttpClient`, `io.ktor.client.engine.darwin.Darwin`, `io.ktor.client.plugins.HttpTimeout`, and `platform.Foundation.*`. The challenge branch preserves normal server trust evaluation and refuses credential challenges. Do not use `NSURLCredential` constructed from server trust, a custom trust bypass, or an arbitrary preconfigured session.

The Ktor delegate rejects native redirection by completing with a null request; common `followRedirects = false` is still required. Its default challenge branch supplies the proposed credential, so explicitly controlling challenge handling is relevant. [Darwin delegate](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/KtorNSURLSessionDelegate.kt).

Ktor creates `defaultSessionConfiguration()`, sets cookie storage to null, applies the supplied session configuration, then constructs its delegate. Preconfigured sessions bypass the configuration/challenge hooks. Closing the owned Darwin session calls `finishTasksAndInvalidate()`. [Session lifecycle](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/internal/DarwinSession.kt), [configuration API](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/DarwinClientEngineConfig.kt).

Apple explicitly documents nil credential storage as disabling the store, nil URL cache as disabling caching, and `httpShouldSetCookies = false` as disabling automatic cookie attachment. Ephemeral configuration alone would still create private in-memory stores; explicit nil settings matter. [Credential storage](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/urlcredentialstorage), [URL cache](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/urlcache), [cookie attachment](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/httpshouldsetcookies).

Darwin supports the Ktor request and socket timeouts, **not a separate connect timeout**. Ktor maps socket milliseconds to `NSMutableURLRequest.timeoutInterval` seconds. Do not report a configured common connect value as an enforced Darwin connect deadline. [Official timeout matrix](https://ktor.io/docs/client-timeout.html), [Darwin timeout adapter](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/TimeoutUtils.kt).

Apple documents null `connectionProxyDictionary` as using system settings. Supplying an empty dictionary is the candidate explicit direct configuration; confirm the exact target OS behavior using a controlled proxy and request metrics before claiming direct-only native runtime verification. Ktor's `proxy = null` does not itself override the native default. [Apple proxy configuration](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/connectionproxydictionary), [Ktor Darwin proxy application](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/ProxySupportCommon.kt).

The stock Darwin body handler queues `NSData` chunks in `Channel.UNLIMITED`. A bounded common reader therefore bounds accepted bytes and adapter allocation, but cannot guarantee a bound on all queued native response bytes. Strict total response-memory enforcement needs an engine/delegate-level bound or an alternative engine, with native stress tests. [Darwin body handler](https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/internal/DarwinTaskHandler.kt).

NSURLSession may retry idempotent requests such as GET and PUT. There is no verified blanket retry-disable property in the reviewed Ktor Darwin API. Use the correct POST command contract, retain command identity for explicit recovery, never automatically reissue a failed write in the adapter, and keep native dropped-connection/replay tests pending. Do not claim exactly-once server effects from transport configuration. [Apple QA1941](https://developer.apple.com/library/archive/qa/qa1941/_index.html).

## Required meaningful verification

Run the JVM suite with the **same hardened OkHttp factory** used by the adapter, against real loopback listeners. MockEngine proves adapter logic, not engine redirects, cookies, proxy selection, caching, retries, or socket cancellation.

1. A 301/302/303/307/308 response pointing at a second listener never reaches it, including after an authenticated POST. Classify the first response locally.
2. Count requests after POST plus `503 Retry-After: 0`, `408`, authentication challenge, and server disconnect after reading the complete body. Each command attempt reaches the server once. A malformed or failed response never triggers a write retry.
3. A `Set-Cookie` response followed by a second operation does not produce `Cookie`; repeated cacheable GETs each reach the listener; a global proxy selector pointing to a trap is never consulted by the direct factory.
4. Oversized fixed-length and chunked bodies fail at the common byte bound. An endless/trickling body is cancelled by the overall deadline. Slow headers and stalled bodies demonstrate timeout/cancellation behavior.
5. Truncated content length, connection failure after a syntactically valid JSON prefix, malformed JSON, invalid UTF-8, and wrong schema do not produce success. No body/token appears in the failure result.
6. Reject all invalid origins before the listener sees a request. Unsupported operation/method/path and expired/missing session credentials fail before request construction.
7. Cancellation closes the in-flight response; session changes cannot accidentally use stale credentials; adapter closure releases owned resources and prevents later operations.

An untrusted certificate/hostname rejection test needs a dedicated local TLS fixture, without weakening the production trust settings. Native compilation, simulator/device execution, native proxy/cookie/cache/challenge assertions, response-memory stress, and write-replay evidence remain separate verification tasks that require a usable Xcode/SDK environment. Android JVM engine tests do not replace the final Android device runtime check.
