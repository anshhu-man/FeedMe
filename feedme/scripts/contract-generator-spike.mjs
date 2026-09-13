#!/usr/bin/env node
// Isolated compatibility evidence only. Never adopts generated code into FeedMe.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const [scratchArg, canonicalArg] = process.argv.slice(2);
if (!scratchArg || !canonicalArg) throw new Error('Usage: node scripts/contract-generator-spike.mjs SCRATCH CANONICAL_JSON');
const scratch = fs.realpathSync(scratchArg);
if (!scratch.startsWith('/private/tmp/feedme-contract-spike.')) throw new Error('Use a dedicated mktemp scratch directory');
const canonical = fs.realpathSync(canonicalArg);
const java = process.env.SPIKE_JAVA || '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java';
const cache = process.env.SPIKE_MAVEN_CACHE || '/Users/LOCAL_USER/.gradle/caches/modules-2/files-2.1';
const digest = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const jars = {
  generator: path.join(scratch, 'openapi-generator-cli-7.25.0.jar'),
  plugin: path.join(scratch, 'kotlin-serialization-compiler-plugin-embeddable-2.3.21.jar'),
  core: path.join(scratch, 'kotlinx-serialization-core-jvm-1.11.0.jar'),
  json: path.join(scratch, 'kotlinx-serialization-json-jvm-1.11.0.jar'),
};
if (digest(canonical) !== 'f0a5f20c4a84822813137c3e6b1ef31ec22afd1d7ee384f7f29b5df09e10aba0') throw new Error('Canonical source changed; reassess spike');
if (digest(jars.generator) !== '41ce4f6b07f196676439d710759fa1ced7a08066d06ff1bf314681470289efae') throw new Error('Generator receipt mismatch');
const runs = [];
function run(name, args) {
  const r = spawnSync(java, args, { cwd: scratch, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 120000 });
  const log = `${r.stdout || ''}${r.stderr || ''}${r.error || ''}`;
  fs.writeFileSync(path.join(scratch, `${name}.log`), log);
  runs.push({ name, executable: java, args, exit: r.status, signal: r.signal });
  console.log(`${name}: exit ${r.status}; ${name}.log`);
  return r.status;
}
run('validate', ['-jar', jars.generator, 'validate', '-i', canonical]);
for (const [name, extra] of [['default-generated', ''], ['explicit-generated', ',serializationLibrary=kotlinx_serialization']]) {
  run(name, ['-jar', jars.generator, 'generate', '-i', canonical, '-g', 'kotlin', '--library', 'multiplatform', '-o', path.join(scratch, name),
    '--additional-properties', `packageName=feedme.contract.spike,dateLibrary=string,omitGradleWrapper=true,hideGenerationTimestamp=true${extra}`]);
}
const models = path.join(scratch, 'default-generated/src/commonMain/kotlin/feedme/contract/spike/models');
const names = fs.readdirSync(models).filter(p => p.endsWith('.kt')).sort();
const schema = JSON.parse(fs.readFileSync(canonical, 'utf8'));
const nullable = [];
for (const [name, s] of Object.entries(schema.components.schemas)) {
  for (const [property, p] of Object.entries(s.properties || {})) {
    if (Array.isArray(p.type) && p.type.includes('null')) {
      const source = fs.readFileSync(path.join(models, `${name}.kt`), 'utf8');
      const line = source.split('\n').find(l => l.includes(`val ${property}:`));
      nullable.push({ name, property, required: s.required?.includes(property) || false, line, nullable: /String\?/.test(line || '') });
    }
  }
}
function cached(group, artifact, version) {
  const base = path.join(cache, group, artifact, version);
  for (const hash of fs.readdirSync(base)) {
    const p = path.join(base, hash, `${artifact}-${version}.jar`);
    if (fs.existsSync(p)) return p;
  }
  throw new Error(`Missing cached ${group}:${artifact}:${version}`);
}
const stdlib = cached('org.jetbrains.kotlin', 'kotlin-stdlib', '2.3.21');
const annotations = cached('org.jetbrains', 'annotations', '13.0');
const compilerCp = [
  cached('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.3.21'), stdlib, annotations,
  cached('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.3.21'),
  cached('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
  cached('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
].join(':');
const runtimeCp = [stdlib, annotations, jars.core, jars.json].join(':');
const compileBase = ['-cp', compilerCp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', runtimeCp, `-Xplugin=${jars.plugin}`];
const fullExit = run('compile-all-models', [...compileBase, '-d', path.join(scratch, 'all-models.jar'), ...names.map(n => path.join(models, n))]);
const explicitModels = path.join(scratch, 'explicit-generated/src/commonMain/kotlin/feedme/contract/spike/models');
const explicitExit = run('compile-explicit-models', [...compileBase, '-d', path.join(scratch, 'explicit-models.jar'), ...names.map(n => path.join(explicitModels, n))]);
// These five unmodified models fail compilation. Exclusion enables separate probes;
// the subset is NOT a repaired or accepted generated client.
const excluded = ['Feedback.kt', 'FeedbackWrite.kt', 'SaveRecipeRequest.kt', 'RevenueCatWebhook.kt', 'RevenueCatWebhookEvent.kt'];
const subset = names.filter(n => !excluded.includes(n));
const probeSource = path.join(scriptDir, 'contract-generator-spike-probe.kt');
const subsetExit = run('compile-probe-subset', [...compileBase, '-d', path.join(scratch, 'probe.jar'), ...subset.map(n => path.join(models, n)), probeSource]);
function fixture(s) {
  if (s.$ref) return fixture(schema.components.schemas[s.$ref.split('/').at(-1)]);
  if (s.enum) return s.enum[0];
  const t = Array.isArray(s.type) ? s.type[0] : s.type;
  if (t === 'object') return Object.fromEntries((s.required || []).map(k => [k, fixture(s.properties[k])]));
  if (t === 'array') return [];
  if (t === 'integer' || t === 'number') return s.minimum ?? 1;
  if (t === 'boolean') return false;
  if (s.format === 'uuid') return '12345678-1234-4234-8234-123456789abc';
  if (s.format === 'date-time') return '2026-09-13T00:00:00Z';
  return 'example';
}
const nullCases = nullable.map(n => ({ ...n, input: { ...fixture(schema.components.schemas[n.name]), [n.property]: null } }));
const conditionalCases = [
  ['PlanRequest', { ...fixture(schema.components.schemas.PlanRequest), mode: 'improve' }],
  ['ThreadCreate', { kind: 'direct' }], ['ThreadCreate', { kind: 'pact' }], ['ThreadCreate', { kind: 'potluck' }],
  ...['cookSession', 'plan', 'recipeVersion', 'ingredient', 'taste', 'preparation'].map(kind => ['FeedbackTarget', {kind}]),
].map(([name, input]) => ({name, input}));
const uniqueCases = [];
for (const [name, s] of Object.entries(schema.components.schemas)) {
  for (const [property, p] of Object.entries(s.properties || {})) {
    if (p.uniqueItems) uniqueCases.push({ name, property, input: { ...fixture(s), [property]: [fixture(p.items), fixture(p.items)] } });
  }
}
const fixtures = { nullable: nullCases, conditional: conditionalCases, unique: uniqueCases, upload: fixture(schema.components.schemas.Upload) };
const fixtureFile = path.join(scratch, 'fixtures.json');
fs.writeFileSync(fixtureFile, JSON.stringify(fixtures, null, 2));
const probeExit = subsetExit === 0 ? run('serialization-probes', ['-cp', `${path.join(scratch, 'probe.jar')}:${runtimeCp}`, 'Contract_generator_spike_probeKt', fixtureFile]) : null;
const receipt = { date: new Date().toISOString(), canonical, canonicalSha256: digest(canonical), jars: Object.fromEntries(Object.entries(jars).map(([k,p]) => [k, {path:p, sha256:digest(p)}])), modelCount: names.length, nullable, excluded, fullExit, explicitExit, subsetExit, probeExit, runs };
fs.writeFileSync(path.join(scratch, 'receipt.json'), JSON.stringify(receipt, null, 2));
console.log(JSON.stringify({ modelCount: names.length, nullableCount: nullable.length, nullableTypePreserved: nullable.filter(x => x.nullable).length, fullExit, explicitExit, subsetExit, probeExit }));
// Successful evidence collection does not turn a failed generated build green.
process.exitCode = fullExit === 0 && explicitExit === 0 && probeExit === 0 ? 0 : 1;
