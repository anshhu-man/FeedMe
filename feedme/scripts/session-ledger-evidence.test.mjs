import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

// Evaluate only the pure evidence parser, never either runner's build/device entry point.
const parsers = ['android-storage-smoke.mjs', 'verify-session-ledger-acknowledgements.mjs'].map(name => {
  const source = fs.readFileSync(fileURLToPath(new URL(name, import.meta.url)), 'utf8');
  const declaration = source.match(/function ledgerSyncFailureEvidence\(log, identities\) \{[\s\S]*?\n\}/)?.[0];
  assert.ok(declaration, `${name} must retain the pure ledger evidence parser`);
  return vm.runInNewContext(`(${declaration})`, Object.create(null), {timeout: 1000});
});

const expected = [
  ['control-journal', 'controlJournalSyncFailureCannotAuthorizeCredentialAbort', 1034],
  ['control-database', 'controlDatabaseSyncFailureCannotAuthorizeCredentialAbort', 1034],
  ['control-directory', 'controlDirectorySyncFailureCannotAuthorizeCredentialAbort', 1290],
  ['work-reservation-journal', 'workReservationJournalSyncFailureCannotInstallNativeWork', 1034],
  ['work-reservation-database', 'workReservationDatabaseSyncFailureCannotInstallNativeWork', 1034],
  ['work-reservation-directory', 'workReservationDirectorySyncFailureCannotInstallNativeWork', 1290],
  ['work-retirement-journal', 'workRetirementJournalSyncFailureCannotCancelNativeWork', 1034],
  ['work-retirement-database', 'workRetirementDatabaseSyncFailureCannotCancelNativeWork', 1034],
  ['work-retirement-directory', 'workRetirementDirectorySyncFailureCannotCancelNativeWork', 1290],
];
const identities = expected.map(([, method]) => `com.feedme.storage.AndroidSessionLedgerSyncFailureTest#${method}`);
const lines = expected.map(([label, , sqlite]) =>
  `INSTRUMENTATION_RESULT: session_ledger_sync_${label}=sqlite=${sqlite};failures=1;effects=0;vfs=1;delegatedUnlink=${sqlite === 1290 ? 1 : 0}`);
const log = lines.join('\n');
const rejects = (text, successful = identities) => {
  for (const parse of parsers) assert.throws(() => parse(text, successful));
};

test('ledger evidence accepts only nine identity-bound VFS results with zero downstream effects', () => {
  const results = parsers.map(parse => JSON.parse(JSON.stringify(parse(log, identities))));
  assert.deepEqual(results[0], results[1]);
  assert.deepEqual(Object.keys(results[0]), expected.map(([label]) => label));
  for (const [index, [label, , sqlite]] of expected.entries()) assert.deepEqual(results[0][label], {
    identity: identities[index], mechanism: 'injected-vfs-sync-failure', sqlite,
    failures: 1, effects: 0, vfs: 1, delegatedUnlink: sqlite === 1290 ? 1 : 0,
  });
});

test('ledger evidence rejects a missing native result even when all test identities passed', () => {
  rejects(lines.slice(1).join('\n'));
});

test('ledger evidence rejects duplicate labels disguised as the required result count', () => {
  rejects([...lines.slice(0, -1), lines[0]].join('\n'));
});

test('ledger evidence rejects additional unrecognized result labels', () => {
  rejects(`${log}\n${lines[0].replace('control-journal', 'unrecognized-private-canary')}`);
});

test('ledger evidence rejects the wrong SQLite error for its exact failure stage', () => {
  rejects(log.replace('sqlite=1034', 'sqlite=1290'));
});

test('ledger evidence rejects native effects after the failed acknowledgement', () => {
  rejects(log.replace('effects=0', 'effects=1'));
});

test('ledger evidence rejects an OS errno claim instead of the explicit VFS mechanism', () => {
  rejects(log.replace('vfs=1', 'errno=5'));
});

test('ledger evidence rejects a delegated-unlink claim on a pre-delegation journal failure', () => {
  rejects(log.replace('delegatedUnlink=0', 'delegatedUnlink=1'));
});

test('ledger evidence rejects a result whose exact source method did not pass', () => {
  rejects(log, identities.slice(1));
});

test('ledger evidence rejects success identities borrowed from another instrumentation class', () => {
  rejects(log, identities.map(identity => identity.replace('AndroidSessionLedgerSyncFailureTest', 'AndroidStateActivationSyncFailureTest')));
});
