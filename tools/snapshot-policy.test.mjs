import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {planSnapshot} from './export-snapshot.mjs';
import {verifyReference} from './verify-reference.mjs';
import {descriptor, inspectTree, manifestPath, generatedPaths, sourceRoots} from './snapshot-policy.mjs';
import {excludedPrefixes, requiredFixtures, historicalApk, safeRelative, isExcluded, isManaged,
  publishBytes, validatePublished, requireFixtures, assertExactPaths, checkReferenceLinks} from './snapshot-policy.mjs';
const syntheticHome = '/' + 'Users/' + 'example';
const syntheticEncodedHome = '%2F' + 'Users%2F' + 'example';

test('exact six exclusions reject whole subtrees but not similarly named siblings', () => {
  assert.equal(excludedPrefixes.length, 6);
  for (const prefix of excludedPrefixes) {
    assert.equal(isExcluded(prefix), true); assert.equal(isExcluded(prefix + '/attempt/report.json'), true);
    assert.equal(isExcluded(prefix + '-notes.md'), false);
    assert.throws(() => validatePublished(prefix + '/report.json', Buffer.from('{}')));
  }
});
test('four real build fixtures stay required and outside all exclusions', () => {
  assert.equal(requiredFixtures.length, 4);
  for (const name of requiredFixtures) assert.equal(isExcluded(name), false);
  requireFixtures([...requiredFixtures, 'feedme/gradlew']);
  for (const name of requiredFixtures) assert.throws(() => requireFixtures([...requiredFixtures.filter(x => x !== name), 'feedme/gradlew']));
});
test('historical demo remains the approved exact artifact, not a mutable build path', () => {
  assert.equal(historicalApk.path, 'Reference/artifacts/FeedMe-android-demo-debug.apk');
  assert.equal(historicalApk.bytes, 18981844);
  assert.equal(historicalApk.sha256, 'bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805');
  assert.equal(isManaged(historicalApk.path), false);
});
test('unsafe paths never become manifest authority', () => {
  for (const name of ['', '/absolute', '../escape', 'a/../b', 'a//b', 'a/./b', 'a\\b', 'a\u0000b']) assert.throws(() => safeRelative(name));
  assert.equal(safeRelative('feedme/src/Main.kt'), 'feedme/src/Main.kt');
});
test('manifest exact coverage rejects omissions additions and duplicate entries', () => {
  assertExactPaths(['b', 'a'], ['a', 'b'], 'bad');
  assert.throws(() => assertExactPaths(['a'], ['a', 'b'], 'omitted'));
  assert.throws(() => assertExactPaths(['a', 'b'], ['a'], 'unlisted'));
  assert.throws(() => assertExactPaths(['a', 'a'], ['a', 'a'], 'duplicate'));
});
test('home redaction preserves ordinary source text and records transformations', () => {
  const input = Buffer.from('path=' + syntheticHome + '/Project; encoded=' + syntheticEncodedHome + '%2FProject');
  const result = publishBytes('feedme/docs/example.md', input);
  assert.equal(result.published.toString(), 'path=/Users/LOCAL_USER/Project; encoded=%2FUsers%2FLOCAL_USER%2FProject');
  assert.equal(result.homePathRedactions, 2); assert.notEqual(result.published, input);
  assert(input.toString().includes(syntheticHome + '/'));
});
test('existing sanitized homes remain idempotent while encoded private homes are rejected', () => {
  const input = Buffer.from('/Users/LOCAL_USER/Project %2FUsers%2FLOCAL_USER%2FProject');
  const result = publishBytes('feedme/docs/example.md', input);
  assert.deepEqual(result.published, input); assert.equal(result.homePathRedactions, 0);
  assert.throws(() => validatePublished('README.md', Buffer.from(syntheticEncodedHome.toLowerCase() + '%2fsecret')));
});
test('NUL bytes do not exempt obvious private data from publication checks', () => {
  assert.throws(() => validatePublished('Reference/image.png', Buffer.from('\0' + syntheticHome + '/secret')));
  assert.throws(() => validatePublished('Reference/image.png', Buffer.from('\0-----BEGIN ' + 'PRIVATE KEY-----')));
});
test('source credential patterns fail instead of being redacted into apparent evidence', () => {
  assert.throws(() => publishBytes('feedme/docs/example.md', Buffer.from('-----BEGIN ' + 'PRIVATE KEY-----')));
  assert.throws(() => publishBytes('feedme/docs/example.md', Buffer.from([0xff, 0xfe])));
});
test('generated and private configuration paths remain forbidden', () => {
  for (const name of ['feedme/build/report.txt', 'feedme/.gradle/state', 'feedme/.env.local', 'feedme/local.properties', 'Reference/account.pem']) {
    assert.throws(() => validatePublished(name, Buffer.from('safe text')));
  }
});
test('diagnostic minimization preserves FeedMe lines and removes unrelated applications', () => {
  const input = Buffer.from('instrumentation:com.feedme.app.test/Runner (target=com.feedme.app.test)\ninstrumentation:unrelated.example/Runner (target=unrelated.example)\n');
  const result = publishBytes('feedme/docs/verification/retained.log', input);
  assert.equal(result.unrelatedInstrumentationRedactions, 1);
  assert.equal(result.published.toString(), 'instrumentation:com.feedme.app.test/Runner (target=com.feedme.app.test)\n');
});
test('reference links resolve only to the complete intended tree', () => {
  const contents = new Map([['README.md', Buffer.from('[screen](outputs/screen.png)')], ['outputs/screen.png', Buffer.from([0])]]);
  assert.equal(checkReferenceLinks('/publication', contents), 1);
  const forward = new Map([['Reference/README.md', Buffer.from('[manifest](SNAPSHOT_MANIFEST.json)')]]);
  assert.throws(() => checkReferenceLinks('/publication', forward));
  forward.set(manifestPath, Buffer.from('{"version":2}')); assert.equal(checkReferenceLinks('/publication', forward), 1);
  assert.throws(() => checkReferenceLinks('/publication', new Map([['README.md', Buffer.from('[missing](not-present.png)')]])));
  assert.throws(() => checkReferenceLinks('/publication', new Map([['README.md', Buffer.from('[escape](../private.txt)')]])));
});
function temporary(t) {
  const root = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'feedme-snapshot-policy-test-'));
  t.after(() => fs.rmSync(root, {recursive: true})); return root;
}
test('each stale excluded destination fails whole-plan preflight before any write', t => {
  const workspace = temporary(t);
  for (const prefix of excludedPrefixes) {
    const destination = temporary(t), stale = path.join(destination, prefix, 'receipt.json');
    fs.mkdirSync(path.dirname(stale), {recursive: true}); fs.writeFileSync(stale, '{"original":true}\n');
    const before = fs.readFileSync(stale), names = inspectTree(destination);
    assert.throws(() => planSnapshot(workspace, destination), /Stale excluded destination/);
    assert.deepEqual(fs.readFileSync(stale), before); assert.deepEqual(inspectTree(destination), names);
    assert.equal(fs.existsSync(path.join(destination, manifestPath)), false);
  }
});
test('whole-tree inspection and descriptors reject symlink and shared inode and retain executable mode', t => {
  const root = temporary(t), p = path.join(root, 'safe.txt'); fs.writeFileSync(p, 'safe');
  fs.symlinkSync(p, path.join(root, 'link')); assert.throws(() => inspectTree(root), /Symlink/); fs.unlinkSync(path.join(root, 'link'));
  fs.linkSync(p, path.join(root, 'shared.txt')); assert.throws(() => descriptor(root, 'safe.txt'), /shared-link/); fs.unlinkSync(path.join(root, 'shared.txt'));
  fs.chmodSync(p, 0o755); assert.equal(descriptor(root, 'safe.txt').mode, 0o755);
});
test('reference verifier rejects changed modes and actual unlisted files before fixture admission', t => {
  const root = temporary(t); fs.mkdirSync(path.join(root, 'Reference'));
  for (const name of generatedPaths) fs.writeFileSync(path.join(root, name), 'reference\n', {mode: 0o644});
  fs.writeFileSync(path.join(root, 'README.md'), 'readme\n', {mode: 0o644});
  const manifest = {version: 2, policy: {excludedPrefixes, requiredFixtures, historicalApk, sourceRoots}, files: [],
    generated: generatedPaths.map(name => descriptor(root, name)), retainedFiles: [descriptor(root, 'README.md')]};
  fs.writeFileSync(path.join(root, manifestPath), JSON.stringify(manifest));
  fs.chmodSync(path.join(root, 'README.md'), 0o755); assert.throws(() => verifyReference(root), /bytes or mode/);
  fs.chmodSync(path.join(root, 'README.md'), 0o644); fs.writeFileSync(path.join(root, 'unlisted.md'), 'unlisted');
  assert.throws(() => verifyReference(root), /Omitted\/unlisted/); fs.unlinkSync(path.join(root, 'unlisted.md'));
  manifest.retainedFiles = []; fs.writeFileSync(path.join(root, manifestPath), JSON.stringify(manifest));
  assert.throws(() => verifyReference(root), /Omitted\/unlisted/);
});
test('export and verifier refuse root and ancestor symlinks before reading a manifest', t => {
  const root = temporary(t), workspace = temporary(t), real = path.join(root, 'real'); fs.mkdirSync(real);
  fs.symlinkSync(real, path.join(root, 'alias'));
  for (const destination of [path.join(root, 'alias'), path.join(root, 'alias/child')]) {
    if (destination.endsWith('/child')) fs.mkdirSync(path.join(real, 'child'));
    assert.throws(() => planSnapshot(workspace, destination), /Canonical regular root/);
    assert.throws(() => verifyReference(destination), /Canonical regular root/);
  }
  assert.throws(() => planSnapshot(path.join(root, 'alias'), workspace), /Canonical regular root/);
});
test('verifier rejects a symlinked manifest before parsing its outside bytes', t => {
  const root = temporary(t), outside = temporary(t), secret = path.join(outside, 'not-json');
  fs.writeFileSync(secret, 'not JSON: must never be parsed'); fs.mkdirSync(path.join(root, 'Reference'));
  fs.symlinkSync(secret, path.join(root, manifestPath));
  assert.throws(() => verifyReference(root), /Non-regular\/shared-link file/);
});
