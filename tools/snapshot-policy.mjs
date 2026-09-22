import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {sanitizeDiagnostics} from './sanitize-diagnostics.mjs';

export const sourceRoots = [
  ['feedme', 'feedme'],
  ['outputs/biteclub_blueprint', 'outputs/biteclub_blueprint'],
  ['outputs/biteclub_ui', 'outputs/biteclub_ui'],
  ['outputs/tasteecho/BiteClub_Brand_Story.md', 'Reference/Brand/BiteClub_Brand_Story.md'],
];
export const excludedPrefixes = [
  'feedme/docs/verification/ui-ux',
  'feedme/docs/verification/social-draft-publication',
  'feedme/docs/verification/cookbook-processor',
  'feedme/docs/verification/recalled-copy-drafts',
  'feedme/docs/verification/progress-cookbook-timers',
  'feedme/docs/verification/cookbook-timer-media',
];
export const requiredFixtures = [
  'feedme/docs/verification/canonical-validation/format-corpus.json',
  'feedme/docs/verification/contract-artifacts/generated-receipt.json',
  'feedme/docs/verification/schema-validator-spike/cases.json',
  'feedme/docs/verification/release-scope-report.json',
];
export const historicalApk = {path: 'Reference/artifacts/FeedMe-android-demo-debug.apk', bytes: 18981844,
  sha256: 'bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805'};
export const manifestPath = 'Reference/SNAPSHOT_MANIFEST.json';
export const generatedPaths = ['Reference/SCREEN_GALLERY.md', 'Reference/LOCAL_EVIDENCE.md'];
export const maxBytes = 50 * 1024 * 1024;
const ignoredDirectories = new Set(['.git', '.gradle', '.kotlin', '.local', 'node_modules', 'build', 'xcuserdata', 'Pods']);
const ignoredFile = name => name === '.DS_Store' || name === 'local.properties' || /^\.env(?:\.|$)/.test(name) || /\.(?:keystore|jks|p12|pfx|mobileprovision|pem|key)$/i.test(name);
const secrets = /-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b|\bAKIA[A-Z0-9]{16}\b|\bAIza[0-9A-Za-z_-]{30,}\b/;
// macOS home paths use a case-sensitive `/Users/` segment. Keep percent-escape
// hex case flexible without treating ordinary lowercase HTTP `/users/` routes as
// local home directories.
const privateHomes = [/\/Users\/(?!LOCAL_USER(?:\/|\b))[^/\s"'<>]+/,
  /%2fUsers%2f(?!LOCAL_USER(?:%2f|\b))[A-Za-z0-9._-]+/i];
const hasPrivateHome = text => privateHomes.some(pattern => pattern.test(text));
export const need = (value, message) => { if (!value) throw new Error(message); };
export const sha = bytes => createHash('sha256').update(bytes).digest('hex');
export const under = (name, prefix) => name === prefix || name.startsWith(prefix + '/');
export function safeRelative(name) {
  need(typeof name === 'string' && name.length > 0 && !path.isAbsolute(name) && !/[\\\x00-\x1f\x7f]/.test(name) &&
    name.split('/').every(part => part && part !== '.' && part !== '..'), 'Unsafe relative path');
  return name;
}
export const isExcluded = name => excludedPrefixes.some(prefix => under(name, prefix));
export const isManaged = name => sourceRoots.some(([, prefix]) => under(name, prefix));
export const ignoredInput = (name, directory) => isExcluded(name) || (directory ? ignoredDirectories.has(path.posix.basename(name)) : ignoredFile(path.posix.basename(name)));
export function canonicalRoot(directory) {
  const root = path.resolve(directory), info = fs.lstatSync(root);
  need(info.isDirectory() && !info.isSymbolicLink() && fs.realpathSync(root) === root, 'Canonical regular root required');
  return root;
}
export function absolute(root, relative) {
  safeRelative(relative); const result = path.resolve(root, relative);
  need(result.startsWith(path.resolve(root) + path.sep), 'Path escapes root');
  let parent = path.resolve(root);
  for (const part of relative.split('/').slice(0, -1)) {
    parent = path.join(parent, part);
    if (fs.existsSync(parent)) { const info = fs.lstatSync(parent); need(info.isDirectory() && !info.isSymbolicLink(), 'Unsafe ancestor: ' + relative); }
  }
  return result;
}
export function fileInfo(filename, label) {
  const info = fs.lstatSync(filename);
  need(info.isFile() && !info.isSymbolicLink() && info.nlink === 1, 'Non-regular/shared-link file: ' + label);
  need(info.size <= maxBytes && (info.mode & 0o7000) === 0, 'Unreviewed size/mode: ' + label);
  return info;
}
export function descriptor(root, relative) {
  const file = absolute(root, relative), info = fileInfo(file, relative), bytes = fs.readFileSync(file);
  return {path: relative, bytes: bytes.length, sha256: sha(bytes), mode: info.mode & 0o777};
}
export function inspectTree(root) {
  const files = [], directories = [];
  function walk(relative) {
    const filename = relative ? absolute(root, relative) : root, info = fs.lstatSync(filename);
    need(!info.isSymbolicLink(), 'Symlink refused: ' + relative);
    if (relative === '.git') return; // The publication repository's own Git metadata is never exported.
    need(info.isFile() || info.isDirectory(), 'Special file refused: ' + relative);
    if (info.isDirectory()) {
      if (relative) directories.push(relative);
      for (const name of fs.readdirSync(filename).sort()) walk(relative ? relative + '/' + name : name);
    } else files.push(relative);
  }
  walk(''); return {files, directories};
}
export function validatePublished(relative, bytes) {
  safeRelative(relative);
  need(!isExcluded(relative), 'Excluded local evidence present: ' + relative);
  need(!relative.split('/').some(part => ignoredDirectories.has(part)) && !ignoredFile(path.posix.basename(relative)), 'Private/generated publication path: ' + relative);
  need(bytes.length <= maxBytes, 'Unreviewed large file: ' + relative);
  // Inspect raw ASCII as well as UTF-8 text; NUL bytes never exempt obvious credentials/home paths.
  need(!secrets.test(bytes.toString('latin1')) && !hasPrivateHome(bytes.toString('latin1')), 'Private data requires review: ' + relative);
  if (!bytes.includes(0)) {
    const text = bytes.toString('utf8'); need(Buffer.from(text).equals(bytes), 'Invalid UTF-8: ' + relative);
    need(!secrets.test(text) && !hasPrivateHome(text), 'Private text requires review: ' + relative);
    need(sanitizeDiagnostics(relative, text).unrelatedInstrumentationRedactions === 0, 'Unrelated instrumentation inventory: ' + relative);
  }
}
export function publishBytes(relative, original) {
  let published = original, homePathRedactions = 0, unrelatedInstrumentationRedactions = 0;
  if (!original.includes(0)) {
    const text = original.toString('utf8'); need(Buffer.from(text).equals(original), 'Invalid source UTF-8: ' + relative);
    need(!secrets.test(text), 'Potential source credential: ' + relative);
    const safe = text.replace(/\/Users\/(?!LOCAL_USER(?:\/|\b))[^/\s"'<>]+/g, () => { homePathRedactions++; return '/Users/LOCAL_USER'; })
      .replace(/%2fUsers%2f(?!LOCAL_USER(?:%2f|\b))[A-Za-z0-9._-]+/gi, () => { homePathRedactions++; return '%2FUsers%2FLOCAL_USER'; });
    const minimized = sanitizeDiagnostics(relative, safe); unrelatedInstrumentationRedactions = minimized.unrelatedInstrumentationRedactions;
    published = Buffer.from(minimized.text);
  }
  validatePublished(relative, published);
  return {published, homePathRedactions, unrelatedInstrumentationRedactions};
}
export function assertExactPaths(actual, expected, label) {
  need(new Set(expected).size === expected.length && new Set(actual).size === actual.length &&
    JSON.stringify([...actual].sort()) === JSON.stringify([...expected].sort()), label);
}
export function requireFixtures(paths) {
  for (const name of requiredFixtures) need(paths.includes(name), 'Missing required build fixture: ' + name);
  need(paths.includes('feedme/gradlew'), 'Missing executable Gradle wrapper');
}
export function checkReferenceLinks(root, contents) {
  let checked = 0; const available = new Set(contents.keys());
  for (const name of contents.keys()) for (let parent = path.posix.dirname(name); parent !== '.'; parent = path.posix.dirname(parent)) available.add(parent);
  for (const [name, bytes] of contents) {
    if (name !== 'README.md' && !(name.startsWith('Reference/') && name.endsWith('.md'))) continue;
    for (const match of bytes.toString('utf8').matchAll(/\]\(([^)]+)\)|\bsrc="([^"]+)"/g)) {
      const target = match[1] || match[2]; if (/^(?:https?:|mailto:|#)/.test(target)) continue;
      const resolved = path.resolve(path.dirname(absolute(root, name)), decodeURIComponent(target.split(/[?#]/)[0]));
      need(resolved.startsWith(path.resolve(root) + path.sep) && available.has(path.relative(root, resolved).split(path.sep).join('/')), 'Broken/escaping reference link: ' + name + ': ' + target);
      checked++;
    }
  }
  return checked;
}
export function intendedSourcePaths(workspace) {
  const files = [];
  function walk(name) {
    const info = fs.lstatSync(absolute(workspace, name));
    need(!info.isSymbolicLink() && (info.isFile() || info.isDirectory()), 'Unsafe source path: ' + name);
    if (ignoredInput(name, info.isDirectory())) return;
    if (info.isDirectory()) for (const child of fs.readdirSync(absolute(workspace, name)).sort()) walk(name + '/' + child);
    else files.push(name);
  }
  for (const [source] of sourceRoots) walk(source);
  return files;
}
