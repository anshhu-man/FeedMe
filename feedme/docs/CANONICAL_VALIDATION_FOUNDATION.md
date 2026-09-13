# FeedMe canonical validation and response binding

13 September 2026. The mobile transport now validates complete request/response bodies against the current pinned contract and binds error metadata before returning a usable reply. This advances M0.07b/c; it does **not** complete authentication, durable client recovery, authorized product endpoints or native UI integration.

## Implemented boundaries

`CanonicalBodyValidator` in shared Kotlin eagerly compiles all 188 named schemas and all 201 operation body maps. It implements every assertion in the current pinned vocabulary: local schema references and siblings, primitive/nullable types, mathematical integers, exact numeric bounds, object properties/required/additional-properties, arrays/items/bounds/uniqueness, Unicode string lengths, reviewed patterns/formats, enum/const, allOf/anyOf and if/then. Defaults remain annotations and never fill in missing fields. Both SaveRecipeRequest selectors remain valid together.

This is not a general-purpose implementation of every JSON Schema keyword. Unknown keywords, formats, patterns or recursive-schema changes stop initialization for review. The unchanged source hash and generator drift check prevent silent contract replacement. The [2020-12 validation vocabulary](https://json-schema.org/draft/2020-12/json-schema-validation) and [core specification](https://json-schema.org/draft/2020-12/json-schema-core) define the implemented semantics.

`ExactDecimal` compares normalized decimal coefficients/scales without Double, Long narrowing or expanded exponent strings. It preserves tiny negative values, exact minima and mathematical integrality beyond Long. Equality treats `1`, `1.0` and `1e0` as equal; object-key order does not affect enum/const/uniqueItems equality. Missing and explicit null remain different.

The shared wire profile still rejects malformed UTF-8/Unicode, duplicate decoded keys and malformed JSON before schema evaluation. Limits are 1 MiB body, depth 64, number token 1,000, raw decimal absolute scale 10,000 and 2,000,000 evaluation work steps. These are explicit resource/interoperability limits, not additional recipe constraints or a global process-memory guarantee. Numeric bounds also apply inside otherwise unrestricted webhook content. Library/configuration failures never become successful validation and never return raw input or exception causes.

## Transport integration

| Stage | Actual behavior |
| --- | --- |
| Account request preflight | Full schema checks run on the immutable request snapshot before reading secure credentials or dispatching; invalid schema returns INVALID_DATA |
| Public request preflight | Scalar/body-presence preparation and full schema checks both pass before dispatch; no secure-store read |
| Network exchange | Existing owned engine, fixed origin, session isolation, bounded streaming, no automatic retry or new command key |
| Response schema | Every usable success/error reply must satisfy the schema for its actual operation, status and media |
| Problem binding | Body status must equal actual HTTP status mathematically, including valid spelling such as `503.0`; when an HTTP trace header exists, it must equal Problem.traceId |
| Invalid read receipt | INVALID_DATA; not an accepted recipe, health result or error record |
| Invalid mutation receipt | OUTCOME_UNKNOWN; an invalid/lost receipt cannot prove rollback, so preserve the original command identity for reconciliation |
| Bound document | `CanonicalResponseBinder` mints a detached `BoundContractResponse`; absence remains `WireBody.Absent`, not JSON null |

The internal `HttpExchange` remains a raw protocol boundary whose tests intentionally include syntax-valid, schema-invalid data. Public `FeedMeTransport` now performs the schema/binding stage before returning a usable ApiReply. The layers are intentionally tested separately, and their claims must not be confused.

The generic Problem schema has only a minimum on its numeric status and cannot impose a relationship to every actual HTTP status. The binder checks that relationship exactly; it does not coerce a huge number into an HTTP integer. Trace matching does not authenticate a server or grant authorization. Schema-valid recalled/unreviewed recipes, unknown business states, insufficient rights and infeasible constraints still require fail-closed domain handling.

## Server format correction

Differential tests found that Networknt's built-in date-time/URI implementations did not faithfully implement the chosen standards profile: they rejected some valid offsets/precision/IP literals and accepted malformed suffixes, ports or user-info. The new common scanner initially allowed hypothetical month-end leap seconds and was tightened to actual announced events. A separate Kotlin/JVM end-anchor issue was corrected so newline-suffixed handles/digests cannot pass the two reviewed anchored patterns.

The server now deliberately overrides only `uri`, `uuid` and `date-time` in Networknt's dialect using public `CanonicalFormats`. Its Jackson exact-number parser and Networknt structural/numeric validator remain independent of the common schema evaluator. Format parity between client/server is therefore **shared implementation consistency**, not independent proof. [Standards, source audit and leap-second update policy](CANONICAL_FORMAT_NOTES.md).

Independent format expectations come from a pinned upstream JSON Schema test suite and RFC/IERS-derived cases: 119 upstream cases, 21 reviewed regressions and all 27 confirmed positive leap-second dates. The 167-case corpus includes the upstream MIT notice and source revision. It tests the format-only schema evaluator, including non-string instances where format assertions do not apply. The server still enforces the canonical property's type separately.

The leap history is pinned to IERS Bulletin C 72/history reviewed on 13 September 2026. Future announced leap events require a reviewed table/test update; no runtime request fetches an announcement. Before releases after the pinned history's validity period, recheck the bulletin and update the evidence. URI syntax validity is never permission to fetch that URI—HTTP transport origins remain independently fixed and restricted.

## Evidence and remaining work

Use [the exact verification receipt](verification/canonical-validation/verification.json), copied JUnit XML, Android library lint report and distribution smoke record for the current source/artifacts. Older transport/wire receipts are historical snapshots.

Verification covers all 149 retained canonical validation fixtures, all 188 oracle-valid complete schema baselines, 8,423 systematic constraint mutations, all 201 request maps and 2,613 response mappings, conditional branches, exact-number and boundary probes, the 167 independent format cases, and ten new public-transport integration tests. The mutation corpus uses Networknt as the independent structure/number oracle; passing it is substantial bounded evidence, not an exhaustive proof of every possible input.

```sh
./gradlew :shared:core:jvmTest :shared:contracts:jvmTest :shared:transport:jvmTest :shared:transport:assembleDebug :shared:transport:lintDebug :server:test :server:integrationTest :server:installDist --rerun-tasks --console=plain
node scripts/generate-contract-artifacts.mjs --check
node scripts/verify-delivery-plan.mjs --write-report
```

PostgreSQL integration requires the explicitly selected existing local PostgreSQL binary and creates isolated temporary test clusters. No deployed database or provider credentials are needed for this verification.

M0.07b/c remain IN_PROGRESS because actual authenticated ingress, native execution and remaining staff/webhook adapter work are not complete. Next is M0.07d's owner-scoped durable command/private-state coordination and explicit approved-domain mapping. The app remains a memory-only demo; no real login, social delivery, purchase, upload, deployment or store submission was enabled. All 54 features, 98 screens and release gates remain retained.
