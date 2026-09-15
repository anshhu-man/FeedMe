# FeedMe — private kitchen persistence and HTTP ingress

14 September 2026. **Verified as a bounded component in the fifth combined batch**, completed2026-09-14T01:54:18.106Z. This package implements canonical preferences, rough pantry persistence and configured ingredient search. It is not a deployed service, a production identity/catalog adapter or proof that the whole application is ready to ship. [Current handoff and frozen evidence](PARALLEL_KITCHEN_COOKING.md), [BUILD_STATUS.md](BUILD_STATUS.md).

## Canonical operations

[KitchenHttpRoutes](../server/src/main/kotlin/com/feedme/server/http/KitchenHttpRoutes.kt) maps the six existing operations to [KitchenStore](../server/src/main/kotlin/com/feedme/server/kitchen/KitchenStore.kt) and an explicitly supplied search adapter. Both verified account and bounded guest principals are supported; resource ownership is never taken from a body or query parameter.

| Operation | Method and path | Success | Command and version controls |
| --- | --- | --- | --- |
| `getPreferences` | GET `/v1/preferences` | 200 `Preference`, ETag | No command key or conditional-read response. |
| `updatePreferences` | PATCH `/v1/preferences` | 200 `Preference`, ETag | Original Idempotency-Key and If-Match required. Omitted fields preserve existing values. |
| `listPantry` | GET `/v1/pantry/items` | 200 `PantryItemPage` | Cursor/limit only; no collection ETag. |
| `upsertPantryItem` | POST `/v1/pantry/items` | 200 `PantryItem`, ETag | Original Idempotency-Key. An existing item requires body `expectedVersion`; there is no If-Match header. |
| `removePantryItem` | DELETE `/v1/pantry/items/{ingredientId}` | Empty 204 | Original Idempotency-Key and If-Match. No request body, response JSON, media type or ETag. |
| `searchIngredients` | GET `/v1/ingredients` | 200 `IngredientPage` | Optional q/cursor/limit, with mandatory current authorized catalog adapter. No command key or collection ETag. |

The canonical contract, not older feature prose suggesting a pantry collection ETag, controls these responses. Undeclared query fields, conditional headers and mutation controls on reads are rejected. Missing required If-Match is 428; stale versions are 412. Schema/input policy errors are 422, malformed or ambiguous ingress is 400, and missing owned preferences/items are 404. Same-key different intent is 409; an expired command receipt is 410. An uncertain commit is 503 `OUTCOME_UNKNOWN`, not a declaration that no write occurred.

## Trust, provisioning and retained references

[KitchenHttpConfiguration](../server/src/main/kotlin/com/feedme/server/http/KitchenHttpConfiguration.kt) requires the environment, real store, verifier, catalog-search adapter and database dispatcher. The verifier must authenticate the actual account token or bounded guest session, including the applicable issuer/audience/token-use/expiry rules. Account responses must match the supplied device-session binding; a verified guest must omit that header. Parsed token spelling or device-header presence is not proof of token kind. There is no accepting verifier, chosen provider, built-in catalog or automatic product-route configuration. Default Main remains health-only with unconfigured product operations returning503.

[KitchenAuthority](../server/src/main/kotlin/com/feedme/server/kitchen/KitchenAuthority.kt) is a second, mandatory transaction-time check. It must lock and revalidate current principal eligibility, device revocation or guest expiry/merge. New preference/pantry writes require current reference and policy validation. The callbacks do not rewrite user selections or silently expand dietary presets; actual catalog, taxonomy, equipment, consent and reviewed-preset policies remain integration obligations.

Preferences have **no permissive GET default**. A trusted lifecycle caller must explicitly invoke `provisionPreferences` with all required initial fields and pass the separate provisioning authority check. Repeating the same initial fields can return the existing resource; conflicting existing fields are not reset. No HTTP operation invokes provisioning. Account bootstrap, guest lifecycle, merge and erasure workflows must be integrated explicitly.

Owned reads and exact-current successful command replays do not require an ingredient to remain selectable or published. They return persisted private IDs/values, not catalog bodies or cooking permission. This preserves editable exclusions and stale rough pantry reports when catalog matching is compromised. New selections still undergo policy validation; owned removal remains possible after catalog retirement. Production preference policy must distinguish retained exclusions from new unsupported selections rather than silently dropping them.

## Transactions, versions and receipts

[V004__private_kitchen.sql](../server/src/main/resources/db/migration/V004__private_kitchen.sql) adds `profile.preferences` and `pantry.pantry_items`, scoped by environment, principal kind and principal UUID. V001–V003 bytes are unchanged; [PlatformMigrations](../server/src/main/kotlin/com/feedme/server/db/PlatformMigrations.kt) registers the new migration without rewriting their history.

Every mutation uses [DurableCommands](../server/src/main/kotlin/com/feedme/server/db/DurableCommands.kt): current principal authorization precedes receipt access, and domain change, original-key receipt and canonical outbox append commit in one PostgreSQL transaction. Original body/path/precondition identity is preserved across retries. JSON object member order and numerically equivalent decimal spellings are canonicalized for request hashing; arrays and differing values are not silently rewritten. Decimal values never pass through Double or the older prose's narrower numeric scale. Responses are reconstructed from actual stored JSONB values before acknowledgement.

Replay equality means the **entire JSON value, status and ETag**, not identical JSON object-member byte order. PostgreSQL JSONB may reorder members. A later preference/pantry version or replacement resource cannot be acknowledged as the old cached response. The service neither reapplies an old command nor rebases its precondition to make it succeed.

Pantry deletion removes the private field payload and retains an exact deletion marker. Versions increase through deletion and recreation; recreation gets a new resource UUID and does not reset the version. Thus an old If-Match or upsert receipt cannot affect the replacement, and only the exact recorded deletion can acknowledge an absent item. This is not a physical-erasure claim: durable command history has its separate seven-day receipt lifecycle, and account-wide erasure remains an integration task.

For a new pantry incarnation, omitted `confirmedAt` becomes null and omitted `staple` becomes false. Quantity, unit and confirmation status are not invented. On an existing item, omitted optional fields preserve the previous explicit report. Persistence does not establish freshness, safety, present stock, a usable quantity or current-meal confirmation, and cooking does not automatically consume pantry quantities.

Produced events are the registered `profile.preferences.changed.v1` and `pantry.item.changed.v1`. Preference data contains principalId, preferenceVersion and changedFieldKinds; pantry data contains principalId, ingredientId and action (`upserted` or `removed`). Events contain no bearer, preference lists, quantities or private input bodies. Outbox failure rolls back the mutation and receipt. No relay/consumer execution or notification delivery is implemented by this package.

## Bounds and planning integration

HTTP uses an explicit 64 KiB request/depth-32 profile. Framing rejects duplicate/folded security controls, conflicting Content-Length/Transfer-Encoding, unsupported compression/charset, invalid UTF-8, duplicate JSON keys and malformed Unicode before mutation. Replies are independently checked for canonical schema, exact status and version-matching ETag. Responses and Problems are no-store, with generated trace IDs and sanitized failures.

`KitchenServicePolicy.maxResponseBytes` is explicit, in 1..262144; HTTP reads that same store bound rather than applying a different post-commit cap. The focused normal fixture uses 65536 bytes, with a 512-byte negative case. Successful representations are validated against the bound **before the transaction commits**, so an oversized mutation cannot commit and then become a success that HTTP refuses to deliver. Production must choose a bound compatible with the client.

Page limit is 1..50, default 20. Search q allows 100 Unicode scalars, preserving absent versus empty; cursor permits 2048 scalars at the ingress/search boundary. ISO controls and malformed UTF-16 are rejected. Pantry cursors use [KitchenCursorCodec](../server/src/main/kotlin/com/feedme/server/kitchen/KitchenCursorCodec.kt), an explicit 1–8-key ring of named 32-byte HMAC keys. They bind environment, principal kind/ID, pantry collection, keyset position and expiry; they are not authorization tokens. Configured lifetime is 1..86400 seconds, checked against database time. Keep applicable old verification keys through the supported rotation window. Search pagination/filters and published-free catalog rights belong to the mandatory search adapter, which runs inside the current principal transaction.

The shared lock obligation is principal → command receipt → preference → pantry rows in ingredient order → current catalog/policy rows; planning additionally locks its lineage before the input rows. Kitchen, planning, revocation and lifecycle adapters must use compatible **actual** locks, not separate preflight checks or remote calls inside the transaction. The focused HTTP tests use a test-only PlanningAuthority that reads real preferences/pantry rows under the same exclusive principal lock. It proves current preference/pantry changes gate READY-plan selection/replay while historical Plan reads remain distinct. Its reviewed candidate, taxonomy and verifier are synthetic, not a production editorial/licensing adapter.

Cancellation after a real commit cannot undo it. HTTP preserves cancellation and never rotates a key, changes a body/version or automatically resends. A canceled caller or lost acknowledgement must reconcile using the original command identity and current authorization.

## Focused verification and open gates

Root-run focused execution `26956` passed **31 unit tests and 48 actual PostgreSQL integration tests**:

- [KitchenPolicyTest](../server/src/test/kotlin/com/feedme/server/kitchen/KitchenPolicyTest.kt): 10 tests for explicit principal/policy/key configuration, redaction, cursor binding, expiry, malformed tokens and registered events.
- [KitchenHttpInputTest](../server/src/test/kotlin/com/feedme/server/http/KitchenHttpInputTest.kt): 21 tests for the six canonical operations, framing/control/schema/Unicode bounds, exact numeric projection, response validation and verifier/cancellation boundaries.
- [KitchenStoreIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/kitchen/KitchenStoreIntegrationTest.kt): 28 real-PostgreSQL tests covering explicit provisioning, ownership/current identity, versions/incarnations, retired references, decimal persistence, cursor isolation, atomic rollback, same-key concurrency, response bounds and ambiguous application acknowledgements.
- [KitchenHttpIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/http/KitchenHttpIntegrationTest.kt): 20 real Ktor/PostgreSQL tests, including actual CIO requests, current principal isolation, READY planning-input integration, cancellation after commit and safe exact retries.

Earlier focused attempts exposed two integration-test compile mistakes (an internal cursor helper was inaccessible to that source set, and a JsonElement needed explicit object access), an incorrect exception expectation at the StoredReply constructor, and JSON-member-order assertions. The tests were corrected without widening production API or weakening semantic response/effect checks. Initial planning fixtures returned non-ready plans, which do not grant selection authority; explicit synthetic reviewed READY candidates replaced those fixtures before claiming stale-input selection proof. Unicode scalar handling was also aligned between ingress and the store.

The subsequent source-frozen full run passes2,266 Kotlin/server/database methods,181 Node checks and345 native successes plus seven separately witnessed interrupted starts; independent audits pass. [Receipt](verification/parallel-kitchen-cooking/verification.json). Fault tests inject application exceptions before or after real PostgreSQL commits; they do not simulate physical fsync failure. Test credentials, principal tables, catalog/review facts and provisioning approval are explicitly synthetic. Real provider/device/guest adapters, production catalog/editorial policy, provisioning and lifecycle/erasure workflows, compatible-lock race coverage, client configuration, deployment, operational retention/key management and outbox consumers remain release gates.
