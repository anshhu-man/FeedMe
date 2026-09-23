// Local operator launcher, not migration-on-start. No credential/target overrides or retries.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash, X509Certificate} from 'node:crypto';
import {spawnSync} from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const javaHome = '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home';
const java = path.join(javaHome, 'bin/java');
const cleanEnvironment = {PATH: `${javaHome}/bin:/usr/bin:/bin`, JAVA_HOME: javaHome};
const project = 'bljskfhazmnzmhkbkcev';
const caFingerprint = '807025ad50d4ed219d2c9c7d299c004f824eb00cf7f65afef607d07b72e6cafa';
const fail = message => { throw new Error(message); };
const need = (value, message) => { if (!value) fail(message); };
const privateMessage = 'Local database inputs are missing or unsafe. Put the existing database password in .local/supabase/database-password and the reviewed public CA in .local/supabase/prod-ca-2021.crt; use owner-only files (0600) and an owner-only supabase directory (0700). Do not paste credentials into commands or source. No connection was attempted.';
const packageMessage = 'Current server distribution is unavailable or stale. Explicitly build :server:installDist with JDK 17 from the current source, then retry this launcher. No connection was attempted.';
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export function migrationOperation(args) {
  if (args.length === 0 || (args.length === 1 && args[0] === '--check')) return '--check';
  if (args.length === 1 && args[0] === '--apply') return '--apply';
  fail('Use node deploy/supabase-migrate.mjs [--check|--apply]. No other arguments or target overrides are accepted.');
}

export function decodeDatabasePassword(bytes) {
  try {
    need(Buffer.isBuffer(bytes) && bytes.length > 0 && bytes.length <= 16386, privateMessage);
    // A single text-file line ending is not part of the credential; other whitespace is preserved.
    const value = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(bytes).replace(/\r?\n$/, '');
    need(value.length >= 1 && value.length <= 4096 && value.trim().length > 0 &&
      !/[\x00-\x1f\x7f-\x9f\ufeff]/u.test(value), privateMessage);
    return value;
  } catch { fail(privateMessage); }
}

export function validateSupabaseCertificate(bytes) {
  const message = 'The local public CA does not match the reviewed Supabase CA certificate. No connection was attempted.';
  try {
    need(Buffer.isBuffer(bytes) && bytes.length > 0 && bytes.length <= 16384, message);
    const pem = new TextDecoder('utf-8', {fatal: true}).decode(bytes);
    need(/^\s*-----BEGIN CERTIFICATE-----\r?\n[A-Za-z0-9+/=\r\n]+\r?\n-----END CERTIFICATE-----\s*$/.test(pem), message);
    const certificate = new X509Certificate(pem);
    need(certificate.ca && digest(certificate.raw) === caFingerprint, message);
    return caFingerprint; // X.509 DER identity, deliberately not the PEM file hash.
  } catch { fail(message); }
}

export function migrationEnvironment(password, certificatePath) {
  return {...cleanEnvironment,
    FEEDME_MIGRATION_ENVIRONMENT: 'production',
    FEEDME_MIGRATION_DB_HOST: 'aws-0-ap-southeast-2.pooler.supabase.com',
    FEEDME_MIGRATION_DB_PORT: '5432', FEEDME_MIGRATION_DB_NAME: 'postgres',
    FEEDME_MIGRATION_DB_USER: `postgres.${project}`, FEEDME_MIGRATION_DB_PASSWORD: password,
    FEEDME_MIGRATION_DB_SSL_ROOT_CERT: certificatePath,
  };
}

function regular(file, maximum, message, ownerOnly = false) {
  let fd; let buffer;
  try {
    const before = fs.lstatSync(file);
    const valid = stat => stat.isFile() && !stat.isSymbolicLink() && stat.nlink === 1 &&
      stat.size > 0 && stat.size <= maximum && (!ownerOnly ||
        (stat.uid === process.getuid() && (stat.mode & 0o077) === 0 && (stat.mode & 0o400) !== 0));
    need(valid(before), message);
    fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
    const opened = fs.fstatSync(fd);
    need(valid(opened) && opened.ino === before.ino && opened.dev === before.dev, message);
    buffer = Buffer.alloc(opened.size + 1);
    let count = 0;
    while (count < buffer.length) {
      const read = fs.readSync(fd, buffer, count, buffer.length - count, null);
      if (!read) break;
      count += read;
    }
    const after = fs.fstatSync(fd);
    need(count === opened.size && after.size === opened.size && after.mtimeMs === opened.mtimeMs &&
      after.ctimeMs === opened.ctimeMs, message);
    return {bytes: Buffer.from(buffer.subarray(0, count)), mtimeMs: after.mtimeMs};
  } catch { fail(message); }
  finally { buffer?.fill(0); if (fd !== undefined) fs.closeSync(fd); }
}

function directory(relative, message, ownerOnly = false) {
  let current = root;
  try {
    for (const part of relative.split('/')) {
      current = path.join(current, part);
      const stat = fs.lstatSync(current);
      need(stat.isDirectory() && !stat.isSymbolicLink() && fs.realpathSync(current) === current, message);
      if (current === path.join(root, relative) && ownerOnly)
        need(stat.uid === process.getuid() && (stat.mode & 0o077) === 0, message);
    }
    return current;
  } catch { fail(message); }
}

function localCommand(command, args, maximum = 2 * 1024 * 1024) {
  const result = spawnSync(command, args, {cwd: root, env: cleanEnvironment, timeout: 10000,
    maxBuffer: maximum, stdio: ['ignore', 'pipe', 'pipe']});
  need(!result.error && result.status === 0, packageMessage);
  return result.stdout;
}

function currentDistribution() {
  const version = spawnSync(java, ['-version'], {env: cleanEnvironment, timeout: 10000, maxBuffer: 16384});
  need(!version.error && version.status === 0 && /version "17(?:\.|"|\+)/.test(version.stderr.toString()),
    'The fixed JDK 17 installation is unavailable. No connection was attempted.');
  const lib = directory('server/build/install/server/lib', packageMessage);
  const names = fs.readdirSync(lib).sort();
  need(names.length > 0 && names.length <= 256 && names.includes('server.jar') &&
    names.every(name => /^[A-Za-z0-9_.+-]+\.jar$/.test(name)), packageMessage);
  for (const name of names) {
    const stat = fs.lstatSync(path.join(lib, name));
    need(stat.isFile() && !stat.isSymbolicLink() && stat.nlink === 1 && stat.size > 0 && stat.size <= 128 * 1024 * 1024, packageMessage);
  }
  const server = regular(path.join(lib, 'server.jar'), 128 * 1024 * 1024, packageMessage);
  const built = regular(path.join(directory('server/build/libs', packageMessage), 'server.jar'), 128 * 1024 * 1024, packageMessage);
  need(server.bytes.equals(built.bytes), packageMessage);
  // Ordinary stale-build guard for the actual CLI/engine/config, not a full source attestation.
  for (const source of ['db/PlatformMigrationMain.kt', 'db/PlatformMigrations.kt', 'config/PlatformMigrationConfig.kt'])
    need(regular(path.join(root, 'server/src/main/kotlin/com/feedme/server', source), 2 * 1024 * 1024, packageMessage).mtimeMs <= built.mtimeMs, packageMessage);
  const jar = path.join(lib, 'server.jar');
  const entries = localCommand('/usr/bin/unzip', ['-Z1', jar]).toString('utf8').split(/\r?\n/).filter(Boolean);
  need(new Set(entries).size === entries.length && entries.includes('com/feedme/server/db/PlatformMigrationMainKt.class'), packageMessage);
  const migrations = directory('server/src/main/resources/db/migration', packageMessage);
  const sql = fs.readdirSync(migrations).filter(name => /^V[0-9]{3}__.*\.sql$/.test(name)).sort();
  need(JSON.stringify(entries.filter(name => /^db\/migration\/.*\.sql$/.test(name)).sort()) ===
    JSON.stringify(sql.map(name => `db/migration/${name}`)), packageMessage);
  for (const name of sql) need(localCommand('/usr/bin/unzip', ['-p', jar, `db/migration/${name}`]).equals(
    regular(path.join(migrations, name), 2 * 1024 * 1024, packageMessage).bytes), packageMessage);
  need(localCommand('/usr/bin/unzip', ['-p', jar, 'feedme-openapi.json']).equals(regular(
    path.resolve(root, '../outputs/biteclub_blueprint/architecture/04_API_Contract.json'), 2 * 1024 * 1024, packageMessage).bytes), packageMessage);
  return {classpath: names.map(name => path.join(lib, name)).join(path.delimiter), sha256: digest(server.bytes)};
}

export function safeMigrationResult(mode, child) {
  const code = child.error || child.signal || ![0, 1, 2, 3, 4, 130].includes(child.status) ? 1 : child.status;
  const status = code === 0 ? (mode === '--apply' ? 'migrations-completed' : 'history-current') :
    mode === '--check' && code === 3 ? 'migrations-pending' : mode === '--check' && code === 4 ? 'history-incompatible' :
    code === 2 ? 'configuration-refused' : 'failed-or-interrupted';
  const report = {project, mode, status, exitCode: code};
  // Never echo driver/JVM output or exception messages. Only canonical integer lists are retained.
  if (mode === '--check' && [0, 3].includes(code)) {
    // PlatformMigrationMain prints successful CURRENT to stdout, but nonzero PENDING to stderr.
    const output = (code === 0 ? child.stdout : child.stderr)?.toString('utf8') ?? '';
    for (const [label, key] of [['Applied', 'appliedVersions'], ['Pending', 'pendingVersions']]) {
      const match = new RegExp(`^${label} versions: (none|[0-9]+(?:,[0-9]+)*)$`, 'm').exec(output);
      if (match) report[key] = match[1] === 'none' ? [] : match[1].split(',').map(Number);
    }
  }
  return report;
}

// Shared synchronous operator boundary for explicit fixed-project deployment tools.
// Imports do not read credentials or connect. Callers must not print/retain the environment.
export function withSupabaseOperatorEnvironment(action) {
  directory('.local/supabase', privateMessage, true);
  const certificatePath = path.join(root, '.local/supabase/prod-ca-2021.crt');
  let passwordBytes; let env;
  try {
    passwordBytes = regular(path.join(root, '.local/supabase/database-password'), 16386, privateMessage, true).bytes;
    validateSupabaseCertificate(regular(certificatePath, 16384, privateMessage, true).bytes);
    env = migrationEnvironment(decodeDatabasePassword(passwordBytes), certificatePath);
    return action(env);
  } finally {
    passwordBytes?.fill(0);
    if (env) delete env.FEEDME_MIGRATION_DB_PASSWORD;
  }
}

function main(args) {
  const mode = migrationOperation(args);
  directory('.local/supabase', privateMessage, true);
  const passwordFile = path.join(root, '.local/supabase/database-password');
  const certificatePath = path.join(root, '.local/supabase/prod-ca-2021.crt');
  let passwordBytes; let env;
  try {
    passwordBytes = regular(passwordFile, 16386, privateMessage, true).bytes;
    const password = decodeDatabasePassword(passwordBytes);
    validateSupabaseCertificate(regular(certificatePath, 16384, privateMessage, true).bytes);
    const distribution = currentDistribution();
    env = migrationEnvironment(password, certificatePath);
    process.stdout.write(`Supabase ${project}: ${mode}; verify-full; no automatic retry.\n`);
    const child = spawnSync(java, ['-XX:-HeapDumpOnOutOfMemoryError', '-XX:ErrorFile=/dev/null', '-cp',
      distribution.classpath, 'com.feedme.server.db.PlatformMigrationMainKt', mode], {
      cwd: root, env, timeout: 15 * 60 * 1000, maxBuffer: 256 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    });
    const report = {...safeMigrationResult(mode, child), serverJarSha256: distribution.sha256, caDerSha256: caFingerprint};
    process.stdout.write(`${JSON.stringify(report)}\n`);
    process.stdout.write(report.exitCode === 0 || (mode === '--check' && [3, 4].includes(report.exitCode))
      ? 'Migration history is not physical-schema, service deployment, or product-readiness proof.\n'
      : 'No retry was attempted. After an apply failure/interruption, the outcome may be unknown; inspect with --check before considering any deliberate retry.\n');
    return report.exitCode;
  } finally {
    passwordBytes?.fill(0);
    if (env) delete env.FEEDME_MIGRATION_DB_PASSWORD;
    // Immutable JS/JVM/process-environment strings cannot be guaranteed zeroized.
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exitCode = main(process.argv.slice(2)); }
  catch (error) {
    const allowed = [privateMessage, packageMessage, 'The local public CA does not match the reviewed Supabase CA certificate. No connection was attempted.',
      'The fixed JDK 17 installation is unavailable. No connection was attempted.',
      'Use node deploy/supabase-migrate.mjs [--check|--apply]. No other arguments or target overrides are accepted.'];
    process.stderr.write(`${allowed.includes(error.message) ? error.message : 'Migration launcher failed; private diagnostics were not printed. No automatic retry was attempted.'}\n`);
    process.exitCode = 2;
  }
}
