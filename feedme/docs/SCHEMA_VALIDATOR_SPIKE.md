# Kotlin Multiplatform schema-validator compatibility spike

Executed 13 September 2026. **OptimumCode JSON Schema Validator 0.5.5 is not suitable as FeedMe's sole faithful contract validator:** ordinary contract cases pass, but exact numeric bounds already present in the canonical contract fail, and a valid integer beyond `Long.MAX_VALUE` throws. No dependency or validator was adopted into the application build. This does not complete M0.07.

## Candidate and compatibility

Candidate: `io.github.optimumcode:json-schema-validator:0.5.5`, MIT license. The [official 0.5.5 release](https://github.com/OptimumCode/json-schema-validator/releases/tag/0.5.5), commit `9bf38a0`, records Kotlin **2.3.21** and serialization **1.11.0** upgrades. Its downloaded JVM POM confirms both exact versions. The root Gradle module metadata advertises `iosArm64` and `iosSimulatorArm64`, alongside JVM; the [tagged README](https://github.com/OptimumCode/json-schema-validator/blob/0.5.5/README.md) documents Draft 2020-12, these targets, and the required validation keywords. This spike actually compiled and executed JVM code using FeedMe's Gradle 8.14 wrapper, JDK 17, Kotlin 2.3.21 and serialization 1.11.0. Native compilation/execution was not attempted after applicable correctness failures appeared.

The successfully compiled API is:

```kotlin
val schema = JsonSchemaLoader.create()
    .withSchemaOption(
        SchemaOption.FORMAT_BEHAVIOR_OPTION,
        FormatBehavior.ANNOTATION_AND_ASSERTION,
    )
    .fromDefinition(schemaDocument.toString())
val errors = mutableListOf<ValidationError>()
val valid = schema.validate(jsonElement, errors::add)
```

`fromDefinition` takes a string. The format option is necessary: formats only annotate by default in Draft 2020-12. The README documents platform-dependent `kotlin.text.Regex` behavior, so broad ECMA-262 regex equivalence must not be inferred from JVM results.

## Reproduction and reference handling

Runner: [schema-validator-spike.mjs](../scripts/schema-validator-spike.mjs). It generates a scratch Gradle project and fixtures from the actual canonical input, then launches the existing repository wrapper with `-p SCRATCH`. It does not run or edit the FeedMe root build. Exit **1** intentionally reports the measured validation failures.

```sh
mktemp -d /private/tmp/feedme-schema-validator.XXXXXX
node scripts/schema-validator-spike.mjs \
  /private/tmp/feedme-schema-validator.OeNCDw \
  /Users/LOCAL_USER/Documents/ChatGPT/Career/outputs/biteclub_blueprint/architecture/04_API_Contract.json
```

Use the directory printed by `mktemp` in a fresh run. `SPIKE_JAVA_HOME` can override the inspected JDK 17 location. Maven Central access and normal Gradle cache writes are needed on an uncached run. The sandbox initially refused the wrapper cache lock; the subsequent approved isolated build downloaded dependencies and ran successfully through compilation and probe execution.

Canonical source SHA-256: `f0a5f20c4a84822813137c3e6b1ef31ec22afd1d7ee384f7f29b5df09e10aba0`.

An initial JSON Schema wrapper with the original `components.schemas` location and original `#/components/schemas/...` references failed loading: `cannot resolve references: {"#/components/schemas/PantryItem": [""]}`. The successful probe therefore relocates the component schema dictionary to `$defs`, rewriting only `$ref` strings beginning `#/components/schemas/` to `#/$defs/`. All other schema members and values are retained recursively. The root declares `https://json-schema.org/draft/2020-12/schema`. Synthetic test definitions are added under `__Spike...` names. This is an explicit equivalent local-reference relocation for this input, not removal of constraints or an OpenAPI rewrite. Schema locations in validation errors accordingly use `$defs`. Original canonical bytes remain untouched.

## Measured results

Final run: **158 cases, 154 passing and 4 failing**, all with unchanged `JsonElement` representations before/after validation. Per-case exceptions are caught only in the evidence harness so one library exception does not hide remaining outcomes.

| Coverage | Observed outcome |
| --- | --- |
| All 33 nullable properties, each absent/null/value | 99 cases pass; 31 required-nullable absences reject, both optional-nullable absences accept; explicit null stays explicit |
| All three presence `anyOf` containers | 9 cases pass: neither required branch rejects; each individual branch accepts |
| All five `if`/`then` rules | 20 cases pass across all discriminator values: required branch members absent reject and present accept |
| All three canonical unique arrays | 6 cases pass; duplicates reject without collapsing the array |
| UUID/date-time/URI formats | Malformed UUID, invalid calendar date, date-only timestamp and malformed URI reject; valid values occur in passing fixtures |
| Object semantics | One closed-object unexpected key rejects; typed string map accepts string entries and rejects a numeric entry; unrestricted webhook object retains an arbitrary nested property |
| Enum and ordinary numeric bound | Unknown enum and quantity `-1` reject |
| Unicode length | Astral emoji counts as one code point; `e` plus combining accent counts as two |
| Numeric uniqueness | `[1,1.0]` and equivalent numeric members in objects reject; distinct large integer values remain distinct |
| Integer typing | Fractional `1.00000000000000000001` rejects; `1e400` accepts as an integer; `9223372036854775808` throws |

The ordinary contract suite has 144 cases; five further cases exercise exact numeric behavior in real contract fields, and nine use synthetic schema definitions to isolate numeric/Unicode semantics. Counts do not establish exhaustive conformance of all constraints or formats. The two unrestricted webhook objects and all 196 closed objects were not each individually probed; the table identifies representative object cases.

### Failures blocking sole-validator adoption

| Schema/property | Raw JSON number | Expected | Actual |
| --- | --- | --- | --- |
| Canonical `Constraint.servings`, `minimum: 0.1` | `0.099999999999999999999` | Reject | Accept |
| Canonical `PantryWrite.quantity`, `minimum: 0` | `-1e-400` | Reject | Accept |
| Canonical `PantryWrite.expectedVersion`, `type: integer`, `minimum: 1` | `9223372036854775808` | Accept; no maximum is declared | Throws `NumberFormatException` |
| Synthetic `multipleOf: 0.1` | `0.30000000000000000001` | Reject | Accept |

The runner inserts these numeric tokens into raw JSON strings, then parses them with Kotlin `Json.parseToJsonElement`; JavaScript floating-point conversion never touches them. Thus these failures are library behavior rather than fixture rounding. The bytecode path for the overflow is `NumberPartsKt.numberParts` → `Long.parseLong`. The library is not an exact-number solution, even though the canonical contract currently contains no `multipleOf`.

## Retained evidence and next gate

Raw [run log](verification/schema-validator-spike/run.log), [158 input cases](verification/schema-validator-spike/cases.json), and [receipt](verification/schema-validator-spike/receipt.json) are retained in the repository. Scratch build, schema wrapper and dependencies remain at `/private/tmp/feedme-schema-validator.OeNCDw` and the normal Gradle cache. The runner recreates the temporary source/build/schema files.

| Artifact | SHA-256 |
| --- | --- |
| Downloaded `json-schema-validator-jvm-0.5.5.jar` | `c75dddbea600503eada9a2d053fedd66efe3c45a618cab329b37c8e9fc174714` |
| Final run log | `b9e517c3fefc3df2149d5ea36f192c1565b9001beb0b03fcd9db33e48a6b2ee1` |

These are local receipt hashes, not publisher-signature verification. The Maven artifact's [published POM](https://repo.maven.apache.org/maven2/io/github/optimumcode/json-schema-validator-jvm/0.5.5/json-schema-validator-jvm-0.5.5.pom) and [root module metadata](https://repo.maven.apache.org/maven2/io/github/optimumcode/json-schema-validator/0.5.5/json-schema-validator-0.5.5.module) identify the tested dependencies and available native variants.

The [official custom-assertions documentation](https://github.com/OptimumCode/json-schema-validator/blob/main/docs/custom_assertions.md) describes `ExternalAssertionFactory`, `ExternalAssertion`, and `JsonSchemaLoader.withExtensions(...)`. This is a possible later extension point for a narrowly scoped exact-number implementation. The docs establish registering custom keywords, not that an extension can safely replace built-in `type`/`minimum` behavior or intercept the integer-overflow path. No extension was implemented or validated here; its feasibility requires a separate bounded proof, including exact numeric parsing, full schema traversal/condition behavior and JVM/native parity. Simply adding extra minimum checks cannot repair a built-in integer exception or prove acceptance of all valid numbers.

Continue reproducible contract artifact generation and operation descriptors independently. Body-validation adoption remains gated on exact-number correctness, exception behavior and native verification. Do not label raw JSON preservation or operation/protocol checks as complete body-schema validation.
