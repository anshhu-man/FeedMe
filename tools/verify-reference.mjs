import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {sanitizeDiagnostics} from './sanitize-diagnostics.mjs';

// Repository/reference integrity only; never a native build or release certification.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const read = name => fs.readFileSync(path.join(root, name));
const json = name => JSON.parse(read(name).toString('utf8'));
const assert = (value, message) => { if (!value) throw new Error(message); };
const manifest = json('Reference/SNAPSHOT_MANIFEST.json');
assert(new Set(manifest.files.map(item => item.path)).size === manifest.files.length, 'Duplicate snapshot path.');
for (const item of manifest.files) {
  const bytes = read(item.path);
  assert(bytes.length === item.bytes && hash(bytes) === item.sha256, `Changed published snapshot file: ${item.path}`);
}
const history = json('Reference/History/HISTORY_MANIFEST.json');
assert(history.audit?.passed && history.archives?.length === 2, 'Missing archive provenance.');
for (const archive of history.archives) {
  assert(/^[A-Za-z0-9_-]+\.zip$/.test(archive.published.filename), 'Unexpected history filename.');
  const bytes = read(`Reference/History/${archive.published.filename}`);
  assert(bytes.length === archive.published.bytes && hash(bytes) === archive.published.sha256, 'Historical ZIP changed.');
  assert(archive.entryOrderNamesAndOtherContentPreserved && archive.sourceUnchangedAfterPackaging, 'Incomplete history comparison.');
}
const registry = json('outputs/biteclub_blueprint/registry/screen_registry.json');
assert(registry.features.length === 54 && registry.screens.length === 98, 'Canonical feature/screen coverage changed.');
assert(new Set(registry.screens.map(item => item.id)).size === 98, 'Duplicate canonical screen.');
for (const screen of registry.screens) {
  assert(fs.existsSync(path.join(root, `outputs/biteclub_ui/screens/${screen.id}.png`)), `Missing design ${screen.id}`);
  assert(fs.existsSync(path.join(root, `outputs/biteclub_blueprint/screens/${screen.id}.md`)), `Missing spec ${screen.id}`);
  assert(read('Reference/SCREEN_GALLERY.md').includes(Buffer.from(`| ${screen.id} |`)), `Missing gallery row ${screen.id}`);
}
const operations = Object.values(json('outputs/biteclub_blueprint/architecture/04_API_Contract.json').paths)
  .flatMap(item => Object.entries(item).filter(([method]) => ['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'].includes(method)));
assert(operations.length === 201, 'API operation coverage changed.');
const artifact = read('Reference/artifacts/FeedMe-android-demo-debug.apk');
assert(artifact.length === 18981844 && hash(artifact) === 'bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805', 'Historical demo APK changed.');
assert((fs.statSync(path.join(root, 'feedme/gradlew')).mode & 0o111) !== 0, 'Gradle wrapper lost executable bit.');

function walk(directory) {
  return fs.readdirSync(directory, {withFileTypes: true}).flatMap(entry => {
    if (entry.name === '.git') return [];
    const filename = path.join(directory, entry.name);
    assert(!entry.isSymbolicLink(), 'Published tree contains a symlink.');
    assert(entry.isDirectory() || entry.isFile(), 'Published tree contains a special file.');
    return entry.isDirectory() ? walk(filename) : [filename];
  });
}
const files = walk(root);
let checkedLinks = 0;
for (const filename of files) {
  const relative = path.relative(root, filename);
  assert(!relative.split(path.sep).some(part => ['.gradle', '.kotlin', '.local', 'node_modules', 'build', 'xcuserdata'].includes(part)), `Generated/private directory: ${relative}`);
  assert(!/(?:^|\/)(?:local\.properties|\.env(?:\..*)?)$|\.(?:keystore|jks|p12|pfx|pem|key|mobileprovision)$/i.test(relative), `Private configuration filename: ${relative}`);
  const bytes = fs.readFileSync(filename);
  assert(bytes.length < 50 * 1024 * 1024, `Large unreviewed file: ${relative}`);
  if (!bytes.includes(0)) {
    const text = bytes.toString('utf8');
    assert(!/\/Users\/(?!LOCAL_USER(?:\/|\b))[\p{L}\p{N}._-]+/u.test(text), `Personal home path: ${relative}`);
    assert(!/-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b|\bAKIA[A-Z0-9]{16}\b|\bAIza[0-9A-Za-z_-]{30,}\b/.test(text), `Potential credential: ${relative}`);
    if (relative.startsWith('feedme/docs/verification/') && relative.endsWith('.log')) {
      assert(sanitizeDiagnostics(relative, text).unrelatedInstrumentationRedactions === 0, `Unrelated app inventory: ${relative}`);
    }
    // Check the new human index, not historical reports referring to excluded build outputs.
    if (relative === 'README.md' || (relative.startsWith('Reference/') && relative.endsWith('.md'))) {
      for (const match of text.matchAll(/\]\(([^)]+)\)|\bsrc="([^"]+)"/g)) {
        const target = match[1] || match[2];
        if (/^(?:https?:|mailto:|#)/.test(target)) continue;
        const resolved = path.resolve(path.dirname(filename), decodeURIComponent(target.split(/[?#]/)[0]));
        assert(resolved.startsWith(root + path.sep) && fs.existsSync(resolved), `Broken local link in ${relative}: ${target}`);
        checkedLinks++;
      }
    }
  }
}
console.log(JSON.stringify({passed: true, scope: 'Public snapshot hashes, reference links, privacy patterns, source/asset coverage only; not app, native or release acceptance.', files: files.length, sourceCopies: manifest.files.length, screens: 98, features: 54, apiOperations: operations.length, checkedLinks, sourceCopyBytes: manifest.files.reduce((sum, item) => sum + item.bytes, 0)}, null, 2));
