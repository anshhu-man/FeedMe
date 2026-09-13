import test from 'node:test';
import assert from 'node:assert/strict';
import {verifyDeliveryPlan} from './verify-delivery-plan.mjs';

function fixture() {
  const screens = Array.from({length: 98}, (_, i) => ({id: `SCREEN_${i}`}));
  const features = Array.from({length: 54}, (_, i) => ({id: `F${String(i + 1).padStart(2, '0')}`, screenIds: [screens[i].id, ...(i < 44 ? [screens[i + 54].id] : [])]}));
  const plan = Array.from({length: 10}, (_, i) => `## M${i} — Milestone\n\n| M${i}.01 | READY | Implement one task | Passing evidence |`).join('\n\n');
  const matrix = features.map(f => `## ${f.id} — Feature\n\nPrimary milestone: M2\nScreens: ${f.screenIds.join(', ')}\n\n- [ ] ${f.id}.1: Implement domain\n- [ ] ${f.id}.2: Connect client\n- [ ] ${f.id}.3: Verify permissions and recovery\n`).join('\n');
  return {plan, matrix, userActions: '| U01 | Scope |', registry: {features, screens}};
}

const fails = (input, name) => assert.equal(verifyDeliveryPlan(input).checks.find(c => c.name === name)?.passed, false);
test('complete structural fixture passes without claiming production readiness', () => {
  const report = verifyDeliveryPlan(fixture());
  assert.equal(report.passed, true);
  assert.equal(report.counts.features, 54);
  assert.match(report.scope, /not native implementation/);
});
test('missing feature is rejected', () => {
  const input = fixture(); input.matrix = input.matrix.replace('## F12 — Feature', '## Missing — Feature');
  fails(input, 'F01–F54 each have one delivery section');
});
test('duplicate feature is rejected', () => {
  const input = fixture(); input.matrix = input.matrix.replace('## F12 — Feature', '## F11 — Feature');
  fails(input, 'F01–F54 each have one delivery section');
});
test('missing screen mapping is rejected without partial-ID matches', () => {
  const input = fixture(); input.matrix = input.matrix.replace('SCREEN_0,', 'SCREEN_000,');
  fails(input, 'Every feature includes all its canonical screen IDs');
});
test('unknown primary milestone is rejected', () => {
  const input = fixture(); input.matrix = input.matrix.replace('Primary milestone: M2', 'Primary milestone: M99');
  fails(input, 'Every feature has a valid primary milestone');
});
test('unchecked implementation work must be broken down', () => {
  const input = fixture(); input.matrix = input.matrix.replace('- [ ] F01.2: Connect client\n', 'Connect client\n');
  fails(input, 'Every feature has at least three checkable implementation tasks');
});
test('duplicate task is rejected', () => {
  const input = fixture(); input.plan += '\n| M0.01 | READY | Duplicate | Evidence |';
  fails(input, 'Task identifiers are unique');
});
test('duplicate feature task is rejected', () => {
  const input = fixture(); input.matrix = input.matrix.replace('F01.2:', 'F01.1:');
  fails(input, 'Feature task identifiers are unique');
});
test('feature task assigned to wrong owner is rejected', () => {
  const input = fixture(); input.matrix = input.matrix.replace('F01.2:', 'F99.2:');
  fails(input, 'Each feature owns at least three correctly identified tasks');
});
test('unknown task status is rejected', () => {
  const input = fixture(); input.plan = input.plan.replace('| READY |', '| SHIPPED_MAYBE |');
  fails(input, 'Every task has a known milestone, status, action and acceptance evidence');
});
test('user blocker without action ID is rejected', () => {
  const input = fixture(); input.plan = input.plan.replace('| READY |', '| USER_BLOCKED |');
  fails(input, 'Every user-blocked task identifies a user action');
});
test('unknown user action reference is rejected', () => {
  const input = fixture(); input.plan = input.plan.replace('Passing evidence', 'U99 approval');
  fails(input, 'All user-action references resolve');
});
test('known user action resolves', () => {
  const input = fixture(); input.plan = input.plan.replace('| READY |', '| USER_BLOCKED |').replace('Passing evidence', 'U01 scope decision');
  assert.equal(verifyDeliveryPlan(input).passed, true);
});
test('missing milestone heading is rejected', () => {
  const input = fixture(); input.plan = input.plan.replace('## M9 — Milestone', '## Missing');
  fails(input, 'M0–M9 each have one task section');
});
