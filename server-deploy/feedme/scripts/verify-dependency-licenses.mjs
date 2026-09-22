#!/usr/bin/env node
import { readFileSync, realpathSync } from 'node:fs';
import { isIP } from 'node:net';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCOPE = 'direct-version-catalog';
const LICENSES = new Set(['Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', 'MIT', 'PostgreSQL']);
// One exact upstream declaration, not general LicenseRef support or legal approval.
const GOOGLE_ID = Object.freeze({
  coordinate: 'com.google.android.libraries.identity.googleid:googleid', version: '1.2.1',
  license: 'LicenseRef-Android-SDK',
  pom: 'https://dl.google.com/dl/android/maven2/com/google/android/libraries/identity/googleid/googleid/1.2.1/googleid-1.2.1.pom',
  terms: 'https://developer.android.com/studio/terms.html',
});
const LIMITATIONS = Object.freeze([
  'Coverage is limited to aliases declared in gradle/libs.versions.toml, not every direct dependency.',
  'Hardcoded Gradle dependencies and compose.* / kotlin(...) plugin-provided libraries are outside this inventory.',
  'Resolved dependencies, transitives, and bundled native or other components are outside this inventory.',
  'Upstream-declared component licenses and evidence URL syntax are checked, not complete bundled notices or legal approval.',
  'No evidence URL is fetched; no artifact integrity, dependency resolution, or CVE/security scan is performed.',
  'googleid 1.2.1 is recorded under the Android SDK License, not an open-source license; free-tier use does not mean all dependencies are OSS.',
]);
const textOrder = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const identity = entry => `${entry.kind}:${entry.alias}`;
const plainObject = value => value !== null && typeof value === 'object' && !Array.isArray(value) &&
  (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
const exactKeys = (value, keys) => plainObject(value) &&
  Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
const nonempty = value => typeof value === 'string' && value.trim().length > 0 &&
  !/[\u0000-\u001f\u007f]/u.test(value);
const aliasPattern = /^[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)*$/u;
const libraryPattern = /^[A-Za-z0-9_][A-Za-z0-9_.-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*$/u;
const pluginPattern = /^[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*)+$/u;

function invalidCatalog(line, message) {
  throw new Error(`Version catalog line ${line}: ${message}`);
}

// Supported TOML is deliberately finite: three named tables, bare aliases, plain double-quoted
// strings, and single-line inline library/plugin tables. Other TOML features fail, not disappear.
function withoutComment(line, number) {
  let quoted = false;
  for (let index = 0; index < line.length; index++) {
    const character = line[index];
    if (quoted && character === '\\') invalidCatalog(number, 'escaped strings are outside the supported subset');
    if (character === '"') quoted = !quoted;
    if (!quoted && character === '#') return line.slice(0, index).trim();
  }
  if (quoted) invalidCatalog(number, 'unterminated or multiline string');
  return line.trim();
}

function scalar(raw, number) {
  if (!/^"[^"\\\u0000-\u001f\u007f]*"$/u.test(raw))
    invalidCatalog(number, 'expected a plain double-quoted string');
  return raw.slice(1, -1);
}

function inlineTable(raw, number) {
  if (!raw.startsWith('{') || !raw.endsWith('}')) invalidCatalog(number, 'expected a single-line inline table');
  const fields = new Map();
  let rest = raw.slice(1, -1).trim();
  if (!rest) invalidCatalog(number, 'empty dependency declaration');
  while (rest) {
    const match = /^([A-Za-z][A-Za-z0-9_.-]*)\s*=\s*("[^"\\\u0000-\u001f\u007f]*")/u.exec(rest);
    if (!match) invalidCatalog(number, 'unsupported inline-table syntax');
    if (fields.has(match[1])) invalidCatalog(number, `duplicate inline key ${match[1]}`);
    fields.set(match[1], scalar(match[2], number));
    rest = rest.slice(match[0].length).trim();
    if (!rest) break;
    if (!rest.startsWith(',')) invalidCatalog(number, 'unconsumed inline-table syntax');
    rest = rest.slice(1).trim();
    if (!rest) invalidCatalog(number, 'trailing inline-table comma');
  }
  return fields;
}

function pinnedVersion(version, number) {
  if (!/^\d+(?:\.[A-Za-z0-9]+)*(?:[-_][A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*)?$/u.test(version) ||
      /(?:^|[._-])(?:snapshot|changing|latest|release)(?:$|[._-])/iu.test(version))
    invalidCatalog(number, `version must be a fixed, non-changing literal: ${version}`);
  return version;
}

/** Pure strict parser. Returns resolved direct aliases sorted by kind/alias, or throws. */
export function parseVersionCatalog(text) {
  if (typeof text !== 'string' || text.length > 1_048_576) throw new Error('Version catalog must be bounded text');
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(text)) throw new Error('Version catalog contains an unsupported control character');
  if (text.includes('\r') && /\r(?!\n)/u.test(text)) throw new Error('Version catalog contains a bare carriage return');
  const tables = new Map();
  const aliases = new Map();
  let current;
  for (const [offset, raw] of text.split(/\r?\n/u).entries()) {
    const number = offset + 1;
    const line = withoutComment(raw, number);
    if (!line) continue;
    const section = /^\[([^\]]+)\]$/u.exec(line);
    if (section) {
      if (!['versions', 'libraries', 'plugins'].includes(section[1])) invalidCatalog(number, 'unsupported table');
      if (tables.has(section[1])) invalidCatalog(number, `duplicate table ${section[1]}`);
      current = section[1];
      tables.set(current, new Map()); aliases.set(current, new Set());
      continue;
    }
    if (!current) invalidCatalog(number, 'declaration outside a supported table');
    const declaration = /^([^=]+?)\s*=\s*(.+)$/u.exec(line);
    if (!declaration) invalidCatalog(number, 'unsupported declaration');
    const name = declaration[1].trim();
    if (!aliasPattern.test(name)) invalidCatalog(number, `unsupported alias ${name}`);
    const normalized = name.replace(/[-_]/gu, '.');
    if (tables.get(current).has(name) || aliases.get(current).has(normalized))
      invalidCatalog(number, `duplicate alias or Gradle accessor collision ${name}`);
    aliases.get(current).add(normalized);
    const value = current === 'versions' ? scalar(declaration[2], number) : inlineTable(declaration[2], number);
    tables.get(current).set(name, { value, number });
  }
  for (const table of ['versions', 'libraries', 'plugins'])
    if (!tables.has(table)) throw new Error(`Version catalog missing [${table}]`);
  for (const table of ['libraries', 'plugins'])
    if (tables.get(table).size === 0) throw new Error(`Version catalog [${table}] must not be empty`);
  const versions = tables.get('versions');
  for (const { value, number } of versions.values()) pinnedVersion(value, number);
  const dependencies = [];
  for (const [table, kind, coordinateKey, pattern] of [
    ['libraries', 'library', 'module', libraryPattern], ['plugins', 'plugin', 'id', pluginPattern],
  ]) {
    for (const [alias, { value: fields, number }] of tables.get(table)) {
      if (fields.size !== 2 || !fields.has(coordinateKey) ||
          (fields.has('version') === fields.has('version.ref')))
        invalidCatalog(number, `expected only ${coordinateKey} and one of version/version.ref`);
      const coordinate = fields.get(coordinateKey);
      if (!pattern.test(coordinate)) invalidCatalog(number, 'invalid dependency coordinate');
      const reference = fields.get('version.ref');
      if (reference !== undefined && !versions.has(reference)) invalidCatalog(number, `unresolved version reference ${reference}`);
      const version = pinnedVersion(reference === undefined ? fields.get('version') : versions.get(reference).value, number);
      dependencies.push({ kind, alias, coordinate, version });
    }
  }
  return dependencies.sort((a, b) => textOrder(identity(a), identity(b)));
}

function validDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/u.test(value) || value.startsWith('0000')) return false;
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
}

function publicEvidenceUrl(value) {
  if (typeof value !== 'string' || /[\u0000-\u0020\u007f]/u.test(value)) return false;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash) return false;
    const hostname = url.hostname.toLowerCase().replace(/\.$/u, '');
    if (isIP(hostname) || hostname.startsWith('[') || !hostname.includes('.') ||
        /(?:^|\.)(?:localhost|local|internal|invalid|test|example|home|lan|onion|arpa)$/u.test(hostname) ||
        /(?:^|\.)example\.(?:com|net|org)$/u.test(hostname)) return false;
    if (![...url.searchParams.keys()].every(key => !/(?:token|secret|password|credential|authorization|signature|api[-_]?key)/iu.test(key))) return false;
    return /^[a-z0-9.-]+$/u.test(hostname) && hostname.split('.').every(label =>
      /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/u.test(label));
  } catch { return false; }
}

/** Pure coverage/metadata check, not a legal opinion or verification of remote license contents. */
export function verifyDependencyLicenses(input = {}) {
  const errors = [];
  const error = (code, path, message) => errors.push({ code, path, message });
  if (!exactKeys(input, ['catalogText', 'inventory'])) error('input-schema', 'inputs', 'Expected catalogText and inventory arguments');
  const catalogText = plainObject(input) ? input.catalogText : undefined;
  const inventory = plainObject(input) ? input.inventory : undefined;
  let catalog = [];
  try { catalog = parseVersionCatalog(catalogText); }
  catch (failure) { error('catalog-invalid', 'catalog', failure.message); }
  if (!exactKeys(inventory, ['schemaVersion', 'scope', 'dependencies']) ||
      inventory.schemaVersion !== 1 || inventory.scope !== SCOPE || !Array.isArray(inventory.dependencies)) {
    error('inventory-schema', 'inventory', 'Expected exact schemaVersion:1, scope:direct-version-catalog, and dependencies array');
  } else {
    const expected = new Map(catalog.map(entry => [identity(entry), entry]));
    const seen = new Set();
    inventory.dependencies.forEach((entry, index) => {
      const path = `dependencies[${index}]`;
      if (!exactKeys(entry, ['kind', 'alias', 'coordinate', 'version', 'license', 'evidence', 'reviewedAt'])) {
        error('entry-schema', path, 'Dependency entry keys must exactly match the schema');
        return;
      }
      if (!['library', 'plugin'].includes(entry.kind) || typeof entry.alias !== 'string' || !aliasPattern.test(entry.alias)) {
        error('entry-identity', path, 'Expected library/plugin kind and supported alias');
        return;
      }
      const key = identity(entry);
      if (seen.has(key)) error('duplicate-entry', path, `Duplicate inventory identity ${key}`);
      seen.add(key);
      const dependency = expected.get(key);
      if (!dependency) error('orphan-entry', path, `No catalog alias ${key}`);
      else for (const field of ['coordinate', 'version']) {
        if (entry[field] !== dependency[field]) error('catalog-mismatch', `${path}.${field}`, `Does not match catalog ${key}`);
      }
      const exactGoogleId = entry.kind === 'library' && entry.coordinate === GOOGLE_ID.coordinate && entry.version === GOOGLE_ID.version;
      if (exactGoogleId ? entry.license !== GOOGLE_ID.license : !LICENSES.has(entry.license))
        error('unsupported-license', `${path}.license`, 'Expected a supported SPDX identifier or the exact reviewed googleid 1.2.1 Android SDK License declaration');
      if (exactGoogleId && entry.license === GOOGLE_ID.license &&
          ![GOOGLE_ID.pom, GOOGLE_ID.terms].every(url => Array.isArray(entry.evidence) && entry.evidence.some(item => item?.url === url)))
        error('android-sdk-evidence', `${path}.evidence`, 'Exact googleid 1.2.1 POM and Android SDK terms evidence URLs are required');
      if (!validDate(entry.reviewedAt)) error('review-date', `${path}.reviewedAt`, 'Expected a valid Gregorian YYYY-MM-DD date');
      if (!Array.isArray(entry.evidence) || entry.evidence.length === 0) error('evidence-empty', `${path}.evidence`, 'License evidence is required');
      else entry.evidence.forEach((evidence, evidenceIndex) => {
        if (!exactKeys(evidence, ['url', 'description']) || !publicEvidenceUrl(evidence.url) || !nonempty(evidence.description))
          error('evidence-invalid', `${path}.evidence[${evidenceIndex}]`, 'Expected a credential-free public HTTPS URL and nonempty description');
      });
    });
    for (const dependency of catalog) if (!seen.has(identity(dependency)))
      error('missing-entry', 'dependencies', `Missing catalog alias ${identity(dependency)}`);
  }
  errors.sort((a, b) => textOrder(`${a.path}:${a.code}:${a.message}`, `${b.path}:${b.code}:${b.message}`));
  return {
    ok: errors.length === 0, scope: SCOPE,
    counts: { catalog: catalog.length, libraries: catalog.filter(entry => entry.kind === 'library').length,
      plugins: catalog.filter(entry => entry.kind === 'plugin').length,
      inventory: Array.isArray(inventory?.dependencies) ? inventory.dependencies.length : 0 },
    errors, limitations: [...LIMITATIONS],
  };
}

function main() {
  try {
    if (process.argv.length !== 2) throw new Error('No arguments supported: inputs are fixed relative to this script');
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
    const catalogText = readFileSync(resolve(root, 'gradle/libs.versions.toml'), 'utf8');
    const inventory = JSON.parse(readFileSync(resolve(root, 'docs/DEPENDENCY_LICENSES.json'), 'utf8'));
    const report = verifyDependencyLicenses({ catalogText, inventory });
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
    process.exitCode = report.ok ? 0 : 1;
  } catch (failure) {
    process.stdout.write(`${JSON.stringify({ ok: false, scope: SCOPE, errors: [{ code: 'input-invalid', path: 'inputs', message: failure.message }], limitations: [...LIMITATIONS] }, null, 2)}\n`);
    process.exitCode = 1;
  }
}

// Resolve an entrypoint symlink too, so invoking the checker through one cannot silently pass.
let invokedAsMain = false;
try { invokedAsMain = Boolean(process.argv[1]) && realpathSync(resolve(process.argv[1])) === fileURLToPath(import.meta.url); }
catch { /* Import from an eval/virtual runner is not CLI execution. */ }
if (invokedAsMain) main();
