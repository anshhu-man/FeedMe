# FeedMe executable transport — M0.07c

13 September 2026. Shared request preparation and an owned HTTP adapter are implemented. The subsequent [canonical validation/binding step](CANONICAL_VALIDATION_FOUNDATION.md) adds full current-contract request/response checks to the public adapter. This is a **local protocol foundation**, not working sign-up, a production backend, native iOS parity, or a shipped cooking/social feature. The Android app still explicitly runs its memory-only demo and does not depend on this transport module.

## What executes

`:shared:transport` targets JVM, Android, iosArm64 and iosSimulatorArm64. JVM and Android use identical hardened OkHttp factory source; iOS has a Darwin candidate whose compilation and runtime gates remain open. The transport uses the unchanged catalog's 162 mobile operations. Staff and webhook operations are rejected at this client boundary; their executable adapters are separate unfinished work.

| Boundary | Executable behavior |
| --- | --- |
| Trusted endpoint | HTTPS origin configured only at the application composition root; no user info, path, query, fragment or invalid port. Explicit HTTP test factory permits only literal `127.0.0.1` |
| Request intent | Canonical operation ID, declared path/query parameters, immutable body bytes, original idempotency key and optional If-Match; no arbitrary URL/header/client configurator at the public factory |
| Scalar preparation | Known/required parameters, single-value cardinality, UUID, enum, Unicode schema lengths, integer bounds without overflow and actual schema patterns; no default injection |
| Resource policy | At most 4,096 UTF-16 units per parameter value and 16,384 combined; bounded request/response wire JSON: 1 MiB, depth 64, number token 1,000 characters; malformed Unicode/control values rejected |
| Public calls | Explicit `PublicTransport`; no credential-store lookup and no Authorization/device-session headers |
| Signed-in calls | Lease/environment/kind checks, secure-store read, expiry/scope check, correct account or guest bearer, required registered account device-session UUID; bootstrap and guest calls omit that header |
| URL encoding | Catalog-owned path segments; dot segments rejected, dynamic segments percent-encoded and query values appended as values, not interpolated URL syntax |
| Response handling | Declared status/media, bounded metadata, positive integer Retry-After, strict UTF-8 JSON and full current schema validation; Problem status/trace binding; exact bytes and metadata preserved |
| Bodyless response | A declared 204 remains absent bytes; not an empty JSON object or explicit JSON null |
| Uncertain mutation | Lost, malformed, oversized or otherwise unusable receipt after possible dispatch returns OUTCOME_UNKNOWN. No automatic retry or newly generated key |

The media policy accepts canonical JSON/problem media with an optional UTF-8 charset. Other media parameters/charsets, HTTP-date Retry-After, duplicate tracked headers and nonidentity response encodings fail closed. These are explicit transport interoperability/resource restrictions, not changes to the canonical JSON schemas. No body is truncated and accepted as a partial document.

`ApiCall` detaches collections; `PrivateBytes` detaches buffers. `ApiReply` now retains content type for the next decoding boundary. Transport/parser failures expose stable categories, not upstream exception messages, credentials, response text or diagnostic causes. Error HTTP responses are `Value(ApiReply)` when their receipt is usable; they are not thrown away by status-based exceptions.

## Ownership, cancellation and safe integration

The application must own `SessionBoundary.activate/clear`, adapter calls and `close()` on the **same serialized dispatcher**, normally UI Main. This is a documented integration requirement, not something a private adapter mutex can enforce on unrelated callers. A parallel dispatcher is not sufficient.

Before network dispatch the adapter rechecks the lease after any secure-store suspension. It rechecks again after the HTTP exchange, so an old owner's response cannot be applied to new-owner state. Environment and guest/account/demo scopes cannot be interchanged. Credentials are never written, refreshed or revoked by this layer. Those provider-specific operations need the user's configuration and separate implementations.

Closing the adapter cancels its owned client job and closes the client; post-close work cannot return a usable response. Cancellation propagates, but cancellation, close, timeout or a stale-session result **cannot roll back an already dispatched server command**. The future durable command coordinator must persist original intent/key before dispatch and reconcile same-key outcomes after interruption, including after a session change with renewed authorization. Neither a fresh key nor an assumed failure is a safe recovery strategy.

Public bootstrap responses also need lifecycle ownership in the future identity coordinator; this transport never installs credentials from such responses. No connectivity monitor is treated as server reachability or authorization proof. An explicit OFFLINE observation stops dispatch; unknown/online observations may still fail over the network.

Integration order:

1. Supply approved fixed endpoint/environment, actual native secure store, clock/connectivity and a serial owner dispatcher; retain one owned adapter and close it at its lifecycle boundary.
2. Restore/verify identity through the approved provider and activate a lease; account bootstrap receives the registered device-session identity before ordinary account calls are enabled.
3. Apply business/authorization rules and persist durable mutation intent/key before sending. Public FeedMeTransport now validates complete body schemas as well as the separate scalar-preparation checks.
4. Project only approved business states into domain models. The public adapter now validates and binds every response; schema-valid recalled/unreviewed data still must not be treated as published, feasible, authorized or unrecalled content.
5. Apply domain/private-storage changes only under the still-current lease. Retain uncertainty for authenticated same-key reconciliation; do not persist transport objects directly as domain state.

Identity, durable coordination, authorized business rules and domain/storage/UI integration in steps 2–5 still require implementation. The current server validates outbound local Health/Problem payloads and rejects unfinished product routes; it does not yet authenticate, accept or persist their incoming commands. The validators and shared projections are building blocks, not a completed end-to-end feature.

## Engine policy and evidence

The owned JVM/Android factory disables redirects, connection retries, automatic cookie/cache/auth stores, event logging, ambient HTTP proxies and ambient Java SOCKS lookup. Direct sockets retain normal platform TLS/hostname checks. Bodies are one-shot, and a per-call network interceptor rejects a second exchange before request bytes are sent, including otherwise-retried bodyless calls. A common request explicitly asks for identity encoding. Connection timeout is 15 seconds; request/socket deadlines are 30 seconds.

Testing exposed two non-obvious defaults: OkHttp can repeat a 503 with Retry-After 0 even when connection retries are disabled, and default Java sockets can consult the process SOCKS selector even with a direct OkHttp route. The explicit exchange guard and direct socket factory address these. The first proxy regression failed before the fix; final passing evidence must be used, not that earlier result. [Pinned engine source audit](KTOR_TRANSPORT_ENGINE_NOTES.md).

The statement's streaming callback avoids Ktor's saved-body buffering. A max+1 reader checks failure at EOF and rejects excess accepted bytes. This is **not a bound on all engine/OS memory**. Darwin's current chunk queue is unbounded, and no blanket native retry-disable switch was verified. Its configured cookie/cache/credential/proxy/trust behavior is a candidate, not native proof. The native transport must pass real stress, interrupted-write, proxy, TLS, cancellation and lifecycle tests—or use a verified alternative—before release.

## Verification and remaining gates

The original [transport verification receipt](verification/transport/verification.json) is historical; use the newer [canonical-validation receipt](verification/canonical-validation/verification.json) for current source/artifact hashes and tests. Common tests run on JVM; real loopback tests use the production owned JVM factory; the server integration uses the actual local CIO application. Android compilation/lint of the transport library is not an Android device network test. Existing APK/emulator receipts remain historical and are not relabeled as execution of this module.

```sh
./gradlew :shared:transport:jvmTest :shared:transport:assembleDebug :shared:transport:lintDebug :shared:core:jvmTest :shared:contracts:jvmTest :server:test :server:installDist --console=plain
node scripts/generate-contract-artifacts.mjs --check
node scripts/verify-delivery-plan.mjs --write-report
```

M0.07c remains IN_PROGRESS: current-contract response binding is implemented, but native execution and staff/webhook adapters are not done. M0.07d still requires production identity, durable private command/state coordination, authorized feature handlers and real UI integration. No cloud deployment, credential provisioning, notification delivery, store signing, purchase, publication or submission occurred. All 54 features and 98 screens remain in scope and unclaimed as fully implemented.
