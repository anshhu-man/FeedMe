import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';

// Explicit local publication copy only. No network, deletion, Git, credentials or deployment.
const destination = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
if (!process.argv[2]) throw new Error('Pass the existing workspace containing feedme/ and outputs/.');
const workspace = fs.realpathSync(process.argv[2]);
if (workspace === destination || workspace.startsWith(destination + path.sep)) throw new Error('Source must be outside publication tree.');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const excludedDirectories = new Set(['.git', '.gradle', '.kotlin', '.local', 'node_modules', 'build', 'xcuserdata', 'Pods']);
const excludedFile = name => name === '.DS_Store' || name === 'local.properties' || /^\.env(?:\.|$)/.test(name) || /\.(?:keystore|jks|p12|pfx|mobileprovision|pem|key)$/i.test(name);
const secrets = /-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{40,}\b|\bAKIA[A-Z0-9]{16}\b|\bAIza[0-9A-Za-z_-]{30,}\b/;
const manifest = {createdAt: new Date().toISOString(), scope: 'Explicit FeedMe publication snapshot; original files preserved. History archives have their own manifest.', files: [], excluded: []};
function copy(sourceRelative, publishedRelative) {
  const input = path.resolve(workspace, sourceRelative);
  if (!input.startsWith(workspace + path.sep)) throw new Error('Source escapes workspace.');
  const info = fs.lstatSync(input);
  if (info.isSymbolicLink()) throw new Error(`Symlink refused: ${sourceRelative}`);
  if (info.isDirectory()) {
    if (excludedDirectories.has(path.basename(input))) { manifest.excluded.push(sourceRelative + '/'); return; }
    for (const name of fs.readdirSync(input).sort()) copy(path.join(sourceRelative, name), path.join(publishedRelative, name));
    return;
  }
  if (!info.isFile()) throw new Error(`Non-regular input refused: ${sourceRelative}`);
  if (excludedFile(path.basename(input))) { manifest.excluded.push(sourceRelative); return; }
  const original = fs.readFileSync(input);
  if (original.length > 50 * 1024 * 1024) throw new Error(`Large file requires separate review: ${sourceRelative}`);
  let published = original, redactions = 0;
  if (!original.includes(0)) {
    const text = original.toString('utf8');
    if (!Buffer.from(text).equals(original)) throw new Error(`Unknown non-UTF8 file requires review: ${sourceRelative}`);
    if (secrets.test(text)) throw new Error(`Potential credential requires review: ${sourceRelative}`);
    const safe = text.replace(/\/Users\/(?!LOCAL_USER(?:\/|\b))[^/\s"'<>]+/g, () => { redactions++; return '/Users/LOCAL_USER'; })
      .replace(/%2fUsers%2f(?!LOCAL_USER)[A-Za-z0-9._-]+/gi, () => { redactions++; return '%2FUsers%2FLOCAL_USER'; });
    published = Buffer.from(safe);
  }
  const output = path.resolve(destination, publishedRelative);
  if (!output.startsWith(destination + path.sep)) throw new Error('Destination escapes publication tree.');
  fs.mkdirSync(path.dirname(output), {recursive: true});
  if (fs.existsSync(output) && !fs.lstatSync(output).isFile()) throw new Error('Refusing non-regular destination.');
  fs.writeFileSync(output, published, {mode: info.mode & 0o777});
  fs.chmodSync(output, info.mode & 0o777);
  manifest.files.push({source: sourceRelative, path: publishedRelative, originalBytes: original.length, originalSha256: sha(original), bytes: published.length, sha256: sha(published), homePathRedactions: redactions});
}
copy('feedme', 'feedme');
copy('outputs/biteclub_blueprint', 'outputs/biteclub_blueprint');
copy('outputs/biteclub_ui', 'outputs/biteclub_ui');
copy('outputs/tasteecho/BiteClub_Brand_Story.md', 'Reference/Brand/BiteClub_Brand_Story.md');
copy('feedme/apps/android/build/outputs/apk/debug/android-debug.apk', 'Reference/artifacts/FeedMe-android-demo-debug.apk');
manifest.files.sort((a, b) => a.path.localeCompare(b.path));
fs.writeFileSync(path.join(destination, 'Reference/SNAPSHOT_MANIFEST.json'), JSON.stringify(manifest, null, 2) + '\n');

const registry = JSON.parse(fs.readFileSync(path.join(destination, 'outputs/biteclub_blueprint/registry/screen_registry.json'), 'utf8'));
const screens = registry.screens;
if (!Array.isArray(screens) || screens.length !== 98) throw new Error('Expected all 98 screens.');
const rows = screens.map(screen => {
  if (!/^[A-Z][A-Z0-9_]*$/.test(screen.id)) throw new Error('Unexpected screen identity.');
  const title = String(screen.title || screen.name || screen.id).replaceAll('|', '\\|');
  return `| ${screen.id} | ${title} | [PNG](../outputs/biteclub_ui/screens/${screen.id}.png) | [Buttons and behavior](../outputs/biteclub_blueprint/screens/${screen.id}.md) |`;
});
fs.writeFileSync(path.join(destination, 'Reference/SCREEN_GALLERY.md'), '# All 98 FeedMe screens\n\nEach design is paired with its screen specification and button behavior. These are prototype designs, not completed native feature claims. Start at AUTH_WELCOME, then signup/login and onboarding. [Interactive viewing instructions](../outputs/biteclub_ui/README.md).\n\n| Screen | Name | Design | Specification |\n| --- | --- | --- | --- |\n' + rows.join('\n') + '\n');
console.log(JSON.stringify({copied: manifest.files.length, bytes: manifest.files.reduce((sum, item) => sum + item.bytes, 0), redactedFiles: manifest.files.filter(item => item.homePathRedactions).length, redactions: manifest.files.reduce((sum, item) => sum + item.homePathRedactions, 0), excluded: manifest.excluded.length}));
