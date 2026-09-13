import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';

// Installs only this module's development test APK. Never clears the FeedMe demo or other apps.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const serial = process.env.FEEDME_TEST_DEVICE || 'emulator-5554';
if (!/^emulator-\d+$/.test(serial)) throw new Error('Storage smoke is restricted to a local emulator.');
if (!process.env.ANDROID_HOME) throw new Error('Set ANDROID_HOME to the local Android SDK.');
const adb = path.join(process.env.ANDROID_HOME, 'platform-tools/adb');
const apkDirectory = path.join(root, 'shared/storage/build/outputs/apk/androidTest/debug');
const metadataBytes = fs.readFileSync(path.join(apkDirectory, 'output-metadata.json'));
const metadata = JSON.parse(metadataBytes.toString('utf8'));
const testPackage = 'com.feedme.storage.test';
const runner = `${testPackage}/androidx.test.runner.AndroidJUnitRunner`;
if (metadata.applicationId !== testPackage || metadata.variantName !== 'debugAndroidTest' ||
    metadata.artifactType?.type !== 'APK' || metadata.elements?.length !== 1) throw new Error('Unexpected test artifact identity.');
const fileName = metadata.elements[0].outputFile;
if (fileName !== 'storage-debug-androidTest.apk') throw new Error('Unexpected test artifact filename.');
const apk = path.join(apkDirectory, fileName);
const apkBytes = fs.readFileSync(apk);
const reportDirectory = path.join(root, 'docs/verification/client-storage');
const startedAt = new Date().toISOString();
const attemptDirectory = path.join(reportDirectory, 'android-attempts', startedAt.replaceAll(':', '-'));
fs.mkdirSync(attemptDirectory, {recursive: true});
fs.writeFileSync(path.join(attemptDirectory, 'output-metadata.json'), metadataBytes);
const report = {
  startedAt, passed: false, serial,
  scope: 'Retained private-data recovery owners now cover partial native acquisition, failed existing-schema initialization, exact full observations/binding, explicit close retries and terminal post-descriptor-close simulation. These are purpose-fixed component owners, not all-owning startup composition or close-before-Complete integration. Adds exact setup-work and reserved-binding abort primitives, each with separately selected real bundled-engine initial/replay journal, database and post-unlink VFS failures. Failed/unknown writes cannot acknowledge consumed markers; work abort performs no OS effect and private binding abort performs no key deletion before its fresh barrier. These primitive tests do not confirm a composite abort or integrate cross-store recovery. Isolated Android storage/control/work and authenticated private-data activation components. No-effect work-origin planning is bound to the exact native work store; setup-selected replay obtains a fresh changed-CAS acknowledgement without a lease, native effect or ordinary work binding. Three separate-process work stages retain an opaque plan in an independent encrypted test control store, select it and re-acknowledge it after controlled owner closes; this is not production composite journaling or hard-kill recovery. Three separate-invocation work-origin VFS tests exercise six initial/replay barriers. Three separately invoked private-binding VFS tests add six initial/replay binding barriers: actual SQLite failures cannot return a receipt, and each successful retry changes the binding revision. Binding is purpose-fixed; observation is not acknowledgement or session authority. The existing nine independent-ledger and six private-data empty-abort VFS cases remain separately invoked. All use explicit URI-selected forwarding VFS errors in the actual bundled engine, not OS errno or physical power-loss failures. Direct owned-connection fixtures do not prove public recovery of rollback journals. Exact selection/recovery grants no lease or record access. Existing storage restart-write/read stages cover a persisted fixture. Component work-origin seal/abort is exercised; explicit composite abort confirmation, cross-store abort ordering and startup completion are not integrated. No provider/UI integration, physical-device, iOS or release claim.',
  apk: {path: path.relative(root, apk), bytes: apkBytes.length, sha256: createHash('sha256').update(apkBytes).digest('hex')},
  apkMetadata: {path: path.relative(root, path.join(attemptDirectory, 'output-metadata.json')),
    bytes: metadataBytes.length, sha256: createHash('sha256').update(metadataBytes).digest('hex')},
  attemptDirectory: path.relative(root, attemptDirectory), checks: [],
};

function run(name, args, timeout = 120000) {
  const result = spawnSync(adb, ['-s', serial, ...args], {encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024});
  const output = (result.stdout || '') + (result.stderr || '');
  fs.writeFileSync(path.join(attemptDirectory, `${name}.log`), output);
  if (result.error || result.status !== 0) throw new Error(`${name} failed; inspect its bounded local log.`);
  return output;
}

function instrument(name, classes, expected, stage) {
  const allowed = classes.split(',').map(selector => selector.split('#'));
  const declared = allowed.flatMap(([className, method]) => {
    const file = path.join(root, 'shared/storage/src/androidInstrumentedTest/kotlin', `${className.replaceAll('.', '/')}.kt`);
    const methods = [...fs.readFileSync(file, 'utf8').matchAll(/@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)]
      .map(match => `${className}#${match[1]}`);
    if (!method) return methods;
    const selected = `${className}#${method}`;
    if (!methods.includes(selected)) throw new Error('Storage selector is not a declared source test.');
    return [selected];
  }).sort();
  if (declared.length !== expected || new Set(declared).size !== expected)
    throw new Error('Storage selectors do not match the exact current source test declarations.');
  const args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes];
  if (stage) args.push('-e', 'processRestartStage', stage);
  args.push(runner);
  const output = run(name, args);
  const summaries = [...output.matchAll(/^OK \((\d+) tests?\)\s*$/gm)];
  const endings = [...output.matchAll(/^INSTRUMENTATION_CODE: (-?\d+)\s*$/gm)];
  const count = Number(summaries[0]?.[1]);
  const events = [], invalid = [], completed = [];
  let fields = {};
  for (const line of output.split(/\r?\n/)) {
    const field = line.match(/^INSTRUMENTATION_STATUS: (class|test|current|numtests|id)=(.*)$/);
    if (field) {
      if (Object.hasOwn(fields, field[1])) invalid.push('duplicate-field');
      fields[field[1]] = field[2];
    }
    const status = line.match(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$/);
    if (status) { events.push({...fields, code: Number(status[1])}); fields = {}; }
  }
  for (let index = 0; index < expected; index++) {
    const start = events[index * 2], finish = events[index * 2 + 1];
    for (const event of [start, finish]) {
      if (!event || !allowed.some(([className, method]) => event.class === className && (!method || event.test === method)) ||
          !/^[A-Za-z_][A-Za-z0-9_]*$/.test(event.test || '') || event.id !== 'AndroidJUnitRunner' ||
          event.current !== String(index + 1) || event.numtests !== String(expected)) invalid.push('identity');
    }
    if (!start || !finish || start.code !== 1 || finish.code !== 0 || start.class !== finish.class || start.test !== finish.test)
      invalid.push('unmatched-pair');
    else completed.push(`${finish.class}#${finish.test}`);
  }
  const passed = summaries.length === 1 && count === expected && endings.length === 1 && endings[0][1] === '-1' &&
    Object.keys(fields).length === 0 && events.length === expected * 2 && invalid.length === 0 &&
    JSON.stringify([...completed].sort()) === JSON.stringify(declared) &&
    !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(output);
  report.checks.push({name, classes, ...(stage ? {stage} : {}), passed, tests: count || 0, expected, identities: completed,
    log: path.relative(root, path.join(attemptDirectory, `${name}.log`))});
  if (!passed) throw new Error(`${name} did not pass every expected non-skipped test.`);
  console.log(`${name}: ${count} tests passed`);
  return {output, identities: completed};
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
    throw new Error('Expected six unique work-origin initial/replay VFS evidence labels.');
  return Object.fromEntries(Object.entries(expected).map(([label, [method, sqlite]]) => {
    const identity = `com.feedme.storage.AndroidSessionWorkOriginPlanSyncFailureTest#${method}`;
    const row = rows.find(value => value[1] === label);
    const delegatedUnlink = sqlite === 1290 ? 1 : 0;
    const match = row?.[2].match(new RegExp(`^sqlite=${sqlite};failures=1;effects=0;vfs=1;delegatedUnlink=${delegatedUnlink}$`));
    if (!match || !identities.includes(identity))
      throw new Error('Work-origin VFS evidence is absent, wrong or lacks its successful source test.');
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

function testJniLibraries() {
  const list = spawnSync('unzip', ['-Z1', apk], {encoding: 'utf8', maxBuffer: 4 * 1024 * 1024});
  if (list.error || list.status !== 0) throw new Error('Cannot inspect test APK native entries.');
  const expected = ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']
    .map(abi => `lib/${abi}/libfeedmeSqliteSyncFailure.so`).sort();
  const actual = list.stdout.split(/\r?\n/).filter(name => name.includes('feedmeSqliteSyncFailure')).sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('Test APK must include exactly four ABI-specific sync injectors.');
  return expected.map(entry => {
    const result = spawnSync('unzip', ['-p', apk, entry], {maxBuffer: 4 * 1024 * 1024});
    if (result.error || result.status !== 0 || !result.stdout.subarray(0, 4).equals(Buffer.from([127, 69, 76, 70])))
      throw new Error('Native test helper is missing or is not an ELF library.');
    return {entry, bytes: result.stdout.length, sha256: createHash('sha256').update(result.stdout).digest('hex')};
  });
}

try {
  report.testJniLibraries = testJniLibraries();
  if (run('boot', ['shell', 'getprop', 'sys.boot_completed'], 10000).trim() !== '1') throw new Error('Start the emulator and wait for boot first.');
  report.androidApi = run('api', ['shell', 'getprop', 'ro.build.version.sdk'], 10000).trim();
  report.androidAbi = run('abi', ['shell', 'getprop', 'ro.product.cpu.abi'], 10000).trim();
  run('install', ['install', '-r', '-t', apk]);
  const runners = run('runner', ['shell', 'pm', 'list', 'instrumentation'], 10000);
  if (!runners.includes(runner)) throw new Error('Expected isolated instrumentation runner unavailable.');
  instrument('regular', 'com.feedme.storage.AndroidStateVaultTest,com.feedme.storage.AndroidStateDatabaseTest,com.feedme.storage.AndroidSessionControlStoreTest,com.feedme.storage.AndroidSessionWorkStoreTest,com.feedme.storage.AndroidStateActivationPlanTest,com.feedme.storage.AndroidStateActivationRecoveryTest,com.feedme.storage.AndroidSessionWorkOriginPlanTest', 71);
  // Deliberately separate am instrument invocation/process: these tests explicitly URI-select a
  // registered forwarding VFS, scope faults to exact owned files and unregister after all closes.
  const sync = instrument('sync-failure', 'com.feedme.storage.AndroidStateActivationSyncFailureTest', 6);
  report.syncFailures = syncFailureEvidence(sync.output, sync.identities);
  instrument('retained-recovery-owner', 'com.feedme.storage.AndroidStateActivationRecoveryOwnerTest', 15);
  // Independent ledgers use their own instrumentation process and exact source-bound evidence.
  const ledger = instrument('ledger-sync-failure', 'com.feedme.storage.AndroidSessionLedgerSyncFailureTest', 9);
  report.ledgerSyncFailures = ledgerSyncFailureEvidence(ledger.output, ledger.identities);
  const workOrigin = instrument('work-origin-sync-failure', 'com.feedme.storage.AndroidSessionWorkOriginPlanSyncFailureTest', 3);
  report.workOriginSyncFailures = workOriginSyncFailureEvidence(workOrigin.output, workOrigin.identities);
  const binding = instrument('binding-sync-failure', 'com.feedme.storage.AndroidStateBindingSyncFailureTest', 3);
  report.bindingSyncFailures = bindingSyncFailureEvidence(binding.output, binding.identities);
  instrument('work-origin-abort', 'com.feedme.storage.AndroidSessionWorkOriginAbortTest', 12);
  const workAbort = instrument('work-origin-abort-sync-failure', 'com.feedme.storage.AndroidSessionWorkOriginAbortSyncFailureTest', 3);
  report.workAbortSyncFailures = workAbortSyncFailureEvidence(workAbort.output, workAbort.identities);
  instrument('binding-abort', 'com.feedme.storage.AndroidStateBindingAbortTest', 10);
  const bindingAbort = instrument('binding-abort-sync-failure', 'com.feedme.storage.AndroidStateBindingAbortSyncFailureTest', 3);
  report.bindingAbortSyncFailures = bindingAbortSyncFailureEvidence(bindingAbort.output, bindingAbort.identities);
  instrument('restart-write', 'com.feedme.storage.AndroidStateProcessRestartTest#writeFixture', 1, 'write');
  instrument('restart-read', 'com.feedme.storage.AndroidStateProcessRestartTest#readFixtureAndCleanup', 1, 'read');
  instrument('work-origin-plan', 'com.feedme.storage.AndroidSessionWorkOriginProcessTest#preparePlanInFirstProcess', 1, 'work-origin-plan');
  instrument('work-origin-select', 'com.feedme.storage.AndroidSessionWorkOriginProcessTest#selectExactPlanInSecondProcess', 1, 'work-origin-select');
  instrument('work-origin-replay', 'com.feedme.storage.AndroidSessionWorkOriginProcessTest#reacknowledgeSelectedPlanAndCleanInThirdProcess', 1, 'work-origin-replay');
  const remaining = run('fixture-cleanup', ['shell', 'run-as', testPackage, 'ls', 'no_backup'], 10000).trim();
  if (/private-state-instrumented-|feedme-state-instrumented-process-restart-v1|feedme-work-origin-instrumented-process-v1|Permission denied|run-as:|No such file or directory|error:/i.test(remaining))
    throw new Error('Owned test fixture directory remains or cleanup inspection failed.');
  report.checks.push({name: 'owned-test-directories-cleaned', passed: true});
  if (!fs.readFileSync(apk).equals(apkBytes) ||
      !fs.readFileSync(path.join(apkDirectory, 'output-metadata.json')).equals(metadataBytes))
    throw new Error('Storage test APK or metadata changed during the native run.');
  report.passed = true;
} catch (failure) {
  report.failure = failure.message;
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(path.join(attemptDirectory, 'report.json'), JSON.stringify(report, null, 2) + '\n');
  fs.writeFileSync(path.join(reportDirectory, 'android-smoke.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
}
