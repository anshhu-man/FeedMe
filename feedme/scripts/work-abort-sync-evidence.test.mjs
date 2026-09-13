import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

// Extract only each pure parser. Never import or execute build/device runner entry points.
const parsers = ['android-storage-smoke.mjs', 'verify-setup-abort-primitives.mjs'].map(name => {
  const source = fs.readFileSync(fileURLToPath(new URL(name, import.meta.url)), 'utf8');
  const declaration = source.match(/function workAbortSyncFailureEvidence\(log, identities\) \{[\s\S]*?\n\}/)?.[0];
  assert.ok(declaration, `${name} must retain the pure work-abort evidence parser`);
  return vm.runInNewContext(`(${declaration})`, Object.create(null), {timeout: 1000});
});

const testClass = 'com.feedme.storage.AndroidSessionWorkOriginAbortSyncFailureTest';
const expected = [
  ['initial-journal', 'journalSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginAbort', 1034],
  ['replay-journal', 'journalSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginAbort', 1034],
  ['initial-database', 'databaseSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginAbort', 1034],
  ['replay-database', 'databaseSyncFailureCannotAcknowledgeInitialOrReplayedWorkOriginAbort', 1034],
  ['initial-directory', 'directorySyncFailureCannotPromoteVisibleWorkOriginAbortOrReplayToAcknowledgement', 1290],
  ['replay-directory', 'directorySyncFailureCannotPromoteVisibleWorkOriginAbortOrReplayToAcknowledgement', 1290],
];
const identities = [...new Set(expected.map(([, method]) => `${testClass}#${method}`))];
const lines = expected.map(([label, , sqlite]) =>
  `INSTRUMENTATION_RESULT: work_abort_sync_${label}=sqlite=${sqlite};failures=1;effects=0;vfs=1;delegatedUnlink=${sqlite === 1290 ? 1 : 0}`);
const log = lines.join('\n');
const rejects = (text, successful = identities) => {
  for (const parse of parsers) assert.throws(() => parse(text, successful));
};

test('work-abort smoke and verifier agree on all six source-bound initial and replay VFS results', () => {
  const source = fs.readFileSync(fileURLToPath(new URL(
    '../shared/storage/src/androidInstrumentedTest/kotlin/com/feedme/storage/AndroidSessionWorkOriginAbortSyncFailureTest.kt',
    import.meta.url)), 'utf8');
  const actualMethods = [...source.matchAll(/@Test\s+fun\s+(\w+)\s*\(/g)].map(match => `${testClass}#${match[1]}`);
  assert.deepEqual(actualMethods.sort(), [...identities].sort());
  const results = parsers.map(parse => JSON.parse(JSON.stringify(parse(log, identities))));
  assert.deepEqual(results[0], results[1]);
  assert.deepEqual(Object.keys(results[0]), expected.map(([label]) => label));
  for (const [label, method, sqlite] of expected) assert.deepEqual(results[0][label], {
    identity: `${testClass}#${method}`, mechanism: 'injected-vfs-sync-failure', sqlite,
    failures: 1, effects: 0, vfs: 1, delegatedUnlink: sqlite === 1290 ? 1 : 0,
  });
});

test('work-abort evidence requires both abort and replay labels for every sync stage', () => {
  for (let omitted = 0; omitted < lines.length; omitted++)
    rejects(lines.filter((_, index) => index !== omitted).join('\n'));
});

test('work-abort evidence rejects duplicate labels even when the total count is still six', () => {
  rejects([...lines.slice(0, -1), lines[0]].join('\n'));
  rejects(`${log}\n${lines[0]}`);
});

test('work-abort evidence rejects forged or additional result labels', () => {
  rejects(log.replace('initial-journal', 'select-unrecognized'));
  rejects(`${log}\n${lines[0].replace('initial-journal', 'unrecognized-recovery')}`);
});

test('work-abort result bytes cannot substitute for a missing paired-success source identity', () => {
  for (const identity of identities) {
    const method = identity.split('#')[1];
    const unpairedStart = `INSTRUMENTATION_STATUS: class=${testClass}\n` +
      `INSTRUMENTATION_STATUS: test=${method}\nINSTRUMENTATION_STATUS_CODE: 1\n`;
    // The transcript validator supplies only paired successful identities, not start events.
    rejects(unpairedStart + log, identities.filter(value => value !== identity));
  }
});

test('work-abort evidence rejects successful identities borrowed from another class or method', () => {
  rejects(log, identities.map(identity => identity.replace('AndroidSessionWorkOriginAbortSyncFailureTest', 'AndroidSessionLedgerSyncFailureTest')));
  rejects(log, identities.map(identity => `${identity}Unrelated`));
});

test('work-abort evidence rejects an incorrect SQLite extended error for the exact stage', () => {
  rejects(log.replace('sqlite=1034', 'sqlite=1290'));
  rejects(log.replace('sqlite=1290', 'sqlite=1034'));
  rejects(log.replace('sqlite=1034', 'sqlite=10'));
});

test('work-abort evidence requires one actual failure and zero downstream effects', () => {
  for (const replacement of ['failures=0', 'failures=2', 'failures=-1'])
    rejects(log.replace('failures=1', replacement));
  rejects(log.replace('effects=0', 'effects=1'));
});

test('work-abort evidence preserves the explicit VFS mechanism and delegated-unlink stage', () => {
  rejects(log.replace('vfs=1', 'errno=5'));
  rejects(log.replace('vfs=1', 'vfs=0'));
  rejects(log.replace('delegatedUnlink=0', 'delegatedUnlink=1'));
  rejects(log.replace('delegatedUnlink=1', 'delegatedUnlink=0'));
});

test('work-abort evidence rejects truncated reordered or extra result fields', () => {
  rejects(log.replace(';effects=0', ''));
  rejects(log.replace('failures=1;effects=0', 'effects=0;failures=1'));
  rejects(log.replace('vfs=1', 'vfs=1;errno=5'));
  rejects(log.replace('sqlite=1034', 'sqlite=1034 '));
});
