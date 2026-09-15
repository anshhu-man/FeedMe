import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';

// Installs only the isolated session library test APK; never clears FeedMe or another app's data.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const serial = process.env.FEEDME_TEST_DEVICE || 'emulator-5554';
if (!/^emulator-\d+$/.test(serial)) throw new Error('Credential smoke is restricted to a local emulator.');
if (!process.env.ANDROID_HOME) throw new Error('Set ANDROID_HOME to the local Android SDK.');
const adb = path.join(process.env.ANDROID_HOME, 'platform-tools/adb');
const apkDirectory = path.join(root, 'shared/session/build/outputs/apk/androidTest/debug');
const metadataBytes = fs.readFileSync(path.join(apkDirectory, 'output-metadata.json'));
const metadata = JSON.parse(metadataBytes.toString('utf8'));
const testPackage = 'com.feedme.session.test';
const runner = `${testPackage}/androidx.test.runner.AndroidJUnitRunner`;
if (metadata.applicationId !== testPackage || metadata.elements?.length !== 1) throw new Error('Unexpected credential test artifact identity.');
const fileName = metadata.elements[0].outputFile;
if (fileName !== 'session-debug-androidTest.apk') throw new Error('Unexpected credential test artifact filename.');
const apk = path.join(apkDirectory, fileName);
const apkBytes = fs.readFileSync(apk);
const reportDirectory = path.join(root, 'docs/verification/native-credentials');
const startedAt = new Date().toISOString();
const attemptDirectory = path.join(reportDirectory, 'android-attempts', startedAt.replaceAll(':', '-'));
fs.mkdirSync(attemptDirectory, {recursive: true});
fs.writeFileSync(path.join(attemptDirectory, 'output-metadata.json'), metadataBytes);
const report = {
  startedAt, passed: false, serial,
  scope: 'Isolated API35 Android credential/work/setup tests, now including twelve real native all-owning interrupted-startup cases. The new public factory retains CONTROL and exact credential/data/work owners before opening, authenticates the original PendingSetup, exposes read-only inspection and an opaque proposal, and requires explicit programmatic confirmation before aborting. Exact ALL-ABORTED checkpoint retention precedes work/data/credential close; truthful close acknowledgements precede fresh changed CONTROL Complete. Failed acquisition and close remain owned, restart after one/two closes reauthenticates original plans, and persisted Complete never becomes consent. Application reservation excludes competing legacy/runtime roots; invalidation fences work and clears live leases without releasing resources. Failed runtime opening retains borrowed native-store close responsibility in the caller. Existing native-file, credential CREATE-plan/abort, control/setup journal, live publication, already-open composite abort and exact work cancellation regressions remain. Real store/OS behavior is distinguished from synthetic identity/provider verification and application acknowledgement-loss wrappers; VFS fault injection belongs to separate storage suites. No production provider/configuration approval, UI confirmation, general orphan/hot-journal recovery, native workload/domain scheduling, hard kill, physical power loss, API26/iOS or release acceptance. Notification and hardlink branches remain explicitly reported. No existing application data is cleared.',
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

function instrument(name, classes, expected, captureBranches = false) {
  const allowedClasses = new Set(classes.split(','));
  const declared = [...allowedClasses].flatMap(className => {
    const file = path.join(root, 'shared/session/src/androidInstrumentedTest/kotlin', `${className.replaceAll('.', '/')}.kt`);
    return [...fs.readFileSync(file, 'utf8').matchAll(/@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)]
      .map(match => `${className}#${match[1]}`);
  }).sort();
  if (declared.length !== expected || new Set(declared).size !== expected)
    throw new Error('Credential selectors do not match the exact current source test declarations.');
  const output = run(name, ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes, runner]);
  const summaries = [...output.matchAll(/^OK \((\d+) tests?\)\s*$/gm)];
  const endings = [...output.matchAll(/^INSTRUMENTATION_CODE: (-?\d+)\s*$/gm)];
  const count = Number(summaries[0]?.[1]);
  let fields = {};
  const events = [], invalid = [];
  for (const line of output.split(/\r?\n/)) {
    const field = line.match(/^INSTRUMENTATION_STATUS: (class|test|current|numtests|id)=(.*)$/);
    if (field) {
      if (Object.hasOwn(fields, field[1])) invalid.push('duplicate-field');
      fields[field[1]] = field[2];
    }
    const status = line.match(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$/);
    if (status) {
      events.push({...fields, code: Number(status[1])});
      fields = {};
    }
  }
  const completed = [];
  for (let index = 0; index < expected; index++) {
    const start = events[index * 2], finish = events[index * 2 + 1];
    for (const event of [start, finish]) {
      if (!event || !allowedClasses.has(event.class) || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(event.test || '') ||
          event.id !== 'AndroidJUnitRunner' || event.current !== String(index + 1) || event.numtests !== String(expected)) {
        invalid.push('identity');
      }
    }
    if (!start || !finish || start.code !== 1 || finish.code !== 0 || start.class !== finish.class || start.test !== finish.test) {
      invalid.push('unmatched-pair');
    } else completed.push(`${finish.class}#${finish.test}`);
  }
  const passed = summaries.length === 1 && count === expected && endings.length === 1 && endings[0][1] === '-1' &&
    Object.keys(fields).length === 0 && events.length === expected * 2 && invalid.length === 0 &&
    JSON.stringify([...completed].sort()) === JSON.stringify(declared) &&
    !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(output);
  report.checks.push({name, classes, passed, tests: count || 0, expected, identities: completed,
    log: path.relative(root, path.join(attemptDirectory, `${name}.log`))});
  if (!passed) throw new Error('Credential instrumentation did not pass every expected non-skipped test.');
  if (captureBranches) {
    const branch = (name, allowed) => {
      const matches = [...output.matchAll(new RegExp(`^INSTRUMENTATION_RESULT: ${name}=(.*)$`, 'gm'))];
      if (matches.length !== 1 || !allowed.test(matches[0][1])) throw new Error('Expected native branch evidence is missing or ambiguous.');
      return matches[0][1];
    };
    const hardlinkBranches = /^(?:platform-denied:(?:1|13); existing-hardlink-branch-unexercised|existing-hardlink-rejected-by-store)$/;
    report.branches = {
      hardlinkManifest: branch('credential_hardlink_manifest', hardlinkBranches),
      hardlinkBlob: branch('credential_hardlink_blob', hardlinkBranches),
      notification: branch('native_work_notification', /^NATIVE_WORK_NOTIFICATION_(?:PERMISSION_UNAVAILABLE|EXACT_CANCELLATION_VERIFIED)$/),
    };
  }
  console.log(`${name}: ${count} isolated session tests passed`);
}

try {
  if (run('boot', ['shell', 'getprop', 'sys.boot_completed'], 10000).trim() !== '1') throw new Error('Start the emulator and wait for boot first.');
  report.androidApi = run('api', ['shell', 'getprop', 'ro.build.version.sdk'], 10000).trim();
  report.androidAbi = run('abi', ['shell', 'getprop', 'ro.product.cpu.abi'], 10000).trim();
  run('install', ['install', '-r', '-t', apk]);
  const runners = run('runner', ['shell', 'pm', 'list', 'instrumentation'], 10000);
  if (!runners.includes(runner)) throw new Error('Expected isolated credential instrumentation runner unavailable.');
  instrument('regular', 'com.feedme.session.AndroidCredentialStoreTest,com.feedme.session.AndroidNativeWorkCancellationTest,com.feedme.session.AndroidCredentialCreatePlanTest', 47, true);
  instrument('integration', 'com.feedme.session.AndroidLocalRetirementIntegrationTest', 11);
  instrument('setup-journal', 'com.feedme.session.AndroidSessionSetupJournalTest', 7);
  instrument('live-setup', 'com.feedme.session.AndroidLiveSessionSetupCoordinatorTest', 11);
  instrument('setup-publication', 'com.feedme.session.AndroidSessionSetupPublicationTest', 9);
  instrument('credential-inspection', 'com.feedme.session.AndroidCredentialCreateInspectionTest', 12);
  instrument('interrupted-setup-inspection', 'com.feedme.session.AndroidInterruptedSetupInspectionTest', 9);
  instrument('credential-plan-abort', 'com.feedme.session.AndroidCredentialPlanAbortTest', 12);
  instrument('composite-setup-abort', 'com.feedme.session.AndroidCompositeSetupAbortTest', 10);
  instrument('retained-recovery-owner', 'com.feedme.session.AndroidCredentialRecoveryOwnerTest', 12);
  instrument('work-recovery', 'com.feedme.session.AndroidSessionWorkRecoveryTest', 7);
  instrument('owned-startup-recovery', 'com.feedme.session.AndroidSessionSetupRecoveryOwnerTest', 12);
  const remaining = run('fixture-cleanup', ['shell', 'run-as', testPackage, 'ls', 'no_backup'], 10000).trim();
  if (/credential-state-instrumented-|retirement-integration-/.test(remaining)) throw new Error('Owned session test fixture directory remains after cleanup.');
  report.checks.push({name: 'owned-test-directories-cleaned', passed: true});
  if (!fs.readFileSync(apk).equals(apkBytes) ||
      !fs.readFileSync(path.join(apkDirectory, 'output-metadata.json')).equals(metadataBytes)) {
    throw new Error('Credential test APK or metadata changed during the native run.');
  }
  report.passed = true;
} catch (failure) {
  report.failure = failure.message;
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  const json = JSON.stringify(report, null, 2) + '\n';
  fs.writeFileSync(path.join(attemptDirectory, 'report.json'), json);
  fs.writeFileSync(path.join(reportDirectory, 'android-smoke.json'), json);
  console.log(json);
}
