// Publication-only minimization. Never change the source workspace or test evidence.
export function sanitizeDiagnostics(sourceRelative, text) {
  let unrelatedInstrumentationRedactions = 0;
  if (!sourceRelative.startsWith('feedme/docs/verification/') || !sourceRelative.endsWith('.log')) {
    return {text, unrelatedInstrumentationRedactions};
  }
  const published = text.replace(/^instrumentation:[^\r\n]*(?:\r?\n|$)/gm, line => {
    if (/^instrumentation:com\.feedme\.[^/\s]+\/\S+ \(target=com\.feedme\.[^)\s]+\)(?:\r?\n)?$/.test(line)) return line;
    unrelatedInstrumentationRedactions++;
    return '';
  });
  return {text: published, unrelatedInstrumentationRedactions};
}
