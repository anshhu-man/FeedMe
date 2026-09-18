import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const GENERATOR = 'feedme-contract-artifacts-v1';
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace']);
const principals = new Set(['public', 'user', 'both', 'guest', 'admin', 'webhook']);
const schemaKeywords = new Set(['type', 'additionalProperties', 'properties', 'format', 'minimum', 'maximum',
  'items', 'required', 'enum', '$ref', 'minLength', 'maxLength', 'pattern', 'description', 'uniqueItems',
  'maxItems', 'minItems', 'allOf', 'if', 'const', 'then', 'anyOf', 'default']);
export const sha256 = value => createHash('sha256').update(value).digest('hex');
const ensure = (condition, message) => { if (!condition) throw new Error(message); };
const reviewedKeys = (value, allowed, label) => ensure(Object.keys(value).every(key => allowed.includes(key)), `Unreviewed ${label} field`);
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;

function resolve(document, value) {
  const seen = new Set();
  while (value?.$ref) {
    reviewedKeys(value, ['$ref'], 'reference alias');
    const ref = value.$ref;
    ensure(typeof ref === 'string' && ref.startsWith('#/'), 'Only bundled local references are supported');
    ensure(!seen.has(ref), 'Cyclic reference alias');
    seen.add(ref);
    value = ref.slice(2).split('/').reduce((node, key) => node?.[key.replaceAll('~1', '/').replaceAll('~0', '~')], document);
    ensure(value !== undefined, `Unresolved reference: ${ref}`);
  }
  return value;
}

function auditSchema(document, schema, counts) {
  ensure(schema && typeof schema === 'object' && !Array.isArray(schema), 'Unsupported schema shape');
  for (const key of Object.keys(schema)) {
    ensure(schemaKeywords.has(key), `Unreviewed schema keyword: ${key}`);
    counts[key] = (counts[key] ?? 0) + 1;
  }
  if (schema.type !== undefined) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    ensure(types.length > 0 && new Set(types).size === types.length &&
      types.every(type => ['object', 'array', 'string', 'integer', 'number', 'boolean', 'null'].includes(type)), 'Unreviewed schema type');
  }
  if (schema.$ref) {
    ensure(schema.$ref.startsWith('#/components/schemas/'), 'Unsupported schema reference namespace');
    resolve(document, schema);
  }
  if (schema.format) ensure(['uuid', 'date-time', 'uri'].includes(schema.format), 'Unreviewed schema format');
  for (const child of Object.values(schema.properties ?? {})) auditSchema(document, child, counts);
  if (schema.items) auditSchema(document, schema.items, counts);
  if (typeof schema.additionalProperties === 'object') auditSchema(document, schema.additionalProperties, counts);
  for (const key of ['allOf', 'anyOf']) for (const child of schema[key] ?? []) auditSchema(document, child, counts);
  for (const key of ['if', 'then']) if (schema[key]) auditSchema(document, schema[key], counts);
}

export function auditContract(document, registry) {
  ensure(document.openapi === '3.1.1', 'OpenAPI version needs review');
  reviewedKeys(document, ['openapi', 'info', 'servers', 'tags', 'security', 'paths', 'components'], 'root');
  reviewedKeys(document.components, ['securitySchemes', 'parameters', 'schemas', 'responses'], 'components');
  ensure(JSON.stringify(document.security) === JSON.stringify([{UserBearer: []}]), 'Default security needs review');
  const schemes = document.components.securitySchemes;
  ensure(JSON.stringify(Object.keys(schemes).sort()) === JSON.stringify(['GuestBearer', 'StaffBearer', 'UserBearer', 'WebhookAuthorization']), 'Security scheme set needs review');
  for (const name of ['UserBearer', 'GuestBearer', 'StaffBearer'])
    ensure(schemes[name].type === 'http' && schemes[name].scheme === 'bearer', 'Bearer security scheme needs review');
  ensure(schemes.WebhookAuthorization.type === 'apiKey' && schemes.WebhookAuthorization.in === 'header' &&
    schemes.WebhookAuthorization.name === 'Authorization', 'Webhook security scheme needs review');
  const keywordCounts = {};
  for (const schema of Object.values(document.components.schemas)) auditSchema(document, schema, keywordCounts);
  function auditParameter(param) {
    reviewedKeys(param, ['name', 'in', 'required', 'description', 'schema'], 'parameter');
    ensure(['path', 'query', 'header'].includes(param.in), 'Unreviewed parameter location');
    ensure(param.required === undefined || typeof param.required === 'boolean', 'Parameter required flag needs review');
    auditSchema(document, param.schema, keywordCounts);
  }
  function auditResponse(response) {
    reviewedKeys(response, ['description', 'headers', 'content'], 'response');
    for (const [media, content] of Object.entries(response.content ?? {})) {
      ensure(['application/json', 'application/problem+json'].includes(media), 'Unreviewed response media');
      reviewedKeys(content, ['schema', 'example'], 'response content');
      auditSchema(document, content.schema, keywordCounts);
    }
    for (const header of Object.values(response.headers ?? {})) {
      reviewedKeys(header, ['schema', 'description', 'required'], 'response header');
      auditSchema(document, header.schema, keywordCounts);
    }
  }
  for (const param of Object.values(document.components.parameters)) auditParameter(resolve(document, param));
  for (const response of Object.values(document.components.responses)) auditResponse(resolve(document, response));
  const operations = [];
  for (const [route, item] of Object.entries(document.paths)) {
    ensure(/^\/v1\//.test(route), 'Unexpected route namespace');
    for (const [method, op] of Object.entries(item)) {
      ensure(methods.has(method), 'Unreviewed path-item field');
      reviewedKeys(op, ['operationId', 'tags', 'summary', 'description', 'security', 'parameters', 'responses',
        'x-feature-ids', 'x-module', 'x-principal', 'x-idempotency-required', 'requestBody'], 'operation');
      ensure(/^[A-Za-z][A-Za-z0-9]+$/.test(op.operationId), 'Invalid operation ID');
      ensure(principals.has(op['x-principal']), 'Unreviewed principal');
      const guestCurrent = op.operationId === 'getCurrentGuestSession' || route === '/v1/guest-sessions/current' || op['x-principal'] === 'guest';
      if (guestCurrent) ensure(op.operationId === 'getCurrentGuestSession' && route === '/v1/guest-sessions/current' &&
        method === 'get' && op['x-principal'] === 'guest' && !op.requestBody && op.parameters?.length === 0 &&
        op['x-idempotency-required'] === false, 'Guest-only lifecycle profile is pinned to its exact read operation');
      ensure(typeof op['x-idempotency-required'] === 'boolean', 'Missing idempotency declaration');
      const params = (op.parameters ?? []).map(p => resolve(document, p));
      ensure(params.every(p => ['path', 'query', 'header'].includes(p.in)), 'Unreviewed parameter location');
      const identities = params.map(p => `${p.in}:${p.in === 'header' ? p.name.toLowerCase() : p.name}`);
      ensure(new Set(identities).size === identities.length, 'Duplicate operation parameter');
      const templateNames = [...route.matchAll(/\{([^}]+)\}/g)].map(m => m[1]).sort();
      const pathNames = params.filter(p => p.in === 'path').map(p => { ensure(p.required === true, 'Path parameter must be required'); return p.name; }).sort();
      ensure(JSON.stringify(templateNames) === JSON.stringify(pathNames), 'Path parameters do not match template');
      for (const param of params) auditParameter(param);
      ensure(params.some(p => p.in === 'header' && p.name === 'Idempotency-Key' && p.required) === op['x-idempotency-required'], 'Idempotency header mismatch');
      for (const [status, responseRef] of Object.entries(op.responses)) {
        ensure(/^[1-5][0-9]{2}$/.test(status), 'Unreviewed response status selector');
        const response = resolve(document, responseRef);
        if (status === '204') ensure(!response.content, '204 cannot have response content');
        auditResponse(response);
      }
      if (op.requestBody) {
        reviewedKeys(op.requestBody, ['required', 'content', 'description'], 'request body');
        ensure(op.requestBody.required === true, 'Optional request body needs review');
        ensure(Object.keys(op.requestBody.content).join() === 'application/json', 'Unreviewed request media');
        reviewedKeys(op.requestBody.content['application/json'], ['schema', 'example'], 'request content');
        auditSchema(document, op.requestBody.content['application/json'].schema, keywordCounts);
      }
      const expectedSecurity = ({public: [], user: [{UserBearer: []}], both: [{UserBearer: []}, {GuestBearer: []}], guest: [{GuestBearer: []}],
        admin: [{StaffBearer: []}], webhook: [{WebhookAuthorization: []}]})[op['x-principal']];
      ensure(JSON.stringify(op.security) === JSON.stringify(expectedSecurity), 'Principal/security alternatives mismatch');
      const device = params.find(p => p.name === 'X-Device-Session');
      ensure(!device || device.in === 'header', 'Device-session parameter must be a header');
      ensure(op['x-principal'] === 'both' ? device?.required === false
        : op['x-principal'] === 'user' && op.operationId !== 'bootstrapAccount' ? device?.required === true : !device,
      'Device-session policy needs explicit review');
      operations.push({id: op.operationId, request: `${method.toUpperCase()} ${route}`, principal: op['x-principal']});
    }
  }
  const byId = new Map(operations.map(op => [op.id, op]));
  ensure(byId.size === operations.length, 'Duplicate operation ID');
  const featureIds = new Set(registry.features.map(f => f.id));
  const screenIds = new Set(registry.screens.map(s => s.id));
  ensure(featureIds.size === registry.features.length && screenIds.size === registry.screens.length, 'Duplicate feature/screen IDs');
  for (const item of Object.values(document.paths)) for (const op of Object.values(item))
    ensure(op['x-feature-ids'].every(id => featureIds.has(id)), 'Unknown operation feature');
  const bindings = registry.screens.flatMap(screen => [
    ...screen.actions.map(action => ({screenId: screen.id, id: action.id, kind: 'action', request: action.request, operationId: action.operationId ?? null})),
    ...(screen.hydration ?? []).map((load, i) => ({screenId: screen.id, id: `${screen.id}.load.${i}`, kind: 'hydration', request: load.request, operationId: load.operationId ?? null})),
  ]);
  ensure(new Set(bindings.map(b => b.id)).size === bindings.length, 'Duplicate binding ID');
  for (const binding of bindings) {
    if (/^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE) /.test(binding.request)) {
      ensure(byId.get(binding.operationId)?.request === binding.request, `Unresolved HTTP binding: ${binding.id}`);
    } else {
      ensure(/^(LOCAL|EXTERNAL) /.test(binding.request), 'Unreviewed local/external binding');
      ensure(binding.operationId === null, 'Local/external binding must not call an HTTP operation');
    }
  }
  return {operations, bindings, counts: {schemas: Object.keys(document.components.schemas).length,
    paths: Object.keys(document.paths).length, operations: operations.length, features: featureIds.size, screens: screenIds.size,
    actions: bindings.filter(b => b.kind === 'action').length,
    httpActions: bindings.filter(b => b.kind === 'action' && b.operationId).length,
    httpHydrations: bindings.filter(b => b.kind === 'hydration' && b.operationId).length,
    localExternalHydrations: bindings.filter(b => b.kind === 'hydration' && !b.operationId).length}, keywordCounts};
}

// Small regular Kotlin string literals avoid interpolation and JVM constant-size limits.
export function kotlinString(value) {
  return JSON.stringify(value).replaceAll('$', '\\$').replaceAll('\u2028', '\\u2028').replaceAll('\u2029', '\\u2029');
}
function kotlinFunction(name, value) {
  const chunks = [];
  for (let i = 0; i < value.length; i += 8000) chunks.push(value.slice(i, i + 8000));
  return `    fun ${name}(): String = buildString(${value.length}) {\n${chunks.map(chunk => `        append(${kotlinString(chunk)})`).join('\n')}\n    }\n`;
}

export function buildArtifacts({canonicalText, registryText, lock, generatorSourceSha256 = null}) {
  ensure(sha256(canonicalText) === lock.sha256, 'Canonical source hash changed; review contract and update lock intentionally');
  ensure(lock.generator === GENERATOR, 'Generator lock mismatch');
  const document = JSON.parse(canonicalText);
  ensure(document.openapi === lock.openapi, 'OpenAPI lock mismatch');
  const audit = auditContract(document, JSON.parse(registryText));
  const source = `// Generated by ${GENERATOR}. Run node scripts/generate-contract-artifacts.mjs --write.\n` +
    '// Lossless metadata only. Does not validate bodies or authorize operations. Do not edit.\n' +
    'package com.feedme.contracts.generated\n\ninternal object GeneratedContract {\n' +
    `    const val SOURCE_SHA256 = ${kotlinString(lock.sha256)}\n` +
    kotlinFunction('documentJson', canonicalText) +
    kotlinFunction('bindingsJson', JSON.stringify(stable(audit.bindings))) + '}\n';
  const report = {generator: GENERATOR, generatorSourceSha256, scope: lock.scope, canonicalSha256: lock.sha256, registrySha256: sha256(registryText),
    generatedSha256: sha256(source), counts: audit.counts, schemaKeywords: stable(audit.keywordCounts)};
  return {source, report: JSON.stringify(report, null, 2) + '\n'};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  ensure(args.length === 1 && ['--write', '--check'].includes(args[0]), 'Usage: node scripts/generate-contract-artifacts.mjs --write|--check');
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const outputs = {
    source: path.join(root, 'shared/contracts/src/commonMain/kotlin/com/feedme/contracts/generated/GeneratedContract.kt'),
    report: path.join(root, 'docs/verification/contract-artifacts/generated-receipt.json'),
  };
  const artifacts = buildArtifacts({
    canonicalText: fs.readFileSync(path.join(root, '../outputs/biteclub_blueprint/architecture/04_API_Contract.json'), 'utf8'),
    registryText: fs.readFileSync(path.join(root, '../outputs/biteclub_blueprint/registry/screen_registry.json'), 'utf8'),
    lock: JSON.parse(fs.readFileSync(path.join(root, 'scripts/contract-source.lock.json'), 'utf8')),
    generatorSourceSha256: sha256(fs.readFileSync(fileURLToPath(import.meta.url))),
  });
  for (const [key, output] of Object.entries(outputs)) {
    if (args[0] === '--write') {
      fs.mkdirSync(path.dirname(output), {recursive: true});
      fs.writeFileSync(output, artifacts[key]);
    } else ensure(fs.existsSync(output) && fs.readFileSync(output, 'utf8') === artifacts[key], `Generated ${key} drift; review and regenerate`);
  }
  console.log(`${GENERATOR}: ${args[0] === '--write' ? 'generated' : 'no drift'}; ${JSON.stringify(JSON.parse(artifacts.report).counts)}`);
}
