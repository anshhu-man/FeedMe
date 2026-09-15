import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawn, spawnSync} from 'node:child_process';
import {verifyReleaseScope} from './verify-release-scope.mjs';
import {startupClass, startupScenarios, startupCheckpoint, startupInterrupted, startupRecovered} from './startup-process-evidence.mjs';

// Source-bound UI/HTTP/witnessed-process component batch; not complete V1 or release acceptance.
// Counts are fixed below and every executed native identity is checked against current source.
// Requires existing JDK17, SDK36, local PostgreSQL binaries and a booted local emulator.
// Fixed after the owning lanes froze their exact source test inventories.
const parallelCounts = {planning: 40, mealflow: 82, app: 34, session: 593, server: 88, postgresql: 119, startupNative: 12};
if (Object.values(parallelCounts).some(value => !Number.isSafeInteger(value) || value < 1))
  throw new Error('Parallel feature counts remain pending; do not run before all source lanes freeze.');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const destination = path.join(root, 'docs/verification/parallel-ui-http-process');
const startedAt = new Date().toISOString();
const attempt = path.join(destination, 'attempts', startedAt.replaceAll(':', '-'));
fs.mkdirSync(attempt, {recursive: true});
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const artifact = name => {
  const file = path.join(root, name);
  if (!fs.lstatSync(file).isFile()) throw new Error('Verification artifacts must be regular files, not symlinks.');
  const bytes = fs.readFileSync(file);
  return {path: name, bytes: bytes.length, sha256: hash(bytes)};
};
const walk = directory => {
  if (!fs.lstatSync(directory).isDirectory()) throw new Error('Verification directories must not be symlinks.');
  return fs.readdirSync(directory, {withFileTypes: true}).flatMap(entry => {
    const name = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error('Verification inputs must not contain symlinks.');
    if (!entry.isDirectory() && !entry.isFile()) throw new Error('Verification inputs must be regular files.');
    return entry.isDirectory() ? walk(name) : [name];
  }).sort();
};
const sourceFiles = () => [
  'build.gradle.kts', 'settings.gradle.kts', 'gradle/libs.versions.toml',
  'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
  ...['core', 'contracts', 'transport', 'storage', 'sync', 'kitchen', 'session', 'planning', 'mealflow', 'app'].flatMap(module => [
    `shared/${module}/build.gradle.kts`,
    ...walk(path.join(root, `shared/${module}/src`)).map(name => path.relative(root, name)),
  ]),
  'server/build.gradle.kts', ...walk(path.join(root, 'server/src')).map(name => path.relative(root, name)),
  ...walk(path.join(root, 'scripts')).map(name => path.relative(root, name)),
  'docs/verification/canonical-validation/format-corpus.json',
  'docs/verification/schema-validator-spike/cases.json',
  'docs/verification/contract-artifacts/generated-receipt.json',
  'apps/android/src/main/AndroidManifest.xml',
  'apps/android/src/main/res/xml/backup_rules.xml',
  'apps/android/src/main/res/xml/data_extraction_rules.xml',
  'docs/V1_RELEASE_SCOPE.json',
  '../outputs/biteclub_blueprint/architecture/02_Architecture.md',
  '../outputs/biteclub_blueprint/architecture/03_Data_Model.md',
  '../outputs/biteclub_blueprint/architecture/04_API_Contract.json',
  '../outputs/biteclub_blueprint/architecture/05_Events.md',
  '../outputs/biteclub_blueprint/registry/screen_registry.json',
  ...['F01', 'F02', 'F03', 'F04', 'F05', 'F06', 'F07', 'F09', 'F11', 'F13', 'F17', 'F23', 'F40', 'F42']
    .map(feature => `../outputs/biteclub_blueprint/features/${feature}.md`),
].sort().map(artifact);
const before = sourceFiles();
const report = {
  startedAt, passed: false,
  scope: "Parallel V1 UI/HTTP/process component batch. Actual retained-session meal form and presentation host, authenticated bounded ingredient catalog lookup/cache, explicit pantry reports and exact durable meal commands remain separate from cooking/save/share authority. Six native Compose presentation tests and five actual screenshots use synthetic view-state fixtures, not a real provider journey or retained-experience end-to-end proof. Four canonical planning HTTP operations are explicitly opt-in to an actual PostgreSQL PlansStore and mandatory trusted verifier/current identity/catalog adapters; default Main remains health-degraded and product-503. HTTP tests use real Ktor/CIO and PostgreSQL with explicitly synthetic identity/catalog fixtures, exact numeric/body validation and same-command outcome reconciliation; no production provider or deployment exists. Seven separate host-witnessed forced terminations occur at acknowledged existing-startup/abort/close boundaries; only the seven fresh-process recovery methods are passed tests, never the deliberately killed starts. Original authenticated plans, confirmation, lifecycle and close ownership remain enforced. Existing native ownership, 39 injected-VFS labels and five controlled process stages remain; test helper packaging is checked for four ABIs, runtime device is API35 ARM64 only. These are not transaction-crash, OS-fsync failure, physical-power-loss, general orphan/hot-journal, real-provider/UI-consent, API26/iOS, physical-device, signing or release acceptance. Approved V1 remains44 P1 features with10 deferred; historical demo bytes remain unchanged.",
  sourceManifestSha256: hash(JSON.stringify(before)), sourceFiles: before,
  commands: [], tests: [], artifacts: [], native: [],
};
fs.writeFileSync(path.join(attempt, 'source-manifest.json'), JSON.stringify(before, null, 2) + '\n');

const nativeSuites = [
  {
    name: 'storage', module: 'storage', directory: 'client-storage', package: 'com.feedme.storage.test',
    script: 'scripts/android-storage-smoke.mjs', cleanupPattern: /private-state-instrumented-|feedme-state-instrumented-process-restart-v1|feedme-work-origin-instrumented-process-v1/,
    checks: [
      {name: 'regular', tests: 71, classes: 'com.feedme.storage.AndroidStateVaultTest,com.feedme.storage.AndroidStateDatabaseTest,com.feedme.storage.AndroidSessionControlStoreTest,com.feedme.storage.AndroidSessionWorkStoreTest,com.feedme.storage.AndroidStateActivationPlanTest,com.feedme.storage.AndroidStateActivationRecoveryTest,com.feedme.storage.AndroidSessionWorkOriginPlanTest'},
      {name: 'retained-recovery-owner', tests: 15, classes: 'com.feedme.storage.AndroidStateActivationRecoveryOwnerTest'},
      {name: 'work-recovery-store', tests: 12, classes: 'com.feedme.storage.AndroidSessionWorkRecoveryStoreTest'},
      {name: 'control-recovery-store', tests: 12, classes: 'com.feedme.storage.AndroidSessionControlRecoveryStoreTest'},
      {name: 'sync-failure', tests: 6, classes: 'com.feedme.storage.AndroidStateActivationSyncFailureTest'},
      {name: 'ledger-sync-failure', tests: 9, classes: 'com.feedme.storage.AndroidSessionLedgerSyncFailureTest'},
      {name: 'work-origin-sync-failure', tests: 3, classes: 'com.feedme.storage.AndroidSessionWorkOriginPlanSyncFailureTest'},
      {name: 'binding-sync-failure', tests: 3, classes: 'com.feedme.storage.AndroidStateBindingSyncFailureTest'},
      {name: 'work-origin-abort', tests: 12, classes: 'com.feedme.storage.AndroidSessionWorkOriginAbortTest'},
      {name: 'work-origin-abort-sync-failure', tests: 3, classes: 'com.feedme.storage.AndroidSessionWorkOriginAbortSyncFailureTest'},
      {name: 'binding-abort', tests: 10, classes: 'com.feedme.storage.AndroidStateBindingAbortTest'},
      {name: 'binding-abort-sync-failure', tests: 3, classes: 'com.feedme.storage.AndroidStateBindingAbortSyncFailureTest'},
      {name: 'work-origin-plan', tests: 1, classes: 'com.feedme.storage.AndroidSessionWorkOriginProcessTest#preparePlanInFirstProcess', stage: 'work-origin-plan'},
      {name: 'work-origin-select', tests: 1, classes: 'com.feedme.storage.AndroidSessionWorkOriginProcessTest#selectExactPlanInSecondProcess', stage: 'work-origin-select'},
      {name: 'work-origin-replay', tests: 1, classes: 'com.feedme.storage.AndroidSessionWorkOriginProcessTest#reacknowledgeSelectedPlanAndCleanInThirdProcess', stage: 'work-origin-replay'},
      {name: 'restart-write', tests: 1, classes: 'com.feedme.storage.AndroidStateProcessRestartTest#writeFixture', stage: 'write'},
      {name: 'restart-read', tests: 1, classes: 'com.feedme.storage.AndroidStateProcessRestartTest#readFixtureAndCleanup', stage: 'read'},
    ],
  },
  {
    name: 'session', module: 'session', directory: 'native-credentials', package: 'com.feedme.session.test',
    script: 'scripts/android-credential-smoke.mjs', cleanupPattern: /credential-state-instrumented-|retirement-integration-/,
    checks: [
      {name: 'regular', tests: 47, classes: 'com.feedme.session.AndroidCredentialStoreTest,com.feedme.session.AndroidNativeWorkCancellationTest,com.feedme.session.AndroidCredentialCreatePlanTest'},
      {name: 'retained-recovery-owner', tests: 12, classes: 'com.feedme.session.AndroidCredentialRecoveryOwnerTest'},
      {name: 'work-recovery', tests: 7, classes: 'com.feedme.session.AndroidSessionWorkRecoveryTest'},
      {name: 'integration', tests: 11, classes: 'com.feedme.session.AndroidLocalRetirementIntegrationTest'},
      {name: 'setup-journal', tests: 7, classes: 'com.feedme.session.AndroidSessionSetupJournalTest'},
      {name: 'live-setup', tests: 11, classes: 'com.feedme.session.AndroidLiveSessionSetupCoordinatorTest'},
      {name: 'setup-publication', tests: 9, classes: 'com.feedme.session.AndroidSessionSetupPublicationTest'},
      {name: 'credential-inspection', tests: 12, classes: 'com.feedme.session.AndroidCredentialCreateInspectionTest'},
      {name: 'interrupted-setup-inspection', tests: 9, classes: 'com.feedme.session.AndroidInterruptedSetupInspectionTest'},
      {name: 'credential-plan-abort', tests: 12, classes: 'com.feedme.session.AndroidCredentialPlanAbortTest'},
      {name: 'composite-setup-abort', tests: 10, classes: 'com.feedme.session.AndroidCompositeSetupAbortTest'},
      {name: 'owned-startup-recovery', tests: parallelCounts.startupNative, classes: 'com.feedme.session.AndroidSessionSetupRecoveryOwnerTest'},
    ],
  },
];

async function run(name, command, args) {
  const logPath = path.join(attempt, `${name}.log`);
  const stream = fs.createWriteStream(logPath);
  const began = new Date().toISOString();
  const result = await new Promise((resolve, reject) => {
    const child = spawn(command, args, {cwd: root, env: process.env, stdio: ['ignore', 'pipe', 'pipe']});
    for (const output of [child.stdout, child.stderr]) output.on('data', data => { stream.write(data); });
    child.on('error', failure => { stream.end(); reject(failure); });
    child.on('close', code => stream.end(() => resolve(code)));
  });
  process.stdout.write(`${name}: exit ${result}\n`);
  report.commands.push({name, startedAt: began, finishedAt: new Date().toISOString(), exitCode: result, log: path.relative(root, logPath)});
  if (result !== 0) throw new Error(`${name} failed; its attempt log is retained.`);
}


function retainReleaseScopeEvidence(notBefore) {
  const sourcePath = 'docs/verification/release-scope-report.json';
  const raw = fs.readFileSync(path.join(root, sourcePath));
  const parsed = JSON.parse(raw.toString('utf8'));
  const retained = path.join(attempt, 'release-scope-report.json');
  fs.writeFileSync(retained, raw);
  const inputs = {
    manifest: fs.readFileSync(path.join(root, 'docs/V1_RELEASE_SCOPE.json'), 'utf8'),
    registry: fs.readFileSync(path.join(root, '../outputs/biteclub_blueprint/registry/screen_registry.json'), 'utf8'),
  };
  const expected = verifyReleaseScope({manifest: JSON.parse(inputs.manifest), registry: JSON.parse(inputs.registry)});
  const {verifiedAt, sourceSha256, ...actual} = parsed;
  const timestamp = Date.parse(verifiedAt);
  if (expected.passed !== true || JSON.stringify(actual) !== JSON.stringify(expected) ||
      sourceSha256 !== hash(JSON.stringify(inputs)) || !Number.isFinite(timestamp) ||
      timestamp < notBefore || timestamp > Date.now())
    throw new Error('Release-scope evidence is stale, incomplete or disagrees with current canonical inputs.');
  const source = artifact(sourcePath);
  const copy = artifact(path.relative(root, retained));
  if (source.sha256 !== copy.sha256) throw new Error('Release-scope evidence changed while being retained.');
  return {report: parsed, source, retained: copy,
    scope: 'Approved V1 planning/build partition only; not runtime gating, native certification or release readiness.'};
}


function declaredJvmTests(name) {
  const roots = name === 'server' ? ['server/src/test'] : name === 'postgresql' ? ['server/src/integrationTest'] :
    ['commonTest', 'jvmTest'].map(kind => 'shared/' + name + '/src/' + kind).filter(dir => fs.existsSync(path.join(root, dir)));
  const identities = [];
  for (const directory of roots) for (const file of walk(path.join(root, directory)).filter(file => file.endsWith('.kt'))) {
    const source = fs.readFileSync(file, 'utf8');
    const methods = [...source.matchAll(/@Test(?:\([^)]*\))?\s+fun\s+(\x60[^\x60]+\x60|[A-Za-z_][A-Za-z0-9_]*)\s*\(/g)]
      .map(match => match[1].replace(/^\x60|\x60$/g, ''));
    if (!methods.length) continue;
    const packageName = source.match(/^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$/m)?.[1];
    const className = path.basename(file, '.kt');
    if (!packageName || !new RegExp('^class\\s+' + className + '\\b', 'm').test(source) ||
        [...source.matchAll(/^\s*@Test\b/gm)].length !== methods.length)
      throw new Error('JUnit source declaration shape needs explicit review: ' + path.relative(root, file));
    methods.forEach(method => identities.push(packageName + '.' + className + '#' + method));
  }
  if (new Set(identities).size !== identities.length) throw new Error('Duplicate declared JUnit identities.');
  return identities.sort();
}
function xmlText(value) {
  return value.replace(/&#(x[0-9a-f]+|\d+);|&(quot|apos|lt|gt|amp);/gi, (whole, number, name) =>
    number ? String.fromCodePoint(number[0].toLowerCase() === 'x' ? parseInt(number.slice(1), 16) : Number(number)) :
      ({quot:'"', apos:"'", lt:'<', gt:'>', amp:'&'})[name.toLowerCase()]);
}
function junit(directory, name, expected, notBefore) {
  const declared = declaredJvmTests(name);
  if (declared.length !== expected) throw new Error(name + ' source test inventory changed.');
  const files = walk(path.join(root, directory)).filter(file => path.basename(file).startsWith('TEST-') && file.endsWith('.xml'));
  const result = {name, tests:0, failures:0, errors:0, skipped:0, identities:[], suites:[]};
  const out = path.join(attempt, 'junit', name); fs.mkdirSync(out, {recursive:true});
  const attributes = text => Object.fromEntries([...text.matchAll(/([A-Za-z]+)="([^"]*)"/g)].map(match => [match[1], xmlText(match[2])]));
  for (const file of files) {
    const stat = fs.statSync(file);
    if (stat.mtimeMs < notBefore || stat.mtimeMs > Date.now()) throw new Error('JUnit output is not from this fresh build.');
    const xml = fs.readFileSync(file, 'utf8');
    const suites = [...xml.matchAll(/<testsuite\b([^>]+)>/g)];
    if (suites.length !== 1 || /<(?:failure|error|skipped)\b/.test(xml)) throw new Error('Unexpected or unsuccessful JUnit document.');
    const attrs = attributes(suites[0][1]);
    const began = Date.parse(attrs.timestamp);
    if (!Number.isFinite(began) || began < notBefore || began > Date.now()) throw new Error('JUnit suite timestamp is not fresh.');
    for (const key of ['tests','failures','errors','skipped']) {
      if (!/^\d+$/.test(attrs[key])) throw new Error('Unexpected JUnit count.');
      result[key] += Number(attrs[key]);
    }
    const cases = [...xml.matchAll(/<testcase\b([^>]+)>/g)].map(match => attributes(match[1]));
    if (cases.length !== Number(attrs.tests) || cases.some(value => value.classname !== attrs.name)) throw new Error('JUnit per-case/class counts disagree.');
    const suffix = name === 'server' || name === 'postgresql' ? '' : '[jvm]';
    for (const test of cases) {
      if (suffix && !test.name.endsWith(suffix)) throw new Error('Expected JVM-target test identity.');
      const method = suffix ? test.name.slice(0, -suffix.length) : test.name;
      result.identities.push(test.classname + '#' + method);
    }
    result.suites.push({name:attrs.name, timestamp:attrs.timestamp, tests:cases.length});
    fs.copyFileSync(file, path.join(out, path.basename(file)));
  }
  if (result.tests !== expected || result.failures || result.errors || result.skipped ||
      new Set(result.suites.map(value => value.name)).size !== result.suites.length ||
      JSON.stringify([...result.identities].sort()) !== JSON.stringify(declared))
    throw new Error(name + ' did not pass every exact source-declared test.');
  report.tests.push(result);
}
function verifyNodeTests(files, log) {
  const declared = [];
  for (const file of files) {
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    // The canonical generator's two helpers each register one literal-named test.
    // No test callback is executed to discover this source inventory.
    const registrations = file === 'scripts/generate-contract-artifacts.test.mjs' ? '(?:test|rejectsDocument|rejectsRegistry)' : 'test';
    const declarations = new RegExp('^\\s*' + registrations + '\\(\\s*(\'(?:\\\\.|[^\'\\\\])*\'|"(?:\\\\.|[^"\\\\])*")', 'gm');
    const names = [...source.matchAll(declarations)].map(match => {
      const literal = match[1];
      if (literal[0] === '"') return JSON.parse(literal);
      const body = literal.slice(1, -1);
      if (/\\[^\\']/.test(body)) throw new Error('Review newly escaped Node test name.');
      return body.replace(/\\(['\\])/g, '$1');
    });
    if (!names.length) throw new Error('Expected declared Node regression file.');
    declared.push(...names);
  }
  const starts = [...log.matchAll(/^# Subtest: (.*)$/gm)].map(match => match[1]);
  const passed = [...log.matchAll(/^ok (\d+) - (.*)$/gm)];
  if (declared.length !== 181 || new Set(declared).size !== 181 ||
      JSON.stringify([...starts].sort()) !== JSON.stringify([...declared].sort()) ||
      JSON.stringify(passed.map(match => match[2]).sort()) !== JSON.stringify([...declared].sort()) ||
      passed.some((match, index) => Number(match[1]) !== index + 1) ||
      /^not ok\b|# (?:SKIP|TODO)\b/m.test(log)) throw new Error('Node TAP identities differ from exact current source declarations.');
  for (const [key, count] of Object.entries({tests:181,pass:181,fail:0,cancelled:0,skipped:0,todo:0})) {
    const rows = [...log.matchAll(new RegExp('^# ' + key + ' (\\d+)$', 'gm'))];
    if (rows.length !== 1 || Number(rows[0][1]) !== count) throw new Error('Unexpected Node TAP summary.');
  }
  return {tests:181,failures:0,errors:0,skipped:0,identities:passed.map(match => match[2]),files};
}

function verifyInstrumentationTranscript(log, check) {
  const summaries = [...log.matchAll(/^OK \((\d+) tests?\)\s*$/gm)];
  const endings = [...log.matchAll(/^INSTRUMENTATION_CODE: (-?\d+)\s*$/gm)];
  if (summaries.length !== 1 || Number(summaries[0][1]) !== check.tests || endings.length !== 1 || endings[0][1] !== '-1' ||
      /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log)) {
    throw new Error('Native transcript has no unique successful instrumentation completion.');
  }
  const events = [];
  let fields = {};
  for (const line of log.split(/\r?\n/)) {
    const field = line.match(/^INSTRUMENTATION_STATUS: (class|test|current|numtests|id)=(.*)$/);
    if (field) {
      if (Object.hasOwn(fields, field[1])) throw new Error('Native transcript repeated a test identity field.');
      fields[field[1]] = field[2];
    }
    const status = line.match(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$/);
    if (status) { events.push({...fields, code: Number(status[1])}); fields = {}; }
  }
  if (Object.keys(fields).length || events.length !== check.tests * 2) throw new Error('Native transcript has incomplete test events.');
  const allowed = check.classes.split(',').map(value => value.split('#'));
  const completed = new Set();
  const classes = new Set();
  for (let index = 0; index < check.tests; index++) {
    const start = events[index * 2];
    const finish = events[index * 2 + 1];
    for (const event of [start, finish]) {
      if (event.id !== 'AndroidJUnitRunner' || event.current !== String(index + 1) || event.numtests !== String(check.tests) ||
          !/^[A-Za-z_][A-Za-z0-9_]*$/.test(event.test || '') ||
          !allowed.some(([className, method]) => event.class === className && (!method || event.test === method))) {
        throw new Error('Native transcript ran an unexpected test identity or test count.');
      }
    }
    const identity = `${finish.class}#${finish.test}`;
    if (start.code !== 1 || finish.code !== 0 || start.class !== finish.class || start.test !== finish.test || completed.has(identity)) {
      throw new Error('Native transcript contains a skipped, repeated, failed or unmatched test.');
    }
    completed.add(identity);
    classes.add(finish.class);
  }
  if (classes.size !== new Set(allowed.map(([className]) => className)).size) throw new Error('Native transcript omitted a required test class.');
  return [...completed];
}

function declaredNativeIdentities(suite, check) {
  return check.classes.split(',').flatMap(selector => {
    const [className, method] = selector.split('#');
    const file = `shared/${suite.module}/src/androidInstrumentedTest/kotlin/${className.replaceAll('.', '/')}.kt`;
    const declared = [...fs.readFileSync(path.join(root, file), 'utf8').matchAll(/@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)]
      .map(match => `${className}#${match[1]}`);
    if (method) {
      const selected = `${className}#${method}`;
      if (!declared.includes(selected)) throw new Error('Native selector is not a declared source test.');
      return [selected];
    }
    return declared;
  }).sort();
}

function nativeBranches(log) {
  const branch = (name, allowed) => {
    const matches = [...log.matchAll(new RegExp(`^INSTRUMENTATION_RESULT: ${name}=(.*)$`, 'gm'))];
    if (matches.length !== 1 || !allowed.test(matches[0][1])) throw new Error('Retained native branch evidence is missing or ambiguous.');
    return matches[0][1];
  };
  const hardlinks = /^(?:platform-denied:(?:1|13); existing-hardlink-branch-unexercised|existing-hardlink-rejected-by-store)$/;
  return {
    hardlinkManifest: branch('credential_hardlink_manifest', hardlinks),
    hardlinkBlob: branch('credential_hardlink_blob', hardlinks),
    notification: branch('native_work_notification', /^NATIVE_WORK_NOTIFICATION_(?:PERMISSION_UNAVAILABLE|EXACT_CANCELLATION_VERIFIED)$/),
  };
}

function syncFailureEvidence(log, identities) {
  const expected = {
    journal: ['injectedJournalVfsSyncFailureRetainsExactKeyUntilFreshSuccessfulAbort', 1034],
    database: ['injectedDatabaseVfsSyncFailureRetainsExactKeyUntilFreshSuccessfulAbort', 1034],
    directory: ['injectedPostUnlinkVfsSyncFailureNeverTreatsVisibleConsumeAsDurableAcknowledgement', 1290],
    'consumed-replay': ['consumedReceiptReplayStillRequiresFreshSuccessfulSyncBeforeDeletingKey', 1290],
    'aborted-replay': ['observedAbortedReplayCannotAcknowledgeWhenItsNewJournalSyncFails', 1034],
    'exact-scope': ['injectedVfsFaultIsRestrictedToExactOwnedDatabaseNotAnotherSandbox', 1034],
  };
  const rows = [...log.matchAll(/^INSTRUMENTATION_RESULT: state_activation_sync_([^=]+)=(.*)$/gm)];
  if (rows.length !== 6 || new Set(rows.map(row => row[1])).size !== 6)
    throw new Error('Expected six unique injected-VFS sync failure evidence labels.');
  return Object.fromEntries(Object.entries(expected).map(([label, [method, sqlite]]) => {
    const identity = `com.feedme.storage.AndroidStateActivationSyncFailureTest#${method}`;
    const row = rows.find(value => value[1] === label);
    const delegatedUnlink = sqlite === 1290 ? 1 : 0;
    const match = row?.[2].match(new RegExp(`^sqlite=${sqlite};failures=1;keyDeletes=0;vfs=1;delegatedUnlink=${delegatedUnlink}$`));
    if (!match || !identities.includes(identity)) throw new Error('Native sync evidence is absent, wrong or lacks its successful source test.');
    return [label, {identity, mechanism: 'injected-vfs-sync-failure', sqlite, failures: 1, keyDeletes: 0, vfs: 1, delegatedUnlink}];
  }));
}

function ledgerSyncFailureEvidence(log, identities) {
  const expected = {
    'control-journal': ['controlJournalSyncFailureCannotAuthorizeCredentialAbort', 1034],
    'control-database': ['controlDatabaseSyncFailureCannotAuthorizeCredentialAbort', 1034],
    'control-directory': ['controlDirectorySyncFailureCannotAuthorizeCredentialAbort', 1290],
    'work-reservation-journal': ['workReservationJournalSyncFailureCannotInstallNativeWork', 1034],
    'work-reservation-database': ['workReservationDatabaseSyncFailureCannotInstallNativeWork', 1034],
    'work-reservation-directory': ['workReservationDirectorySyncFailureCannotInstallNativeWork', 1290],
    'work-retirement-journal': ['workRetirementJournalSyncFailureCannotCancelNativeWork', 1034],
    'work-retirement-database': ['workRetirementDatabaseSyncFailureCannotCancelNativeWork', 1034],
    'work-retirement-directory': ['workRetirementDirectorySyncFailureCannotCancelNativeWork', 1290],
  };
  const rows = [...log.matchAll(/^INSTRUMENTATION_RESULT: session_ledger_sync_([^=]+)=(.*)$/gm)];
  if (rows.length !== 9 || new Set(rows.map(row => row[1])).size !== 9)
    throw new Error('Expected nine unique injected-VFS session-ledger evidence labels.');
  return Object.fromEntries(Object.entries(expected).map(([label, [method, sqlite]]) => {
    const identity = `com.feedme.storage.AndroidSessionLedgerSyncFailureTest#${method}`;
    const row = rows.find(value => value[1] === label);
    const delegatedUnlink = sqlite === 1290 ? 1 : 0;
    const match = row?.[2].match(new RegExp(`^sqlite=${sqlite};failures=1;effects=0;vfs=1;delegatedUnlink=${delegatedUnlink}$`));
    if (!match || !identities.includes(identity))
      throw new Error('Ledger sync evidence is absent, wrong or lacks its successful source test.');
    return [label, {identity, mechanism: 'injected-vfs-sync-failure', sqlite, failures: 1,
      effects: 0, vfs: 1, delegatedUnlink}];
  }));
}

function workOriginSyncFailureEvidence(log, identities) {
  const expected = {
    'select-journal': ['journalSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginSelection', 1034],
    'replay-journal': ['journalSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginSelection', 1034],
    'select-database': ['databaseSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginSelection', 1034],
    'replay-database': ['databaseSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginSelection', 1034],
    'select-directory': ['directorySyncFailureCannotPromoteVisibleWorkOriginSelectionOrReplayToAcknowledgement', 1290],
    'replay-directory': ['directorySyncFailureCannotPromoteVisibleWorkOriginSelectionOrReplayToAcknowledgement', 1290],
  };
  const rows = [...log.matchAll(/^INSTRUMENTATION_RESULT: work_origin_sync_([^=]+)=(.*)$/gm)];
  if (rows.length !== 6 || new Set(rows.map(row => row[1])).size !== 6)
    throw new Error('Expected six unique injected-VFS work-origin evidence labels.');
  return Object.fromEntries(Object.entries(expected).map(([label, [method, sqlite]]) => {
    const identity = `com.feedme.storage.AndroidSessionWorkOriginPlanSyncFailureTest#${method}`;
    const row = rows.find(value => value[1] === label);
    const delegatedUnlink = sqlite === 1290 ? 1 : 0;
    const match = row?.[2].match(new RegExp(`^sqlite=${sqlite};failures=1;effects=0;vfs=1;delegatedUnlink=${delegatedUnlink}$`));
    if (!match || !identities.includes(identity))
      throw new Error('Work-origin sync evidence is absent, wrong or lacks its successful source test.');
    return [label, {identity, mechanism: 'injected-vfs-sync-failure', sqlite, failures: 1,
      effects: 0, vfs: 1, delegatedUnlink}];
  }));
}

function bindingSyncFailureEvidence(log, identities) {
  const expected = {
    'bind-journal': ['journalSyncFailureCannotAcknowledgeInitialOrReplayedPrivateBinding', 1034],
    'replay-journal': ['journalSyncFailureCannotAcknowledgeInitialOrReplayedPrivateBinding', 1034],
    'bind-database': ['databaseSyncFailureCannotAcknowledgeInitialOrReplayedPrivateBinding', 1034],
    'replay-database': ['databaseSyncFailureCannotAcknowledgeInitialOrReplayedPrivateBinding', 1034],
    'bind-directory': ['directorySyncFailureCannotPromoteVisibleBindingOrReplayToAcknowledgement', 1290],
    'replay-directory': ['directorySyncFailureCannotPromoteVisibleBindingOrReplayToAcknowledgement', 1290],
  };
  const rows = [...log.matchAll(/^INSTRUMENTATION_RESULT: data_binding_sync_([^=]+)=(.*)$/gm)];
  if (rows.length !== 6 || new Set(rows.map(row => row[1])).size !== 6)
    throw new Error('Expected six unique private-binding initial/replay VFS evidence labels.');
  return Object.fromEntries(Object.entries(expected).map(([label, [method, sqlite]]) => {
    const identity = `com.feedme.storage.AndroidStateBindingSyncFailureTest#${method}`;
    const row = rows.find(value => value[1] === label);
    const delegatedUnlink = sqlite === 1290 ? 1 : 0;
    const match = row?.[2].match(new RegExp(`^sqlite=${sqlite};failures=1;receipts=0;vfs=1;delegatedUnlink=${delegatedUnlink}$`));
    if (!match || !identities.includes(identity))
      throw new Error('Private-binding VFS evidence is absent, wrong or lacks its successful source test.');
    return [label, {identity, mechanism: 'injected-vfs-sync-failure', sqlite, failures: 1,
      receipts: 0, vfs: 1, delegatedUnlink}];
  }));
}

function workAbortSyncFailureEvidence(log, identities) {
  const expected = {
    'initial-journal': ['journalSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginAbort', 1034],
    'replay-journal': ['journalSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginAbort', 1034],
    'initial-database': ['databaseSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginAbort', 1034],
    'replay-database': ['databaseSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginAbort', 1034],
    'initial-directory': ['directorySyncFailureCannotPromoteVisibleWorkOriginAbortOrReplayToAcknowledgement', 1290],
    'replay-directory': ['directorySyncFailureCannotPromoteVisibleWorkOriginAbortOrReplayToAcknowledgement', 1290],
  };
  const rows = [...log.matchAll(/^INSTRUMENTATION_RESULT: work_abort_sync_([^=]+)=(.*)$/gm)];
  if (rows.length !== 6 || new Set(rows.map(row => row[1])).size !== 6)
    throw new Error('Expected six unique work-origin abort initial/replay VFS evidence labels.');
  return Object.fromEntries(Object.entries(expected).map(([label, [method, sqlite]]) => {
    const identity = `com.feedme.storage.AndroidSessionWorkOriginAbortSyncFailureTest#${method}`;
    const row = rows.find(value => value[1] === label);
    const delegatedUnlink = sqlite === 1290 ? 1 : 0;
    const match = row?.[2].match(new RegExp(`^sqlite=${sqlite};failures=1;effects=0;vfs=1;delegatedUnlink=${delegatedUnlink}$`));
    if (!match || !identities.includes(identity))
      throw new Error('Work-origin abort VFS evidence is absent, wrong or lacks its successful source test.');
    return [label, {identity, mechanism: 'injected-vfs-sync-failure', sqlite, failures: 1,
      effects: 0, vfs: 1, delegatedUnlink}];
  }));
}

function bindingAbortSyncFailureEvidence(log, identities) {
  const expected = {
    'initial-journal': ['journalSyncFailureCannotDeleteKeyDuringInitialOrReplayedBindingAbort', 1034],
    'replay-journal': ['journalSyncFailureCannotDeleteKeyDuringInitialOrReplayedBindingAbort', 1034],
    'initial-database': ['databaseSyncFailureCannotDeleteKeyDuringInitialOrReplayedBindingAbort', 1034],
    'replay-database': ['databaseSyncFailureCannotDeleteKeyDuringInitialOrReplayedBindingAbort', 1034],
    'initial-directory': ['directorySyncFailureCannotPromoteVisibleBindingRemovalToKeyDeletion', 1290],
    'replay-directory': ['directorySyncFailureCannotPromoteVisibleBindingRemovalToKeyDeletion', 1290],
  };
  const rows = [...log.matchAll(/^INSTRUMENTATION_RESULT: data_binding_abort_sync_([^=]+)=(.*)$/gm)];
  if (rows.length !== 6 || new Set(rows.map(row => row[1])).size !== 6)
    throw new Error('Expected six unique private-binding abort initial/replay VFS evidence labels.');
  return Object.fromEntries(Object.entries(expected).map(([label, [method, sqlite]]) => {
    const identity = `com.feedme.storage.AndroidStateBindingAbortSyncFailureTest#${method}`;
    const row = rows.find(value => value[1] === label);
    const delegatedUnlink = sqlite === 1290 ? 1 : 0;
    const match = row?.[2].match(new RegExp(`^sqlite=${sqlite};failures=1;keyDeletes=0;vfs=1;delegatedUnlink=${delegatedUnlink}$`));
    if (!match || !identities.includes(identity))
      throw new Error('Private-binding abort VFS evidence is absent, wrong or lacks its successful source test.');
    return [label, {identity, mechanism: 'injected-vfs-sync-failure', sqlite, failures: 1,
      keyDeletes: 0, vfs: 1, delegatedUnlink}];
  }));
}

function archiveEntries(name) {
  const result = spawnSync('unzip', ['-Z1', path.join(root, name)], {encoding: 'utf8', maxBuffer: 16 * 1024 * 1024});
  if (result.error || result.status !== 0) throw new Error('Cannot inspect native packaging archive.');
  const entries = result.stdout.split(/\r?\n/).filter(Boolean);
  if (new Set(entries).size !== entries.length) throw new Error('Native packaging archive has duplicate entries.');
  return {entries, listing: result.stdout};
}

function verifyInjectionPackaging() {
  const helper = 'libfeedmeSqliteSyncFailure.so';
  const testApk = 'shared/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk';
  const abis = { 'arm64-v8a': [2, 183], 'armeabi-v7a': [1, 40], x86: [1, 3], x86_64: [2, 62] };
  const inventory = archiveEntries(testApk);
  const expected = Object.keys(abis).map(abi => `lib/${abi}/${helper}`).sort();
  const actual = inventory.entries.filter(name => name.includes('feedmeSqliteSyncFailure')).sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('Only the four exact instrumentation ABI helpers may be packaged.');
  const out = path.join(attempt, 'native-packaging');
  fs.mkdirSync(out, {recursive: true});
  fs.writeFileSync(path.join(out, 'storage-test-apk-entries.txt'), inventory.listing);
  const libraries = Object.entries(abis).map(([abi, [elfClass, machine]]) => {
    const entry = `lib/${abi}/${helper}`;
    const extracted = spawnSync('unzip', ['-p', path.join(root, testApk), entry], {maxBuffer: 4 * 1024 * 1024});
    const bytes = extracted.stdout;
    if (extracted.error || extracted.status !== 0 || bytes.length < 20 ||
        !bytes.subarray(0, 4).equals(Buffer.from([127, 69, 76, 70])) || bytes[4] !== elfClass ||
        bytes[5] !== 1 || bytes.readUInt16LE(18) !== machine) throw new Error('Instrumented helper ELF ABI does not match its APK path.');
    const selected = `shared/storage/build/intermediates/stripped_native_libs/debugAndroidTest/stripDebugAndroidTestDebugSymbols/out/${entry}`;
    const source = artifact(selected);
    if (source.bytes !== bytes.length || source.sha256 !== hash(bytes)) throw new Error('APK helper is not the freshly built test-only native library.');
    const retained = path.join(out, `${abi}-${helper}`);
    fs.writeFileSync(retained, bytes);
    return {abi, entry, bytes: bytes.length, sha256: hash(bytes), source, retained: artifact(path.relative(root, retained))};
  });
  const forbidden = [
    ...['storage', 'transport', 'sync', 'kitchen', 'session', 'planning', 'mealflow', 'app'].map(module => `shared/${module}/build/outputs/aar/${module}-debug.aar`),
    'shared/session/build/outputs/apk/androidTest/debug/session-debug-androidTest.apk',
    'shared/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
    'apps/android/build/outputs/apk/debug/android-debug.apk',
  ];
  const excluded = forbidden.map((name, index) => {
    const archive = archiveEntries(name);
    if (archive.entries.some(entry => /feedmeSqliteSyncFailure|sqlite_sync_failure/i.test(entry)))
      throw new Error('Fault-injection native code escaped the isolated storage test APK.');
    const listing = path.join(out, `excluded-${index}-entries.txt`);
    fs.writeFileSync(listing, archive.listing);
    return {archive: artifact(name), helperEntries: 0, listing: artifact(path.relative(root, listing))};
  });
  const fromSmoke = report.native.find(native => native.name === 'storage')?.android.testJniLibraries;
  const packaged = libraries.map(({entry, bytes, sha256}) => ({entry, bytes, sha256}));
  if (JSON.stringify(packaged) !== JSON.stringify(fromSmoke)) throw new Error('Test helper bytes changed between smoke and retained packaging proof.');
  return {helper, testApk: artifact(testApk), libraries, excluded,
    scope: 'Actual four-ABI ELF test helper in storage test APK only; absent from eight current Android AARs, session/app test APKs and unchanged historical demo. No newly built demo or release artifact claim.'};
}

function retainNativeEvidence(suite, notBefore) {
  const reportPath = `docs/verification/${suite.directory}/android-smoke.json`;
  const reportBytes = fs.readFileSync(path.join(root, reportPath));
  const android = JSON.parse(reportBytes.toString('utf8'));
  const began = Date.parse(android.startedAt);
  const ended = Date.parse(android.finishedAt);
  const serial = process.env.FEEDME_TEST_DEVICE || 'emulator-5554';
  if (!/^emulator-\d+$/.test(serial) || android.serial !== serial || !Number.isFinite(began) ||
      !Number.isFinite(ended) || began < notBefore || ended < began || ended > Date.now()) {
    throw new Error('Native receipt is not from this fresh emulator run.');
  }
  const nativePath = android.attemptDirectory;
  const prefix = `docs/verification/${suite.directory}/android-attempts/`;
  const stamp = typeof nativePath === 'string' && nativePath.startsWith(prefix) ? nativePath.slice(prefix.length) : '';
  const match = stamp.match(/^(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-(\d{2}\.\d{3}Z)$/);
  const directoryTime = match ? Date.parse(`${match[1]}T${match[2]}:${match[3]}:${match[4]}`) : NaN;
  if (!Number.isFinite(directoryTime) || directoryTime < notBefore || directoryTime > began) {
    throw new Error('Unexpected or stale native attempt evidence location.');
  }
  const nativeDirectory = path.join(root, nativePath);
  const files = walk(nativeDirectory);
  const immutableReport = fs.readFileSync(path.join(nativeDirectory, 'report.json'));
  if (!immutableReport.equals(reportBytes)) throw new Error('Native report pointer does not match its retained attempt.');
  const out = path.join(attempt, `android-${suite.name}`);
  fs.mkdirSync(out, {recursive: true});
  const retainedReport = path.join(attempt, `android-${suite.name}-smoke.json`);
  fs.writeFileSync(retainedReport, reportBytes);
  const evidence = files.map(file => {
    const relative = path.relative(nativeDirectory, file);
    const retained = path.join(out, relative);
    fs.mkdirSync(path.dirname(retained), {recursive: true});
    fs.copyFileSync(file, retained);
    const source = artifact(path.relative(root, file));
    const copy = artifact(path.relative(root, retained));
    if (source.sha256 !== copy.sha256) throw new Error('Native evidence changed while being retained.');
    return {source, retained: copy};
  });
  const native = {name: suite.name, android, report: artifact(path.relative(root, retainedReport)), evidence};
  report.native.push(native);

  // Retain fresh failure evidence before rejecting it; an older successful receipt cannot pass.
  if (android.passed !== true || android.failure || !Array.isArray(android.checks) || android.checks.length !== suite.checks.length + 1) {
    throw new Error(`Native ${suite.name} receipt did not pass every required check.`);
  }
  native.completedTests = [];
  for (const check of suite.checks) {
    const matches = android.checks.filter(value => value.name === check.name);
    const actual = matches[0];
    if (matches.length !== 1 || actual.passed !== true || actual.tests !== check.tests || actual.expected !== check.tests ||
        actual.classes !== check.classes || actual.stage !== check.stage || actual.log !== `${nativePath}/${check.name}.log`) {
      throw new Error('Native receipt has unexpected, missing or skipped tests.');
    }
    const log = fs.readFileSync(path.join(out, `${check.name}.log`), 'utf8');
    const identities = verifyInstrumentationTranscript(log, check);
    const declared = declaredNativeIdentities(suite, check);
    if (declared.length !== check.tests || JSON.stringify([...identities].sort()) !== JSON.stringify(declared))
      throw new Error('Native transcript test identities do not match the exact current source declarations.');
    if (JSON.stringify(actual.identities) !== JSON.stringify(identities))
      throw new Error('Native summary identities disagree with retained instrumentation.');
    native.completedTests.push({name: check.name, identities});
  }
  if (suite.module === 'session') {
    native.branches = nativeBranches(fs.readFileSync(path.join(out, 'regular.log'), 'utf8'));
    if (JSON.stringify(native.branches) !== JSON.stringify(android.branches)) throw new Error('Native branch summary disagrees with retained instrumentation.');
    native.deliveredNotificationCancellationProven = native.branches.notification === 'NATIVE_WORK_NOTIFICATION_EXACT_CANCELLATION_VERIFIED';
  }
  if (suite.module === 'storage') {
    const identities = native.completedTests.find(check => check.name === 'sync-failure').identities;
    native.syncFailures = syncFailureEvidence(fs.readFileSync(path.join(out, 'sync-failure.log'), 'utf8'), identities);
    if (JSON.stringify(native.syncFailures) !== JSON.stringify(android.syncFailures))
      throw new Error('Native sync failure summary disagrees with retained identity-bound instrumentation.');
    const ledgerIdentities = native.completedTests.find(check => check.name === 'ledger-sync-failure').identities;
    native.ledgerSyncFailures = ledgerSyncFailureEvidence(fs.readFileSync(path.join(out, 'ledger-sync-failure.log'), 'utf8'), ledgerIdentities);
    if (JSON.stringify(native.ledgerSyncFailures) !== JSON.stringify(android.ledgerSyncFailures))
      throw new Error('Native ledger failure summary disagrees with retained identity-bound instrumentation.');
    const workOriginIdentities = native.completedTests.find(check => check.name === 'work-origin-sync-failure').identities;
    native.workOriginSyncFailures = workOriginSyncFailureEvidence(fs.readFileSync(path.join(out, 'work-origin-sync-failure.log'), 'utf8'), workOriginIdentities);
    if (JSON.stringify(native.workOriginSyncFailures) !== JSON.stringify(android.workOriginSyncFailures))
      throw new Error('Native work-origin failure summary disagrees with retained identity-bound instrumentation.');
    const bindingIdentities = native.completedTests.find(check => check.name === 'binding-sync-failure').identities;
    native.bindingSyncFailures = bindingSyncFailureEvidence(fs.readFileSync(path.join(out, 'binding-sync-failure.log'), 'utf8'), bindingIdentities);
    if (JSON.stringify(native.bindingSyncFailures) !== JSON.stringify(android.bindingSyncFailures))
      throw new Error('Native private-binding failure summary disagrees with retained identity-bound instrumentation.');
    const workAbortIdentities = native.completedTests.find(check => check.name === 'work-origin-abort-sync-failure').identities;
    native.workAbortSyncFailures = workAbortSyncFailureEvidence(fs.readFileSync(path.join(out, 'work-origin-abort-sync-failure.log'), 'utf8'), workAbortIdentities);
    if (JSON.stringify(native.workAbortSyncFailures) !== JSON.stringify(android.workAbortSyncFailures))
      throw new Error('Native work-abort summary disagrees with retained identity-bound instrumentation.');
    const bindingAbortIdentities = native.completedTests.find(check => check.name === 'binding-abort-sync-failure').identities;
    native.bindingAbortSyncFailures = bindingAbortSyncFailureEvidence(fs.readFileSync(path.join(out, 'binding-abort-sync-failure.log'), 'utf8'), bindingAbortIdentities);
    if (JSON.stringify(native.bindingAbortSyncFailures) !== JSON.stringify(android.bindingAbortSyncFailures))
      throw new Error('Native binding-abort summary disagrees with retained identity-bound instrumentation.');
  }
  const cleanup = android.checks.filter(value => value.name === 'owned-test-directories-cleaned');
  const cleanupLog = fs.readFileSync(path.join(out, 'fixture-cleanup.log'), 'utf8');
  if (cleanup.length !== 1 || cleanup[0].passed !== true || suite.cleanupPattern.test(cleanupLog) ||
      /Permission denied|run-as:|No such file or directory|error:/i.test(cleanupLog)) {
    throw new Error('Native fixture cleanup is missing or failed in the retained transcript.');
  }
  const readLog = name => fs.readFileSync(path.join(out, `${name}.log`), 'utf8').trim();
  if (readLog('boot') !== '1' || !/^\d+$/.test(android.androidApi) || readLog('api') !== android.androidApi ||
      !/^[A-Za-z0-9_-]+$/.test(android.androidAbi) || readLog('abi') !== android.androidAbi ||
      !readLog('runner').includes(`${suite.package}/androidx.test.runner.AndroidJUnitRunner`) ||
      !/^Success\s*$/m.test(readLog('install'))) {
    throw new Error('Retained native setup logs do not match the reported device and test runner.');
  }
  const apkDirectory = `shared/${suite.module}/build/outputs/apk/androidTest/debug`;
  const apk = artifact(`${apkDirectory}/${suite.module}-debug-androidTest.apk`);
  if (android.apk?.path !== apk.path || android.apk?.bytes !== apk.bytes || android.apk?.sha256 !== apk.sha256) {
    throw new Error('Freshly tested Android APK does not match the built artifact.');
  }
  const metadataPath = `${apkDirectory}/output-metadata.json`;
  const metadata = JSON.parse(fs.readFileSync(path.join(root, metadataPath), 'utf8'));
  if (metadata.applicationId !== suite.package || metadata.variantName !== 'debugAndroidTest' || metadata.artifactType?.type !== 'APK' ||
      metadata.elements?.length !== 1 || metadata.elements[0].outputFile !== `${suite.module}-debug-androidTest.apk`) {
    throw new Error('Native APK build metadata has unexpected package or output identity.');
  }
  {
    const captured = artifact(path.relative(root, path.join(out, 'output-metadata.json')));
    if (android.apkMetadata?.path !== `${nativePath}/output-metadata.json` ||
        android.apkMetadata?.bytes !== captured.bytes || android.apkMetadata?.sha256 !== captured.sha256 ||
        captured.sha256 !== artifact(metadataPath).sha256) {
      throw new Error('Native APK metadata changed since the native smoke captured it.');
    }
  }
  const retainedMetadata = path.join(out, 'output-metadata.json');
  fs.copyFileSync(path.join(root, metadataPath), retainedMetadata);
  native.apkMetadata = {source: artifact(metadataPath), retained: artifact(path.relative(root, retainedMetadata))};
  if (native.apkMetadata.source.sha256 !== native.apkMetadata.retained.sha256) throw new Error('Native APK metadata changed during capture.');
  native.tests = {tests: suite.checks.reduce((sum, check) => sum + check.tests, 0), failures: 0, errors: 0, skipped: 0};
}

function equal(value, expected, message) {
  if (JSON.stringify(value) !== JSON.stringify(expected)) throw new Error(message);
}
function freshDescriptor(descriptor, expectedPath) {
  if (!descriptor || descriptor.path !== expectedPath) throw new Error('Unexpected auxiliary evidence path.');
  const actual = artifact(expectedPath);
  equal(descriptor, actual, 'Auxiliary source/evidence bytes disagree.');
  return actual;
}
function retainAuxiliaryNative(name, directory, module, packageName, notBefore, runnerLog) {
  const receiptPath = 'docs/verification/' + directory + '/last-attempt.json';
  const raw = fs.readFileSync(path.join(root, receiptPath));
  const android = JSON.parse(raw);
  const began = Date.parse(android.startedAt), ended = Date.parse(android.finishedAt);
  const expectedDir = 'docs/verification/' + directory + '/attempts/' + android.startedAt?.replaceAll(':','-');
  if (!Number.isFinite(began) || !Number.isFinite(ended) || began < notBefore || ended < began || ended > Date.now() ||
      android.attemptDirectory !== expectedDir || android.serial !== process.env.FEEDME_TEST_DEVICE ||
      !/^emulator-\d+$/.test(android.serial) || android.androidApi !== '35' || android.androidAbi !== 'arm64-v8a')
    throw new Error('Auxiliary native receipt is stale or has unexpected device/attempt identity.');
  const nativeDir = path.join(root, expectedDir), immutable = fs.readFileSync(path.join(nativeDir, 'report.json'));
  if (!raw.equals(immutable)) throw new Error('Auxiliary native immutable/pointer receipt differs.');
  const out = path.join(attempt, 'android-' + name); fs.mkdirSync(out, {recursive:true});
  const files = walk(nativeDir), evidence = files.map(file => {
    const retained = path.join(out, path.relative(nativeDir, file)); fs.mkdirSync(path.dirname(retained), {recursive:true});
    fs.copyFileSync(file, retained);
    const source = artifact(path.relative(root, file)), copy = artifact(path.relative(root, retained));
    if (source.sha256 !== copy.sha256 || source.bytes !== copy.bytes) throw new Error('Auxiliary evidence changed during copy.');
    return {source,retained:copy};
  });
  const native = {name,android,evidence,report:artifact(path.relative(root,path.join(out,'report.json'))),completedTests:[]};
  report.native.push(native);
  const declaredEvidence = android.evidence;
  if (!Array.isArray(declaredEvidence)) throw new Error('Auxiliary evidence inventory is missing.');
  const expectedEvidence = files.filter(file => path.basename(file) !== 'report.json').map(file => artifact(path.relative(root,file))).sort((a,b)=>a.path.localeCompare(b.path));
  equal([...declaredEvidence].sort((a,b)=>a.path.localeCompare(b.path)),expectedEvidence,'Auxiliary evidence inventory differs from all actual retained files.');
  if (!android.passed || android.failure || !raw.equals(fs.readFileSync(path.join(root,'docs/verification/' + directory + '/verification.json'))))
    throw new Error('Fresh auxiliary native run did not pass.');
  const read = name => fs.readFileSync(path.join(out,name),'utf8');
  if (read('boot.log').trim() !== '1' || read('api.log').trim() !== android.androidApi || read('abi.log').trim() !== android.androidAbi ||
      !read(runnerLog).includes(packageName + '/androidx.test.runner.AndroidJUnitRunner') || !/^Success\s*$/m.test(read('install.log')))
    throw new Error('Auxiliary native boot/runner/install evidence disagrees.');
  const apkDir = 'shared/' + module + '/build/outputs/apk/androidTest/debug';
  freshDescriptor(android.apk,apkDir + '/' + module + '-debug-androidTest.apk');
  const currentMetadata = artifact(apkDir + '/output-metadata.json');
  const capturedMetadata = freshDescriptor(android.apkMetadata,expectedDir + '/output-metadata.json');
  if (capturedMetadata.sha256 !== currentMetadata.sha256) throw new Error('Auxiliary APK metadata changed.');
  const metadata = JSON.parse(read('output-metadata.json'));
  if (metadata.applicationId !== packageName || metadata.variantName !== 'debugAndroidTest' || metadata.artifactType?.type !== 'APK' ||
      metadata.elements?.length !== 1 || metadata.elements[0].outputFile !== module + '-debug-androidTest.apk') throw new Error('Auxiliary APK package/output is wrong.');
  native.apkMetadata = {source:currentMetadata,retained:artifact(path.relative(root,path.join(out,'output-metadata.json')))};
  return {native,android,out,read};
}
function retainMealUi(notBefore) {
  const {native,android,out,read} = retainAuxiliaryNative('meal-ui','meal-ui','app','com.feedme.app.test',notBefore,'runner.log');
  const check = {classes:'com.feedme.app.mealflow.AndroidMealFlowPresentationTest',tests:6};
  const identities = verifyInstrumentationTranscript(read('instrumentation.log'),check);
  equal([...identities].sort(),declaredNativeIdentities({module:'app'},check),'UI transcript differs from exact source methods.');
  equal(android.identities,identities,'UI reported identities disagree with native transcript.');
  equal(android.tests,{tests:6,failures:0,errors:0,skipped:0},'UI tests/counts do not match.');
  native.tests=android.tests; native.completedTests=[{name:'presentation',identities}];
  const names=['request','recommendations','recipe-ingredients','recipe-safety-step','unavailable'];
  if (!Array.isArray(android.screenshots) || android.screenshots.length !== names.length) throw new Error('Expected five actual UI screenshots.');
  native.screenshots=names.map(name=>{
    const expectedPath=android.attemptDirectory + '/' + name + '.png';
    const found=android.screenshots.filter(value=>value.path===expectedPath);
    if(found.length!==1)throw new Error('Missing/duplicate screenshot identity.');
    freshDescriptor(found[0],expectedPath);
    const file=path.join(out,name+'.png'), bytes=fs.readFileSync(file);
    if(bytes.length<45 || !bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])) ||
        bytes.toString('ascii',12,16)!=='IHDR' || bytes.readUInt32BE(8)!==13 ||
        bytes.readUInt32BE(16)<1 || bytes.readUInt32BE(20)<1 || bytes.readUInt32BE(16)>8192 || bytes.readUInt32BE(20)>8192 ||
        bytes.toString('ascii',bytes.length-8,bytes.length-4)!=='IEND')throw new Error('Native screenshot is not a bounded complete PNG.');
    return {name,width:bytes.readUInt32BE(16),height:bytes.readUInt32BE(20),source:found[0],retained:artifact(path.relative(root,file))};
  });
}
function retainStartupProcesses(notBefore) {
  const {native,android,read} = retainAuxiliaryNative('startup-process','startup-process','session','com.feedme.session.test',notBefore,'runners.log');
  equal(android.tests,{tests:7,failures:0,errors:0,skipped:0},'Only seven recovery tests may be counted.');
  if(android.witnessedInterruptions!==7 || !Array.isArray(android.stages) || android.stages.length!==7)throw new Error('Seven exact witnessed pairs required.');
  const declared=declaredNativeIdentities({module:'session'},{classes:startupClass});
  equal(declared,startupScenarios.flatMap(s=>[startupClass+'#'+s.interrupt,startupClass+'#'+s.recover]).sort(),'Source interruption/recovery selector inventory changed.');
  const runIds=new Set(), completed=[], interrupted=[];
  for(const [index,scenario] of startupScenarios.entries()){
    const stage=android.stages[index],prefix=(index+1)+'-'+scenario.name;
    if(stage.name!==scenario.name || !stage.passed || stage.interruptSelector!==startupClass+'#'+scenario.interrupt ||
        stage.recoverSelector!==startupClass+'#'+scenario.recover || runIds.has(stage.runId))throw new Error('Wrong or duplicate process scenario.');
    runIds.add(stage.runId);
    const live=read(prefix+'-live-pid.log').trim();
    if(!/^[1-9]\d*$/.test(live))throw new Error('Expected exactly one live test process PID.');
    const witness=startupCheckpoint(read(prefix+'-checkpoint.json'),read(prefix+'-ownership.log'),stage.runId,scenario,Number(live));
    equal(stage.checkpoint,witness,'Checkpoint object differs from actual canonical markers/live PID.');
    const before=startupInterrupted(read(prefix+'-before-stop.log'),scenario);
    const after=startupInterrupted(read(prefix+'-interrupted.log'),scenario);
    equal(stage.interruption,after,'Interrupted report differs from start-only native evidence.');
    equal(before,after,'Interrupted test identity changed after host force-stop.');
    if(stage.forceStop?.status!==0 || stage.forceStop?.error || stage.forceStop?.out!==read(prefix+'-force-stop.log') ||
        stage.processAbsentAfterStop!==true || read(prefix+'-preflight-pid.log').trim() || read(prefix+'-after-stop-pid.log').trim() ||
        /error|Permission denied/i.test(read(prefix+'-preflight-stop.log')+read(prefix+'-force-stop.log')) ||
        !Number.isInteger(stage.interruptedExit?.code) || stage.interruptedExit?.signal != null)
      throw new Error('Host stop/exit/PID disappearance was not established.');
    const recovery=startupRecovered(read(prefix+'-recovered.log'),scenario,witness.pid);
    equal(stage.recovery,recovery,'Fresh recovery summary differs from native transcript.');
    const cleanup=read(prefix+'-fixture-cleanup.log');
    if(/retirement-integration-process-|Permission denied|run-as:|error:/i.test(cleanup))throw new Error('Process fixture cleanup failed.');
    completed.push(recovery.identity);interrupted.push(after);
  }
  if(new Set(completed).size!==7)throw new Error('Recovery identities are not unique.');
  native.tests=android.tests;native.completedTests=[{name:'fresh-process-recoveries',identities:completed}];
  native.witnessedInterruptions=interrupted;
  report.witnessedInterruptions={count:7,passedTests:0,identities:interrupted.map(value=>value.identity),
    scope:'Host-witnessed acknowledged-boundary process stops, not successful killed tests or physical/transaction-crash proof.'};
}


function verifyFreshGradleTasks(log) {
  const modules = ['storage','transport','sync','kitchen','session','planning','mealflow','app'];
  const required = [
    ...['core','contracts',...modules].map(module => ':shared:' + module + ':jvmTest'),
    ...modules.flatMap(module => ['jvmJar','assembleDebug','lintDebug'].map(task => ':shared:' + module + ':' + task)),
    ...['storage','session','app'].map(module => ':shared:' + module + ':assembleDebugAndroidTest'),
    ':server:test', ':server:integrationTest', ':verifyReleaseScope',
  ];
  const lines = log.split(/\r?\n/);
  for (const task of required) {
    if (lines.filter(line => line.trimEnd() === '> Task ' + task).length !== 1)
      throw new Error('Expected freshly executed Gradle task without cached/skipped status: ' + task);
  }
  if (!/^BUILD SUCCESSFUL\b/m.test(log)) throw new Error('Gradle build did not complete successfully.');
  return required;
}
function verifyGlobalNativeInventory() {
  const declared = [];
  for (const module of ['storage','session','app']) {
    for (const file of walk(path.join(root,'shared',module,'src/androidInstrumentedTest')).filter(name=>name.endsWith('.kt'))) {
      const source = fs.readFileSync(file,'utf8');
      const count = [...source.matchAll(/^\s*@Test\b/gm)].length;
      if (!count) continue;
      const methods = [...source.matchAll(/@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)].map(match=>match[1]);
      const packageName = source.match(/^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$/m)?.[1];
      const className = path.basename(file,'.kt');
      if (methods.length !== count || !packageName || !new RegExp('^class\\s+' + className + '\\b','m').test(source))
        throw new Error('Review native source inventory shape: ' + path.relative(root,file));
      declared.push(...methods.map(method=>packageName+'.'+className+'#'+method));
    }
  }
  const passed = report.native.flatMap(native=>native.completedTests.flatMap(check=>check.identities));
  const interrupted = report.witnessedInterruptions.identities;
  if (passed.length!==336 || interrupted.length!==7 || declared.length!==343 ||
      new Set([...passed,...interrupted]).size!==343 ||
      JSON.stringify([...passed,...interrupted].sort())!==JSON.stringify(declared.sort()))
    throw new Error('Native transcripts do not cover the complete exact source inventory, with interruptions separate.');
  return {declared:343,passed:336,witnessedInterruptions:7,passedIdentities:passed.sort(),interruptedIdentities:[...interrupted].sort()};
}

try {
  const buildStarted = Date.now();
  await run('gradle', './gradlew', [
    ':shared:core:jvmTest', ':shared:contracts:jvmTest', ':shared:transport:jvmTest', ':shared:storage:jvmTest', ':shared:sync:jvmTest', ':shared:kitchen:jvmTest', ':shared:session:jvmTest', ':shared:planning:jvmTest', ':shared:mealflow:jvmTest', ':shared:app:jvmTest',
    ':shared:storage:jvmJar', ':shared:storage:assembleDebug', ':shared:storage:assembleDebugAndroidTest', ':shared:storage:lintDebug',
    ':shared:transport:jvmJar', ':shared:transport:assembleDebug', ':shared:transport:lintDebug',
    ':shared:sync:jvmJar', ':shared:sync:assembleDebug', ':shared:sync:lintDebug',
    ':shared:kitchen:jvmJar', ':shared:kitchen:assembleDebug', ':shared:kitchen:lintDebug',
    ':shared:session:jvmJar', ':shared:session:assembleDebug', ':shared:session:assembleDebugAndroidTest', ':shared:session:lintDebug',
    ':shared:planning:jvmJar', ':shared:planning:assembleDebug', ':shared:planning:lintDebug',
    ':shared:mealflow:jvmJar', ':shared:mealflow:assembleDebug', ':shared:mealflow:lintDebug',
    ':shared:app:jvmJar', ':shared:app:assembleDebug', ':shared:app:assembleDebugAndroidTest', ':shared:app:lintDebug',
    ':server:test', ':server:integrationTest', ':verifyReleaseScope', '--rerun-tasks', '--console=plain',
  ]);
  report.gradleTasks = verifyFreshGradleTasks(fs.readFileSync(path.join(attempt,'gradle.log'),'utf8'));
  const scopeStarted = Date.now();
  await run('release-scope', process.execPath, ['scripts/verify-release-scope.mjs', '--write-report']);
  report.releaseScope = retainReleaseScopeEvidence(scopeStarted);
  if (!/^> Task :verifyReleaseScope\s*$/m.test(fs.readFileSync(path.join(attempt, 'gradle.log'), 'utf8')))
    throw new Error('The actual Gradle release-scope gate did not execute.');
  report.releaseScope.gradleTaskVerified = true;
  for (const suite of nativeSuites) {
    const nativeStarted = Date.now();
    try {
      await run(`android-${suite.name}-instrumentation`, process.execPath, [suite.script]);
    } finally {
      retainNativeEvidence(suite, nativeStarted);
    }
  }
  const uiStarted=Date.now();
  try { await run('android-meal-ui-instrumentation',process.execPath,['scripts/android-meal-ui-smoke.mjs']); }
  finally { retainMealUi(uiStarted); }
  const processStarted=Date.now();
  try { await run('android-startup-process-instrumentation',process.execPath,['scripts/android-startup-process-smoke.mjs']); }
  finally { retainStartupProcesses(processStarted); }
  report.nativeTests = {tests: report.native.reduce((sum, native) => sum + native.tests.tests, 0), failures: 0, errors: 0, skipped: 0};
  if (report.nativeTests.tests !== 336) throw new Error('Expected 336 passed native tests; interrupted starts must never be counted.');
  report.nativeIdentityInventory = verifyGlobalNativeInventory();
  const nodeTests = fs.readdirSync(path.join(root, 'scripts')).filter(name => name.endsWith('.test.mjs')).sort().map(name => `scripts/${name}`);
  await run('node-tests', process.execPath, ['--test', '--test-reporter=tap', ...nodeTests]);
  const nodeLog = fs.readFileSync(path.join(attempt, 'node-tests.log'), 'utf8');
  if (!/^# tests 181$/m.test(nodeLog) || !/^# pass 181$/m.test(nodeLog) || !/^# fail 0$/m.test(nodeLog) || !/^# skipped 0$/m.test(nodeLog))
    throw new Error('Expected all 181 Node regressions without skips.');
  for (const [directory, name, expected] of [
    ['shared/core/build/test-results/jvmTest', 'core', 54],
    ['shared/contracts/build/test-results/jvmTest', 'contracts', 123],
    ['shared/transport/build/test-results/jvmTest', 'transport', 72],
    ['shared/storage/build/test-results/jvmTest', 'storage', 544],
    ['shared/sync/build/test-results/jvmTest', 'sync', 103],
    ['shared/kitchen/build/test-results/jvmTest', 'kitchen', 142],
    ['shared/session/build/test-results/jvmTest', 'session', parallelCounts.session],
    ['shared/planning/build/test-results/jvmTest', 'planning', parallelCounts.planning],
    ['shared/mealflow/build/test-results/jvmTest', 'mealflow', parallelCounts.mealflow],
    ['shared/app/build/test-results/jvmTest', 'app', parallelCounts.app],
    ['server/build/test-results/test', 'server', parallelCounts.server],
    ['server/build/test-results/integrationTest', 'postgresql', parallelCounts.postgresql],
  ]) junit(directory, name, expected, buildStarted);
  report.node = verifyNodeTests(nodeTests, nodeLog);
  report.kotlinTests = {tests:report.tests.reduce((sum,suite)=>sum+suite.tests,0),shared:report.tests.filter(suite=>!['server','postgresql'].includes(suite.name)).reduce((sum,suite)=>sum+suite.tests,0),failures:0,errors:0,skipped:0};
  if(report.kotlinTests.tests!==1994 || report.kotlinTests.shared!==1787)throw new Error('Unexpected exact Kotlin/server/PostgreSQL totals.');
  report.androidLint = [];
  for (const module of ['storage', 'transport', 'sync', 'kitchen', 'session', 'planning', 'mealflow', 'app']) {
    const lintPath = `shared/${module}/build/reports/lint-results-debug.xml`;
    if(fs.statSync(path.join(root,lintPath)).mtimeMs<buildStarted)throw new Error('Lint evidence is stale.');
    const lint = fs.readFileSync(path.join(root,lintPath), 'utf8');
    fs.writeFileSync(path.join(attempt, `android-${module}-lint.xml`), lint);
    if (/<issue\b/.test(lint)) throw new Error(`Android ${module} lint has outstanding issues.`);
    report.androidLint.push({module, issues: 0,source:artifact(lintPath),retained:artifact(path.relative(root,path.join(attempt,`android-${module}-lint.xml`)))});
  }
  report.artifacts = [
    'shared/app/build/libs/app-jvm.jar', 'shared/app/build/outputs/aar/app-debug.aar',
    'shared/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
    'shared/planning/build/libs/planning-jvm.jar', 'shared/planning/build/outputs/aar/planning-debug.aar',
    'shared/mealflow/build/libs/mealflow-jvm.jar', 'shared/mealflow/build/outputs/aar/mealflow-debug.aar',
    'shared/session/build/libs/session-jvm.jar', 'shared/session/build/outputs/aar/session-debug.aar',
    'shared/session/build/outputs/apk/androidTest/debug/session-debug-androidTest.apk',
    'shared/kitchen/build/libs/kitchen-jvm.jar', 'shared/kitchen/build/outputs/aar/kitchen-debug.aar',
    'shared/sync/build/libs/sync-jvm.jar', 'shared/sync/build/outputs/aar/sync-debug.aar',
    'shared/storage/build/libs/storage-jvm.jar', 'shared/storage/build/outputs/aar/storage-debug.aar',
    'shared/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk',
    'shared/transport/build/libs/transport-jvm.jar', 'shared/transport/build/outputs/aar/transport-debug.aar',
    'apps/android/build/outputs/apk/debug/android-debug.apk',
  ].map(artifact);
  if (report.artifacts.at(-1).sha256 !== 'bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805')
    throw new Error('Recorded historical demo artifact unexpectedly changed.');
  if(report.artifacts.length!==20 || report.androidLint.length!==8)throw new Error('Expected twenty artifacts and eight lint reports.');
  for(const value of report.artifacts.slice(0,-1)){
    const modified=fs.statSync(path.join(root,value.path)).mtimeMs;
    if(modified<buildStarted || modified>Date.now())throw new Error('Current artifact is not freshly rebuilt: '+value.path);
  }
  report.nativeInjectionPackaging = verifyInjectionPackaging();
  for (const native of report.native) {
    const finalTestApk = report.artifacts.find(value => value.path === native.android.apk.path);
    if (finalTestApk?.sha256 !== native.android.apk.sha256) throw new Error('Tested native artifact changed after execution.');
    if (artifact(native.apkMetadata.source.path).sha256 !== native.apkMetadata.source.sha256) throw new Error('Native APK metadata changed after execution.');
  }
  if (hash(JSON.stringify(sourceFiles())) !== report.sourceManifestSha256) throw new Error('Sources changed during verification; rerun.');
  report.passed = true;
} catch (failure) {
  report.failure = failure.message;
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  report.receiptArtifacts = walk(attempt).map(name => artifact(path.relative(root, name)));
  const json = JSON.stringify(report, null, 2) + '\n';
  fs.writeFileSync(path.join(attempt, 'verification.json'), json);
  fs.writeFileSync(path.join(destination, 'last-attempt.json'), json);
  if (report.passed) fs.writeFileSync(path.join(destination, 'verification.json'), json);
  console.log(JSON.stringify({passed:report.passed, startedAt:report.startedAt, finishedAt:report.finishedAt, failure:report.failure, sourceFiles:before.length, sourceManifestSha256:report.sourceManifestSha256, tests:report.tests.map(({name,tests,failures,errors,skipped})=>({name,tests,failures,errors,skipped})), node:report.node && {tests:report.node.tests,failures:report.node.failures,skipped:report.node.skipped}, nativeTests:report.nativeTests, witnessedInterruptions:report.witnessedInterruptions?.count, artifacts:report.artifacts.length, receiptArtifacts:report.receiptArtifacts.length}, null, 2));
}
