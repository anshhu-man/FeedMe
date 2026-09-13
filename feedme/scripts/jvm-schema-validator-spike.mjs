#!/usr/bin/env node
// Isolated JVM probe: reuses every retained case from the KMP validator spike.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const [scratchArg, canonicalArg] = process.argv.slice(2);
if (!scratchArg || !canonicalArg) throw new Error('Usage: node scripts/jvm-schema-validator-spike.mjs SCRATCH CANONICAL_JSON');
const scratch = fs.realpathSync(scratchArg);
if (!scratch.startsWith('/private/tmp/feedme-jvm-schema-validator.')) throw new Error('Use a dedicated mktemp scratch directory');
const canonical = fs.realpathSync(canonicalArg);
const repo = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const digest = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const casesPath = path.join(repo, 'docs/verification/schema-validator-spike/cases.json');
const cases = JSON.parse(fs.readFileSync(casesPath, 'utf8'));
if (cases.length !== 158) throw new Error('Review changes to the baseline 158-case suite');
fs.mkdirSync(path.join(scratch, 'src/main/kotlin'), {recursive:true});
fs.copyFileSync(canonical, path.join(scratch, 'canonical.json'));
fs.copyFileSync(casesPath, path.join(scratch, 'cases.json'));
fs.writeFileSync(path.join(scratch, 'settings.gradle.kts'), 'pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\nrootProject.name = "feedme-jvm-schema-validator-spike"\n');
fs.writeFileSync(path.join(scratch, 'build.gradle.kts'), `plugins { kotlin("jvm") version "2.3.21"; application }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencies {
 implementation("com.networknt:json-schema-validator:3.0.7")
 implementation("org.jruby.joni:joni:2.2.6")
}
application { mainClass.set("SpikeKt") }
tasks.named<JavaExec>("run") { workingDir = projectDir }
tasks.register("resolvedDependencies") { doLast {
 configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.forEach {
  println("DEPENDENCY " + it.moduleVersion.id + " " + it.file)
 }
} }
`);
fs.writeFileSync(path.join(scratch, 'src/main/kotlin/Spike.kt'), String.raw`import com.networknt.schema.*
import com.networknt.schema.dialect.Dialects
import com.networknt.schema.regex.JoniRegularExpressionFactory
import com.networknt.schema.resource.SchemaLoader
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File

const val MAX_BYTES = 1_048_576
val mapper = JsonMapper.builder(JsonFactory.builder()
 .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
  .maxDocumentLength(MAX_BYTES.toLong()).maxNumberLength(1024).maxStringLength(MAX_BYTES).build())
 .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
 .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
 .build())
 .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
 .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
 .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
 .build()

fun parse(text: String): JsonNode {
 require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "JSON exceeds byte limit" }
 val value = mapper.readTree(text)
 require(!value.isMissingNode) { "JSON document is empty" }
 fun boundNumbers(node: JsonNode) {
  if (node.isBigDecimal) require(kotlin.math.abs(node.decimalValue().scale().toLong()) <= 10_000) {
   "JSON decimal scale exceeds resource limit"
  }
  if (node.isObject || node.isArray) for (child in node) boundNumbers(child)
 }
 boundNumbers(value)
 return value
}

fun main() {
 val source = parse(File("canonical.json").readText())
 // Keep original OpenAPI component positions and original local references.
 val document = mapper.createObjectNode()
 document.put("\$schema", "https://json-schema.org/draft/2020-12/schema")
 document.set("components", source.get("components"))
 document.set("\$defs", parse("""{"__SpikeExactMinimum":{"type":"number","minimum":9007199254740992},"__SpikeExactMultiple":{"type":"number","multipleOf":0.1},"__SpikeUnicode":{"type":"string","minLength":1,"maxLength":1},"__SpikeNumericUnique":{"type":"array","uniqueItems":true}}"""))
 val registry = SchemaRegistry.withDialect(Dialects.getDraft202012()) { builder ->
  builder.nodeReader { it.jsonMapper(mapper) }
   .schemaRegistryConfig(SchemaRegistryConfig.builder()
    .regularExpressionFactory(JoniRegularExpressionFactory.getInstance()).build())
   .schemaLoader(SchemaLoader.builder().fetchRemoteResources(false).allow { false }.build())
 }
 val cases = parse(File("cases.json").readText())
 val compiled = mutableMapOf<String, Schema>()
 var failures = 0
 for (case in cases) {
  val original = case.get("ref").asString()
  val ref = if (original.startsWith("#/\$defs/__Spike")) original else original.replace("#/\$defs/", "#/components/schemas/")
  val schema = compiled.getOrPut(ref) {
   val root = document.deepCopy()
   root.put("\$ref", ref)
   registry.getSchema(SchemaLocation.of("urn:feedme:spike:" + compiled.size), root)
  }
  val value = case.get("inputJson")?.let { parse(it.asString()) } ?: case.get("input")
  val before = value.toString()
  val expected = case.get("expected").asBoolean()
  val validation = runCatching { schema.validate(value) { context ->
   context.executionConfig { it.formatAssertionsEnabled(true) }
  } }
  val actual = validation.getOrNull()?.isEmpty()
  val unchanged = before == value.toString()
  val pass = actual == expected && unchanged
  if (!pass) failures++
  println("PROBE " + case.get("label").asString() + " expected=" + expected + " actual=" + actual + " unchanged=" + unchanged + " pass=" + pass + " errors=" + validation.getOrNull() + " exception=" + validation.exceptionOrNull())
 }
 var guards = 0
 fun reject(label: String, block: () -> Unit) {
  guards++
  val failure = runCatching(block).exceptionOrNull()
  val pass = failure != null
  if (!pass) failures++
  println("GUARD " + label + " rejected=" + pass + " exception=" + failure)
 }
 reject("duplicate-key") { parse("""{"a":1,"a":2}""") }
 reject("trailing-document") { parse("{} {}") }
 reject("NaN") { parse("NaN") }
 reject("Infinity") { parse("Infinity") }
 reject("empty") { parse("") }
 reject("whitespace") { parse("   ") }
 reject("comment") { parse("/* comment */ {}") }
 reject("trailing-comma") { parse("[1,]") }
 reject("leading-zero") { parse("01") }
 reject("depth") { parse("[".repeat(65) + "0" + "]".repeat(65)) }
 reject("document-size") { parse(" " .repeat(MAX_BYTES) + "{}") }
 reject("number-length") { parse("1".repeat(1025)) }
 reject("decimal-positive-exponent-bound") { parse("1e10001") }
 reject("decimal-negative-exponent-bound") { parse("1e-10001") }
 for (ref in listOf("https://example.invalid/schema", "http://127.0.0.1:9/schema", "file:///private/tmp/forbidden-schema.json", "classpath:forbidden-schema.json")) {
  reject("external-ref:" + ref) {
   val node = mapper.createObjectNode().put("\$ref", ref)
   registry.getSchema(SchemaLocation.of("urn:feedme:external"), node).validate(parse("null"))
  }
 }
 val exact = parse("""[9223372036854775808,0.099999999999999999999,-1e-400,1e400]""")
 println("NUMERIC_NODES " + buildList { for (node in exact) add(node.javaClass.simpleName + ":" + node.toString()) })
 println("TOTAL=" + cases.size() + " GUARDS=" + guards + " FAILURES=" + failures)
 check(failures == 0) { "Compatibility probes failed: " + failures }
}
`);
const args = ['-p', scratch, '--no-daemon', '--console=plain', 'run', 'resolvedDependencies'];
const result = spawnSync(path.join(repo, 'gradlew'), args, {cwd:scratch,encoding:'utf8',maxBuffer:16*1024*1024,timeout:240000,env:{...process.env,JAVA_HOME:process.env.SPIKE_JAVA_HOME || '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home'}});
const output = `${result.stdout || ''}${result.stderr || ''}${result.error || ''}`;
fs.writeFileSync(path.join(scratch, 'run.log'), output);
const artifacts = [...output.matchAll(/^DEPENDENCY ([^ ]+) (.+)$/gm)].map(([,id,file]) => ({id,file,sha256:digest(file)}));
fs.writeFileSync(path.join(scratch, 'receipt.json'), JSON.stringify({canonical,canonicalSha256:digest(canonical),baselineCasesSha256:digest(casesPath),library:'com.networknt:json-schema-validator:3.0.7',kotlin:'2.3.21',jdk:17,command:[path.join(repo,'gradlew'),...args],total:cases.length,exit:result.status,logSha256:digest(path.join(scratch,'run.log')),artifacts},null,2));
process.stdout.write(output);
process.exitCode = result.status ?? 1;
