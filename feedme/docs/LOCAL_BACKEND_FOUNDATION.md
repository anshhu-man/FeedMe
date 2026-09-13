# Local backend foundation — M1.01

Implemented and verified 13 September 2026. **This is a local development scaffold, not a deployed backend or working account/social service.** Android and iOS remain disconnected from it.

## What actually works

| Request / behavior | Actual result |
| --- | --- |
| `GET /v1/health` (`getServiceHealth`, F54) | Public HTTP 200 with canonical `Health`: `status: degraded`, server UTC time and minimum app version |
| Other 200 canonical operations | HTTP 503 with canonical `Problem`, code `OPERATION_NOT_IMPLEMENTED`; no reads, writes, credentials accepted or successful webhook acknowledgements |
| Private `GET /v1/config` | Same 503; it is not public configuration and does not expose invented feature flags |
| Staff `GET /v1/admin/health` | Same 503; it never aliases public health |
| Unknown paths / unsupported methods | HTTP 404 JSON `Problem`, without reflecting path/query contents |
| Handler input error / unexpected error | Redacted HTTP 400 / 500 JSON `Problem`; cancellation propagates |
| Response metadata | Server-created UUID trace, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`; no cookies or CORS permission |

Health means the local process can answer, **not readiness to accept product traffic**. The degraded status is deliberate: there is no database, identity adapter, feature configuration store or provider health check. No `Retry-After` is invented for missing implementation. Clients must not endlessly retry this permanent development placeholder.

The F54 administrative screens (`ADMIN_HOME`, `ADMIN_AUDIT`, `ADMIN_FLAGS`, `ADMIN_INCIDENT`) are not implemented by public health. No screen/action binding changed, and no native button was connected to this service. No full feature work package is complete.

## Boundaries and production integration path

The `server` JVM module follows the blueprint's Kotlin/Ktor modular-monolith direction, without selecting cloud infrastructure. Its current packages separate startup, environment validation, canonical routing metadata and HTTP behavior:

- `config/LocalServerConfig`: accepts only local mode and a literal IPv4 loopback bind; rejects bad ports, invalid version strings and unknown `FEEDME_SERVER_*` settings before starting. Validation messages do not echo supplied values.
- `contract/ContractCatalog`: packages the existing canonical OpenAPI document, checks its pinned byte hash, extracts all 201 unique operations and retains module/principal metadata. This is routing metadata, **not** an authorization or JSON Schema validator.
- `http/FeedMeApplication`: implements the public health response and explicit unavailable routes; centrally redacts handler failures and response metadata. It never logs request bodies, tokens or exception contents. SLF4J currently has no provider and emits a startup warning; production observability is not configured.
- `Main`: validates configuration and the contract before opening the CIO listener. There is no production-mode switch, deployment manifest or public tunnel.

To implement the next real route, keep the canonical operation ID/path and replace only that route's unavailable handler after adding its request/response validation, principal/device-session verification, object authorization, durable transaction, idempotency and tests. Guest, account, staff and webhook credentials remain different trust domains; the stored `principal` label does not enforce them. Add request size limits, rate limiting and safe structured monitoring before accepting untrusted product traffic. Client DTO/domain mappings and identity/storage ports remain separate from this HTTP layer.

M1.02 subsequently added independently tested local database migrations and durable command/outbox behavior; see [storage evidence](DURABLE_STORAGE_FOUNDATION.md). No HTTP handler is connected to those primitives yet. Provider accounts, regions, permanent identities, deployment and spend still require the corresponding user actions. M1.07's server-authorization tests and all 43 production security groups remain outstanding. The M1.01 test counts/artifact receipt below are historical evidence for this first scaffold slice; current totals are in BUILD_STATUS.md.

## Local configuration

| Environment key | Default / constraint |
| --- | --- |
| `FEEDME_SERVER_MODE` | `local`, the only accepted value |
| `FEEDME_SERVER_HOST` | `127.0.0.1`, the only accepted value |
| `FEEDME_SERVER_PORT` | `8780`; integer 1024–65535 |
| `FEEDME_MINIMUM_APP_VERSION` | `0.1.0-dev`; bounded three-part version with optional development suffix |

No `.env` loader or credentials are required. This local-only guard prevents accidental remote binding in this entry point; it is not a substitute for production network isolation, TLS or authorization.

Use the root project's JDK 17 / Android SDK setup, then:

```sh
./gradlew --no-daemon --console=plain :server:test :server:installDist :shared:core:jvmTest
./gradlew :server:run
```

The second command stays running on `http://127.0.0.1:8780`; stop it with Ctrl-C. The test suite starts an ephemeral loopback CIO server and stops it in `finally`; no server is left running by verification. `server/build/install/server/bin/server` is the generated local launcher, not a production deployment artifact.

## Evidence and limits

17 backend tests passed: 5 configuration, 4 contract, 7 HTTP behavior and 1 real CIO listener test. The HTTP suite sends a request to each of the 200 unavailable operations, including fake credentials/body data, and confirms non-success responses. This is **unavailability coverage**, not 200 implemented APIs or authorization coverage. The loopback test uses Java's independent HTTP client for health, private config, staff health and an unknown path. `installDist` completed; all 46 existing shared Kotlin tests passed again. Android assembly/lint and a fresh 14-check emulator journey also pass; the Android runtime dependency report includes neither Ktor nor the server module.

JUnit XML, artifact/source hashes and the evidence receipt are retained in [backend verification](verification/backend-foundation/report.json). A read-only independent review found no actionable issue in this bounded slice. Tests are not load/performance certification, a security audit, a database test or two-platform integration evidence.

The canonical source remains `../outputs/biteclub_blueprint/architecture/04_API_Contract.json` relative to the project root, OpenAPI 3.1.1, SHA-256 `f0a5f20c4a84822813137c3e6b1ef31ec22afd1d7ee384f7f29b5df09e10aba0`. A changed source makes the catalog fail closed until its lock and behavior are reviewed; missing resources also fail startup. This byte lock is intentional drift detection, not general schema validation.

Ktor 3.5.2 and kotlinx.serialization JSON 1.11.0 are pinned in the version catalog. The published Ktor JVM metadata specifies Kotlin 2.3.21, matching this project; compilation verified that combination here. See [official Ktor releases](https://ktor.io/docs/releases.html), [embedded server configuration](https://ktor.io/docs/server-configuration-code.html), [server testing](https://ktor.io/docs/server-testing.html) and [published dependency metadata](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-core-jvm/3.5.2/ktor-server-core-jvm-3.5.2.pom). The new dependencies have not yet undergone M0.08's license/security inventory.
