// Optional public build-tool input only. Never import a host Gradle home/cache.
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

export const gradleDistribution = Object.freeze({
  path: 'feedme/deploy/build-inputs/gradle-8.14-all.zip',
  bytes: 224116304,
  sha256: 'efe9a3d147d948d7528a9887fa35abcf24ca1a43ad06439996490f77569b02d1',
  url: 'https://services.gradle.org/distributions/gradle-8.14-all.zip',
  cache: 'wrapper/dists/gradle-8.14-all/c2qonpi39x1mddn7hk5gh9iqj/gradle-8.14-all.zip',
});
const need = value => { if (!value) throw Error('Pinned Gradle distribution unavailable'); };

export function requirePinnedGradleProperties(text) {
  const fields = new Map();
  for (const line of text.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue;
    const at = line.indexOf('=');
    need(at > 0 && !fields.has(line.slice(0, at)));
    fields.set(line.slice(0, at), line.slice(at + 1));
  }
  need(fields.get('distributionUrl') === gradleDistribution.url.replace(':', '\\:') &&
    fields.get('distributionSha256Sum') === gradleDistribution.sha256 &&
    fields.get('distributionBase') === 'GRADLE_USER_HOME' && fields.get('distributionPath') === 'wrapper/dists' &&
    fields.get('zipStoreBase') === 'GRADLE_USER_HOME' && fields.get('zipStorePath') === 'wrapper/dists');
}

/** Bounded streaming hash; rejects symlinks, aliases and changes during inspection. */
export function inspectGradleDistribution(file) {
  need(path.isAbsolute(file) && fs.realpathSync(file) === file);
  const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    const before = fs.fstatSync(fd);
    need(before.isFile() && before.nlink === 1 && before.size === gradleDistribution.bytes);
    const hash = createHash('sha256'), buffer = Buffer.alloc(1024 * 1024);
    let total = 0, count;
    while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) {
      total += count;
      need(total <= gradleDistribution.bytes);
      hash.update(buffer.subarray(0, count));
    }
    const after = fs.fstatSync(fd), named = fs.lstatSync(file);
    need(total === gradleDistribution.bytes && hash.digest('hex') === gradleDistribution.sha256 &&
      after.size === before.size && after.mtimeMs === before.mtimeMs && after.ctimeMs === before.ctimeMs &&
      named.isFile() && named.dev === before.dev && named.ino === before.ino && named.nlink === 1 &&
      fs.realpathSync(file) === file);
    return {bytes: total, sha256: gradleDistribution.sha256};
  } finally { fs.closeSync(fd); }
}

/** The wrapper receives only its original ZIP: no extracted tree or .ok marker. */
export function stageGradleDistribution(workspace, gradleHome) {
  const source = path.join(workspace, gradleDistribution.path.slice('feedme/'.length));
  if (!fs.existsSync(source)) return false;
  requirePinnedGradleProperties(fs.readFileSync(path.join(workspace, 'gradle/wrapper/gradle-wrapper.properties'), 'utf8'));
  inspectGradleDistribution(source);
  need(path.isAbsolute(gradleHome) && !fs.existsSync(gradleHome) &&
    fs.realpathSync(path.dirname(gradleHome)) === path.dirname(gradleHome));
  const target = path.join(gradleHome, gradleDistribution.cache);
  fs.mkdirSync(path.dirname(target), {recursive: true, mode: 0o700});
  fs.copyFileSync(source, target, fs.constants.COPYFILE_EXCL);
  inspectGradleDistribution(target);
  inspectGradleDistribution(source);
  return true;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    need(process.argv.length === 2 && process.cwd() === '/workspace/feedme' &&
      process.env.GRADLE_USER_HOME === '/tmp/feedme-container-gradle');
    const staged = stageGradleDistribution(process.cwd(), process.env.GRADLE_USER_HOME);
    console.log(staged ? 'Pinned Gradle ZIP staged; wrapper verification remains required.' :
      'No staged Gradle ZIP; original wrapper download remains enabled.');
  } catch {
    console.error('Pinned Gradle distribution unavailable; build refused.');
    process.exitCode = 1;
  }
}
