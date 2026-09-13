# Exact server validation and shared wire models

Historical wire-validation step. The subsequent [transport](TRANSPORT_FOUNDATION.md) and [canonical validation/binding](CANONICAL_VALIDATION_FOUNDATION.md) continuations now implement mobile HTTP plus complete current-contract body checks and correct server format behavior. Use the latter document's receipt for current tests, dependencies and artifacts; claims and hashes below describe this earlier step.

Verified 13 September 2026. This continuation delivers working JVM body validation, bounded shared wire handling and cooking-slice data projections. It also connects outbound validation to the local HTTP service. **It does not connect authentication, product request handlers, mobile networking or a real cooking/social transaction. M0.07 remains in progress.**

## Delivered work and integration boundary

| Work package | Current evidence | Remaining gate |
| --- | --- | --- |
| M0.07b: authoritative server body validation | Networknt candidate verified and adopted; all 188 named schemas compile; all 201 request/response operation maps tested | Apply request validation after real identity/authorization and bounded HTTP body reading when handlers are implemented |
| M0.07b: presence-preserving shared wire models | Original bytes/numeric tokens retained; strict syntax, duplicate-key and resource guards; 27 JVM boundary tests | Native compilation/runtime under M0.05; not a native JSON Schema validator |
| M0.07d: cooking data projection foundation | Recipe/version/plan/session/save identities and full snapshots retained; 14 shared tests plus cross-layer tests | Domain feasibility, rights, recall, durable storage and actual authenticated UI/service orchestration |
| M0.07c: executable transport adapters | Metadata and wire boundaries are available | Not implemented: credential/session headers, parameter encoding/validation, safe retries, bodyless responses and lease/result isolation on real transport |

These are subordinate steps, not additional top-level milestone completions. No production feature acceptance box is checked. Related existing screens include RECIPE, ADAPT, VARIANT, RECOMMENDATIONS, MEAL_DONE and COOKBOOK; this work does not claim new native screen/button implementation. F30 post-save rights remain a separate transaction, not a generic private-save shortcut.

## Server validator in production source

`server/.../contract/ContractBodyValidator.kt` loads the same hash-pinned OpenAPI bytes as routing. Networknt **3.0.7**, Jackson **3.2.1** and Joni **2.2.6** are server-only dependencies. The [verified candidate report](JVM_SCHEMA_VALIDATOR_SPIKE.md) records primary sources, dependency hashes and the original **158/158 cases + 18/18 guards** spike. The rejected stock DTO generator and rejected OptimumCode KMP validator remain unadopted.

Both schema and instance JSON are parsed using BigInteger/BigDecimal, never Double/Long first. Original local `#/components/schemas/…` references work without relocation. A single immutable schema tree and deduplicated, eagerly initialized compiled-schema map handle shared response references. All external resource loading is denied. Boolean/fail-fast validation avoids collecting attacker-controlled error messages.

The API validates a named schema or an operation's request/response body with a declared media type. `null` byte-array arguments mean body absence; empty bytes are malformed JSON when a body is required. An operation with no request body, or a bodyless 204 response, rejects supplied bytes. Errors retain `application/problem+json`; success media is selected from the contract. Media parsing handles quoted semicolons and escapes, rejects malformed trailing content/duplicate parameters/CRLF, and accepts only UTF-8 when charset is specified.

Results are `Valid` or a stable rejection reason: syntax, resource limit, schema violation, missing/unexpected body, unknown schema/operation/status or unsupported media. No input values, field names, Jackson nodes or library exception text/cause appear in results. Trusted initialization or unexpected validator failures throw redacted configuration/internal errors; they never return success. Validity is **not** authentication, authorization, ownership, entitlement, consent or recipe approval.

### Explicit bounded wire profile

Defaults are 1 MiB UTF-8 body, nesting depth 64 and numeric-token length 1,000. Server decimal absolute scale is bounded at 10,000 before schema evaluation; field-name/string bounds are explicitly configured. Duplicate decoded property names, non-JSON primitive tokens, invalid UTF-8 and lone surrogate strings/keys are rejected, not replaced. Valid Unicode surrogate pairs and exact numbers beyond Long, including `1e400`, remain supported.

These are operational/interoperability limits, not additional minima/maxima silently added to the canonical schemas. Oversize/over-depth/over-scale inputs receive resource-limit failures. The shared wire layer preserves number tokens without arithmetic and therefore does not need the server's decimal-scale arithmetic bound. Real HTTP adapters must enforce byte bounds **while reading**, before allocating a complete body; the current byte-array validation API alone does not provide streaming ingress protection.

The Unicode profile follows [RFC 8259's interoperability discussion](https://www.rfc-editor.org/rfc/rfc8259#section-8.2): the grammar can contain unpaired surrogate escapes, but implementations handle them unpredictably. FeedMe deliberately rejects them. Do not describe that choice as an extra recipe-domain constraint or as proof of universal JSON conformance.

## Shared Kotlin wire and cooking models

`WireDocument` retains original valid UTF-8 text and returns fresh output buffers. Its immutable document cannot be altered through exposed lists, detached JSON trees or snapshots. `WireBody.Absent` differs from `Present(JSON null)`. `WireField.Missing`, `Null` and `Value` preserve field presence for every schema, including the optional-nullable plan cursor. All original unknown fields, array ordering/duplicates and exact numeric spelling remain present; schema validation decides whether they are allowed.

The lexical guard bounds input and catches duplicate keys before kotlinx.serialization's structural parser. It does not replace that parser or implement JSON Schema. `WireDocument.parse/decode` succeeding is not evidence of schema validity. Shared common source is tested on JVM; native/iOS execution remains unverified.

`CookingWireModels.kt` provides:

- Distinct, redacted RecipeId, RecipeVersionId, PlanId, CookSessionId and SavedRecipeId types, plus source/grant/ingredient/timer identifiers. Step IDs remain stable strings, not array positions or assumed UUIDs.
- Constructive `CookStart(PlanId)` and `SaveRecipeRequest` builders. Save's anyOf stays nonexclusive: either or both plan/version selectors are allowed. A save-from-post grant is not invented from a known post ID. The optional CookStart sequence builder supports nonnegative Long, while raw wire data retains larger exact values.
- Full-document recipe, ingredient, step, plan, cooking-session, timer and saved-snapshot projections. ExactWireNumber stores a numeric token rather than rounding. Missing/null cursor, current/completed step IDs, device sequence, recall, source/grant references and immutable snapshots survive mapping.

These are typed convenience projections, **not 188 fully validated generated DTO classes**. Unknown enum states are retained as strings, not promoted to approved/published content. Future domain/UI adapters must fail closed on unsupported states, ranges and rights. Projected fields require their expected structural type, but remaining schema and business rules still belong to validation/domain services.

## Actual HTTP correction

Integration inspection found that the existing local Problem formatter used `application/json` despite the canonical `application/problem+json`. The formatter and real-loopback/header regressions now use the declared media type.

Local health and every unavailable canonical response are checked against the current response schema before sending. Unknown-route/internal-error Problems use the canonical named Problem schema. A deliberately out-of-RFC3339 health timestamp is rejected and becomes a redacted 500 Problem. The formatter sets Problem.status from the actual HTTP status; generic schema validation alone does not invent that relationship because the canonical Problem schema has no status-specific const.

Only public degraded health is implemented. The remaining 200 routes still return explicit 503; incoming product bodies are **not** parsed/accepted as authorized commands. No fake token, webhook acknowledgement, account, social post or payment is enabled.

## Verification and artifacts

| Check | Result |
| --- | --- |
| Shared contract tests | 55 passed: 14 metadata, 27 wire boundary, 14 cooking projection |
| Existing shared core tests | 46 passed; total shared JVM tests 101 |
| Server unit/integration-boundary tests | 37 passed, including 149 real canonical fixtures, all 201 operations / 2,613 response mappings, 400 concurrent validations and the HTTP formatter |
| Cross-layer data checks | All 149 canonical fixtures round-trip byte-for-byte through shared wire; all 9 documented request examples validate; typed cooking/save mappings validate without conflating identities |
| PostgreSQL integration regressions | 45 passed against newly isolated PostgreSQL 15.19 clusters with the new server classpath |
| Node regressions | 92 passed: 75 generator + 14 tracker + 3 backup; generated source has no drift |
| Exact packaged server distribution | Four loopback probes passed: health, private config unavailable, plan write unavailable, unknown route; service explicitly stopped |
| Android / iOS | APK unchanged; no new Android run. iOS remains uncompiled because full Xcode is missing |

[Verification receipt](verification/wire-validation/verification.json) contains all source/runtime-JAR hashes and JUnit files; [packaged smoke evidence](verification/wire-validation/distribution-smoke.json) records actual media/status outcomes. The latest server JAR SHA-256 is `f4623d1280884de29f445a4449139fcd7775129e39f9e49eecc140a74bc38ab8`; shared contracts JVM JAR is `cf717687b9e1c3f5895af2851680c635733d9b90b58288a210d886b9c43cfba6`. Previous metadata/storage receipts are historical and do not identify these new JARs. The Android hash remains the one in BUILD_STATUS.md.

Temporary PostgreSQL test clusters and the distribution smoke process were stopped; synthetic cluster files remain for inspection. No existing database or other local service was modified. The server still uses the NOP logger, not production monitoring. No native, performance, deployment or security-feature certification follows from these tests.

```sh
node scripts/generate-contract-artifacts.mjs --check
./gradlew --no-daemon --console=plain :shared:contracts:jvmTest :shared:core:jvmTest :server:test :server:installDist
FEEDME_POSTGRES_BIN=/opt/homebrew/opt/postgresql@15/bin ./gradlew --no-daemon --console=plain :server:integrationTest
```

Next safe step: implement the executable shared transport adapter against the existing AccountTransport/session-lease boundary, keeping anonymous/guest/account bootstrap and registered sessions distinct. Connect a local protocol test before involving provider setup. Authentication, durable feature transactions and real UI orchestration then need their own evidence; owner approvals, iOS build and all retained feature/release gates remain open.
