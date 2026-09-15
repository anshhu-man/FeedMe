import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {verifyReleaseScope} from './verify-release-scope.mjs';

const ids = Array.from({length: 54}, (_, index) => `F${String(index + 1).padStart(2, '0')}`);
const later = ['F32', 'F33', 'F34', 'F35', 'F36', 'F37', 'F38', 'F44', 'F45', 'F46'];
function fixture() {
  const features = ids.map(id => ({id, phase: ['F32', 'F33'].includes(id) ? 'P2' : later.includes(id) ? 'P3' : 'P1', screenIds: [`SCREEN_${id}`], operationIds: [`operation${id}`]}));
  return {
    manifest: {schemaVersion: 1, release: 'v1', decision: 'existing-phase-deferrals-only', includedFeatureIds: ids.filter(id => !later.includes(id)), deferredFeatureIds: [...later]},
    registry: {features, screens: features.map(feature => ({id: feature.screenIds[0]}))},
  };
}
const reject = input => {
  const report = verifyReleaseScope(input);
  assert.equal(report.passed, false);
  assert.equal(report.coverage, null);
  assert.ok(report.checks.some(check => !check.passed));
};

test('approved existing-phase partition passes without claiming runtime gating or readiness', () => {
  const report = verifyReleaseScope(fixture());
  assert.equal(report.passed, true);
  assert.equal(report.counts.includedFeatures, 44); assert.equal(report.counts.deferredFeatures, 10);
  assert.match(report.scope, /does not implement runtime feature gating/);
  assert.match(report.scope, /implementation\/release readiness/);
});

test('pure validation preserves input and returns detached deterministic coverage', () => {
  const input = fixture(); const original = structuredClone(input);
  const first = verifyReleaseScope(input); const second = verifyReleaseScope(input);
  assert.deepEqual(first, second); assert.deepEqual(input, original);
  first.coverage.screens.v1.length = 0;
  assert.deepEqual(verifyReleaseScope(input), second); assert.deepEqual(input, original);
});

test('actual canonical registry keeps 81 V1 screens and 157 feature-associated operations', () => {
  const input = fixture();
  input.registry = JSON.parse(fs.readFileSync(new URL('../../outputs/biteclub_blueprint/registry/screen_registry.json', import.meta.url), 'utf8'));
  const report = verifyReleaseScope(input);
  assert.equal(report.passed, true);
  assert.equal(report.counts.canonicalScreens, 98); assert.equal(report.counts.v1Screens, 81);
  assert.equal(report.counts.sharedScreens, 11); assert.equal(report.counts.deferredOnlyScreens, 17);
  assert.equal(report.counts.canonicalOperations, 201); assert.equal(report.counts.v1Operations, 157);
  assert.equal(report.counts.sharedOperations, 27); assert.equal(report.counts.deferredOnlyOperations, 44);
});

test('unsupported manifest version release decision or additional fields require a new contract', () => {
  for (const mutation of [m => { m.schemaVersion = '1'; }, m => { m.schemaVersion = 2; }, m => { m.release = 'v2'; }, m => { m.decision = 'focused-shipaton'; }, m => { m.extraApproval = true; }]) {
    const input = fixture(); mutation(input.manifest); reject(input);
  }
});

test('missing manifest fields and non-array partitions are rejected', () => {
  for (const field of ['schemaVersion', 'release', 'decision', 'includedFeatureIds', 'deferredFeatureIds']) {
    const input = fixture(); delete input.manifest[field]; reject(input);
  }
  for (const field of ['includedFeatureIds', 'deferredFeatureIds']) {
    const input = fixture(); input.manifest[field] = 'F01'; reject(input);
  }
});

test('missing duplicate and unknown canonical feature identities are rejected', () => {
  for (const mutation of [f => f.pop(), f => { f[1].id = 'F01'; }, f => { f[1].id = 'F99'; }]) {
    const input = fixture(); mutation(input.registry.features); reject(input);
  }
});

test('malformed registry and manifest values produce failed reports rather than partial coverage', () => {
  for (const value of [null, 1, 'bad', [], {}, {features: [null], screens: [null]}]) {
    const input = fixture(); input.registry = value; reject(input);
    input.registry = fixture().registry; input.manifest = value; reject(input);
  }
  reject({}); reject(); reject(null);
});

test('duplicate IDs in either partition are rejected even when every feature is still present', () => {
  for (const field of ['includedFeatureIds', 'deferredFeatureIds']) {
    const input = fixture(); input.manifest[field].push(input.manifest[field][0]); reject(input);
  }
});

test('unknown malformed or non-string feature IDs are never normalized into approval', () => {
  for (const id of ['F00', 'F55', 'f01', ' F01', 'F01 ', 1, null]) {
    const input = fixture(); input.manifest.includedFeatureIds[0] = id; reject(input);
  }
});

test('overlapping partitions cannot authorize both inclusion and deferral', () => {
  const input = fixture(); input.manifest.deferredFeatureIds.push('F01'); reject(input);
});

test('omitting any included or deferred feature cannot silently shrink the full blueprint', () => {
  for (const field of ['includedFeatureIds', 'deferredFeatureIds']) {
    const input = fixture(); input.manifest[field].pop(); reject(input);
  }
});

test('reenabling any later feature is rejected despite an otherwise complete unique partition', () => {
  for (const id of later) {
    const input = fixture(); input.manifest.deferredFeatureIds = input.manifest.deferredFeatureIds.filter(value => value !== id);
    input.manifest.includedFeatureIds.push(id); reject(input);
  }
});

test('deferring an additional P1 feature including F50 requires a new explicit decision', () => {
  for (const id of ['F01', 'F21', 'F42', 'F50', 'F54']) {
    const input = fixture(); input.manifest.includedFeatureIds = input.manifest.includedFeatureIds.filter(value => value !== id);
    input.manifest.deferredFeatureIds.push(id); reject(input);
  }
});

test('changing registry phase labels cannot bypass the existing-phase decision', () => {
  for (const id of ['F01', 'F32', 'F34']) {
    const input = fixture(); input.registry.features.find(feature => feature.id === id).phase = id === 'F01' ? 'P2' : 'P1'; reject(input);
  }
});

test('shared screens and operations stay in V1 and never appear in deferred-only resources', () => {
  const input = fixture(); const shared = input.registry.features[0]; const deferred = input.registry.features.find(feature => feature.id === 'F32');
  deferred.screenIds.push(shared.screenIds[0]); deferred.operationIds.push(shared.operationIds[0]);
  const report = verifyReleaseScope(input); assert.equal(report.passed, true);
  for (const kind of ['screens', 'operations']) {
    assert.equal(report.coverage[kind].shared.length, 1);
    const id = report.coverage[kind].shared[0];
    assert.ok(report.coverage[kind].v1.includes(id)); assert.ok(!report.coverage[kind].deferredOnly.includes(id));
    assert.equal(new Set([...report.coverage[kind].v1, ...report.coverage[kind].deferredOnly]).size, report.coverage[kind].all.length);
  }
});

test('unknown duplicate and orphan screen mappings are rejected instead of dropping resources', () => {
  for (const mutation of [r => { r.features[0].screenIds = ['UNKNOWN']; }, r => { r.features[0].screenIds.push(r.features[0].screenIds[0]); }, r => { r.screens.push({id: 'ORPHAN'}); }, r => { r.screens.push({...r.screens[0]}); }]) {
    const input = fixture(); mutation(input.registry); reject(input);
  }
});

test('missing duplicate and malformed operation associations are rejected', () => {
  for (const value of [null, [], ['operationF01', 'operationF01'], ['not an operation'], [null]]) {
    const input = fixture(); input.registry.features[0].operationIds = value; reject(input);
  }
});

test('partition and registry ordering do not change coverage or validate an unapproved subset', () => {
  const input = fixture(); const before = verifyReleaseScope(input);
  input.manifest.includedFeatureIds.reverse(); input.manifest.deferredFeatureIds.reverse();
  input.registry.features.reverse(); input.registry.screens.reverse();
  assert.deepEqual(verifyReleaseScope(input), before);
  input.manifest.includedFeatureIds.pop(); reject(input);
});
