# JVM Draft 2020-12 validator spike

Executed 13 September 2026. **Networknt JSON Schema Validator 3.0.7 passes all 158 retained compatibility cases and 18 parser/reference guards, with zero failures, on JDK 17 / Kotlin 2.3.21.** This is a viable server validation candidate when configured with exact numeric parsing and the limits below. This isolated spike does not adopt a server dependency or establish iOS validation parity.

## Version and authoritative references

The [official Maven metadata](https://repo.maven.apache.org/maven2/com/networknt/json-schema-validator/maven-metadata.xml) reported `release` and `latest` as `3.0.7` (metadata updated 20 August 2026). The [published 3.0.7 POM](https://repo.maven.apache.org/maven2/com/networknt/json-schema-validator/3.0.7/json-schema-validator-3.0.7.pom) declares Apache-2.0, Java 17, Jackson 3.2.1 and Ethlo Time 1.14.0. The [official README](https://github.com/networknt/json-schema-validator/tree/3.0.7) documents the 3.x Java 17/Jackson 3 line, Draft 2020-12, explicit format assertions, and optional regex engines. The probe additionally pins Joni 2.2.6, matching the POM, to improve ECMA-262 regex compatibility. Retain Ethlo Time for strict RFC 3339 calendar checking.

The [tagged SchemaRegistry API](https://github.com/networknt/json-schema-validator/blob/3.0.7/src/main/java/com/networknt/schema/SchemaRegistry.java) provides `withDialect`, a custom `nodeReader`, and `getSchema(SchemaLocation, JsonNode)`. The [tagged SchemaLoader](https://github.com/networknt/json-schema-validator/blob/3.0.7/src/main/java/com/networknt/schema/resource/SchemaLoader.java) defaults to no remote fetching; `fetchRemoteResources` is an explicit opt-in. Its allow predicate runs before classpath or registered resource loaders. The [default mapper factory](https://github.com/networknt/json-schema-validator/blob/3.0.7/src/main/java/com/networknt/schema/serialization/JsonMapperFactory.java) uses a shared default mapper; this spike supplies its own exact mapper. The [minimum validator implementation](https://github.com/networknt/json-schema-validator/blob/3.0.7/src/main/java/com/networknt/schema/keyword/MinimumValidator.java) has a separate fixed-width-integer path, which is another reason to parse **schema bounds as well as instances** with the same BigInteger/BigDecimal configuration.

## Reproduction and retained evidence

```sh
mktemp -d /private/tmp/feedme-jvm-schema-validator.XXXXXX
node scripts/jvm-schema-validator-spike.mjs \
  /private/tmp/feedme-jvm-schema-validator.ha8LsP \
  /Users/LOCAL_USER/Documents/ChatGPT/Career/outputs/biteclub_blueprint/architecture/04_API_Contract.json
```

Replace the scratch path with the fresh `mktemp` result. The [runner](../scripts/jvm-schema-validator-spike.mjs) creates and compiles an isolated Gradle project using the repository's Gradle 8.14 wrapper; it never runs the FeedMe root build or edits application Gradle files. `SPIKE_JAVA_HOME` may override the observed JDK 17 path. First execution needs Maven access and ordinary Gradle cache writes. The retained final run exits **0**.

The runner copies the exact [prior 158-case fixture](verification/schema-validator-spike/cases.json), preserving raw `inputJson` number lexemes. Its baseline fixture hash is recorded in the [receipt](verification/jvm-schema-validator-spike/receipt.json). Canonical JSON is parsed on the JVM using the exact mapper, and the original `components.schemas` positions and `#/components/schemas/...` references are retained. Only the cases' earlier `$defs` reference relocation is reversed. Synthetic definitions remain under `$defs/__Spike...`. Each compiled wrapper adds the Draft 2020-12 declaration and one local `$ref`; no schema constraints are removed or rewritten. No application contract bytes are changed.

Retained: [run log](verification/jvm-schema-validator-spike/run.log), [cases](verification/jvm-schema-validator-spike/cases.json), [compiled Kotlin source](verification/jvm-schema-validator-spike/Spike.kt), and [receipt with all resolved dependency hashes](verification/jvm-schema-validator-spike/receipt.json). The generated scratch build remains at `/private/tmp/feedme-jvm-schema-validator.ha8LsP`.

| Artifact | SHA-256 |
| --- | --- |
| Canonical contract | `f0a5f20c4a84822813137c3e6b1ef31ec22afd1d7ee384f7f29b5df09e10aba0` |
| Networknt 3.0.7 JAR | `27b90bce60845f353b203b735cda2e0cb9a32c4962550e249141a2b065b9be5c` |
| Final run log | `c09a5f73c16dfc009531ec8b1778c13b38fc4a0c91c03fb8c46133f2ea9d7283` |

Hashes are local reproducibility receipts, not publisher-signature verification.

## Measured outcomes

| Cases | Outcome |
| --- | --- |
| All 33 nullable properties: absent/null/value (99 cases) | Pass, including 31 required-nullable absences rejected and two optional absences accepted |
| All three presence `anyOf` containers (9 cases) | Pass |
| All five `if`/`then` rules across discriminator values (20 cases) | Pass |
| All three canonical unique arrays (6 cases) | Pass; arrays remain intact |
| UUID, URI and date-time formats | Malformed UUID/URI, invalid calendar date and date-only timestamp rejected |
| Closed object, string map, unrestricted webhook object | Unknown key rejected when closed; typed map rejects number; arbitrary webhook field retained |
| Enum, ordinary minimum, Unicode code-point lengths | Pass |
| Numeric array/object equivalence and distinct large integers | Pass |
| Remaining exact number and integer cases | Pass, with no validation exceptions |

The four failures that ruled out the prior KMP candidate are corrected:

| Probe | Expected and observed |
| --- | --- |
| `Constraint.servings = 0.099999999999999999999`, minimum `0.1` | Reject |
| `PantryWrite.quantity = -1e-400`, minimum `0` | Reject |
| `PantryWrite.expectedVersion = 9223372036854775808`, integer/minimum `1` | Accept without exception |
| Synthetic `multipleOf: 0.1`, value `0.30000000000000000001` | Reject |

`1e400` is also accepted as a valid integer, and `1.00000000000000000001` rejected as a non-integer. The emitted node classes are `BigIntegerNode` and `DecimalNode`, retaining exact mathematical values beyond floating-point or `Long` bounds. Every one of the 158 validation calls leaves its input node representation unchanged. This tests semantic preservation during validation; retain original wire bytes separately if exact lexical round trips are required, because parsing can normalize decimal exponent spelling.

The 18 guards reject duplicate keys, trailing documents, NaN, Infinity, empty input, whitespace-only input, comments, trailing commas, leading zeroes, depth 65, a document above 1 MiB, a number token above 1024 characters, decimal scales beyond either sign of 10000, and HTTPS/HTTP/file/classpath references. Reference rejection is produced by the configured allow predicate before any resource loading.

## Verified adoption configuration

Use `com.networknt:json-schema-validator:3.0.7` and `org.jruby.joni:joni:2.2.6` in the server JVM module. The compiled example is retained in full in `Spike.kt`; its critical settings are:

```kotlin
val mapper = JsonMapper.builder(JsonFactory.builder()
    .streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(64)
        .maxDocumentLength(1_048_576L)
        .maxNumberLength(1024)
        .maxStringLength(1_048_576)
        .build())
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
    .build())
    .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .build()

val registry = SchemaRegistry.withDialect(Dialects.getDraft202012()) { builder ->
    builder.nodeReader { it.jsonMapper(mapper) }
        .schemaRegistryConfig(SchemaRegistryConfig.builder()
            .regularExpressionFactory(JoniRegularExpressionFactory.getInstance())
            .build())
        .schemaLoader(SchemaLoader.builder()
            .fetchRemoteResources(false).allow { false }.build())
}
val schema = registry.getSchema(SchemaLocation.of("urn:feedme:contract"), schemaNode)
val errors = schema.validate(inputNode) { context ->
    context.executionConfig { it.formatAssertionsEnabled(true) }
}
```

Imports use `tools.jackson.*` for Jackson 3. The `parse` wrapper additionally enforces an explicit UTF-8 byte size, rejects a missing document, and traverses parsed decimal nodes to require `abs(scale.toLong()) <= 10000`. Number-token length alone does not bound exponent magnitude. Apply that wrapper before validation, including inputs nested in unrestricted webhook objects. Production should also bound bytes during request-body reading so an oversize body is rejected before allocation. The same exact mapper must parse bundled schema data and body data. Validate an already parsed `JsonNode`; do not let an earlier Double/Long conversion lose information.

These depth/size/numeric limits are operational resource limits, **not bounds declared by the canonical schema**; return a distinct input/resource-limit failure. The supported domain includes the beyond-Long integer and `1e400` probes. This spike does not claim acceptance of every arbitrarily large finite JSON number. Do not silently round, clamp, coerce strings, deduplicate arrays, inject defaults, or erase explicit nulls to fit a DTO.

Cache immutable compiled schemas/registry after trusted bundled-contract initialization. The deny-all resource policy works with the tested in-document component references; pre-register any later approved external schema resources deliberately rather than enabling network fetching. Translate parse errors and schema violations into stable boundary failures; unresolved trusted schema references are initialization/configuration defects. The spike catches per-case exceptions only so evidence collection can continue.

## Scope of the conclusion

This establishes a concrete server candidate and removes the measured exact-number blocker for JVM validation. It does not establish exhaustive Draft 2020-12 conformance, every format/regex corner case, worst-case validation performance, all canonical objects individually, or native iOS behavior. The 158 cases include representative closed/open objects and all nullable/anyOf/conditional/unique-array locations. Server request/response boundary integration and its tests remain separate work; shared raw-wire models alone must not be described as full schema validation.
