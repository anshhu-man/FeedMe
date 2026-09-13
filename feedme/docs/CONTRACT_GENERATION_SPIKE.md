# OpenAPI Generator 7.25.0 compatibility spike

Executed 13 September 2026. **M0.07 remains incomplete: the pinned stock Kotlin multiplatform generator does not preserve the canonical contract and its full model output does not compile.** No generated source, Gradle template, or dependency configuration was adopted into FeedMe.

## Reproducible scope and provenance

Canonical input: `../outputs/biteclub_blueprint/architecture/04_API_Contract.json` relative to the FeedMe repository, resolved during this run to `/Users/LOCAL_USER/Documents/ChatGPT/Career/outputs/biteclub_blueprint/architecture/04_API_Contract.json`.

Input SHA-256: `f0a5f20c4a84822813137c3e6b1ef31ec22afd1d7ee384f7f29b5df09e10aba0`.

The official [7.25.0 release](https://github.com/OpenAPITools/openapi-generator/releases/tag/v7.25.0) records a stable release on 24 August 2026 at commit `ef964b0`. The [installation documentation](https://openapi-generator.tech/docs/installation/) points to the pinned Maven Central CLI artifact. The downloaded CLI printed `7.25.0`; generation still emitted the OpenAPI 3.1 beta-support warning. Search-index snippets claiming 7.25.0 is upcoming were stale relative to the release page.

Downloaded only into `/private/tmp/feedme-contract-spike.71oPZn`:

| Artifact | SHA-256 |
| --- | --- |
| `org.openapitools:openapi-generator-cli:7.25.0` | `41ce4f6b07f196676439d710759fa1ced7a08066d06ff1bf314681470289efae` |
| `org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.3.21` | `542a36fe5ae5c35b7bb4cc297100f3634d3b5f97c7b5f78ce443621830bbbef4` |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0` | `f4a801c647d4351327cd9e1ac4113e2be9ea37a64ab0abae269c25a52e28f35d` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0` | `563a25b4eb5c9128ae9c2479f3d1a5c44dcd176112b91cf03682e906eba5c935` |

Artifacts came from `https://repo1.maven.org/maven2/` using their standard Maven coordinate paths. These are locally computed receipt hashes, not a separately checked publisher signature. Initial sandboxed download failed with curl exit 6 (`Could not resolve host`); approved network escalation then downloaded the artifacts successfully. No provider account, cloud service, or paid resource was used.

The reproducible runner is [contract-generator-spike.mjs](../scripts/contract-generator-spike.mjs), with [contract-generator-spike-probe.kt](../scripts/contract-generator-spike-probe.kt). It writes generated output, logs, fixtures and `receipt.json` only under the supplied `/private/tmp/feedme-contract-spike.*` directory; all subprocess arguments are recorded in the receipt. It intentionally exits **1** when full generated-model compilation fails, even when evidence collection succeeds.

```sh
node scripts/contract-generator-spike.mjs \
  /private/tmp/feedme-contract-spike.71oPZn \
  /Users/LOCAL_USER/Documents/ChatGPT/Career/outputs/biteclub_blueprint/architecture/04_API_Contract.json
```

For a fresh run, create a directory with `mktemp -d /private/tmp/feedme-contract-spike.XXXXXX`, download the four pinned artifacts above into it using their artifact-version filenames, then pass that path. The script uses the existing Kotlin 2.3.21 compiler and its pinned dependencies from the local Gradle Maven cache. `SPIKE_JAVA` and `SPIKE_MAVEN_CACHE` override the inspected JDK 17 and cache paths. It does not run the repository build or modify its cache.

Generation commands use `-g kotlin --library multiplatform`, `packageName=feedme.contract.spike`, `dateLibrary=string`, `omitGradleWrapper=true`, and `hideGenerationTimestamp=true`. A second variant additionally sets `serializationLibrary=kotlinx_serialization`. No input preprocessing, template patches, normalizer overrides, or validation bypasses were used. String dates deliberately isolate DTO compatibility from a new date-library dependency; this does not establish date-format enforcement.

The emitted build template pins Kotlin **2.4.0**, Ktor **3.5.1**, coroutines **1.11.0**, and serialization **1.11.0**. It was inspected, not executed or adopted. The [Kotlin generator documentation](https://openapi-generator.tech/docs/generators/kotlin/) supports a multiplatform library and limits `generateOneOfAnyOfWrappers` to the JVM Retrofit library. Its older descriptive dependency-version text does not match this downloaded release's emitted template.

## Actual results

| Check | Observed outcome |
| --- | --- |
| CLI validation | Exit 0; one recommendation: unused `HouseholdPreferencePage` |
| Default and explicit-serialization generation | Both exit 0; each emits 196 model files |
| Full default model compilation | **Exit 1**, five broken models identified below |
| Full explicit-serialization model compilation | **Exit 1**, duplicate non-repeatable `@Serializable` annotations, plus the schema-specific defects |
| Compile unmodified remaining 191 models with JVM probe | Exit 0, Kotlin 2.3.21 compiler/serialization plugin, JDK 17 target, serialization runtime 1.11.0 |
| Execute subset serialization probes | Exit 0 means the evidence program ran; reported contract failures remain failures |
| Operation method inventory | 14 generated API files contain 201 `suspend fun` declarations; operation semantics and transport compilation remain unverified |

The default model compiler reports:

```text
Feedback.kt:38:21: error: data class must have at least one primary constructor parameter.
FeedbackWrite.kt:38:26: error: data class must have at least one primary constructor parameter.
SaveRecipeRequest.kt:37:30: error: data class must have at least one primary constructor parameter.
RevenueCatWebhook.kt:46:53: error: syntax error: Expecting a top level declaration.
RevenueCatWebhookEvent.kt:54:53: error: syntax error: Expecting a top level declaration.
```

The probe excludes exactly those five files without editing any generated file. Thus its successful subset compilation is **not** successful compilation of the client. Native compilation, full transport compilation, and backend integration were not attempted after the full model build failed.

### All 33 nullable string unions

All 33 become Kotlin `String?`; all 33 non-null string values survive decode/encode unchanged. The 31 required-nullable fields (30 page cursors and `PantryItem.confirmedAt`) carry `@Required`, preserve explicit JSON null, and reject an absent field. All 31 were individually exercised with null, absence and a string.

Both optional-nullable fields, `PantryWrite.confirmedAt` and `Plan.nextAlternativeCursor`, become `String? = null`. An absent field and an explicit null decode to equal model values. Re-encoding an explicit-null input omits the field. Therefore the absent/null/value distinction is lost in both cases. Setting `encodeDefaults=true` cannot recover presence already lost during decoding. The probe matches the generated client's `Json { ignoreUnknownKeys = true }`; explicit-null and default encoding behavior otherwise remains at library defaults. See the official [explicitNulls](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/explicit-nulls.html) and [encodeDefaults](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/encode-defaults.html) descriptions; the pinned 1.11.0 runtime outcomes above were measured directly.

### Three presence unions and five conditional rules

`Feedback`, `FeedbackWrite`, and `SaveRecipeRequest` each contain a presence-only `anyOf`. The generator drops **all their properties**, emitting empty `data class ... ()` definitions. These lose the data contract as well as the presence constraint and cannot compile. Their serialization is therefore untestable as emitted.

The log repeats `Unset/Removed allOf after cleaning up allOf sub-schemas that are not yet supported.` three times. These are the three schema containers holding the five `if`/`then` rules. The emitted DTOs accept all ten exercised invalid branches:

| Schema | Invalid input accepted by generated serializer |
| --- | --- |
| `PlanRequest` | `mode=improve` and otherwise required fields, missing `baseMeal` |
| `ThreadCreate` | `kind=direct` without `recipientUserId`; `kind=pact` and `kind=potluck` without `contextType`/`contextId` |
| `FeedbackTarget` | Each of `cookSession`, `plan`, `recipeVersion`, `ingredient` without `resourceId`; each of `taste`, `preparation` without `tag` |

### Additional properties and other boundary behavior

Both unrestricted objects, `RevenueCatWebhook` and its inline `event`, emit invalid `HashMap<String, kotlin.Any>()()` inheritance. Their declared and arbitrary-field round trips could not be tested because they do not compile.

The one typed map, `Upload.uploadFields`, becomes `Map<String, String>?`; a two-entry string map survives round-trip, and a numeric map value is rejected.

The canonical contract contains 196 `additionalProperties:false` objects, but the generated client globally sets `ignoreUnknownKeys=true`. A `FeedbackTarget` with an unexpected key is accepted and that key disappears during re-encoding. A separate strict JSON configuration rejects the same extra key. This is one runtime exemplar of the global setting, not an exhaustive round-trip of all 196 schemas; a blanket strict setting would also need a deliberate treatment for the two unrestricted webhook objects.

One unknown enum value (`FeedbackTarget.kind`) is rejected. Invalid UUID text and a below-minimum quantity in `PantryWrite` are accepted. All three `uniqueItems:true` arrays (`Constraint.householdParticipantIds`, `AdaptRequest.excludeRecipeVersionIds`, `CollectionOrder.orderedSavedRecipeIds`) become sets: duplicate inputs are accepted and collapse to one item on output. Validation and preservation of all remaining bounds, formats, enums and response variants are not established by these representative probes.

## Receipt and next gate

Scratch receipt: `/private/tmp/feedme-contract-spike.71oPZn/receipt.json`; SHA-256 `2d9f21e05f24f46691ac5af03fa7f492ab77e242c94afd588d0c437b7288d763`.

| Evidence file in scratch | SHA-256 |
| --- | --- |
| `compile-all-models.log` | `6f024fb17707d0d46fd0a0c59a18379561d918cfde8ab9708d6a5cd9b860afb8` |
| `compile-explicit-models.log` | `1d677d687983f72b96e33a9737171463a482298d0b93ed736be0388721cfc0d2` |
| `serialization-probes.log` | `d596e5f31cb8a4d1fe077a0d40488b0327872619624f0dac0d33111f00d06fdd` |
| `default-generated/build.gradle.kts` | `c5c922b4ca519b20f29cea5614a8cca1d4104bb5a458f1b69ce540d83cf8e66f` |

Scratch files are temporary evidence. The receipt and all validation/generation/compilation/probe logs have also been copied byte-for-byte into [retained verification evidence](verification/contract-generator-spike/receipt.json), so the measured failures do not depend on keeping the temporary directory. The runner, probe and this report preserve the reproduction method and substantive observations. The generated source trees and downloaded JARs remain scratch-only and were not adopted.

M0.07 needs an explicit solution for presence-aware optional-nullable properties, the three presence unions, all five conditional rules, additional-property handling, and validation at the contract boundary. That could involve owned templates/serializers plus schema validation, or a different generator after an equally concrete spike. Do not remove canonical constraints to make generation pass. First require an unmodified complete output to compile and preserve these cases, then test JVM/native targets, operation/header/status contracts, drift, and the real catalog → plan → cooking → private-save slice.
