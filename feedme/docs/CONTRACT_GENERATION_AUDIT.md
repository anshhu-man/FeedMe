# Canonical client-contract audit — M0.07 preparation

Inspected 13 September 2026; read-only contract audit, not generated client implementation.

Canonical OpenAPI SHA-256: `f0a5f20c4a84822813137c3e6b1ef31ec22afd1d7ee384f7f29b5df09e10aba0`.

| Construct | Observed |
| --- | ---: |
| OpenAPI version | 3.1.1 |
| Named schemas / paths / operations | 188 / 155 / 201 |
| Local reference occurrences / unresolved | 3,088 / 0 |
| Inline objects | 11 |
| String enum schemas in components / operation parameters | 138 / 3 |
| Nullable string types: required / optional properties | 31 / 2 |
| anyOf / allOf / if-then rules | 3 / 3 / 5 |
| oneOf / discriminators / legacy nullable | 0 / 0 / 0 |
| Strict objects / unrestricted additional-property objects / typed map | 196 / 2 / 1 |
| UUID / date-time / URI annotations in components | 229 / 158 / 5 |

Registry has 54 features, 98 screens and 900 actions. All 152 HTTP action bindings and 99 HTTP hydrations resolve; 32 additional LOCAL/EXTERNAL hydrations must not be converted into HTTP endpoints.

Presence unions occur in `Feedback`, `FeedbackWrite` and `SaveRecipeRequest`. Conditional field rules occur in `PlanRequest`, `ThreadCreate` and `FeedbackTarget`. Optional-nullable cases are `PantryWrite.confirmedAt` and `Plan.nextAlternativeCursor`. Required-nullable page cursors must remain present when null.

## Completed spike and remaining integration work

The pinned generator was subsequently downloaded and exercised. [Actual compatibility results](CONTRACT_GENERATION_SPIKE.md): full model compilation fails; three presence-union models lose their properties, five conditional rules are dropped, and two optional-nullable fields lose presence. Generated source is not adopted. This audit's original environment observations below are historical; M0.07 remains in progress.

The subsequent [owned metadata foundation](CONTRACT_METADATA_FOUNDATION.md) completes reproducible generation, drift checks and full operation descriptors without adopting the failed DTO output. It preserves original canonical bytes. A separate [validator spike](SCHEMA_VALIDATOR_SPIKE.md) found applicable numeric failures and was not adopted. Current completed/pending packages are M0.07a–d in the foundation document.

Original acceptance checklist (not all items remain pending):

1. Pin generator/tool version and canonical source hash; generate only into isolated scratch output initially. Do not adopt a generated Gradle project/dependency set blindly.
2. Compile common Kotlin models on the chosen JVM/native targets, keeping transport DTOs separate from domain and UI models.
3. Test absent/null round trips, unknown enums, strict objects/maps, presence unions and all conditional branches, bounds, formats and uniqueness. Fail on unsupported constructs rather than dropping them.
4. Generate all operation descriptors, request parameters/header contracts and response/status variants. Separately constrain mobile, staff and webhook adapters.
5. Validate registry hydration/action references and regenerated-file drift. Keep a reproducible receipt identifying the generator/template and schema hash.
6. Map one real catalog → plan → cooking → private-save slice with the identity/version rules in CLIENT_INTEGRATION_BOUNDARIES.md. Passing generation is not a working backend.

At initial inspection, no OpenAPI Generator/Swagger Codegen installation was found in the inspected local tool/cache locations. The later spike kept the downloaded generator in isolated scratch space. The server now declares JSON serialization runtime 1.11.0, but the shared client still has no DTO serialization compiler/JSON runtime or Ktor client configured. Compose brings a transitive serialization-core dependency; that is not a configured contract adapter. No provider account or deployment follows from these development dependencies.

The reviewed OpenAPI Generator release exposes Kotlin multiplatform support but labels OpenAPI 3.1 support beta. A compatibility spike is required rather than assuming faithful generation. [Official generator project](https://github.com/OpenAPITools/openapi-generator), [Kotlin generator support](https://openapi-generator.tech/docs/generators/kotlin/). Explicit-null handling also needs intentional tests. [Kotlin serialization null behavior](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/explicit-nulls.html).
