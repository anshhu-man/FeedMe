import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseCsv, loadCoverageInputs, verifyUiUxCoverage} from './verify-ui-ux-coverage.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fresh = () => loadCoverageInputs(root);
const rejects = mutate => { const input = fresh(); mutate(input); assert.equal(verifyUiUxCoverage(input).passed, false); };

test('actual registry has exact 98 screens 900 actions and feature-derived 81 17 coverage', () => {
  const result = verifyUiUxCoverage(fresh());
  assert.deepEqual(result.failures, []);
  assert.equal(result.passed, true);
  assert.equal(result.counts.screens, 98);
  assert.equal(result.counts.actions, 900);
  assert.equal(result.counts.v1Screens, 81);
  assert.equal(result.counts.deferredOnlyScreens, 17);
  assert.equal(result.counts.conditionalEdges, 52);
});
test('duplicate screen cannot hide a missing screen', () => rejects(i => { i.coverage.screens[1] = structuredClone(i.coverage.screens[0]); }));
test('duplicate action cannot preserve a false 900 count', () => rejects(i => { i.coverage.screens[0].actions[1] = structuredClone(i.coverage.screens[0].actions[0]); }));
test('deferred screen cannot be relabelled V1 or built', () => rejects(i => {
  const s = i.coverage.screens.find(s => s.id === 'VIDEO_EDIT'); s.release.classification = 'v1_only'; s.implementation.completeScreenVerified = true;
}));
test('conditional branch omission or destination substitution is rejected', () => rejects(i => {
  const a = i.coverage.screens.flatMap(s => s.actions).find(a => a.canonical.branches?.length); a.transitions.splice(1, 1);
}));
test('action request changes cannot launder registry or CSV meaning', () => rejects(i => { i.coverage.screens[0].actions[0].canonical.request = 'POST fake'; }));
test('navigation and function ownership cannot be unassigned', () => rejects(i => {
  i.coverage.screens.find(s => s.actions.some(a => a.canonical.scope === 'navigation')).actions.find(a => a.canonical.scope === 'navigation').ownerRole = 'nobody';
  i.coverage.runtimeOwnership[0].functions.push({name: 'unowned', ownerRole: 'nobody'});
}));
test('missing actual source or new unowned runtime file fails', () => {
  rejects(i => { i.pathExists = () => false; });
  rejects(i => { i.runtimePaths.push('shared/app/src/commonMain/kotlin/com/feedme/app/Unowned.kt'); });
});
test('test file declaration is not proof of action or screen acceptance', () => rejects(i => {
  const s = i.coverage.screens[0]; s.implementation.status = 'implemented_verified'; s.implementation.completeScreenVerified = true;
  s.actions[0].implementation.status = 'implemented_verified'; s.evidence.verifiedActionIds.push(s.actions[0].id);
}));
test('shared photo or collection action cannot lose its V1 restrictions', () => rejects(i => {
  const a = i.coverage.screens.find(s => s.id === 'COOKBOOK').actions.find(a => a.id === 'COOKBOOK.04');
  a.release = {classification: 'planned_v1', runtimeEnabledByThisDocument: false, blockedReasons: [], restrictions: []};
}));
test('canonical hash drift and CSV duplicate identities are detected', () => {
  rejects(i => { i.sourceHashes.registry = '0'.repeat(64); });
  rejects(i => { i.buttonCsv = i.buttonCsv.replace('AUTH_WELCOME.02', 'AUTH_WELCOME.01'); });
});
test('CSV preserves quoted commas escaped quotes and embedded newlines', () => {
  assert.deepEqual(parseCsv('id,label\r\n"a","a, b ""quote""\nline"\r\n'), [{id: 'a', label: 'a, b "quote"\nline'}]);
  assert.throws(() => parseCsv('id,label\n"a","unfinished'), /Unterminated/);
});
