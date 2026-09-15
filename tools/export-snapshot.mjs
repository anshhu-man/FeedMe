import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {verifyHistoricalParserFixture} from './verify-historical-parser-fixture.mjs';
import {sourceRoots, excludedPrefixes, requiredFixtures, historicalApk, manifestPath, generatedPaths,
  need, sha, absolute, canonicalRoot, fileInfo, descriptor, inspectTree, ignoredInput, isExcluded, isManaged,
  publishBytes, validatePublished, requireFixtures, assertExactPaths, checkReferenceLinks, intendedSourcePaths} from './snapshot-policy.mjs';

// Explicit local publication copy only. No network, deletion, Git, credentials or deployment.
// Build the complete plan and validate every retained/intended byte before the first write.
export function planSnapshot(sourceWorkspace, publicationRoot) {
  const workspace = canonicalRoot(sourceWorkspace), destination = canonicalRoot(publicationRoot);
  need(workspace !== destination && !workspace.startsWith(destination + path.sep), 'Source must be outside publication tree');
  for (const [source] of sourceRoots) {
    const input = absolute(workspace, source);
    need(destination !== input && !destination.startsWith(input + path.sep), 'Destination cannot be inside a copied source');
  }
  const existing = inspectTree(destination);
  for (const name of [...existing.files, ...existing.directories]) need(!isExcluded(name), 'Stale excluded destination; use a separately reviewed clean publication tree: ' + name);
  // This separate portable fixture is created explicitly by its own reviewed tool, never here.
  const portable = verifyHistoricalParserFixture(destination);
  need(portable.passed === true && portable.files === 9 && portable.originalReceiptBundled === false && portable.testsExecuted === 0, 'Portable parser fixture integrity not confirmed');
  const apk = descriptor(destination, historicalApk.path);
  need(apk.bytes === historicalApk.bytes && apk.sha256 === historicalApk.sha256, 'Historical demo APK missing/changed; never replace it from a mutable build');
  const inputs = [], files = [], excluded = [], writes = new Map();
  function visit(source, published) {
    const input = absolute(workspace, source), info = fs.lstatSync(input);
    need(!info.isSymbolicLink(), 'Source symlink refused: ' + source);
    need(info.isDirectory() || info.isFile(), 'Special source refused: ' + source);
    if (ignoredInput(source, info.isDirectory())) { excluded.push(source + (info.isDirectory() ? '/' : '')); return; }
    if (info.isDirectory()) {
      for (const name of fs.readdirSync(input).sort()) visit(source + '/' + name, published + '/' + name);
      return;
    }
    fileInfo(input, source); const original = fs.readFileSync(input), safe = publishBytes(source, original);
    need(!writes.has(published), 'Duplicate intended path: ' + published);
    absolute(destination, published); validatePublished(published, safe.published);
    inputs.push({path: source, bytes: original.length, sha256: sha(original), mode: info.mode & 0o777});
    files.push({source, path: published, originalBytes: original.length, originalSha256: sha(original),
      bytes: safe.published.length, sha256: sha(safe.published), mode: info.mode & 0o777,
      homePathRedactions: safe.homePathRedactions, unrelatedInstrumentationRedactions: safe.unrelatedInstrumentationRedactions});
    writes.set(published, {bytes: safe.published, mode: info.mode & 0o777});
  }
  for (const [source, published] of sourceRoots) visit(source, published);
  const sourcePaths = files.map(item => item.path); requireFixtures(sourcePaths);
  need((files.find(item => item.path === 'feedme/gradlew').mode & 0o111) !== 0, 'Source Gradle wrapper must be executable');
  for (const name of existing.files) if (isManaged(name)) need(writes.has(name), 'Stale/unlisted managed destination file: ' + name);
  for (const name of writes.keys()) need(!existing.directories.includes(name), 'File would overwrite directory: ' + name);
  const registry = JSON.parse(writes.get('outputs/biteclub_blueprint/registry/screen_registry.json').bytes.toString());
  need(registry.features?.length === 54 && registry.screens?.length === 98 && new Set(registry.screens.map(s => s.id)).size === 98, 'Expected canonical 54 features/98 screens');
  const rows = registry.screens.map(screen => {
    need(/^[A-Z][A-Z0-9_]*$/.test(screen.id), 'Unexpected screen identity');
    for (const name of ['outputs/biteclub_ui/screens/' + screen.id + '.png', 'outputs/biteclub_blueprint/screens/' + screen.id + '.md']) need(writes.has(name), 'Missing intended screen asset/spec: ' + name);
    const title = String(screen.title || screen.name || screen.id).replaceAll('|', '\\|');
    return `| ${screen.id} | ${title} | [PNG](../outputs/biteclub_ui/screens/${screen.id}.png) | [Buttons and behavior](../outputs/biteclub_blueprint/screens/${screen.id}.md) |`;
  });
  const api = JSON.parse(writes.get('outputs/biteclub_blueprint/architecture/04_API_Contract.json').bytes.toString());
  const operations = Object.values(api.paths).flatMap(item => Object.entries(item).filter(([method]) => ['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace'].includes(method)));
  need(operations.length === 201, 'Expected canonical 201 operations');
  const generated = [];
  function generate(name, text) {
    need(generatedPaths.includes(name), 'Unapproved generated reference');
    const bytes = Buffer.from(text); validatePublished(name, bytes); writes.set(name, {bytes, mode: 0o644});
    generated.push({path: name, bytes: bytes.length, sha256: sha(bytes), mode: 0o644});
  }
  generate('Reference/SCREEN_GALLERY.md', '# All 98 FeedMe screens\n\nEach design is paired with its screen specification and button behavior. These are prototype designs, not completed native feature claims. Start at AUTH_WELCOME, then signup/login and onboarding. [Interactive viewing instructions](../outputs/biteclub_ui/README.md). [Local evidence boundary](LOCAL_EVIDENCE.md).\n\n| Screen | Name | Design | Specification |\n| --- | --- | --- | --- |\n' + rows.join('\n') + '\n');
  generate('Reference/LOCAL_EVIDENCE.md', '# Local verification evidence boundary\n\nThe source and engineering docs are preserved, but repeated local verification attempts are deliberately excluded from this public snapshot. Links in copied engineering docs to those local attempts are historical/local-only references, not public replay instructions or newly asserted acceptance. Original evidence remains in the source workspace; no source evidence is deleted by export.\n\nExcluded source prefixes:\n\n' + excludedPrefixes.map(p => '- `' + p + '`').join('\n') + '\n\nThe four required build/test fixtures remain included. The separately reviewed [portable ninth-parser fixture](Fixtures/ninth-parser/README.md) retains only its exact selected source closure and does not bundle or reinterpret the original receipt. Its independent integrity/replay checks do not certify the original historical acceptance run.\n\nThe historical demo APK is preserved byte-for-byte and is not the current preview or a store release. Live providers, backend deployment, signing and distribution acceptance remain separate.\n');
  const retainedFiles = existing.files.filter(name => !isManaged(name) && !generatedPaths.includes(name) && name !== manifestPath).map(name => descriptor(destination, name));
  const existingDescriptors = existing.files.map(name => descriptor(destination, name));
  for (const item of retainedFiles) validatePublished(item.path, fs.readFileSync(absolute(destination, item.path)));
  for (const name of [...writes.keys(), manifestPath]) need(!existing.directories.includes(name), 'Output would overwrite directory: ' + name);
  const contents = new Map([...writes].map(([name, item]) => [name, item.bytes]));
  for (const item of retainedFiles) contents.set(item.path, fs.readFileSync(absolute(destination, item.path)));
  assertExactPaths([...writes.keys(), ...retainedFiles.map(x => x.path), manifestPath], [...new Set([...writes.keys(), ...retainedFiles.map(x => x.path), manifestPath])], 'Overlapping publication inventory');
  files.sort((a, b) => a.path.localeCompare(b.path)); retainedFiles.sort((a, b) => a.path.localeCompare(b.path));
  const manifest = {version: 2, createdAt: new Date().toISOString(),
    scope: 'Explicit FeedMe source/reference snapshot. Local attempts excluded; portable parser fixture is not historical-run replay or app/release acceptance.',
    policy: {excludedPrefixes, requiredFixtures, historicalApk, sourceRoots}, files, generated, retainedFiles, excluded: excluded.sort()};
  const manifestBytes = Buffer.from(JSON.stringify(manifest, null, 2) + '\n'); validatePublished(manifestPath, manifestBytes);
  contents.set(manifestPath, manifestBytes); checkReferenceLinks(destination, contents);
  return {workspace, destination, inputs, existing, existingDescriptors, manifest, manifestBytes, writes};
}

export function exportSnapshot(sourceWorkspace, publicationRoot) {
  const plan = planSnapshot(sourceWorkspace, publicationRoot);
  // Recheck the full read set immediately before writes. The copy is not an atomic filesystem
  // transaction; an I/O failure may leave a partial staging tree, which must never be published.
  const current = inspectTree(plan.destination);
  assertExactPaths(current.files, plan.existing.files, 'Destination files changed during preflight');
  assertExactPaths(current.directories, plan.existing.directories, 'Destination directories changed during preflight');
  for (const item of plan.existingDescriptors) need(JSON.stringify(descriptor(plan.destination, item.path)) === JSON.stringify(item), 'Destination changed during preflight: ' + item.path);
  for (const item of plan.inputs) need(JSON.stringify(descriptor(plan.workspace, item.path)) === JSON.stringify(item), 'Source changed during preflight: ' + item.path);
  assertExactPaths(intendedSourcePaths(plan.workspace), plan.inputs.map(item => item.path), 'Intended source coverage changed during preflight');
  for (const [relative, item] of plan.writes) {
    const output = absolute(plan.destination, relative); fs.mkdirSync(path.dirname(output), {recursive: true});
    if (fs.existsSync(output)) fileInfo(output, relative);
    fs.writeFileSync(output, item.bytes, {mode: item.mode}); fs.chmodSync(output, item.mode);
  }
  fs.writeFileSync(absolute(plan.destination, manifestPath), plan.manifestBytes, {mode: 0o644});
  fs.chmodSync(absolute(plan.destination, manifestPath), 0o644);
  return {copied: plan.manifest.files.length, generated: plan.manifest.generated.length, retained: plan.manifest.retainedFiles.length,
    bytes: plan.manifest.files.reduce((sum, item) => sum + item.bytes, 0), exclusions: excludedPrefixes.length};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) throw new Error('Pass the existing workspace containing feedme/ and outputs/.');
  const destination = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  console.log(JSON.stringify(exportSnapshot(process.argv[2], destination)));
}
