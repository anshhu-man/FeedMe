import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';

const FEATURE_IDS = Array.from({length: 54}, (_, index) => `F${String(index + 1).padStart(2, '0')}`);
const DEFERRED_IDS = ['F32', 'F33', 'F34', 'F35', 'F36', 'F37', 'F38', 'F44', 'F45', 'F46'];
const INCLUDED_IDS = FEATURE_IDS.filter(id => !DEFERRED_IDS.includes(id));
const MANIFEST_KEYS = ['schemaVersion', 'release', 'decision', 'includedFeatureIds', 'deferredFeatureIds'];
const SCOPE = 'Planning/build-manifest enforcement and canonical feature-association coverage only. Shared screens and operations remain in the V1 mapping. This does not implement runtime feature gating, remove endpoints or shared resources, establish authorization, or prove implementation/release readiness.';
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const uniqueStrings = (value, pattern) => Array.isArray(value) && value.every(item => typeof item === 'string' && pattern.test(item)) && new Set(value).size === value.length;
const sameSet = (actual, expected) => Array.isArray(actual) && actual.length === expected.length && new Set(actual).size === actual.length && expected.every(value => actual.includes(value));
const sorted = values => [...new Set(values)].sort();
const expectedPhase = id => id === 'F32' || id === 'F33' ? 'P2' : DEFERRED_IDS.includes(id) ? 'P3' : 'P1';

/** Pure structural enforcement of the owner's existing-phase-only V1 decision; no I/O. */
export function verifyReleaseScope(input = {}) {
  const {manifest, registry} = object(input) ? input : {};
  const checks = [];
  const check = (name, passed) => checks.push({name, passed: Boolean(passed)});
  const validManifest = object(manifest);
  const features = object(registry) && Array.isArray(registry.features) ? registry.features : [];
  const screens = object(registry) && Array.isArray(registry.screens) ? registry.screens : [];
  const featureIds = features.map(feature => object(feature) ? feature.id : null);
  const screenIds = screens.map(screen => object(screen) ? screen.id : null);
  const included = validManifest && Array.isArray(manifest.includedFeatureIds) ? manifest.includedFeatureIds : [];
  const deferred = validManifest && Array.isArray(manifest.deferredFeatureIds) ? manifest.deferredFeatureIds : [];

  check('Manifest has exactly the supported V1 fields', validManifest && sameSet(Object.keys(manifest), MANIFEST_KEYS));
  check('Manifest schema, release and decision match the approved partition', validManifest && manifest.schemaVersion === 1 && manifest.release === 'v1' && manifest.decision === 'existing-phase-deferrals-only');
  check('Canonical registry contains F01–F54 exactly once', sameSet(featureIds, FEATURE_IDS));
  check('Canonical feature phases retain the existing P1/P2/P3 assignments', features.length === 54 && features.every(feature => object(feature) && FEATURE_IDS.includes(feature.id) && feature.phase === expectedPhase(feature.id)));
  check('Included and deferred lists contain unique known feature IDs', validManifest && uniqueStrings(manifest.includedFeatureIds, /^F\d{2}$/) && uniqueStrings(manifest.deferredFeatureIds, /^F\d{2}$/) && [...included, ...deferred].every(id => FEATURE_IDS.includes(id)));
  check('Included and deferred features never overlap', included.every(id => !deferred.includes(id)));
  check('The release partition retains all 54 canonical features', sameSet([...included, ...deferred], FEATURE_IDS));
  check('V1 includes exactly the existing 44 P1 features', sameSet(included, INCLUDED_IDS));
  check('Only the ten existing P2/P3 features are deferred', sameSet(deferred, DEFERRED_IDS));
  check('Canonical screen IDs are unique and well formed', screens.length > 0 && uniqueStrings(screenIds, /^[A-Z][A-Z0-9_]*$/));
  check('Every feature has unique canonical screen and operation associations', features.length === 54 && features.every(feature => object(feature) && uniqueStrings(feature.screenIds, /^[A-Z][A-Z0-9_]*$/) && feature.screenIds.length > 0 && feature.screenIds.every(id => screenIds.includes(id)) && uniqueStrings(feature.operationIds, /^[A-Za-z][A-Za-z0-9]*$/) && feature.operationIds.length > 0));
  check('Every canonical screen is retained in feature coverage', screens.length > 0 && screenIds.every(id => features.some(feature => object(feature) && Array.isArray(feature.screenIds) && feature.screenIds.includes(id))));
  const passed = checks.every(result => result.passed);
  let coverage = null;
  if (passed) {
    const classify = field => {
      const v1 = sorted(features.filter(feature => included.includes(feature.id)).flatMap(feature => feature[field]));
      const later = sorted(features.filter(feature => deferred.includes(feature.id)).flatMap(feature => feature[field]));
      return {
        v1,
        v1Only: v1.filter(id => !later.includes(id)),
        shared: v1.filter(id => later.includes(id)),
        deferredOnly: later.filter(id => !v1.includes(id)),
        all: sorted([...v1, ...later]),
      };
    };
    coverage = {screens: classify('screenIds'), operations: classify('operationIds')};
  }
  return {
    passed, scope: SCOPE,
    counts: {
      canonicalFeatures: featureIds.length, includedFeatures: included.length, deferredFeatures: deferred.length,
      ...(coverage ? {
        canonicalScreens: coverage.screens.all.length, v1Screens: coverage.screens.v1.length,
        v1OnlyScreens: coverage.screens.v1Only.length, sharedScreens: coverage.screens.shared.length,
        deferredOnlyScreens: coverage.screens.deferredOnly.length,
        canonicalOperations: coverage.operations.all.length, v1Operations: coverage.operations.v1.length,
        v1OnlyOperations: coverage.operations.v1Only.length, sharedOperations: coverage.operations.shared.length,
        deferredOnlyOperations: coverage.operations.deferredOnly.length,
      } : {}),
      checks: checks.length,
    },
    coverage, checks,
  };
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const args = process.argv.slice(2);
  let report;
  try {
    if (args.length > 1 || args.some(arg => arg !== '--write-report')) throw new Error('Unsupported arguments');
    const inputs = {
      manifest: fs.readFileSync(path.join(root, 'docs/V1_RELEASE_SCOPE.json'), 'utf8'),
      registry: fs.readFileSync(path.join(root, '../outputs/biteclub_blueprint/registry/screen_registry.json'), 'utf8'),
    };
    report = verifyReleaseScope({manifest: JSON.parse(inputs.manifest), registry: JSON.parse(inputs.registry)});
    report.sourceSha256 = createHash('sha256').update(JSON.stringify(inputs)).digest('hex');
  } catch {
    report = {passed: false, scope: SCOPE, error: 'Release-scope inputs or arguments are unavailable or malformed.'};
  }
  report.verifiedAt = new Date().toISOString();
  if (args.length === 1 && args[0] === '--write-report') {
    fs.mkdirSync(path.join(root, 'docs/verification'), {recursive: true});
    fs.writeFileSync(path.join(root, 'docs/verification/release-scope-report.json'), JSON.stringify(report, null, 2) + '\n');
  }
  // Keep the full mapping in the report; build logs need only the decision and check summary.
  const {coverage, ...summary} = report;
  console.log(JSON.stringify(summary, null, 2));
  if (!report.passed) process.exitCode = 1;
}
