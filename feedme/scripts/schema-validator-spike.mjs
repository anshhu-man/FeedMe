#!/usr/bin/env node
// Isolated library compatibility probe; never mutates the application build.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const [scratchArg, canonicalArg] = process.argv.slice(2);
if (!scratchArg || !canonicalArg) throw new Error('Usage: node scripts/schema-validator-spike.mjs SCRATCH CANONICAL_JSON');
const scratch = fs.realpathSync(scratchArg);
if (!scratch.startsWith('/private/tmp/feedme-schema-validator.')) throw new Error('Use a dedicated mktemp scratch directory');
const canonical = fs.realpathSync(canonicalArg);
const repo = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const source = JSON.parse(fs.readFileSync(canonical, 'utf8'));
const schemas = source.components.schemas;
const uuid = '12345678-1234-4234-8234-123456789abc';
const suite = [];
function fixture(s, depth = 0) {
  if (depth > 30) throw new Error('Fixture recursion');
  if (s.$ref) return fixture(schemas[s.$ref.split('/').at(-1)], depth + 1);
  if (s.const !== undefined) return s.const;
  if (s.enum) return s.enum[0];
  const type = Array.isArray(s.type) ? s.type.find(t => t !== 'null') : s.type;
  if (type === 'object') return Object.fromEntries((s.required || []).map(k => [k, fixture(s.properties[k], depth + 1)]));
  if (type === 'array') return Array.from({length:s.minItems || 0}, () => fixture(s.items, depth + 1));
  if (type === 'integer' || type === 'number') return s.minimum ?? 1;
  if (type === 'boolean') return false;
  if (s.format === 'uuid') return uuid;
  if (s.format === 'date-time') return '2026-09-13T00:00:00Z';
  if (s.format === 'uri') return 'https://example.com/a';
  return 'example';
}
function add(label, ref, input, expected) { suite.push({label, ref, input: structuredClone(input), expected}); }
function property(schema, name, value, expected, suffix) {
  const input = fixture(schemas[schema]);
  if (value !== undefined) input[name] = value; else delete input[name];
  add(`${schema}.${name}:${suffix}`, `#/components/schemas/${schema}`, input, expected);
}
for (const [name, schema] of Object.entries(schemas)) {
  for (const [prop, p] of Object.entries(schema.properties || {})) {
    if (Array.isArray(p.type) && p.type.includes('null')) {
      property(name, prop, null, true, 'null');
      property(name, prop, undefined, !(schema.required || []).includes(prop), 'absent');
      property(name, prop, fixture(p), true, 'value');
    }
    if (p.uniqueItems) {
      const item = fixture(p.items);
      property(name, prop, [item], true, 'unique');
      property(name, prop, [item, item], false, 'duplicate');
    }
  }
  if (schema.anyOf) {
    const base = fixture(schema);
    add(`${name}:anyOf-none`, `#/components/schemas/${name}`, base, false);
    for (const [index, branch] of schema.anyOf.entries()) {
      const input = {...base};
      for (const p of branch.required) input[p] = p === 'target' ? {kind:'taste', tag:'crunch'} : fixture(schema.properties[p]);
      add(`${name}:anyOf-${index}`, `#/components/schemas/${name}`, input, true);
    }
  }
  for (const [index, conditional] of (schema.allOf || []).entries()) {
    if (!conditional.if) continue;
    const [discriminator, restriction] = Object.entries(conditional.if.properties)[0];
    for (const value of restriction.enum || [restriction.const]) {
      const input = {...fixture(schema), [discriminator]:value};
      add(`${name}:conditional-${index}-${value}-missing`, `#/components/schemas/${name}`, input, false);
      for (const p of conditional.then.required) input[p] = fixture(schema.properties[p]);
      add(`${name}:conditional-${index}-${value}-present`, `#/components/schemas/${name}`, input, true);
    }
  }
}
property('PantryWrite', 'ingredientId', 'broken', false, 'invalid-uuid');
property('PantryWrite', 'confirmedAt', '2026-02-30T00:00:00Z', false, 'invalid-calendar-date');
property('PantryWrite', 'confirmedAt', '2026-09-13', false, 'missing-time');
property('Upload', 'uploadUrl', 'not a uri', false, 'invalid-uri');
property('Upload', 'uploadFields', {a:'one', b:'two'}, true, 'typed-map');
property('Upload', 'uploadFields', {a:1}, false, 'typed-map-number');
property('PantryWrite', 'quantity', -1, false, 'below-minimum');
property('PantryWrite', 'unexpected', true, false, 'unknown-property');
property('PantryWrite', 'presence', 'unknown', false, 'unknown-enum');
property('RevenueCatWebhook', 'unexpected', {nested:[1,null,'a']}, true, 'unrestricted');
const extensions = {
  ExactMinimum:{type:'number', minimum:9007199254740992},
  ExactMultiple:{type:'number', multipleOf:0.1},
  Unicode:{type:'string', minLength:1, maxLength:1},
  NumericUnique:{type:'array', uniqueItems:true},
};
const raw = (label, schema, inputJson, expected) => suite.push({label,ref:`#/$defs/__Spike${schema}`,inputJson,expected});
raw('numeric:below-large-min', 'ExactMinimum', '9007199254740991', false);
raw('numeric:large-min-equal', 'ExactMinimum', '9007199254740992', true);
raw('numeric:decimal-multiple', 'ExactMultiple', '0.3', true);
raw('numeric:almost-decimal-multiple', 'ExactMultiple', '0.30000000000000000001', false);
raw('unicode:astral-codepoint', 'Unicode', JSON.stringify('😀'), true);
raw('unicode:combining-two-codepoints', 'Unicode', JSON.stringify('e\u0301'), false);
raw('unique:numeric-equivalence', 'NumericUnique', '[1,1.0]', false);
raw('unique:object-numeric-equivalence', 'NumericUnique', '[{"a":1},{"a":1.0}]', false);
raw('unique:large-distinct', 'NumericUnique', '[9007199254740992,9007199254740993]', true);
// Raw number lexemes keep probes out of JavaScript floating-point conversion.
const constraint = fixture(schemas.Constraint);
suite.push({label:'Constraint.servings:exact-below-minimum',ref:'#/components/schemas/Constraint',inputJson:JSON.stringify({...constraint,servings:'DECIMAL'}).replace('"DECIMAL"','0.099999999999999999999'),expected:false});
const pantry = fixture(schemas.PantryWrite);
suite.push({label:'PantryWrite.quantity:tiny-negative',ref:'#/components/schemas/PantryWrite',inputJson:JSON.stringify({...pantry,quantity:'DECIMAL'}).replace('"DECIMAL"','-1e-400'),expected:false});
for (const [label,lexeme,expected] of [['fractional-integer','1.00000000000000000001',false],['integer-beyond-long','9223372036854775808',true],['integer-exponent-overflow','1e400',true]]) {
  suite.push({label:`PantryWrite.expectedVersion:${label}`,ref:'#/components/schemas/PantryWrite',inputJson:JSON.stringify({...pantry,expectedVersion:'DECIMAL'}).replace('"DECIMAL"',lexeme),expected});
}
// This library only indexes schema locations under schema keywords, not OpenAPI
// components. Relocate unchanged schemas to $defs and rewrite local refs exactly.
function relocate(value) {
  if (Array.isArray(value)) return value.map(relocate);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, v]) =>
    [key, key === '$ref' && typeof v === 'string' && v.startsWith('#/components/schemas/') ? v.replace('#/components/schemas/', '#/$defs/') : relocate(v)]));
  return value;
}
for (const c of suite) c.ref = c.ref.replace('#/components/schemas/', '#/$defs/');
const document = {$schema:'https://json-schema.org/draft/2020-12/schema',$defs:{...relocate(schemas),...Object.fromEntries(Object.entries(extensions).map(([k,v]) => [`__Spike${k}`,v]))}};
fs.mkdirSync(path.join(scratch, 'src/main/kotlin'), {recursive:true});
fs.writeFileSync(path.join(scratch, 'schema.json'), JSON.stringify(document));
fs.writeFileSync(path.join(scratch, 'cases.json'), JSON.stringify(suite));
fs.writeFileSync(path.join(scratch, 'settings.gradle.kts'), 'pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\nrootProject.name = "feedme-schema-validator-spike"\n');
fs.writeFileSync(path.join(scratch, 'build.gradle.kts'), `plugins { kotlin("jvm") version "2.3.21"; application }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencies {
 implementation("io.github.optimumcode:json-schema-validator:0.5.5")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
application { mainClass.set("SpikeKt") }
tasks.named<JavaExec>("run") { workingDir = projectDir }
`);
fs.writeFileSync(path.join(scratch, 'src/main/kotlin/Spike.kt'), `import io.github.optimumcode.json.schema.*
import kotlinx.serialization.json.*
import java.io.File

fun main() {
 val document = Json.parseToJsonElement(File("schema.json").readText()).jsonObject
 val cases = Json.parseToJsonElement(File("cases.json").readText()).jsonArray
 val compiled = mutableMapOf<String, JsonSchema>()
 var failures = 0
 for (c in cases) {
  val case = c.jsonObject
  val ref = case.getValue("ref").jsonPrimitive.content
  val schema = compiled.getOrPut(ref) {
   val root = JsonObject(document + ("$" + "ref" to JsonPrimitive(ref)))
   JsonSchemaLoader.create()
    .withSchemaOption(SchemaOption.FORMAT_BEHAVIOR_OPTION, FormatBehavior.ANNOTATION_AND_ASSERTION)
    .fromDefinition(root.toString())
  }
  val value = case["inputJson"]?.jsonPrimitive?.content?.let(Json::parseToJsonElement) ?: case.getValue("input")
  val expected = case.getValue("expected").jsonPrimitive.boolean
  val errors = mutableListOf<ValidationError>()
  val before = value.toString()
  val validation = runCatching { schema.validate(value, errors::add) }
  val actual = validation.getOrNull()
  val unchanged = before == value.toString()
  val pass = actual == expected && unchanged
  if (!pass) failures++
  println("PROBE " + case.getValue("label").jsonPrimitive.content + " expected=" + expected + " actual=" + actual + " unchanged=" + unchanged + " pass=" + pass + " errors=" + errors + " exception=" + validation.exceptionOrNull())
 }
 println("TOTAL=" + cases.size + " FAILURES=" + failures)
 check(failures == 0) { "Compatibility probes failed: " + failures }
}
`);
const args = ['-p', scratch, '--no-daemon', '--console=plain', 'run'];
const result = spawnSync(path.join(repo, 'gradlew'), args, {cwd:scratch,encoding:'utf8',maxBuffer:16*1024*1024,timeout:240000,env:{...process.env,JAVA_HOME:process.env.SPIKE_JAVA_HOME || '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home'}});
const output = `${result.stdout || ''}${result.stderr || ''}${result.error || ''}`;
fs.writeFileSync(path.join(scratch, 'run.log'), output);
const digest = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
fs.writeFileSync(path.join(scratch, 'receipt.json'), JSON.stringify({canonical,canonicalSha256:digest(canonical),library:'io.github.optimumcode:json-schema-validator:0.5.5',kotlin:'2.3.21',serialization:'1.11.0',command:[path.join(repo,'gradlew'),...args],total:suite.length,exit:result.status,logSha256:digest(path.join(scratch,'run.log'))},null,2));
process.stdout.write(output);
process.exitCode = result.status ?? 1;
