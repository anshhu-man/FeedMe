import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';

// Structural tracking checks only. A checked box is never proof of a shipped feature.
export function verifyDeliveryPlan({plan, matrix, userActions, registry}) {
  const checks = [];
  const check = (name, passed, detail = '') => checks.push({name, passed: Boolean(passed), ...(detail ? {detail} : {})});
  const expectedMilestones = Array.from({length: 10}, (_, i) => `M${i}`);
  const expectedFeatures = Array.from({length: 54}, (_, i) => `F${String(i + 1).padStart(2, '0')}`);
  const milestoneIds = [...plan.matchAll(/^## (M\d+) — /gm)].map(m => m[1]);
  check('M0–M9 each have one task section', milestoneIds.length === 10 && expectedMilestones.every(id => milestoneIds.filter(x => x === id).length === 1));

  const rows = plan.split('\n').filter(line => /^\| M\d+\.\d+ \|/.test(line)).map(line => {
    const cells = line.split('|').slice(1, -1).map(s => s.trim());
    return {id: cells[0], status: cells[1], task: cells[2], evidence: cells[3], columns: cells.length};
  });
  const allowedStatuses = new Set(['TODO', 'READY', 'IN_PROGRESS', 'USER_BLOCKED', 'DEFERRED', 'DONE']);
  const actionIds = new Set([...userActions.matchAll(/^\| (U\d{2}) \|/gm)].map(m => m[1]));
  check('Every milestone has small tasks', expectedMilestones.every(id => rows.some(row => row.id.startsWith(`${id}.`))));
  check('Task identifiers are unique', new Set(rows.map(row => row.id)).size === rows.length);
  check('Every task has a known milestone, status, action and acceptance evidence', rows.every(row => row.columns === 4 && expectedMilestones.includes(row.id.split('.')[0]) && allowedStatuses.has(row.status) && row.task && row.evidence));
  const blockedWithoutAction = rows.filter(row => row.status === 'USER_BLOCKED' && !/\bU\d{2}\b/.test(row.evidence));
  check('Every user-blocked task identifies a user action', blockedWithoutAction.length === 0, blockedWithoutAction.map(row => row.id).join(', '));
  const unknownActions = rows.flatMap(row => [...row.evidence.matchAll(/\bU\d{2}\b/g)].map(m => ({task: row.id, action: m[0]}))).filter(ref => !actionIds.has(ref.action));
  check('All user-action references resolve', unknownActions.length === 0, unknownActions.map(ref => `${ref.task}:${ref.action}`).join(', '));

  const headings = [...matrix.matchAll(/^## (F\d{2}) — [^\n]+/gm)];
  const blocks = headings.map((heading, i) => ({id: heading[1], text: matrix.slice(heading.index, headings[i + 1]?.index ?? matrix.length)}));
  const featureTasks = [...matrix.matchAll(/^- \[[ x]\] (F\d{2}\.\d+): /gm)].map(m => m[1]);
  check('F01–F54 each have one delivery section', blocks.length === 54 && expectedFeatures.every(id => blocks.filter(block => block.id === id).length === 1));
  check('Feature task identifiers are unique', new Set(featureTasks).size === featureTasks.length);
  check('Each feature owns at least three correctly identified tasks', blocks.every(block => {
    const ids = [...block.text.matchAll(/^- \[[ x]\] (F\d{2}\.\d+): /gm)].map(m => m[1]);
    return ids.length >= 3 && ids.every(id => id.startsWith(`${block.id}.`));
  }));
  check('Canonical registry retains 54 features and 98 screens', registry.features.length === 54 && new Set(registry.features.map(f => f.id)).size === 54 && registry.screens.length === 98 && new Set(registry.screens.map(s => s.id)).size === 98);

  const missingScreens = [];
  const missingTasks = [];
  const missingMilestones = [];
  for (const feature of registry.features) {
    const block = blocks.find(b => b.id === feature.id)?.text || '';
    for (const screen of feature.screenIds) if (!new RegExp(`\\b${screen}\\b`).test(block)) missingScreens.push(`${feature.id}:${screen}`);
    const primary = block.match(/Primary(?: milestone)?[^:\n]*:\s*(?:\*\*)?(M\d+)\b/i)?.[1];
    if (!expectedMilestones.includes(primary)) missingMilestones.push(feature.id);
    if ([...block.matchAll(/^- \[[ x]\] /gm)].length < 3) missingTasks.push(feature.id);
  }
  check('Every feature includes all its canonical screen IDs', missingScreens.length === 0, missingScreens.join(', '));
  check('Every feature has a valid primary milestone', missingMilestones.length === 0, missingMilestones.join(', '));
  check('Every feature has at least three checkable implementation tasks', missingTasks.length === 0, missingTasks.join(', '));
  const screenUnion = new Set(registry.features.flatMap(f => f.screenIds));
  check('Feature mapping covers all 98 canonical screens', registry.screens.every(s => screenUnion.has(s.id)));

  return {
    passed: checks.every(c => c.passed),
    scope: 'Execution-board structural coverage only; not native implementation, production testing, milestone completion or release certification.',
    counts: {milestones: milestoneIds.length, milestoneTasks: rows.length, features: blocks.length, featureTasks: featureTasks.length, screens: registry.screens.length, checks: checks.length},
    tasksByStatus: Object.fromEntries([...allowedStatuses].map(status => [status, rows.filter(row => row.status === status).length])),
    checks,
  };
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const paths = {plan: path.join(root, 'docs/RELEASE_PLAN.md'), matrix: path.join(root, 'docs/FEATURE_DELIVERY_MATRIX.md'), userActions: path.join(root, 'docs/USER_ACTIONS.md'), registry: path.join(root, '../outputs/biteclub_blueprint/registry/screen_registry.json')};
  const inputs = Object.fromEntries(Object.entries(paths).map(([key, file]) => [key, fs.readFileSync(file, 'utf8')]));
  const report = verifyDeliveryPlan({...inputs, registry: JSON.parse(inputs.registry)});
  report.verifiedAt = new Date().toISOString();
  report.sourceSha256 = createHash('sha256').update(JSON.stringify(inputs)).digest('hex');
  if (process.argv.includes('--write-report')) {
    fs.mkdirSync(path.join(root, 'docs/verification'), {recursive: true});
    fs.writeFileSync(path.join(root, 'docs/verification/delivery-plan-report.json'), JSON.stringify(report, null, 2) + '\n');
  }
  console.log(JSON.stringify(report, null, 2));
  if (!report.passed) process.exitCode = 1;
}
