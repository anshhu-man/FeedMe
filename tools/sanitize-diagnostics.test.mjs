import assert from 'node:assert/strict';
import test from 'node:test';
import {sanitizeDiagnostics} from './sanitize-diagnostics.mjs';

const log = 'feedme/docs/verification/example/runner.log';
const ours = 'instrumentation:com.feedme.storage.test/androidx.test.runner.AndroidJUnitRunner (target=com.feedme.storage.test)';
const unrelated = 'instrumentation:org.example.synthetic.test/Runner (target=org.example.synthetic)';

test('preserves FeedMe instrumentation and its line endings byte-for-byte', () => {
  for (const end of ['', '\n', '\r\n']) {
    assert.deepEqual(sanitizeDiagnostics(log, ours + end), {text: ours + end, unrelatedInstrumentationRedactions: 0});
  }
});

test('removes unrelated inventory lines with LF, CRLF or no terminal newline', () => {
  for (const end of ['', '\n', '\r\n']) {
    assert.deepEqual(sanitizeDiagnostics(log, unrelated + end), {text: '', unrelatedInstrumentationRedactions: 1});
  }
});

test('counts only removed lines and keeps test outcomes and FeedMe inventory', () => {
  assert.deepEqual(sanitizeDiagnostics(log, `${unrelated}\n${ours}\nOK (12 tests)\n${unrelated}\n`), {
    text: `${ours}\nOK (12 tests)\n`, unrelatedInstrumentationRedactions: 2,
  });
});

test('does not treat a lookalike package prefix as FeedMe', () => {
  assert.equal(sanitizeDiagnostics(log, 'instrumentation:com.feedmeOther/Runner\n').text, '');
});

test('requires a FeedMe target as well as a FeedMe instrumentation package', () => {
  const text = 'instrumentation:com.feedme.storage.test/Runner (target=org.example.synthetic)\n';
  assert.deepEqual(sanitizeDiagnostics(log, text), {text: '', unrelatedInstrumentationRedactions: 1});
});

test('does not alter code, JSON, other projects or non-inventory log lines', () => {
  for (const filename of ['feedme/scripts/probe.mjs', 'feedme/docs/verification/report.json', 'other/docs/verification/runner.log']) {
    assert.deepEqual(sanitizeDiagnostics(filename, unrelated), {text: unrelated, unrelatedInstrumentationRedactions: 0});
  }
  const text = `INSTRUMENTATION_STATUS: test=example\nnotice ${unrelated}\n`;
  assert.deepEqual(sanitizeDiagnostics(log, text), {text, unrelatedInstrumentationRedactions: 0});
});

test('sanitization is idempotent', () => {
  const once = sanitizeDiagnostics(log, `${unrelated}\n${ours}\n`);
  assert.deepEqual(sanitizeDiagnostics(log, once.text), {text: once.text, unrelatedInstrumentationRedactions: 0});
});
