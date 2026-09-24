import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const forbiddenActivation = /com\.android\.billingclient|com\.revenuecat|BillingClient\s*\.\s*newBuilder|Purchases\s*\.\s*configure|RevenueCat\s*\.\s*configure/i;
const referenceCommerce = /\bBlueprint(?:Commerce|StoreOffer|PaidAccess|Purchase)[A-Za-z0-9_]*\b/;
const freePlanOwnerPath = 'shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/FeedMeFreePlanFlow.kt';
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

function isStrictFreePlanOwner(entry) {
  if (entry.path !== freePlanOwnerPath || !entry.text.includes('BlueprintCommerceScreen(') ||
      !entry.text.includes('page = BlueprintCommercePage.MANAGE_PLAN') ||
      !entry.text.includes('phase = BlueprintCommercePhase.UNAVAILABLE') ||
      !entry.text.includes('setOf("MANAGE_PLAN.back")') ||
      !entry.text.includes('else emptySet()') ||
      !entry.text.includes('BlueprintCommercePolicyFact.CORE_COOKING_FREE') ||
      !entry.text.includes('BlueprintCommercePolicyFact.RETAINED_SAVED_ACCESS') ||
      !entry.text.includes('event.expected === state') ||
      !entry.text.includes('event.actionId == "MANAGE_PLAN.back"')) return false;

  const pages = [...entry.text.matchAll(/BlueprintCommercePage\.([A-Z_]+)/g)].map(match => match[1]);
  const phases = [...entry.text.matchAll(/BlueprintCommercePhase\.([A-Z_]+)/g)].map(match => match[1]);
  const policies = [...entry.text.matchAll(/BlueprintCommercePolicyFact\.([A-Z_]+)/g)].map(match => match[1]);
  const actionIds = [...entry.text.matchAll(/["']([A-Z_]+\.[A-Za-z0-9_]+)["']/g)].map(match => match[1]);
  return pages.length === 1 && pages[0] === 'MANAGE_PLAN' &&
    phases.length === 1 && phases[0] === 'UNAVAILABLE' &&
    policies.length === 2 && new Set(policies).size === 2 &&
    policies.every(value => value === 'CORE_COOKING_FREE' || value === 'RETAINED_SAVED_ACCESS') &&
    actionIds.length === 2 && actionIds.every(value => value === 'MANAGE_PLAN.back') &&
    !/\bBlueprint(?:StoreOffer|PaidAccess|Purchase)[A-Za-z0-9_]*\b/.test(entry.text) &&
    !/\bBlueprintCommerce(?:Context|Reference|Pack|Offer|Transaction|Entitlement)\b/.test(entry.text);
}

/** Pure source/build admission. Final AAB component policy remains a separate artifact gate. */
export function inspectV1RuntimeSurface(input = {}) {
  if (!object(input) || typeof input.runtimeSource !== 'string' || typeof input.policySource !== 'string' ||
      typeof input.manifestSource !== 'string' || !Array.isArray(input.dependencySources) ||
      !Array.isArray(input.productSources) || [...input.dependencySources, ...input.productSources]
        .some(entry => !object(entry) || typeof entry.path !== 'string' || typeof entry.text !== 'string')) {
    return {passed: false, findings: ['V1_RUNTIME_INPUT_INVALID']};
  }
  const findings = [];
  const admission = input.runtimeSource.indexOf('V1ReleaseScope.requireProductionRegistrations(');
  const loop = input.runtimeSource.indexOf('ReactionNotificationLoop(');
  if (admission < 0 || loop < 0 || admission > loop ||
      !input.runtimeSource.includes('setOf(V1ReleaseScope.reactionNotificationRegistration)') ||
      !input.runtimeSource.includes('paidOfferIds = emptySet()'))
    findings.push('V1_SERVER_REGISTRATION_ADMISSION_MISSING');

  if (!input.policySource.includes('BackgroundRegistration("reaction-notifications", setOf("F27", "F41"))') ||
      !input.policySource.includes('registration.featureIds.none { it in deferredFeatureIds }') ||
      !input.policySource.includes('check(paidOfferIds.isEmpty())'))
    findings.push('V1_SERVER_REGISTRATION_POLICY_MISMATCH');

  if (input.dependencySources.some(entry => forbiddenActivation.test(entry.text)))
    findings.push('V1_PAID_OFFER_DEPENDENCY_PRESENT');
  if (input.productSources.some(entry => forbiddenActivation.test(entry.text)))
    findings.push('V1_PAID_OFFER_ACTIVATION_PRESENT');
  if (input.productSources.some(entry => referenceCommerce.test(entry.text) && !isStrictFreePlanOwner(entry)))
    findings.push('V1_REFERENCE_COMMERCE_HOSTED_IN_PRODUCT');
  const cookbook = input.productSources.find(entry =>
    entry.path === 'shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/BlueprintCookbookPresentation.kt');
  const cookbookActions = cookbook?.text.match(/allowedActions\s*=([\s\S]*?)allowedNavigation\s*=/)?.[1];
  if (typeof cookbookActions !== 'string' || cookbookActions.includes('BlueprintLibraryAction.LIBRARY_TOOLS'))
    findings.push('V1_WITHHELD_LIBRARY_OFFER_INGRESS_PRESENT');

  const receiver = input.manifestSource.match(/<receiver\b[^>]*android:name="\.AccountTimerCancellationReceiver"[^>]*\/>/g) ?? [];
  if (receiver.length !== 1 || !/android:enabled="false"/.test(receiver[0]) ||
      !/android:exported="false"/.test(receiver[0]))
    findings.push('V1_ANDROID_TIMER_BACKGROUND_SURFACE_MISMATCH');
  return {passed: findings.length === 0, findings};
}

function files(root, accept, excluded = new Set()) {
  const found = [];
  const visit = directory => {
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
      const target = path.join(directory, entry.name);
      const relative = path.relative(root, target).split(path.sep).join('/');
      if (entry.isDirectory()) {
        if (!excluded.has(entry.name)) visit(target);
      } else if (entry.isFile() && accept(relative)) found.push({path: relative, text: fs.readFileSync(target, 'utf8')});
    }
  };
  visit(root);
  return found;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  let report;
  try {
    if (process.argv.length !== 2) throw new Error('unsupported arguments');
    const excluded = new Set(['.git', '.gradle', 'build', 'docs', 'node_modules']);
    const dependencySources = files(root,
      value => value.endsWith('.gradle.kts') || value === 'gradle/libs.versions.toml', excluded);
    const productRoots = ['apps/androidApp/src/main', 'shared/app/src/commonMain',
      'shared/app/src/androidMain', 'shared/app/src/iosMain', 'server/src/main'];
    const productSources = productRoots.flatMap(relative => files(path.join(root, relative),
      value => value.endsWith('.kt') && !value.includes('/blueprint/'), excluded)
      .map(entry => ({...entry, path: `${relative}/${entry.path}`})));
    report = inspectV1RuntimeSurface({
      runtimeSource: fs.readFileSync(path.join(root, 'server/src/main/kotlin/com/feedme/server/runtime/AccountCoreRuntime.kt'), 'utf8'),
      policySource: fs.readFileSync(path.join(root, 'server/src/main/kotlin/com/feedme/server/runtime/V1ReleaseScope.kt'), 'utf8'),
      manifestSource: fs.readFileSync(path.join(root, 'apps/androidApp/src/main/AndroidManifest.xml'), 'utf8'),
      dependencySources, productSources,
    });
    report.counts = {dependencySources: dependencySources.length, productSources: productSources.length};
  } catch {
    report = {passed: false, findings: ['V1_RUNTIME_INPUT_UNAVAILABLE']};
  }
  console.log(JSON.stringify(report, null, 2));
  if (!report.passed) process.exitCode = 1;
}
