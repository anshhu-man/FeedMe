#!/usr/bin/env node
// Fixed-project rollout coordinator for V089, V090 and the reviewed optional
// staff-moderation serving grants. The default is credential-free planning.
// There is no target override, migration selection, automatic retry, service
// deployment, staff enrollment, policy mutation or product activation.
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const project = 'bljskfhazmnzmhkbkcev';
const applyArgument = '--apply-v089-v090-and-staff-grants';
const generic = 'FeedMe V090 staff rollout refused or outcome unverified. Private diagnostics were not printed. No automatic retry.';
const usage = `Use node deploy/feedme-v090-staff-rollout.mjs [--plan|--preflight|${applyArgument}].`;
const expectedVersions = Array.from({length: 90}, (_, index) => index + 1);
const expectedBefore = expectedVersions.slice(0, 88);
const artifactPins = Object.freeze({
  'server/src/main/resources/db/migration/V089__never_dispatched_export_erasure.sql':
    'da4f0903499b3257c97bc8a9f2f86159e0f78a0a9ef8b6a205ebd11cca87dc5f',
  'server/src/main/resources/db/migration/V090__staff_operational_health_reader.sql':
    '13b093289a20928b91bbf439714a788525ea179bbe3ec52a87e5ce47e8d20b0d',
  'server/src/main/resources/db/provider/feedme-staff-moderation-serving-grants.sql':
    '06d7452ef2d3e08a680726c857b52a13eb68fccdd4ee95c99c7a4b6e3c629984',
  'deploy/supabase-migrate.mjs':
    'd4c64225226372450a6832158d413dd280333ba0a7a62ec350cb834b0fe3f836',
  'deploy/feedme-staff-moderation-role.mjs':
    'cd48ba4ceb72ee54c7e63d5e44dfe5e870c312a3b988b7e295ae3ed0c272b801',
});

const need = value => { if (!value) throw Error(generic); };
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const exact = (actual, expected) => Array.isArray(actual) && actual.length === expected.length &&
  actual.every((value, index) => value === expected[index]);

export function rolloutOperation(args) {
  if (args.length === 0 || args.length === 1 && args[0] === '--plan') return '--plan';
  if (args.length === 1 && args[0] === '--preflight') return '--preflight';
  if (args.length === 1 && args[0] === applyArgument) return '--apply';
  throw Error(usage);
}

export function verifyRolloutArtifacts(entries) {
  need(entries && typeof entries === 'object' && !Array.isArray(entries));
  need(Object.keys(entries).sort().join('\n') === Object.keys(artifactPins).sort().join('\n'));
  for (const [name, expected] of Object.entries(artifactPins)) {
    const bytes = entries[name];
    need(Buffer.isBuffer(bytes) && bytes.length > 0 && bytes.length <= 2 * 1024 * 1024);
    need(digest(bytes) === expected);
  }
  return {...artifactPins};
}

function localArtifacts() {
  const entries = {};
  for (const name of Object.keys(artifactPins)) {
    const target = path.join(root, name);
    const before = fs.lstatSync(target);
    need(before.isFile() && !before.isSymbolicLink() && before.nlink === 1 &&
      before.size > 0 && before.size <= 2 * 1024 * 1024 && fs.realpathSync(target) === target);
    const bytes = fs.readFileSync(target);
    const after = fs.lstatSync(target);
    need(after.ino === before.ino && after.dev === before.dev && after.size === before.size &&
      after.mtimeMs === before.mtimeMs && after.ctimeMs === before.ctimeMs);
    entries[name] = bytes;
  }
  return verifyRolloutArtifacts(entries);
}

export function parseRolloutChildReport(stdout, allowedStatuses) {
  need(Buffer.isBuffer(stdout) || typeof stdout === 'string');
  const text = stdout.toString('utf8');
  need(text.length <= 256 * 1024);
  const reports = [];
  for (const line of text.split(/\r?\n/)) {
    if (!line.startsWith('{') || !line.endsWith('}')) continue;
    try { reports.push(JSON.parse(line)); } catch { /* Refuse below. */ }
  }
  need(reports.length === 1);
  const report = reports[0];
  need(report && report.project === project && allowedStatuses.includes(report.status));
  return report;
}

function migrationState(report) {
  if (report.status === 'migrations-pending') {
    need(exact(report.appliedVersions, expectedBefore) && exact(report.pendingVersions, [89, 90]));
    return 'v088';
  }
  need(report.status === 'history-current' && exact(report.appliedVersions, expectedVersions) &&
    exact(report.pendingVersions, []));
  return 'v090';
}

function grantState(report) {
  if (report.status === 'staff-moderation-grants-pending') return 'pending';
  need(report.status === 'staff-moderation-grants-current');
  return 'current';
}

export function runRollout(operation, runner) {
  need(['--preflight', '--apply'].includes(operation) && typeof runner === 'function');
  let changed = false;
  let migration = runner('migration-check');
  const initial = migrationState(migration);

  if (operation === '--preflight' && initial === 'v088') {
    return {project, status:'ready-for-v089-v090-and-staff-grant-approval',
      hostedSchema:'v088', pendingVersions:[89,90], grantsChecked:false,
      changed:false, automaticRetry:false};
  }

  if (initial === 'v088') {
    need(operation === '--apply');
    const applied = runner('migration-apply');
    need(applied.status === 'migrations-completed');
    changed = true;
    migration = runner('migration-check');
    need(migrationState(migration) === 'v090');
  }

  let grants = runner('grant-preflight');
  const initialGrants = grantState(grants);
  if (operation === '--preflight') {
    return {project,
      status: initialGrants === 'current' ? 'v090-and-staff-grants-current' : 'v090-current-staff-grants-pending',
      hostedSchema:'v090', pendingVersions:[], grantsChecked:true, grants:initialGrants,
      changed:false, automaticRetry:false};
  }

  if (initialGrants === 'pending') {
    const applied = runner('grant-apply');
    need(applied.status === 'staff-moderation-grants-installed-and-verified');
    changed = true;
    grants = runner('grant-preflight');
    need(grantState(grants) === 'current');
  }
  return {project,status:'v090-and-staff-grants-current',hostedSchema:'v090',pendingVersions:[],
    grantsChecked:true,grants:'current',changed,automaticRetry:false,
    limitation:'Database migrations and optional serving grants only; no service configuration, deployment, staff enrollment or product activation'};
}

function childRunner(step) {
  const commands = {
    'migration-check': ['deploy/supabase-migrate.mjs', ['--check'], [0,3],
      ['history-current','migrations-pending']],
    'migration-apply': ['deploy/supabase-migrate.mjs', ['--apply'], [0],
      ['migrations-completed']],
    'grant-preflight': ['deploy/feedme-staff-moderation-role.mjs', ['--preflight'], [0],
      ['staff-moderation-grants-pending','staff-moderation-grants-current']],
    'grant-apply': ['deploy/feedme-staff-moderation-role.mjs', ['--apply'], [0],
      ['staff-moderation-grants-installed-and-verified']],
  };
  const command = commands[step];
  need(command);
  const child = spawnSync(process.execPath, [path.join(root, command[0]), ...command[1]], {
    cwd: root,
    env: {PATH:'/opt/homebrew/bin:/usr/bin:/bin'},
    timeout: 20 * 60 * 1000,
    maxBuffer: 256 * 1024,
    stdio: ['ignore','pipe','pipe'],
  });
  need(!child.error && !child.signal && command[2].includes(child.status));
  return parseRolloutChildReport(child.stdout, command[3]);
}

function main(args) {
  const operation = rolloutOperation(args);
  const pins = localArtifacts();
  if (operation === '--plan') {
    console.log(JSON.stringify({project,status:'local-only-v090-staff-rollout-plan',
      requiredInitialVersionRange:[1,88],pendingVersions:[89,90],artifactSha256:pins,
      credentialRead:false,connected:false,changed:false,automaticRetry:false,
      applyArgument,
      limitation:'Plan only; no database connection, migration, grant, configuration, service deployment, staff enrollment or product activation'}));
    return;
  }
  console.log(JSON.stringify(runRollout(operation, childRunner)));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); }
  catch (error) {
    console.error(error.message === usage ? usage : generic);
    process.exitCode = 1;
  }
}
