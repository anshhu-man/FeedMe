import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {verifyReleaseScope} from './verify-release-scope.mjs';

const ROOT_ROLE = 'root_design_system_navigation';
const ROLES = [ROOT_ROLE, 'cooking_experience', 'social_library', 'ux_coverage_qa'];
const OMIT = ['guard', 'offline', 'errors', 'analytics', 'idempotency', 'wireContract'];
const SCOPE = 'Read-only ownership/contract/evidence-shape coverage. No build, device execution, runtime feature gating or whole-screen acceptance.';
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const unique = values => new Set(values).size === values.length;
const sameSet = (a, b) => Array.isArray(a) && Array.isArray(b) && a.length === b.length && unique(a) && b.every(x => a.includes(x));
const countBy = (items, key) => items.reduce((out, item) => { const k = key(item); out[k] = (out[k] || 0) + 1; return out; }, {});

/** RFC-style quoted CSV parser; preserves embedded commas/newlines/escaped quotes. */
export function parseCsv(text) {
  const rows = []; let row = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') {
      if (field.length) throw new Error('Malformed quoted field');
      quoted = true;
    } else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); rows.push(row); row = []; field = '';
    } else field += c;
  }
  if (quoted) throw new Error('Unterminated CSV field');
  if (field.length || row.length) { row.push(field); rows.push(row); }
  if (!rows.length) throw new Error('Empty CSV');
  const header = rows.shift();
  if (!unique(header)) throw new Error('Duplicate CSV column');
  return rows.map(values => {
    if (values.length !== header.length) throw new Error('Wrong CSV column count');
    return Object.fromEntries(header.map((key, i) => [key, values[i]]));
  });
}

export function classifyAction(screen, action, release) {
  const blockedReasons = [], restrictions = [];
  const laterOnly = new Set(release.screens.deferredOnly);
  if (laterOnly.has(screen.id)) blockedReasons.push('source_screen_deferred');
  if (action.target && laterOnly.has(action.target)) blockedReasons.push('default_destination_deferred');
  if (action.operationId && release.operations.deferredOnly.includes(action.operationId)) blockedReasons.push('operation_deferred_only');
  if (['COLLECTION', 'COLLECTION_EDIT', 'ADMIN_PACK'].includes(screen.id) && action.scope === 'screen' && !/Cancel|Back|Return/.test(action.label)) blockedReasons.push('later_collection_or_pack_variant');
  if ((screen.id === 'COOKBOOK' && ['COOKBOOK.03', 'COOKBOOK.04', 'COOKBOOK.06'].includes(action.id)) || (screen.id === 'PACK_DETAIL' && ['PACK_DETAIL.01', 'PACK_DETAIL.02', 'PACK_DETAIL.03'].includes(action.id))) blockedReasons.push('later_library_or_pack_variant');
  if (screen.id === 'PAYWALL' && /purchase|buy|subscribe/i.test(action.request + ' ' + action.label)) blockedReasons.push('U07_approved_v1_offer_required');
  if ((action.branches || []).some(b => laterOnly.has(b[0]))) restrictions.push('Reject later-feature picker/branch modes; retain only approved V1 return contexts.');
  if (['prepareMediaUpload', 'completeMediaUpload', 'getMediaStatus', 'deleteDraftMedia', 'getMediaAccess'].includes(action.operationId)) restrictions.push('Photo-only V1 payloads; no short-clip capture/transcoding.');
  if (screen.id === 'ADMIN_FLAGS') restrictions.push('Staff authorization and approved V1 flag allowlist; no deferred feature enablement.');
  if (release.screens.shared.includes(screen.id)) restrictions.push('Shared association is not an action/payload allowlist; review exact feature variant.');
  return {classification: blockedReasons.length ? 'withheld' : restrictions.length ? 'planned_v1_restricted' : 'planned_v1', runtimeEnabledByThisDocument: false, blockedReasons, restrictions};
}

export function verifyUiUxCoverage({coverage, registry, manifest, buttonCsv, pathExists = () => false, runtimePaths = [], sourceHashes = {}} = {}) {
  const failures = []; let checks = 0;
  const check = (label, pass) => { checks++; if (!pass) failures.push(label); };
  const failEarly = () => ({passed: false, scope: SCOPE, checks, failures});
  const release = verifyReleaseScope({registry, manifest});
  check('Actual V1 feature partition is valid', release.passed);
  check('Coverage object is versioned and explicit', coverage?.schemaVersion === 1 && coverage?.product === 'FeedMe' && Array.isArray(coverage?.screens));
  if (!release.passed || !Array.isArray(coverage?.screens)) return failEarly();
  check('Exactly four accountable roles', sameSet(coverage.roles?.map(x => x.id), ROLES));
  check('Canonical omitted fields are explicit references, not altered contracts', same(coverage.canonicalProjectionOmissions, OMIT));
  check('Canonical new-user start is preserved', coverage.canonicalStart === registry.product.start && coverage.canonicalStart === 'AUTH_WELCOME');
  check('98 exact screen IDs, no duplicates or omissions', sameSet(coverage.screens.map(s => s.id), registry.screens.map(s => s.id)) && coverage.screens.length === 98);
  check('44 included / 10 deferred features unchanged', sameSet(coverage.releasePolicy?.includedFeatureIds, manifest.includedFeatureIds) && sameSet(coverage.releasePolicy?.deferredFeatureIds, manifest.deferredFeatureIds));
  check('Scope association never grants runtime permission', coverage.releasePolicy?.associationIsAllowlist === false && coverage.releasePolicy?.allDeferredVariantsRemainDisabled === true);
  for (const [name, descriptor] of Object.entries(coverage.sources || {})) {
    check('Source exists: ' + name, typeof descriptor.path === 'string' && pathExists(descriptor.path));
    check('Pinned canonical source hash: ' + name, /^[0-9a-f]{64}$/.test(descriptor.sha256) && descriptor.sha256 === sourceHashes[name]);
  }
  check('All six canonical sources pinned', sameSet(Object.keys(coverage.sources || {}), ['registry', 'buttons', 'scope', 'design', 'interactions', 'release']));
  let csv;
  try { csv = parseCsv(buttonCsv); } catch { csv = []; }
  check('CSV has exact 900 unique action IDs', csv.length === 900 && unique(csv.map(x => x.actionId)));
  const csvById = new Map(csv.map(x => [x.actionId, x]));
  const canonicalById = new Map(registry.screens.map(s => [s.id, s]));
  const actionIds = [], tasks = [];
  const sourceExists = (label, p) => check(label + ': ' + p, typeof p === 'string' && pathExists(p));
  for (const screen of coverage.screens) {
    const source = canonicalById.get(screen.id);
    if (!source) continue;
    check(screen.id + ' owner is one known role', ROLES.includes(screen.ownerRole));
    check(screen.id + ' title/features/back match reference', screen.title === source.title && same(screen.features, source.features) && screen.canonicalBack === source.back);
    const classification = release.coverage.screens.deferredOnly.includes(screen.id) ? 'deferred_only' : release.coverage.screens.shared.includes(screen.id) ? 'v1_shared' : 'v1_only';
    check(screen.id + ' exact feature-derived classification', screen.release?.classification === classification && screen.release.runtimeEnabledByThisDocument === false);
    check(screen.id + ' exact field mapping', same(screen.fields?.map(f => f.canonical), source.fields) && screen.fields.every(f => f.ownerRole === screen.ownerRole && f.coverageKey === screen.id + '::' + f.canonical.id));
    check(screen.id + ' exact hydration mapping', same(screen.hydration?.map(h => h.canonical), source.hydration) && screen.hydration.every(h => h.ownerRole === screen.ownerRole));
    const impl = screen.implementation, evidence = screen.evidence;
    check(screen.id + ' declared implementation classification', coverage.evidencePolicy.allowedScreenStatuses.includes(impl?.status));
    check(screen.id + ' deferred never marked built', classification !== 'deferred_only' || (impl.status === 'deferred_not_enabled' && impl.completeScreenVerified === false));
    check(screen.id + ' missing means no fabricated implementation path', impl.status !== 'not_found' || impl.paths.length === 0);
    check(screen.id + ' partial/source lead never called complete', impl.status === 'implemented_verified' || impl.completeScreenVerified === false);
    check(screen.id + ' no fake screen acceptance', !impl.completeScreenVerified || (evidence.executedInThisAudit === true && evidence.screenshots.length > 0 && evidence.verifiedActionIds.length > 0));
    for (const item of impl.paths) sourceExists(screen.id + ' implementation', item.path);
    for (const p of evidence.declaredTestCandidates) sourceExists(screen.id + ' candidate test', p);
    check(screen.id + ' explicit gaps/tasks/evidence', screen.gaps.length > 0 && screen.tasks.length > 0 && typeof evidence.executedInThisAudit === 'boolean' && Array.isArray(evidence.screenshots) && Array.isArray(evidence.verifiedActionIds));
    for (const task of screen.tasks) { tasks.push(task.id); check(task.id + ' task owner/state', ROLES.includes(task.ownerRole) && ['planned', 'deferred', 'in_progress', 'complete'].includes(task.status)); }
    check(screen.id + ' all action IDs once', sameSet(screen.actions.map(a => a.id), source.actions.map(a => a.id)));
    const sourceActions = new Map(source.actions.map(a => [a.id, a]));
    for (const action of screen.actions) {
      actionIds.push(action.id); tasks.push(action.task.id);
      const original = sourceActions.get(action.id);
      if (!original) { check(action.id + ' exists in its source screen', false); continue; }
      const projected = Object.fromEntries(Object.entries(original).filter(([k]) => !OMIT.includes(k)));
      check(action.id + ' exact canonical action/branch contract', same(action.canonical, projected));
      const expectedOwner = original.scope === 'back' || original.scope === 'navigation' ? ROOT_ROLE : screen.ownerRole;
      check(action.id + ' exactly assigned owner and trace task', action.ownerRole === expectedOwner && action.task.ownerRole === expectedOwner && typeof action.task.id === 'string');
      check(action.id + ' exact V1 action/variant restriction', same(action.release, classifyAction(source, original, release.coverage)));
      const row = csvById.get(action.id);
      check(action.id + ' CSV projection matches registry', row?.screenId === screen.id && row?.label === original.label && row?.scope === original.scope && row?.request === original.request && row?.operationId === (original.operationId || '') && row?.destination === (original.target || '') && row?.conditionalBranches === JSON.stringify(original.branches || []));
      const expected = [{kind: original.target ? 'default_destination' : 'in_place_result', destination: original.target ?? null},
        ...(original.branches || []).map(([destination, condition], branchIndex) => ({kind: 'conditional_destination', destination, condition, branchIndex})),
        ...(original.confirm ? [{kind: 'confirmation_boundary', destination: 'CONFIRM_ACTION'}] : [])];
      check(action.id + ' complete exact transition shapes', action.transitions.length === expected.length && expected.every((edge, i) => Object.entries(edge).every(([k, v]) => action.transitions[i]?.[k] === v)));
      check(action.id + ' every edge has root owner and known destination', action.transitions.every(edge => edge.ownerRole === ROOT_ROLE && (edge.destination === null || canonicalById.has(edge.destination))));
      check(action.id + ' explicit action implementation status', coverage.evidencePolicy.allowedActionStatuses.includes(action.implementation?.status));
      const verified = action.implementation?.status === 'implemented_verified';
      check(action.id + ' no proof-free action acceptance', !verified || (action.implementation.evidence.length > 0 && action.implementation.evidence.every(e => typeof e.testMethod === 'string' && e.testMethod.length > 0 && e.result === 'PASS' && typeof e.platform === 'string' && typeof e.artifactPath === 'string' && pathExists(e.artifactPath)) && evidence.verifiedActionIds.includes(action.id)));
      for (const p of action.implementation.relatedPaths) sourceExists(action.id + ' related path', p);
    }
    check(screen.id + ' verified IDs refer only to actual verified actions', evidence.verifiedActionIds.every(id => screen.actions.some(a => a.id === id && a.implementation.status === 'implemented_verified')));
  }
  check('900 exact canonical action identities, no duplicates', actionIds.length === 900 && sameSet(actionIds, registry.screens.flatMap(s => s.actions.map(a => a.id))));
  check('Every generated task identity is unique', unique(tasks));
  const runtime = coverage.runtimeOwnership || [];
  check('Every current Kotlin UI/progress file has one owner', sameSet(runtime.map(x => x.path), runtimePaths));
  for (const file of runtime) {
    sourceExists('Owned runtime source', file.path);
    check(file.path + ' single whole-file/function ownership', ROLES.includes(file.ownerRole) && typeof file.ownershipScope === 'string' && file.functions.every(f => f.ownerRole === file.ownerRole) && unique(file.functions.map(f => f.name)));
  }
  const actions = coverage.screens.flatMap(s => s.actions);
  const expectedCounts = {
    screens: 98, actions: 900, v1Screens: 81, deferredOnlyScreens: 17, sharedScreens: 11, includedFeatures: 44, deferredFeatures: 10,
    actionScopes: countBy(actions, a => a.canonical.scope),
    defaultNavigationTargets: actions.filter(a => a.canonical.target).length,
    inPlaceResults: actions.filter(a => !a.canonical.target).length,
    conditionalBranchActions: actions.filter(a => a.canonical.branches?.length).length,
    conditionalEdges: actions.reduce((n, a) => n + (a.canonical.branches?.length || 0), 0),
    confirmationBoundaries: actions.filter(a => a.canonical.confirm).length,
    destructiveActions: actions.filter(a => a.canonical.destructive).length,
    screenOwners: countBy(coverage.screens, s => s.ownerRole), actionOwners: countBy(actions, a => a.ownerRole),
    implementationStatuses: countBy(coverage.screens, s => s.implementation.status), runtimeFiles: runtime.length
  };
  check('All reported counts recomputed exactly', same(coverage.counts, expectedCounts));
  check('V1 81 / later 17 actual rows, not just labels', coverage.screens.filter(s => s.release.classification !== 'deferred_only').length === 81 && coverage.screens.filter(s => s.release.classification === 'deferred_only').length === 17);
  check('Reported acceptance count equals actual evidence', coverage.evidencePolicy.productionActionAcceptanceCount === actions.filter(a => a.implementation.status === 'implemented_verified').length);
  return {passed: failures.length === 0, scope: SCOPE, checks, failures, counts: expectedCounts};
}

const walk = directory => fs.readdirSync(directory, {withFileTypes: true}).flatMap(entry => {
  const target = path.join(directory, entry.name);
  return entry.isDirectory() ? walk(target) : entry.isFile() && target.endsWith('.kt') ? [target] : [];
});

export function loadCoverageInputs(repoRoot) {
  const read = relative => fs.readFileSync(path.resolve(repoRoot, relative), 'utf8');
  const coverage = JSON.parse(read('docs/UI_UX_COVERAGE.json'));
  const registry = JSON.parse(read('../outputs/biteclub_blueprint/registry/screen_registry.json'));
  const sourceHashes = Object.fromEntries(Object.entries(coverage.sources).map(([k, s]) => [k, createHash('sha256').update(read(s.path)).digest('hex')]));
  return {coverage, registry, manifest: JSON.parse(read('docs/V1_RELEASE_SCOPE.json')), buttonCsv: read('../outputs/biteclub_blueprint/registry/button_actions.csv'),
    pathExists: p => { try { return fs.statSync(path.resolve(repoRoot, p)).isFile(); } catch { return false; } },
    runtimePaths: ['shared/app/src/commonMain', 'shared/app/src/iosMain', 'apps/android/src/progress'].flatMap(p => walk(path.join(repoRoot, p))).map(p => path.relative(repoRoot, p)).sort(),
    sourceHashes};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let report;
  try {
    if (process.argv.length !== 2) throw new Error('No CLI options; read-only check only');
    report = verifyUiUxCoverage(loadCoverageInputs(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')));
  } catch (error) {
    report = {passed: false, scope: SCOPE, failures: [error.message]};
  }
  console.log(JSON.stringify(report, null, 2));
  if (!report.passed) process.exitCode = 1;
}
