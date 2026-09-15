import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {verifyHistoricalParserFixture} from './verify-historical-parser-fixture.mjs';
import {sourceRoots, excludedPrefixes, requiredFixtures, historicalApk, manifestPath, generatedPaths,
  need, sha, absolute, canonicalRoot, descriptor, inspectTree, isExcluded, isManaged, safeRelative,
  validatePublished, requireFixtures, assertExactPaths, checkReferenceLinks} from './snapshot-policy.mjs';

// Repository/reference integrity only; never a native build or release certification.
export function verifyReference(publicationRoot) {
  const root = canonicalRoot(publicationRoot), read = name => fs.readFileSync(absolute(root, name));
  descriptor(root, manifestPath); // Refuse a symlink/shared inode before reading or parsing the manifest.
  const json = name => JSON.parse(read(name).toString('utf8')), manifest = json(manifestPath);
  need(manifest.version === 2 && JSON.stringify(manifest.policy) === JSON.stringify({excludedPrefixes, requiredFixtures, historicalApk, sourceRoots}), 'Missing/changed exact snapshot policy');
  for (const key of ['files', 'generated', 'retainedFiles']) need(Array.isArray(manifest[key]), 'Missing complete manifest section: ' + key);
  assertExactPaths(manifest.generated.map(item => item.path), generatedPaths, 'Missing/unlisted generated reference');
  for (const item of manifest.files) {
    safeRelative(item.source); safeRelative(item.path);
    const mapping = sourceRoots.find(([source, published]) => item.source === source || item.source.startsWith(source + '/'));
    need(mapping && item.path === mapping[1] + item.source.slice(mapping[0].length) && !isExcluded(item.source), 'Source mapping/exclusion mismatch: ' + item.path);
    need(Number.isSafeInteger(item.originalBytes) && item.originalBytes >= 0 && /^[a-f0-9]{64}$/.test(item.originalSha256), 'Missing original descriptor: ' + item.path);
  }
  for (const item of manifest.retainedFiles) need(!isManaged(item.path) && !generatedPaths.includes(item.path) && item.path !== manifestPath, 'Retained entry cannot hide an omitted source/generated file');
  const listed = [...manifest.files, ...manifest.generated, ...manifest.retainedFiles];
  for (const item of listed) {
    safeRelative(item.path); need(item.path !== manifestPath, 'Manifest cannot certify itself');
    need(Number.isSafeInteger(item.bytes) && item.bytes >= 0 && /^[a-f0-9]{64}$/.test(item.sha256) &&
      Number.isInteger(item.mode) && item.mode >= 0 && item.mode <= 0o777, 'Invalid file descriptor: ' + item.path);
    const actual = descriptor(root, item.path);
    need(actual.bytes === item.bytes && actual.sha256 === item.sha256 && actual.mode === item.mode, 'Changed/missing snapshot bytes or mode: ' + item.path);
  }
  const tree = inspectTree(root);
  for (const name of [...tree.files, ...tree.directories]) need(!isExcluded(name), 'Excluded local evidence present: ' + name);
  assertExactPaths(tree.files, [...listed.map(item => item.path), manifestPath], 'Omitted/unlisted public file or duplicate manifest path');
  requireFixtures(manifest.files.map(item => item.path));
  const contents = new Map();
  for (const name of tree.files) { const bytes = read(name); validatePublished(name, bytes); contents.set(name, bytes); }
  const portable = verifyHistoricalParserFixture(root); // Integrity only; no test process launched.
  need(portable.passed === true && portable.files === 9 && portable.originalReceiptBundled === false && portable.testsExecuted === 0, 'Portable fixture integrity not confirmed');
  const history = json('Reference/History/HISTORY_MANIFEST.json');
  need(history.audit?.passed && history.archives?.length === 2, 'Missing archive provenance');
  for (const archive of history.archives) {
    need(/^[A-Za-z0-9_-]+\.zip$/.test(archive.published.filename), 'Unexpected history filename');
    const bytes = read('Reference/History/' + archive.published.filename);
    need(bytes.length === archive.published.bytes && sha(bytes) === archive.published.sha256, 'Historical ZIP changed');
    need(archive.entryOrderNamesAndOtherContentPreserved && archive.sourceUnchangedAfterPackaging, 'Incomplete history comparison');
  }
  const registry = json('outputs/biteclub_blueprint/registry/screen_registry.json');
  need(registry.features?.length === 54 && registry.screens?.length === 98, 'Canonical feature/screen coverage changed');
  need(new Set(registry.screens.map(item => item.id)).size === 98, 'Duplicate canonical screen');
  for (const screen of registry.screens) {
    need(/^[A-Z][A-Z0-9_]*$/.test(screen.id), 'Invalid canonical screen identity');
    for (const name of ['outputs/biteclub_ui/screens/' + screen.id + '.png', 'outputs/biteclub_blueprint/screens/' + screen.id + '.md']) need(contents.has(name), 'Missing design/spec: ' + screen.id);
    need(read('Reference/SCREEN_GALLERY.md').includes(Buffer.from('| ' + screen.id + ' |')), 'Missing gallery row: ' + screen.id);
  }
  const operations = Object.values(json('outputs/biteclub_blueprint/architecture/04_API_Contract.json').paths)
    .flatMap(item => Object.entries(item).filter(([method]) => ['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'].includes(method)));
  need(operations.length === 201, 'API operation coverage changed');
  const artifact = read(historicalApk.path);
  need(artifact.length === historicalApk.bytes && sha(artifact) === historicalApk.sha256, 'Historical demo APK changed');
  need((descriptor(root, 'feedme/gradlew').mode & 0o111) !== 0, 'Gradle wrapper lost executable bit');
  const checkedLinks = checkReferenceLinks(root, contents);
  return {passed: true, scope: 'Complete public inventory, bytes/modes, links, privacy, required fixtures and portable parser-fixture integrity only; not app/native/release or original historical-run acceptance.',
    files: tree.files.length, sourceCopies: manifest.files.length, retainedFiles: manifest.retainedFiles.length,
    screens: 98, features: 54, apiOperations: 201, checkedLinks, portableFixtureVerified: true,
    sourceCopyBytes: manifest.files.reduce((sum, item) => sum + item.bytes, 0)};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(JSON.stringify(verifyReference(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')), null, 2));
}
