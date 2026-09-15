import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {deflateSync} from 'node:zlib';
import {cookingHostClass, cookingHostMethods, cookingHostScreenshots, verifyCookingHostSource,
  verifyCookingHostTranscript, cookingPngInfo, verifyCookingNativeParent, verifyCookingCaptureDirectory} from './verify-native-cooking-host.mjs';

const source = fs.readFileSync(new URL('../shared/app/src/androidInstrumentedTest/kotlin/com/feedme/app/mealflow/AndroidCookingFlowHostTest.kt', import.meta.url), 'utf8');
function transcript(methods = cookingHostMethods) {
  const events = methods.flatMap((method, i) => [1, 0].map(code =>
    `INSTRUMENTATION_STATUS: class=${cookingHostClass}\nINSTRUMENTATION_STATUS: test=${method}\nINSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: current=${i + 1}\nINSTRUMENTATION_STATUS: numtests=9\nINSTRUMENTATION_STATUS_CODE: ${code}\n`));
  return events.join('') + 'OK (9 tests)\nINSTRUMENTATION_CODE: -1\n';
}
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) { crc ^= byte; for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data = Buffer.alloc(0)) {
  const bytes = Buffer.alloc(data.length + 12); bytes.writeUInt32BE(data.length); bytes.write(type, 4, 'ascii'); data.copy(bytes, 8);
  bytes.writeUInt32BE(crc32(bytes.subarray(4, bytes.length - 4)), bytes.length - 4); return bytes;
}
function png({width = 1, height = 1, color = 6, pixels = Buffer.from([0, 0, 0, 0, 255])} = {}) {
  const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = color;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND')]);
}

test('cooking host source declares exactly nine methods and seven method-bound captures', () => {
  assert.deepEqual(verifyCookingHostSource(source), cookingHostMethods); assert.equal(Object.keys(cookingHostScreenshots).length, 7);
});
test('cooking host source rejects renamed duplicate added or removed test identities', () => {
  for (const changed of [source.replace(cookingHostMethods[0], 'differentMethod'),
    source.replace(cookingHostMethods[0], cookingHostMethods[1]), source + '\n@Test fun additional() {}',
    source.replace('@Test fun', 'fun')]) assert.throws(() => verifyCookingHostSource(changed));
});
test('cooking host source rejects missing duplicate or reassigned screenshot names', () => {
  assert.throws(() => verifyCookingHostSource(source.replace('screenshot("cooking-progress")', 'screenshot("other")')));
  assert.throws(() => verifyCookingHostSource(source.replace('screenshot("cooking-progress")', 'screenshot("cooking-confirmation")')));
  const changed = source.replace('screenshot("cooking-progress")', '').replace('screenshot("cooking-pending")', 'screenshot("cooking-pending"); screenshot("cooking-progress")');
  assert.throws(() => verifyCookingHostSource(changed));
});
test('cooking host transcript accepts only paired exact source identities in execution order', () => {
  const reversed = [...cookingHostMethods].reverse();
  assert.deepEqual(verifyCookingHostTranscript(transcript(reversed)), reversed.map(method => `${cookingHostClass}#${method}`));
});
test('cooking host transcript rejects count-only duplicate missing foreign and malformed pairs', () => {
  const good = transcript();
  for (const changed of ['OK (9 tests)\nINSTRUMENTATION_CODE: -1\n', transcript([...cookingHostMethods.slice(1), cookingHostMethods[1]]),
    good.replace(cookingHostClass, 'com.feedme.app.OtherTest'), good.replace('current=1', 'current=01'),
    good.replace('numtests=9', 'numtests=8'), good.replace('id=AndroidJUnitRunner', 'id=OtherRunner'),
    good.replace(`test=${cookingHostMethods[0]}`, `test=${cookingHostMethods[1]}`),
    good.replace('INSTRUMENTATION_STATUS: class=', 'INSTRUMENTATION_STATUS: test=duplicate\nINSTRUMENTATION_STATUS: class=')])
    assert.throws(() => verifyCookingHostTranscript(changed));
});
test('cooking host transcript rejects skips errors crashes duplicate endings and dangling fields', () => {
  const good = transcript();
  for (const changed of [good.replace('STATUS_CODE: 0', 'STATUS_CODE: -3'), good.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'),
    good + 'Process crashed\n', good + 'INSTRUMENTATION_CODE: -1\n', good + 'OK (9 tests)\n',
    good + 'INSTRUMENTATION_STATUS: test=unfinished\n', good.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0')])
    assert.throws(() => verifyCookingHostTranscript(changed));
});
test('cooking screenshots require complete decodable bounded native RGB or RGBA PNGs', () => {
  assert.deepEqual(cookingPngInfo(png()), {width: 1, height: 1});
  assert.deepEqual(cookingPngInfo(png({color: 2, pixels: Buffer.from([0, 0, 0, 0])})), {width: 1, height: 1});
});
test('cooking screenshots reject signatures truncation trailing bytes and CRC corruption', () => {
  const good = png(); const corrupt = Buffer.from(good); corrupt[45] ^= 1;
  for (const changed of [good.subarray(0, 8), good.subarray(0, good.length - 1), Buffer.concat([good, Buffer.from([0])]), corrupt,
    Buffer.from('not a PNG')]) assert.throws(() => cookingPngInfo(changed));
});
test('cooking screenshots reject unsupported dimensions pixel lengths and row filters', () => {
  for (const changed of [png({width: 0}), png({height: 8193}), png({color: 0}), png({pixels: Buffer.from([0])}),
    png({pixels: Buffer.from([5, 0, 0, 0, 0])}), png({width: 8192, height: 8192})]) assert.throws(() => cookingPngInfo(changed));
});

test('cooking parent preflight accepts genuine absence without creating or repairing Android directories', () => {
  assert.deepEqual(verifyCookingNativeParent('cache\nfiles\n', '10207\n'), {present: false, uid: '10207', mode: null});
  for (const mode of ['700', '771']) assert.deepEqual(verifyCookingNativeParent('no_backup\n', '10207', `directory:${mode}:10207:10207`),
    {present: true, uid: '10207', mode, gid: '10207'});
  const helper = fs.readFileSync(new URL('./verify-native-cooking-host.mjs', import.meta.url), 'utf8');
  assert.doesNotMatch(helper, /\['shell',\s*'run-as',\s*pkg,\s*'(?:mkdir|chmod|chown)'/);
  assert.throws(() => verifyCookingNativeParent('files\n', '10207', 'directory:700:10207:10207'));
  assert.deepEqual(verifyCookingCaptureDirectory('other-evidence\n', '10207'), {present: false, uid: '10207', mode: null});
  assert.equal(verifyCookingCaptureDirectory('cooking-host-ui-evidence\n', '10207', 'directory:700:10207:10207').present, true);
});
test('cooking parent preflight rejects permissive symlink foreign or unreadable existing parents', () => {
  for (const output of ['directory:777:10207:10207', 'directory:755:10207:10207', 'directory:771:1:1',
    'symbolic link:777:10207:10207', 'regular file:700:10207:10207', '', null])
    assert.throws(() => verifyCookingNativeParent('no_backup\n', '10207', output));
  assert.throws(() => verifyCookingNativeParent('no_backup\nno_backup\n', '10207', 'directory:700:10207:10207'));
  assert.throws(() => verifyCookingNativeParent('run-as: permission denied', '10207'));
  assert.throws(() => verifyCookingCaptureDirectory('cooking-host-ui-evidence\n', '10207', 'symbolic link:700:10207:10207'));
});
