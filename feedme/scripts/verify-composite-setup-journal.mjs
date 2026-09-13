import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawn, spawnSync} from 'node:child_process';

// Structural composite setup journal and conservative control-gate evidence only.
// Counts are fixed below and every executed native identity is checked against current source.
// Requires existing JDK17, SDK36, local PostgreSQL binaries and a booted local emulator.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const destination = path.join(root, 'docs/verification/composite-setup-journal');
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
  ...['core', 'contracts', 'transport', 'storage', 'sync', 'kitchen', 'session'].flatMap(module => [
    `shared/${module}/build.gradle.kts`,
    ...walk(path.join(root, `shared/${module}/src`)).map(name => path.relative(root, name)),
  ]),
  'server/build.gradle.kts', ...walk(path.join(root, 'server/src')).map(name => path.relative(root, name)),
  ...walk(path.join(root, 'scripts')).map(name => path.relative(root, name)),
  'docs/verification/canonical-validation/format-corpus.json',
  '../outputs/biteclub_blueprint/architecture/04_API_Contract.json',
  '../outputs/biteclub_blueprint/registry/screen_registry.json',
].sort().map(artifact);
const before = sourceFiles();
const report = {
  startedAt, passed: false,
  scope: 'M1.05d.5b.2b.3.3 bounded structural codec and control gates only: one canonical, bounded composite intent retains exact credential CREATE, private-data activation and work-origin plan bytes, operation UUID, owner scope and the existing configuration-binding digest. Decoding checks nested structure and the visible work-scope relationship, not native MACs or the opaque credential/data scope relationship. Persisted pending setup blocks ordinary runtime restoration, activation, retirement and work effects; inspection is not confirmation or authentication. The actual native journal suite verifies encrypted control retention, reopen and conservative gates with synthetic test fixtures. No live composite coordinator, setup sealing, composite abort, verified provider, active session lease, credential use or OS work is granted by this slice. All prior work-origin component, exact-process and actual-engine VFS regressions remain included; injected SQLite VFS errors are not OS errno failures or physical power loss, and direct owned-connection fixtures do not prove public hot-journal recovery. Trusted typed configuration/digest production, cross-store native preflight and ordered setup orchestration remain separate gates. Provider/bootstrap/UI, first-factory repair, hard-kill/power-loss, physical devices, API26 recovery, iOS and release acceptance remain open. Fresh shared/server/isolated PostgreSQL/Node regressions, five Android library builds/lint, and test-only four-ABI JNI provenance are included. The historical demo APK and public GitHub snapshot are unchanged; no new deployment or publication.',
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
      {name: 'sync-failure', tests: 6, classes: 'com.feedme.storage.AndroidStateActivationSyncFailureTest'},
      {name: 'ledger-sync-failure', tests: 9, classes: 'com.feedme.storage.AndroidSessionLedgerSyncFailureTest'},
      {name: 'work-origin-sync-failure', tests: 3, classes: 'com.feedme.storage.AndroidSessionWorkOriginPlanSyncFailureTest'},
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
      {name: 'integration', tests: 10, classes: 'com.feedme.session.AndroidLocalRetirementIntegrationTest'},
      {name: 'setup-journal', tests: 7, classes: 'com.feedme.session.AndroidSessionSetupJournalTest'},
    ],
  },
];

async function run(name, command, args) {
  const logPath = path.join(attempt, `${name}.log`);
  const stream = fs.createWriteStream(logPath);
  const began = new Date().toISOString();
  const result = await new Promise((resolve, reject) => {
    const child = spawn(command, args, {cwd: root, env: process.env, stdio: ['ignore', 'pipe', 'pipe']});
    for (const output of [child.stdout, child.stderr]) output.on('data', data => { stream.write(data); process.stdout.write(data); });
    child.on('error', failure => { stream.end(); reject(failure); });
    child.on('close', code => stream.end(() => resolve(code)));
  });
  report.commands.push({name, startedAt: began, finishedAt: new Date().toISOString(), exitCode: result, log: path.relative(root, logPath)});
  if (result !== 0) throw new Error(`${name} failed; its attempt log is retained.`);
}

function junit(directory, name, expected, notBefore) {
  const files = walk(path.join(root, directory)).filter(file => path.basename(file).startsWith('TEST-') && file.endsWith('.xml'));
  const result = {name, tests: 0, failures: 0, errors: 0, skipped: 0};
  const out = path.join(attempt, 'junit', name);
  fs.mkdirSync(out, {recursive: true});
  for (const file of files) {
    const stat = fs.statSync(file);
    if (stat.mtimeMs < notBefore || stat.mtimeMs > Date.now()) throw new Error('JUnit output is not from this fresh build.');
    const xml = fs.readFileSync(file, 'utf8');
    const opening = xml.match(/<testsuite\b([^>]+)>/)?.[1];
    if (!opening) throw new Error('Expected a single Gradle JUnit suite document.');
    const attributes = Object.fromEntries([...opening.matchAll(/([A-Za-z]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
    for (const key of ['tests', 'failures', 'errors', 'skipped']) {
      if (!/^\d+$/.test(attributes[key])) throw new Error('Unexpected JUnit count.');
      result[key] += Number(attributes[key]);
    }
    fs.copyFileSync(file, path.join(out, path.basename(file)));
  }
  report.tests.push(result);
  if (result.tests !== expected || result.failures || result.errors || result.skipped) throw new Error(`${name} did not pass every expected test.`);
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
    ...['storage', 'transport', 'sync', 'kitchen', 'session'].map(module => `shared/${module}/build/outputs/aar/${module}-debug.aar`),
    'shared/session/build/outputs/apk/androidTest/debug/session-debug-androidTest.apk',
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
    scope: 'Actual four-ABI ELF test helper in storage test APK only; absent from five current Android AARs, session test APK and unchanged historical demo. No newly built demo or release artifact claim.'};
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

try {
  const buildStarted = Date.now();
  await run('gradle', './gradlew', [
    ':shared:core:jvmTest', ':shared:contracts:jvmTest', ':shared:transport:jvmTest', ':shared:storage:jvmTest', ':shared:sync:jvmTest', ':shared:kitchen:jvmTest', ':shared:session:jvmTest',
    ':shared:storage:jvmJar', ':shared:storage:assembleDebug', ':shared:storage:assembleDebugAndroidTest', ':shared:storage:lintDebug',
    ':shared:transport:jvmJar', ':shared:transport:assembleDebug', ':shared:transport:lintDebug',
    ':shared:sync:jvmJar', ':shared:sync:assembleDebug', ':shared:sync:lintDebug',
    ':shared:kitchen:jvmJar', ':shared:kitchen:assembleDebug', ':shared:kitchen:lintDebug',
    ':shared:session:jvmJar', ':shared:session:assembleDebug', ':shared:session:assembleDebugAndroidTest', ':shared:session:lintDebug',
    ':server:test', ':server:integrationTest', '--rerun-tasks', '--console=plain',
  ]);
  for (const suite of nativeSuites) {
    const nativeStarted = Date.now();
    try {
      await run(`android-${suite.name}-instrumentation`, process.execPath, [suite.script]);
    } finally {
      retainNativeEvidence(suite, nativeStarted);
    }
  }
  report.nativeTests = {tests: report.native.reduce((sum, native) => sum + native.tests.tests, 0), failures: 0, errors: 0, skipped: 0};
  const nodeTests = fs.readdirSync(path.join(root, 'scripts')).filter(name => name.endsWith('.test.mjs')).sort().map(name => `scripts/${name}`);
  await run('node-tests', process.execPath, ['--test', '--test-reporter=tap', ...nodeTests]);
  const nodeLog = fs.readFileSync(path.join(attempt, 'node-tests.log'), 'utf8');
  if (!/^# tests 112$/m.test(nodeLog) || !/^# pass 112$/m.test(nodeLog) || !/^# fail 0$/m.test(nodeLog) || !/^# skipped 0$/m.test(nodeLog))
    throw new Error('Expected all 112 Node regressions without skips.');
  for (const [directory, name, expected] of [
    ['shared/core/build/test-results/jvmTest', 'core', 48],
    ['shared/contracts/build/test-results/jvmTest', 'contracts', 119],
    ['shared/transport/build/test-results/jvmTest', 'transport', 72],
    ['shared/storage/build/test-results/jvmTest', 'storage', 356],
    ['shared/sync/build/test-results/jvmTest', 'sync', 103],
    ['shared/kitchen/build/test-results/jvmTest', 'kitchen', 142],
    ['shared/session/build/test-results/jvmTest', 'session', 322],
    ['server/build/test-results/test', 'server', 46],
    ['server/build/test-results/integrationTest', 'postgresql', 45],
  ]) junit(directory, name, expected, buildStarted);
  report.node = {tests: 112, failures: 0, errors: 0, skipped: 0};
  report.androidLint = [];
  for (const module of ['storage', 'transport', 'sync', 'kitchen', 'session']) {
    const lint = fs.readFileSync(path.join(root, `shared/${module}/build/reports/lint-results-debug.xml`), 'utf8');
    fs.writeFileSync(path.join(attempt, `android-${module}-lint.xml`), lint);
    if (/<issue\b/.test(lint)) throw new Error(`Android ${module} lint has outstanding issues.`);
    report.androidLint.push({module, issues: 0});
  }
  report.artifacts = [
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
  console.log(JSON.stringify({...report, sourceFiles: `${before.length} source inputs`, receiptArtifacts: `${report.receiptArtifacts.length} retained evidence files`}, null, 2));
}
