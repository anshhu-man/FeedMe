# Shared contract metadata — M0.07a

Verified 13 September 2026. **The reproducible metadata work package is complete; M0.07 is not.** This is a shared Kotlin catalog of the unchanged contract, not a generated HTTP client, body validator, authenticated adapter or live feature.

Subsequent update: [wire/server validation foundation](WIRE_VALIDATION_FOUNDATION.md) now adds shared wire/projection code and an adopted JVM server validator. The original metadata-only artifact hashes and test receipts below remain historical; use that newer document for current JARs and verification. M0.07 still requires native/transport/live-integration gates.

## Small work packages

| Package | State | Exit evidence / remaining work |
| --- | --- | --- |
| M0.07a — lossless artifacts and operation metadata | DONE | Pinned source, deterministic regeneration/drift check, 75 generator tests, 14 shared metadata tests, server parity test |
| M0.07b — faithful body validation and presence-aware wire models | IN_PROGRESS | CANONICAL_VALIDATION_FOUNDATION.md: exact server and full current-vocabulary shared body validation, 188 schema baselines, 8,423 mutations, 167 format cases. Native proof and authorized ingress remain; no claim of 188 complete typed DTO classes |
| M0.07c — actual protocol/transport adapters | IN_PROGRESS | Executable shared mobile transport now validates complete request/response bodies and binds Problem status/trace metadata; Android library build and real protocol tests pass. Native evidence and staff/webhook adapters remain |
| M0.07d — production domain mapping and authorized slice | IN_PROGRESS | Cooking data projections/cross-layer fixtures now verified; real domain, storage, ownership/lifecycle and UI orchestration remain |

These are subordinate work packages, not additional independent feature estimates or new top-level milestone rows. No feature acceptance box or entire milestone is closed by M0.07a.

## Source and generation

Canonical OpenAPI 3.1.1 remains at `../outputs/biteclub_blueprint/architecture/04_API_Contract.json`. SHA-256 is `f0a5f20c4a84822813137c3e6b1ef31ec22afd1d7ee384f7f29b5df09e10aba0`. The original bytes are embedded as escaped/chunked Kotlin strings; no JSON-number conversion, schema rewriting, nullable flattening or Set conversion occurs in the artifact. The reconstruction regression checks the complete original string, not a hand-selected subset.

`scripts/contract-source.lock.json` pins source and generator identity. `scripts/generate-contract-artifacts.mjs` produces only:

- `shared/contracts/.../generated/GeneratedContract.kt`: full original contract plus normalized screen-operation bindings.
- `docs/verification/contract-artifacts/generated-receipt.json`: source, registry, generator-code and generated-source hashes; coverage counts and reviewed keyword inventory. No timestamp makes regeneration deterministic.

Generation audits component schemas, parameters, responses and each operation; references, status/media shapes, source security declarations, idempotency/header locations, device-session policy and feature references must agree. Unreviewed keywords, types, formats, path-item fields, callbacks, external schema references or media shapes stop generation for review. This is a supported-shape audit, **not** validation of instance bodies or schema defaults. Example payload `$ref` strings remain literal example data, never remote lookup instructions.

The screen registry yields all **900 actions**, **99 HTTP hydrations** and **32 LOCAL/EXTERNAL hydrations**, retaining screen and action identity. All **152 HTTP action bindings** match the canonical method/path/operation ID. Local/provider actions are not manufactured into HTTP endpoints. This generator checks `screen_registry.json`; it does not independently re-parse `button_actions.csv` or prove that the screens/buttons exist natively.

Gradle's shared-contract compile/check tasks run `--check` and fail if source, registry, generator code or generated artifacts drift. Node.js is therefore a declared local build prerequisite. Source review and deliberate regeneration are required after a contract change; changing a lock is not automatic approval of changed behavior.

## Shared Kotlin boundary

The new `:shared:contracts` module targets JVM, iosArm64 and iosSimulatorArm64 using the existing Kotlin 2.3.21 / serialization JSON 1.11.0 toolchain. It contains no Ktor client, provider credentials, secure storage or validator dependency. The module is currently used only by the server's parity test, not the mobile app or server production runtime.

`ContractCatalog` exposes 188 schema definitions and all 201 operations with path/query/header schemas, request-body media, every declared response status, Problem media, response-header metadata and original descriptions. `operationJson` retains full fields—including examples/tags not in convenience projections. Original local schema references are retained and resolved only against the bundle. Schema JSON and formatted strings are metadata; no function labels an instance valid.

The convenience surface views contain 162 mobile, 38 staff and one webhook operation. `requirementsFor` distinguishes caller classes and returns security-scheme/header metadata. In particular:

- Account bootstrap needs account bearer credentials but no existing app device-session ID.
- Subsequent account operations need the registered X-Device-Session header, including dual account/guest operations where the raw parameter is conditionally optional.
- Guest requests omit that header and cannot call account-only operations.
- Staff/webhook operations are absent from the mobile view. A caller category is not authentication; these views are not server access control, and metadata is not secret.

The server parity test compares every route's method/path/principal/module, source hash and all 188 component schemas against the server's separately bundled canonical resource. The production server remains local-only: degraded public health works; its other 200 routes remain explicitly unavailable. No account, upload, save, post or purchase is sent by this work.

## Verification

| Check | Result |
| --- | --- |
| Deterministic generator and negative mutation tests | 75 passed |
| Existing tracker / Android backup Node regressions | 14 + 3 passed; combined Node report has 92 tests |
| Shared contract metadata tests | 14 passed; includes all 1,005 operation/caller and 603 operation/surface combinations |
| Shared core JVM tests | Existing 46 passed/up-to-date; no core source changed |
| Server JVM tests | 25 passed, including new shared/server parity; real CIO loopback test runs and closes |
| Local server distribution | Builds/up-to-date; production JAR unchanged |
| iOS/native contract compile/runtime | NOT RUN; full Xcode remains missing |
| Android / PostgreSQL integration rerun | Not needed for unchanged production sources in this package; prior specific receipts retained, not relabeled as new runs |

[Verification receipt](verification/contract-artifacts/verification.json) records source hashes, JUnit XML and exact artifact hashes. Shared contract JVM JAR SHA-256: `1f0590235f669e5866e6d1d3002e9b8a7041d36de08def59fcdea664b24f406d`. The current Android APK and server production JAR are unchanged. Native parity, runtime performance and live security are separate gates.

```sh
node scripts/generate-contract-artifacts.mjs --check
node --test scripts/generate-contract-artifacts.test.mjs
./gradlew --no-daemon --console=plain :shared:contracts:jvmTest :shared:core:jvmTest :server:test :server:installDist
```

Use `--write` only after reviewing an intentional contract/generator change. JDK 17, the existing SDK configuration and standard Gradle cache access are required.

## Validator finding and next decision

The [actual OptimumCode validator spike](SCHEMA_VALIDATOR_SPIKE.md) compiled against the selected Kotlin/serialization versions, but **154/158 cases passed and four failed**. Three failures affect existing canonical numeric fields: two inaccurate minimum comparisons and one integer overflow exception. The fourth is a synthetic multipleOf probe. None of that validator dependency or its schema relocation was adopted.

The next continuation adopted a separately verified exact JVM validator and shared presence-preserving wire types; see WIRE_VALIDATION_FOUNDATION.md for evidence and explicit operational limits. The server remains authoritative; no common native schema validator is claimed. Do not silently round/narrow data to accommodate DTOs. Continue executable protocol/domain integration while native/provider/owner gates remain open.
