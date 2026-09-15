import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {inflateSync} from 'node:zlib';

export const cookingHostClass = 'com.feedme.app.mealflow.AndroidCookingFlowHostTest';
export const cookingHostMethods = Object.freeze([
  'exactPreparedConsentSurvivesRecreationAndBackNeverStartsCooking',
  'dirtyFormAndStaleDialogCannotConfirmAnotherPlanOrPriorCookingPin',
  'lostCreateReplyKeepsOriginalBodyKeyAndExplicitRetryAcrossRecreation',
  'retainedCreatedReceiptRetriesDownloadWithoutSecondPost',
  'explicitOfflineProgressAndCompletionUseHeadOnlySyncAndNeverAutoFinishLastStep',
  'pendingStricterPreferencesStopContinuedCookingButPermitExplicitSafeStop',
  'invalidationRedactsConsentAndNoncooperativeCreateCannotRevivePrivateScreen',
  'missingProviderAndExactRecallNeverCreateOrRetainEligiblePreview',
  'immediateBackDuringDownloadAndReopenedOwnerKeepOriginalReceiptAndPinnedRecipe',
].sort());
export const cookingHostScreenshots = Object.freeze({
  'cooking-confirmation': 'exactPreparedConsentSurvivesRecreationAndBackNeverStartsCooking',
  'cooking-progress': 'exactPreparedConsentSurvivesRecreationAndBackNeverStartsCooking',
  'cooking-pending': 'retainedCreatedReceiptRetriesDownloadWithoutSecondPost',
  'cooking-done-pending': 'explicitOfflineProgressAndCompletionUseHeadOnlySyncAndNeverAutoFinishLastStep',
  'cooking-done-acknowledged': 'explicitOfflineProgressAndCompletionUseHeadOnlySyncAndNeverAutoFinishLastStep',
  'cooking-unavailable': 'invalidationRedactsConsentAndNoncooperativeCreateCannotRevivePrivateScreen',
  'cooking-retained-recipe': 'immediateBackDuringDownloadAndReopenedOwnerKeepOriginalReceiptAndPinnedRecipe',
});
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const fail = message => { throw new Error(message); };

/** No mkdir/chmod/repair: only Android Context may create its test package's parent directory. */
export function verifyCookingNativeParent(rootListing, ownerOutput, statOutput = null) {
  return verifyOwnedDirectory('no_backup', rootListing, ownerOutput, statOutput);
}
export function verifyCookingCaptureDirectory(parentListing, ownerOutput, statOutput = null) {
  return verifyOwnedDirectory('cooking-host-ui-evidence', parentListing, ownerOutput, statOutput);
}
function verifyOwnedDirectory(name, rootListing, ownerOutput, statOutput) {
  const uid = ownerOutput.trim();
  if (!/^[1-9][0-9]*$/.test(uid) || /run-as:|Permission denied|error:/i.test(rootListing)) fail('Cannot inspect native test package parent');
  const names = rootListing.split(/\r?\n/).filter(Boolean);
  if (new Set(names).size !== names.length) fail('Duplicate native test package directory entry');
  const present = names.includes(name);
  if (!present) {
    if (statOutput !== null) fail('Unexpected native parent metadata for absent directory');
    return {present: false, uid, mode: null};
  }
  const match = statOutput?.trim().match(/^directory:(700|771):([1-9][0-9]*):([1-9][0-9]*)$/);
  if (!match || match[2] !== uid) fail('Native no_backup parent is not an exact owned non-symlink Android directory');
  return {present: true, uid, mode: match[1], gid: match[3]};
}

/** Source identity is exact, including each literal screenshot call's enclosing test method. */
export function verifyCookingHostSource(source) {
  const declarations = [...source.matchAll(/@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)];
  const methods = declarations.map(x => x[1]).sort();
  if (!equal(methods, cookingHostMethods) || new Set(methods).size !== 9) fail('Cooking host source inventory changed');
  const captures = declarations.flatMap((entry, i) => {
    const body = source.slice(entry.index, declarations[i + 1]?.index ?? source.length);
    return [...body.matchAll(/\bscreenshot\("([a-z0-9-]+)"\)/g)].map(x => [x[1], entry[1]]);
  });
  if (captures.length !== 7 || new Set(captures.map(x => x[0])).size !== 7 ||
      !equal(captures.sort((a, b) => a[0].localeCompare(b[0])), Object.entries(cookingHostScreenshots).sort((a, b) => a[0].localeCompare(b[0]))))
    fail('Cooking screenshot calls differ from exact source method mapping');
  return methods;
}

/** Summary counts alone never prove a test. Require one ordered start/success pair per method. */
export function verifyCookingHostTranscript(output) {
  const events = []; let fields = {};
  for (const line of output.split(/\r?\n/)) {
    const field = line.match(/^INSTRUMENTATION_STATUS: (class|test|id|current|numtests)=(.*)$/);
    if (field) {
      if (Object.hasOwn(fields, field[1])) fail('Duplicate cooking runner identity field');
      fields[field[1]] = field[2];
    }
    const code = line.match(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$/);
    if (code) { events.push({...fields, code: Number(code[1])}); fields = {}; }
  }
  if (Object.keys(fields).length || events.length !== 18) fail('Incomplete cooking host runner events');
  const identities = [];
  for (let i = 0; i < 9; i++) {
    const start = events[i * 2], end = events[i * 2 + 1];
    for (const e of [start, end]) if (e.class !== cookingHostClass || e.id !== 'AndroidJUnitRunner' ||
        e.current !== String(i + 1) || e.numtests !== '9' || !cookingHostMethods.includes(e.test)) fail('Wrong cooking host event identity');
    if (start.code !== 1 || end.code !== 0 || start.test !== end.test) fail('Cooking host test failed, skipped or mismatched');
    identities.push(`${cookingHostClass}#${end.test}`);
  }
  const endings = [...output.matchAll(/^INSTRUMENTATION_CODE: (-?\d+)\s*$/gm)];
  if (!equal(identities.map(x => x.split('#')[1]).sort(), cookingHostMethods) ||
      [...output.matchAll(/^OK \(9 tests\)\s*$/gm)].length !== 1 || endings.length !== 1 || endings[0][1] !== '-1' ||
      /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED/.test(output)) fail('Cooking host did not pass the exact source inventory');
  return identities;
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
  return (crc ^ 0xffffffff) >>> 0;
}
/** Complete bounded native RGB/RGBA PNG, not signature-only or a stale file assumption. */
export function cookingPngInfo(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 45 || bytes.length > 16 * 1024 * 1024 ||
      !bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) fail('Invalid cooking screenshot signature/size');
  let offset = 8, width, height, channels, ended = false, idatEnded = false; const compressed = [];
  while (offset < bytes.length) {
    if (offset + 12 > bytes.length || ended) fail('Truncated/trailing cooking PNG');
    const length = bytes.readUInt32BE(offset), end = offset + 12 + length;
    if (end > bytes.length) fail('Truncated cooking PNG chunk');
    const type = bytes.toString('ascii', offset + 4, offset + 8), data = bytes.subarray(offset + 8, end - 4);
    if (crc32(bytes.subarray(offset + 4, end - 4)) !== bytes.readUInt32BE(end - 4)) fail('Cooking PNG CRC mismatch');
    if (offset === 8 && type !== 'IHDR') fail('Cooking PNG header missing');
    if (type === 'IHDR') {
      if (width !== undefined || length !== 13) fail('Duplicate/wrong cooking PNG header');
      width = data.readUInt32BE(0); height = data.readUInt32BE(4); channels = data[9] === 2 ? 3 : data[9] === 6 ? 4 : 0;
      if (width < 1 || height < 1 || width > 8192 || height > 8192 || data[8] !== 8 || !channels || data[10] || data[11] || data[12] ||
          (width * channels + 1) * height > 64 * 1024 * 1024) fail('Unsupported/unbounded native cooking PNG');
    } else if (type === 'IDAT') {
      if (idatEnded) fail('Noncontiguous cooking PNG pixels');
      compressed.push(data);
    } else {
      if (compressed.length) idatEnded = true;
      if (type === 'IEND') { if (length || !compressed.length) fail('Cooking PNG pixels/end missing'); ended = true; }
      else if (/^[A-Z]/.test(type) && type !== 'PLTE') fail('Unknown critical cooking PNG chunk');
    }
    offset = end;
  }
  if (!ended) fail('Cooking PNG end missing');
  const expected = (width * channels + 1) * height;
  const pixels = inflateSync(Buffer.concat(compressed), {maxOutputLength: expected + 1});
  if (pixels.length !== expected) fail('Cooking PNG pixel length mismatch');
  for (let i = 0; i < height; i++) if (pixels[i * (width * channels + 1)] > 4) fail('Cooking PNG filter invalid');
  return {width, height};
}

export function runNativeCookingHost() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const serial = process.env.FEEDME_TEST_DEVICE;
  if (!/^emulator-\d+$/.test(serial || '') || !process.env.ANDROID_HOME) fail('Explicit local emulator and Android SDK required');
  const adb = path.join(process.env.ANDROID_HOME, 'platform-tools/adb'), pkg = 'com.feedme.app.test';
  const runner = `${pkg}/androidx.test.runner.AndroidJUnitRunner`;
  const apkDir = path.join(root, 'shared/app/build/outputs/apk/androidTest/debug');
  const metadataPath = path.join(apkDir, 'output-metadata.json'), metadataBytes = fs.readFileSync(metadataPath), metadata = JSON.parse(metadataBytes);
  if (metadata.applicationId !== pkg || metadata.variantName !== 'debugAndroidTest' || metadata.artifactType?.type !== 'APK' ||
      metadata.elements?.length !== 1 || metadata.elements[0].outputFile !== 'app-debug-androidTest.apk') fail('Unexpected isolated cooking test artifact');
  const apk = path.join(apkDir, metadata.elements[0].outputFile), apkBytes = fs.readFileSync(apk);
  const source = path.join(root, 'shared/app/src/androidInstrumentedTest/kotlin/com/feedme/app/mealflow/AndroidCookingFlowHostTest.kt');
  const sourceBytes = fs.readFileSync(source); verifyCookingHostSource(sourceBytes.toString('utf8'));
  const startedAt = new Date().toISOString(), base = path.join(root, 'docs/verification/cooking-host');
  fs.mkdirSync(path.join(base, 'attempts'), {recursive: true});
  const attempt = path.join(base, 'attempts', startedAt.replaceAll(':', '-')); fs.mkdirSync(attempt, {recursive: false});
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  const descriptor = file => { const b = fs.readFileSync(file); return {path: path.relative(root, file), bytes: b.length, sha256: hash(b)}; };
  fs.writeFileSync(path.join(attempt, 'output-metadata.json'), metadataBytes);
  const report = {startedAt, passed: false, serial,
    scope: 'Nine actual retained Android FeedMeMealFlow/MealFlowExperience/CookingFlowController host tests using public native session factories, owner-scoped encrypted storage and the actual kitchen queue. Account verification and canonical service replies are explicitly synthetic. Proves declared UI consent, progress, Back, recreation, original create/201-download retry, preference conflict and invalidation paths only. No deployed provider/HTTP/catalog/reviewer/manifest claim; no timer, Save, Make Again, feedback/share, iOS, physical power-loss or native final-attachment-ACK injection acceptance. Screenshots are actual settled native captures, not generated images; visual acceptance remains independent review.',
    apk: descriptor(apk), apkMetadata: descriptor(path.join(attempt, 'output-metadata.json')), source: descriptor(source),
    attemptDirectory: path.relative(root, attempt), screenshots: [], screenshotEvidence: [],
    invocation: {classes: cookingHostClass, runner, expected: 9}};
  function run(name, args, timeout = 10000) {
    const r = spawnSync(adb, ['-s', serial, ...args], {encoding: 'utf8', timeout, maxBuffer: 8 * 1024 * 1024});
    const output = (r.stdout || '') + (r.stderr || ''); fs.writeFileSync(path.join(attempt, `${name}.log`), output);
    if (r.error || r.status !== 0) fail(`${name} failed; inspect retained log`); return output;
  }
  const clean = output => { if (/run-as:|Permission denied|error:/i.test(output) || output.split(/\r?\n/).some(n => n.startsWith('meal-host-native-')))
    fail('Unresolved native host fixture ownership; preserve for inspection'); };
  try {
    if (run('boot', ['shell', 'getprop', 'sys.boot_completed']).trim() !== '1') fail('Emulator not booted');
    report.androidApi = run('api', ['shell', 'getprop', 'ro.build.version.sdk']).trim();
    report.androidAbi = run('abi', ['shell', 'getprop', 'ro.product.cpu.abi']).trim();
    if (!/^\d+$/.test(report.androidApi) || Number(report.androidApi) < 27 || !report.androidAbi) fail('Unsupported cooking test device');
    if (!/^Success\s*$/m.test(run('install', ['install', '-r', '-t', apk], 60000))) fail('APK installation not acknowledged');
    const runners = run('runner', ['shell', 'pm', 'list', 'instrumentation']).split(/\r?\n/);
    if (runners.filter(x => x === `instrumentation:${runner} (target=${pkg})`).length !== 1) fail('Exact cooking host runner unavailable');
    const uid = run('preflight-uid', ['shell', 'run-as', pkg, 'id', '-u']);
    const packageRoot = run('preflight-root', ['shell', 'run-as', pkg, 'ls', '-1', '.']);
    const parentPresent = packageRoot.split(/\r?\n/).includes('no_backup');
    const parentStat = parentPresent ? run('preflight-directory', ['shell', 'run-as', pkg, 'stat', '-c', '%F:%a:%u:%g', 'no_backup']) : null;
    report.nativeParent = verifyCookingNativeParent(packageRoot, uid, parentStat);
    let inventory = '';
    if (report.nativeParent.present) { inventory = run('preflight', ['shell', 'run-as', pkg, 'ls', '-1', 'no_backup']); clean(inventory); }
    else fs.writeFileSync(path.join(attempt, 'preflight.log'), ''); // Absence is backed by preflight-root + nativeParent, never created here.
    // Only these seven disposable, source-declared PNGs; no directory deletion or clear-app-data.
    const names = Object.keys(cookingHostScreenshots);
    const capturePresent = inventory.split(/\r?\n/).includes('cooking-host-ui-evidence');
    const captureStat = capturePresent ? run('capture-directory', ['shell', 'run-as', pkg, 'stat', '-c', '%F:%a:%u:%g', 'no_backup/cooking-host-ui-evidence']) : null;
    report.captureDirectory = verifyCookingCaptureDirectory(inventory, uid, captureStat);
    if (report.captureDirectory.present)
      run('capture-preflight', ['shell', 'run-as', pkg, 'rm', '-f', ...names.map(n => `no_backup/cooking-host-ui-evidence/${n}.png`)]);
    else fs.writeFileSync(path.join(attempt, 'capture-preflight.log'), ''); // No named capture can exist without its non-symlink parent.
    const output = run('instrumentation', ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', cookingHostClass, runner], 600000);
    report.identities = verifyCookingHostTranscript(output);
    report.tests = {tests: 9, failures: 0, errors: 0, skipped: 0};
    clean(run('cleanup', ['shell', 'run-as', pkg, 'ls', '-1', 'no_backup']));
    for (const [name, method] of Object.entries(cookingHostScreenshots)) {
      const nativePath = `no_backup/cooking-host-ui-evidence/${name}.png`;
      const result = spawnSync(adb, ['-s', serial, 'exec-out', 'run-as', pkg, 'cat', nativePath], {timeout: 10000, maxBuffer: 16 * 1024 * 1024});
      fs.writeFileSync(path.join(attempt, `${name}-pull.log`), result.stderr || '');
      if (result.error || result.status !== 0 || !result.stdout) fail('Cooking native screenshot missing');
      const dimensions = cookingPngInfo(result.stdout), file = path.join(attempt, `${name}.png`);
      fs.writeFileSync(file, result.stdout); const image = descriptor(file); report.screenshots.push(image);
      report.screenshotEvidence.push({name, identity: `${cookingHostClass}#${method}`, nativePath, ...dimensions, image});
    }
    if (!fs.readFileSync(apk).equals(apkBytes) || !fs.readFileSync(metadataPath).equals(metadataBytes) || !fs.readFileSync(source).equals(sourceBytes))
      fail('Cooking APK, metadata or test source changed during run');
    report.passed = true;
  } catch (error) { report.failure = error.message; process.exitCode = 1; }
  finally {
    report.finishedAt = new Date().toISOString();
    report.evidence = fs.readdirSync(attempt).filter(n => n !== 'report.json').sort().map(n => descriptor(path.join(attempt, n)));
    const json = JSON.stringify(report, null, 2) + '\n'; fs.writeFileSync(path.join(attempt, 'report.json'), json);
    fs.writeFileSync(path.join(base, 'last-attempt.json'), json); if (report.passed) fs.writeFileSync(path.join(base, 'verification.json'), json);
    console.log(JSON.stringify({passed: report.passed, tests: report.tests, failure: report.failure, attemptDirectory: report.attemptDirectory}));
  }
  return report;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  runNativeCookingHost();
}
