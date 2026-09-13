// Run: node --test scripts/android-backup-policy.test.mjs
// Requires xmllint (provided by macOS; libxml2-utils on Linux).
// Configuration regression only: OEM backup/transfer behavior still needs device QA.
// Policy syntax: https://developer.android.com/identity/data/autobackup
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const main = new URL('../apps/android/src/main/', import.meta.url);
const manifest = new URL('AndroidManifest.xml', main);
const legacy = new URL('res/xml/backup_rules.xml', main);
const modern = new URL('res/xml/data_extraction_rules.xml', main);
const domains = [
  'root', 'file', 'database', 'sharedpref', 'external',
  'device_root', 'device_file', 'device_database', 'device_sharedpref',
];

function xpath(file, expression) {
  return execFileSync('xmllint', ['--nonet', '--xpath', expression, fileURLToPath(file)], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function androidAttribute(name) {
  return xpath(manifest,
    `string(/manifest/application/@*[namespace-uri()='http://schemas.android.com/apk/res/android' and local-name()='${name}'])`);
}

function assertAllDomainsExcluded(file, scope) {
  assert.equal(xpath(file, `count(${scope})`), '1', `${scope} must be explicit`);
  assert.equal(xpath(file, `count(${scope}/include)`), '0', 'No backup inclusions permitted');
  assert.equal(xpath(file, `count(${scope}/exclude)`), String(domains.length));
  for (const domain of domains) {
    assert.equal(xpath(file, `count(${scope}/exclude[@domain='${domain}' and @path='.'])`),
      '1', `${scope} must exclude the entire ${domain} domain exactly once`);
  }
}

test('manifest explicitly disables backups and references both Android policy formats', () => {
  assert.equal(androidAttribute('allowBackup'), 'false');
  assert.equal(androidAttribute('fullBackupContent'), '@xml/backup_rules');
  assert.equal(androidAttribute('dataExtractionRules'), '@xml/data_extraction_rules');
  assert.equal(androidAttribute('backupAgent'), '', 'Custom backup agents need separate review');
});

test('Android 8–11 policy excludes all credential- and device-protected storage domains', () => {
  assert.equal(xpath(legacy, 'name(/*)'), 'full-backup-content');
  assertAllDomainsExcluded(legacy, '/full-backup-content');
});

test('Android 12+ cloud and device-transfer policies match the older exclusion policy', () => {
  assert.equal(xpath(modern, 'name(/*)'), 'data-extraction-rules');
  assert.equal(xpath(modern, 'string(/data-extraction-rules/cloud-backup/@disableIfNoEncryptionCapabilities)'), 'true');
  assertAllDomainsExcluded(modern, '/data-extraction-rules/cloud-backup');
  assertAllDomainsExcluded(modern, '/data-extraction-rules/device-transfer');
  assert.equal(xpath(modern, 'count(//include)'), '0');
});
