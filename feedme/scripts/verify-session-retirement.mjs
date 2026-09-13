import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';

// Local retirement components and isolated native storage tests, not actual app logout/auth.
// Requires existing JDK17, SDK36, local PostgreSQL binaries and a booted local emulator.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const destination = path.join(root, 'docs/verification/session-retirement');
const startedAt = new Date().toISOString();
const attempt = path.join(destination, 'attempts', startedAt.replaceAll(':', '-'));
fs.mkdirSync(attempt, {recursive: true});
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const artifact = name => {
  const bytes = fs.readFileSync(path.join(root, name));
  return {path: name, bytes: bytes.length, sha256: hash(bytes)};
};
const walk = directory => fs.readdirSync(directory, {withFileTypes: true}).flatMap(entry => {
  const name = path.join(directory, entry.name);
  if (entry.isSymbolicLink()) throw new Error('Verification inputs must not contain symlinks.');
  return entry.isDirectory() ? walk(name) : [name];
});
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
  scope: 'Durable local retirement coordinator, separate encrypted control/work storage and exact owner-incarnation retirement; fresh shared/server/isolated-PostgreSQL/Node regressions, Android library builds/lint and 45 isolated native storage tests. Common runtime tests retain fake or synthetic identity verification, and this runner does not invoke the separate native session integration suite. No actual identity-provider/app logout, account switch/deletion, UI workflow, hard-kill during transactions, physical-device, power-loss, iOS or release claim.',
  sourceManifestSha256: hash(JSON.stringify(before)), sourceFiles: before,
  commands: [], tests: [], artifacts: [],
};
fs.writeFileSync(path.join(attempt, 'source-manifest.json'), JSON.stringify(before, null, 2) + '\n');

async function run(name, command, args) {
  const logPath = path.join(attempt, `${name}.log`);
  const stream = fs.createWriteStream(logPath);
  const result = await new Promise((resolve, reject) => {
    const child = spawn(command, args, {cwd: root, env: process.env, stdio: ['ignore', 'pipe', 'pipe']});
    for (const output of [child.stdout, child.stderr]) output.on('data', data => { stream.write(data); process.stdout.write(data); });
    child.on('error', failure => { stream.end(); reject(failure); });
    child.on('close', code => stream.end(() => resolve(code)));
  });
  report.commands.push({name, exitCode: result, log: path.relative(root, logPath)});
  if (result !== 0) throw new Error(`${name} failed; its attempt log is retained.`);
}

function junit(directory, name, expected) {
  const files = walk(path.join(root, directory)).filter(file => path.basename(file).startsWith('TEST-') && file.endsWith('.xml'));
  const result = {name, tests: 0, failures: 0, errors: 0, skipped: 0};
  const out = path.join(attempt, 'junit', name);
  fs.mkdirSync(out, {recursive: true});
  for (const file of files) {
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

function retainNativeEvidence(notBefore) {
  const reportPath = 'docs/verification/client-storage/android-smoke.json';
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
  if (typeof nativePath !== 'string' || !/^docs\/verification\/client-storage\/android-attempts\/\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}\.\d{3}Z$/.test(nativePath)) {
    throw new Error('Unexpected native attempt evidence location.');
  }
  const nativeDirectory = path.join(root, nativePath);
  if (fs.lstatSync(nativeDirectory).isSymbolicLink()) throw new Error('Native evidence directory must not be a symlink.');
  const immutableReport = fs.readFileSync(path.join(nativeDirectory, 'report.json'));
  if (!immutableReport.equals(reportBytes)) throw new Error('Native report pointer does not match its retained attempt.');
  const out = path.join(attempt, 'android-native');
  fs.mkdirSync(out, {recursive: true});
  fs.writeFileSync(path.join(attempt, 'android-smoke.json'), reportBytes);
  report.nativeEvidence = walk(nativeDirectory).map(file => {
    const relative = path.relative(nativeDirectory, file);
    const retained = path.join(out, relative);
    fs.mkdirSync(path.dirname(retained), {recursive: true});
    fs.copyFileSync(file, retained);
    const source = artifact(path.relative(root, file));
    const evidence = artifact(path.relative(root, retained));
    if (source.sha256 !== evidence.sha256) throw new Error('Native evidence changed while being retained.');
    return {source, retained: evidence};
  });
  report.android = android;
  report.nativeReport = artifact(path.relative(root, path.join(attempt, 'android-smoke.json')));

  const expected = [
    {name: 'regular', tests: 57, classes: 'com.feedme.storage.AndroidStateVaultTest,com.feedme.storage.AndroidStateDatabaseTest,com.feedme.storage.AndroidSessionControlStoreTest,com.feedme.storage.AndroidSessionWorkStoreTest,com.feedme.storage.AndroidStateActivationPlanTest,com.feedme.storage.AndroidStateActivationRecoveryTest'},
    {name: 'sync-failure', tests: 6, classes: 'com.feedme.storage.AndroidStateActivationSyncFailureTest'},
    {name: 'restart-write', tests: 1, classes: 'com.feedme.storage.AndroidStateProcessRestartTest#writeFixture', stage: 'write'},
    {name: 'restart-read', tests: 1, classes: 'com.feedme.storage.AndroidStateProcessRestartTest#readFixtureAndCleanup', stage: 'read'},
  ];
  if (android.passed !== true || android.failure || !Array.isArray(android.checks) || android.checks.length !== 5) {
    throw new Error('Native storage receipt did not pass every required check.');
  }
  for (const check of expected) {
    const matches = android.checks.filter(value => value.name === check.name);
    const actual = matches[0];
    if (matches.length !== 1 || actual.passed !== true || actual.tests !== check.tests || actual.expected !== check.tests ||
        actual.classes !== check.classes || actual.stage !== check.stage || actual.log !== `${nativePath}/${check.name}.log`) {
      throw new Error('Native receipt has unexpected, missing or skipped tests.');
    }
    // Verify the retained instrumentation transcript, not only the mutable summary counts.
    const log = fs.readFileSync(path.join(out, `${check.name}.log`), 'utf8');
    const count = Number(log.match(/OK \((\d+) tests?\)/)?.[1]);
    const statuses = [...log.matchAll(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$/gm)].map(match => Number(match[1]));
    if (count !== check.tests || /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log) ||
        statuses.filter(code => code === 0).length !== check.tests || !statuses.every(code => code === 0 || code === 1)) {
      throw new Error('Retained native transcript does not prove every expected non-skipped test.');
    }
  }
  const cleanup = android.checks.filter(value => value.name === 'owned-test-directories-cleaned');
  if (cleanup.length !== 1 || cleanup[0].passed !== true) throw new Error('Native fixtures were not cleaned.');
  const apk = artifact('shared/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk');
  if (android.apk?.path !== apk.path || android.apk?.bytes !== apk.bytes || android.apk?.sha256 !== apk.sha256) {
    throw new Error('Freshly tested Android APK does not match the built artifact.');
  }
  report.nativeTests = {tests: 65, failures: 0, errors: 0, skipped: 0};
}

try {
  await run('gradle', './gradlew', [
    ':shared:core:jvmTest', ':shared:contracts:jvmTest', ':shared:transport:jvmTest', ':shared:storage:jvmTest', ':shared:sync:jvmTest', ':shared:kitchen:jvmTest', ':shared:session:jvmTest',
    ':shared:storage:jvmJar', ':shared:storage:assembleDebug', ':shared:storage:assembleDebugAndroidTest', ':shared:storage:lintDebug',
    ':shared:transport:jvmJar', ':shared:transport:assembleDebug', ':shared:transport:lintDebug',
    ':shared:sync:jvmJar', ':shared:sync:assembleDebug', ':shared:sync:lintDebug',
    ':shared:kitchen:jvmJar', ':shared:kitchen:assembleDebug', ':shared:kitchen:lintDebug',
    ':shared:session:jvmJar', ':shared:session:assembleDebug', ':shared:session:lintDebug',
    ':server:test', ':server:integrationTest', '--rerun-tasks', '--console=plain',
  ]);
  const nativeStarted = Date.now();
  try {
    await run('android-instrumentation', process.execPath, ['scripts/android-storage-smoke.mjs']);
  } finally {
    // Retain a fresh failed native attempt too; an old receipt can never satisfy this verification.
    retainNativeEvidence(nativeStarted);
  }
  const nodeTests = fs.readdirSync(path.join(root, 'scripts')).filter(name => name.endsWith('.test.mjs')).sort().map(name => `scripts/${name}`);
  await run('node-tests', process.execPath, ['--test', '--test-reporter=tap', ...nodeTests]);
  const nodeLog = fs.readFileSync(path.join(attempt, 'node-tests.log'), 'utf8');
  if (!/^# tests 92$/m.test(nodeLog) || !/^# pass 92$/m.test(nodeLog) || !/^# fail 0$/m.test(nodeLog) || !/^# skipped 0$/m.test(nodeLog))
    throw new Error('Expected all 92 Node regressions without skips.');
  for (const [directory, name, expected] of [
    ['shared/core/build/test-results/jvmTest', 'core', 48],
    ['shared/contracts/build/test-results/jvmTest', 'contracts', 119],
    ['shared/transport/build/test-results/jvmTest', 'transport', 72],
    ['shared/storage/build/test-results/jvmTest', 'storage', 315],
    ['shared/sync/build/test-results/jvmTest', 'sync', 103],
    ['shared/kitchen/build/test-results/jvmTest', 'kitchen', 142],
    ['shared/session/build/test-results/jvmTest', 'session', 219],
    ['server/build/test-results/test', 'server', 46],
    ['server/build/test-results/integrationTest', 'postgresql', 45],
  ]) junit(directory, name, expected);
  report.node = {tests: 92, failures: 0, errors: 0, skipped: 0};
  report.androidLint = [];
  for (const module of ['storage', 'transport', 'sync', 'kitchen', 'session']) {
    const lint = fs.readFileSync(path.join(root, `shared/${module}/build/reports/lint-results-debug.xml`), 'utf8');
    fs.writeFileSync(path.join(attempt, `android-${module}-lint.xml`), lint);
    if (/<issue\b/.test(lint)) throw new Error(`Android ${module} lint has outstanding issues.`);
    report.androidLint.push({module, issues: 0});
  }
  report.artifacts = [
    'shared/session/build/libs/session-jvm.jar', 'shared/session/build/outputs/aar/session-debug.aar',
    'shared/kitchen/build/libs/kitchen-jvm.jar', 'shared/kitchen/build/outputs/aar/kitchen-debug.aar',
    'shared/sync/build/libs/sync-jvm.jar', 'shared/sync/build/outputs/aar/sync-debug.aar',
    'shared/storage/build/libs/storage-jvm.jar', 'shared/storage/build/outputs/aar/storage-debug.aar',
    'shared/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk',
    'shared/transport/build/libs/transport-jvm.jar', 'shared/transport/build/outputs/aar/transport-debug.aar',
    'apps/android/build/outputs/apk/debug/android-debug.apk',
  ].map(artifact);
  if (report.artifacts.at(-1).sha256 !== 'bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805')
    throw new Error('Recorded historical demo artifact unexpectedly changed.');
  const finalTestApk = report.artifacts.find(value => value.path === report.android.apk.path);
  if (finalTestApk?.sha256 !== report.android.apk.sha256) throw new Error('Tested native artifact changed after execution.');
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
