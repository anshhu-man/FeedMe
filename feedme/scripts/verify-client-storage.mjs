import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';

// Local-only verification. Requires an already running emulator and the documented JDK/SDK/PG.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const destination = path.join(root, 'docs/verification/client-storage');
fs.mkdirSync(destination, {recursive: true});
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
  startedAt: new Date().toISOString(), passed: false,
  scope: 'Client encrypted-storage component plus freshly rerun shared/server/PG/Node regressions. Not product/auth/UI integration, iOS, arbitrary-database safety or release certification.',
  sourceManifestSha256: hash(JSON.stringify(before)), sourceFiles: before,
  commands: [], tests: [], artifacts: [],
};
fs.writeFileSync(path.join(destination, 'source-manifest.json'), JSON.stringify(before, null, 2) + '\n');

async function run(name, command, args) {
  const logPath = path.join(destination, `${name}.log`);
  const stream = fs.createWriteStream(logPath);
  const result = await new Promise((resolve, reject) => {
    const child = spawn(command, args, {cwd: root, env: process.env, stdio: ['ignore', 'pipe', 'pipe']});
    for (const output of [child.stdout, child.stderr]) output.on('data', data => { stream.write(data); process.stdout.write(data); });
    child.on('error', reject);
    child.on('close', code => stream.end(() => resolve(code)));
  });
  report.commands.push({name, exitCode: result, log: path.relative(root, logPath)});
  if (result !== 0) throw new Error(`${name} failed; its log is retained.`);
}

function junit(directory, name, expected) {
  const files = walk(path.join(root, directory)).filter(file => path.basename(file).startsWith('TEST-') && file.endsWith('.xml'));
  const result = {name, tests: 0, failures: 0, errors: 0, skipped: 0};
  const out = path.join(destination, 'junit', name);
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

try {
  await run('gradle', './gradlew', [
    ':shared:core:jvmTest', ':shared:contracts:jvmTest', ':shared:transport:jvmTest', ':shared:storage:jvmTest', ':shared:sync:jvmTest', ':shared:kitchen:jvmTest', ':shared:session:jvmTest',
    ':shared:storage:assembleDebug', ':shared:storage:assembleDebugAndroidTest', ':shared:storage:lintDebug',
    ':shared:session:jvmJar', ':shared:session:assembleDebug', ':shared:session:lintDebug',
    ':server:test', ':server:integrationTest', '--rerun-tasks', '--console=plain',
  ]);
  const nodeTests = fs.readdirSync(path.join(root, 'scripts')).filter(name => name.endsWith('.test.mjs')).sort().map(name => `scripts/${name}`);
  await run('node-tests', process.execPath, ['--test', '--test-reporter=tap', ...nodeTests]);
  const nodeLog = fs.readFileSync(path.join(destination, 'node-tests.log'), 'utf8');
  if (!/^# tests 92$/m.test(nodeLog) || !/^# pass 92$/m.test(nodeLog) || !/^# fail 0$/m.test(nodeLog) || !/^# skipped 0$/m.test(nodeLog)) {
    throw new Error('Expected all 92 Node regressions without skips.');
  }
  await run('android-instrumentation', process.execPath, ['scripts/android-storage-smoke.mjs']);
  const android = JSON.parse(fs.readFileSync(path.join(destination, 'android-smoke.json'), 'utf8'));
  if (!android.passed || android.checks?.filter(check => Number.isInteger(check.tests)).reduce((sum, check) => sum + check.tests, 0) !== 65)
    throw new Error('Native storage receipt did not pass all 65 tests.');
  report.android = android;
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
  for (const module of ['storage', 'session']) {
    const lint = fs.readFileSync(path.join(root, `shared/${module}/build/reports/lint-results-debug.xml`), 'utf8');
    fs.writeFileSync(path.join(destination, `android-${module}-lint.xml`), lint);
    if (/<issue\b/.test(lint)) throw new Error(`Android ${module} lint has outstanding issues.`);
    report.androidLint.push({module, issues: 0});
  }
  report.artifacts = [
    'shared/storage/build/libs/storage-jvm.jar',
    'shared/storage/build/outputs/aar/storage-debug.aar',
    'shared/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk',
    'shared/session/build/libs/session-jvm.jar', 'shared/session/build/outputs/aar/session-debug.aar',
    'apps/android/build/outputs/apk/debug/android-debug.apk',
  ].map(artifact);
  const demo = report.artifacts.at(-1);
  if (demo.sha256 !== 'bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805') throw new Error('Recorded demo artifact unexpectedly changed.');
  if (report.artifacts[2].sha256 !== android.apk.sha256) throw new Error('Tested Android artifact does not match the final artifact.');
  if (hash(JSON.stringify(sourceFiles())) !== report.sourceManifestSha256) throw new Error('Sources changed during verification; rerun.');
  report.passed = true;
} catch (failure) {
  report.failure = failure.message;
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  report.receiptArtifacts = ['gradle.log', 'node-tests.log', 'android-smoke.json', 'source-manifest.json']
    .filter(name => fs.existsSync(path.join(destination, name)))
    .map(name => artifact(path.relative(root, path.join(destination, name))));
  fs.writeFileSync(path.join(destination, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({...report, sourceFiles: `${before.length} files in source-manifest.json`}, null, 2));
}
