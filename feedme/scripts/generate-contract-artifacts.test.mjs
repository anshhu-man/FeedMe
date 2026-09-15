// Run: node --test scripts/generate-contract-artifacts.test.mjs
// These checks prove metadata preservation and audited input rejection, not body validation or authorization.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {GENERATOR, auditContract, buildArtifacts, kotlinString, sha256} from './generate-contract-artifacts.mjs';

const canonicalText = fs.readFileSync(new URL('../../outputs/biteclub_blueprint/architecture/04_API_Contract.json', import.meta.url), 'utf8');
const registryText = fs.readFileSync(new URL('../../outputs/biteclub_blueprint/registry/screen_registry.json', import.meta.url), 'utf8');
const lock = JSON.parse(fs.readFileSync(new URL('./contract-source.lock.json', import.meta.url), 'utf8'));
const canonical = JSON.parse(canonicalText);
const registry = JSON.parse(registryText);
const generatorSourceSha256 = sha256(fs.readFileSync(new URL('./generate-contract-artifacts.mjs', import.meta.url)));
const inputs = {canonicalText, registryText, lock, generatorSourceSha256};
const artifacts = buildArtifacts(inputs);
const expectedCounts = {
  schemas: 188, paths: 155, operations: 201, features: 54, screens: 98,
  actions: 900, httpActions: 152, httpHydrations: 99, localExternalHydrations: 32,
};

function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value === null || typeof value !== 'object') return value;
  return Object.fromEntries(Object.keys(value).sort().map(key => [key, normalize(value[key])]));
}

// Generated code uses regular Kotlin literals. Decode Kotlin's extra dollar escape before JSON escapes.
function decodeKotlinString(literal) {
  return JSON.parse(literal.replaceAll('\\$', '$'));
}

function generatedString(source, name) {
  const signature = new RegExp(`    fun ${name}\\(\\): String = buildString\\((\\d+)\\) \\{\\n([\\s\\S]*?)\\n    \\}`);
  const match = source.match(signature);
  assert.ok(match, `Missing generated ${name} function`);
  const chunks = match[2].split('\n').map(line => {
    const append = line.match(/^        append\(("(?:[^"\\]|\\.)*")\)$/u);
    assert.ok(append, `Nonliteral generated append in ${name}`);
    const chunk = decodeKotlinString(append[1]);
    assert.ok(chunk.length <= 8000, 'Each Kotlin literal must remain below the JVM constant limit');
    return chunk;
  });
  const result = chunks.join('');
  assert.equal(result.length, Number(match[1]), `${name} length must account for every chunk`);
  return result;
}

const generatedDocument = JSON.parse(generatedString(artifacts.source, 'documentJson'));
const generatedBindings = JSON.parse(generatedString(artifacts.source, 'bindingsJson'));
const operation = (document, id) => Object.values(document.paths).flatMap(Object.values).find(op => op.operationId === id);
const action = (screenRegistry, predicate) => screenRegistry.screens.flatMap(screen => screen.actions).find(predicate);
const hydration = (screenRegistry, predicate) => screenRegistry.screens.flatMap(screen => screen.hydration ?? []).find(predicate);
const httpBinding = item => /^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE) /.test(item.request);

function resolve(document, value) {
  if (!value.$ref) return value;
  return resolve(document, value.$ref.slice(2).split('/').reduce((parent, key) =>
    parent[key.replaceAll('~1', '/').replaceAll('~0', '~')], document));
}

function assertTreeEqual(actual, expected, location = '#') {
  if (expected === null || typeof expected !== 'object') {
    assert.equal(actual, expected, location);
    return;
  }
  assert.equal(Array.isArray(actual), Array.isArray(expected), location);
  assert.deepEqual(Object.keys(actual).sort(), Object.keys(expected).sort(), `${location}: exact keys, including absent values`);
  for (const key of Object.keys(expected)) assertTreeEqual(actual[key], expected[key], `${location}/${key}`);
}

function rejectsDocument(name, mutate, error) {
  test(name, () => {
    const document = structuredClone(canonical);
    mutate(document);
    // Exercise the structural audit after an intentional source-lock update, not merely the hash guard.
    const changedText = JSON.stringify(document);
    assert.throws(() => buildArtifacts({...inputs, canonicalText: changedText,
      lock: {...lock, sha256: sha256(changedText)}}), error);
  });
}

function rejectsRegistry(name, mutate, error) {
  test(name, () => {
    const changedRegistry = structuredClone(registry);
    mutate(changedRegistry);
    assert.throws(() => buildArtifacts({...inputs, registryText: JSON.stringify(changedRegistry)}), error);
  });
}

test('generation is reproducible and checked-in artifacts match the locked inputs byte for byte', () => {
  assert.deepEqual(buildArtifacts(inputs), artifacts);
  assert.equal(artifacts.source, fs.readFileSync(new URL('../shared/contracts/src/commonMain/kotlin/com/feedme/contracts/generated/GeneratedContract.kt', import.meta.url), 'utf8'));
  assert.equal(artifacts.report, fs.readFileSync(new URL('../docs/verification/contract-artifacts/generated-receipt.json', import.meta.url), 'utf8'));
  const receipt = JSON.parse(artifacts.report);
  assert.equal(receipt.generator, GENERATOR);
  assert.equal(receipt.generatorSourceSha256, generatorSourceSha256);
  assert.equal(receipt.canonicalSha256, sha256(canonicalText));
  assert.equal(receipt.registrySha256, sha256(registryText));
  assert.equal(receipt.generatedSha256, sha256(artifacts.source));
  assert.deepEqual(receipt.counts, expectedCounts);
  assert.match(receipt.scope, /not body validation, transport or authorization/);
});

test('Kotlin chunks reconstruct exact canonical bytes and the full normalized document without losing any key or value', () => {
  assert.equal(generatedString(artifacts.source, 'documentJson'), canonicalText);
  assert.equal(JSON.stringify(normalize(generatedDocument)), JSON.stringify(normalize(canonical)));
  assertTreeEqual(generatedDocument, canonical);
  assert.equal(Object.keys(generatedDocument.components.schemas).length, 188);
  for (const [name, schema] of Object.entries(canonical.components.schemas)) {
    assert.deepEqual(generatedDocument.components.schemas[name], schema, `Every schema, property and constraint: ${name}`);
  }
});

test('missing, null, false, empty, and default values retain distinct meanings', () => {
  const pantry = generatedDocument.components.schemas.PantryWrite;
  assert.deepEqual(pantry.properties.confirmedAt.type, ['string', 'null']);
  assert.equal(pantry.required.includes('confirmedAt'), false);
  assert.equal(pantry.additionalProperties, false);
  assert.deepEqual(generatedDocument.components.schemas.ProfilePatch.required, []);
  const example = operation(generatedDocument, 'upsertPantryItem').requestBody.content['application/json'].example;
  assert.equal(Object.hasOwn(example, 'confirmedAt'), true);
  assert.equal(example.confirmedAt, null);
  assert.equal(Object.hasOwn(example, 'quantity'), false);
  assert.equal(Object.hasOwn(operation(generatedDocument, 'getMe'), 'requestBody'), false);
  assert.equal(Object.hasOwn(operation(generatedDocument, 'createGuestSession').requestBody.content['application/json'], 'example'), false);
  assert.equal(generatedDocument.components.parameters.Limit.schema.default, 20);
  assert.equal(Object.hasOwn(generatedDocument.components.parameters.Limit.schema, 'example'), false);
});

test('Kotlin escaping round-trips interpolation markers, JSON quotes, slashes, controls, and Unicode', () => {
  const values = ['', '$ref ${untrusted} $name', '"quoted" \\ slash / \n\r\t\b\f\u0000', 'café देवनागरी 😀 \u2028\u2029', '\\${literal}'];
  for (const value of values) {
    assert.equal(decodeKotlinString(kotlinString(value)), value);
    assert.equal(kotlinString(value).includes('\u2028'), false);
    assert.equal(kotlinString(value).includes('\u2029'), false);
  }
  assert.equal(kotlinString('${value}'), '"\\${value}"');
});

test('long descriptions and example data survive chunk boundaries and literal example references', () => {
  const document = structuredClone(canonical);
  document.info.description = 'a'.repeat(7999) + '😀' + 'b'.repeat(8000) + '\n\u2028\u2029 $ref ${literal} \\ "';
  operation(document, 'createGuestSession').requestBody.content['application/json'].example = {
    note: '${literal}', $ref: 'https://example.invalid/example-data', explicitNull: null,
  };
  const changedText = JSON.stringify(document);
  const generated = buildArtifacts({...inputs, canonicalText: changedText, lock: {...lock, sha256: sha256(changedText)}});
  assertTreeEqual(JSON.parse(generatedString(generated.source, 'documentJson')), document);
});

test('all operation methods, routes, headers, statuses, security alternatives, and guard metadata are retained', () => {
  const audit = auditContract(canonical, registry);
  const expectedOperations = [];
  const principalCounts = {public: 0, user: 0, both: 0, admin: 0, webhook: 0};
  let noContent = 0;
  for (const [route, pathItem] of Object.entries(canonical.paths)) {
    for (const [method, original] of Object.entries(pathItem)) {
      const retained = generatedDocument.paths[route][method];
      expectedOperations.push({id: original.operationId, request: `${method.toUpperCase()} ${route}`, principal: original['x-principal']});
      principalCounts[retained['x-principal']]++;
      for (const key of ['operationId', 'description', 'security', 'parameters', 'responses', 'x-feature-ids', 'x-module', 'x-principal', 'x-idempotency-required']) {
        assert.deepEqual(retained[key], original[key], `${original.operationId}: ${key}`);
      }
      const parameters = retained.parameters.map(parameter => resolve(generatedDocument, parameter));
      assert.equal(parameters.some(parameter => parameter.in === 'header' && parameter.name === 'Idempotency-Key' && parameter.required), retained['x-idempotency-required']);
      const device = parameters.find(parameter => parameter.in === 'header' && parameter.name === 'X-Device-Session');
      if (retained['x-principal'] === 'both') assert.equal(device.required, false);
      else if (retained['x-principal'] === 'user' && retained.operationId !== 'bootstrapAccount') assert.equal(device.required, true);
      else assert.equal(device, undefined);
      for (const [status, response] of Object.entries(retained.responses)) {
        assert.match(status, /^[1-5][0-9]{2}$/);
        assert.deepEqual(resolve(generatedDocument, response), resolve(canonical, original.responses[status]));
        if (status === '204') {
          noContent++;
          assert.equal(Object.hasOwn(resolve(generatedDocument, response), 'content'), false);
        }
      }
    }
  }
  assert.deepEqual(audit.operations, expectedOperations);
  assert.deepEqual(principalCounts, {public: 3, user: 123, both: 36, admin: 38, webhook: 1});
  assert.ok(noContent > 0);
  assert.deepEqual(generatedDocument.components.securitySchemes, canonical.components.securitySchemes);
});

test('all 900 actions and 131 hydrations retain identity, request, operation and screen association', () => {
  const expected = [];
  for (const screen of registry.screens) {
    for (const item of screen.actions) expected.push({screenId: screen.id, id: item.id,
      kind: 'action', request: item.request, operationId: item.operationId ?? null});
    for (const [index, item] of (screen.hydration ?? []).entries()) expected.push({screenId: screen.id,
      id: `${screen.id}.load.${index}`, kind: 'hydration', request: item.request, operationId: item.operationId ?? null});
  }
  assert.deepEqual(generatedBindings, expected);
  assert.equal(generatedBindings.length, 1031);
  assert.equal(new Set(generatedBindings.map(binding => binding.id)).size, 1031);
  const actions = generatedBindings.filter(binding => binding.kind === 'action');
  const hydrations = generatedBindings.filter(binding => binding.kind === 'hydration');
  assert.equal(actions.length, 900);
  assert.equal(actions.filter(httpBinding).length, 152);
  assert.equal(actions.filter(binding => binding.request.startsWith('LOCAL ')).length, 718);
  assert.equal(actions.filter(binding => binding.request.startsWith('EXTERNAL ')).length, 30);
  assert.equal(hydrations.filter(httpBinding).length, 99);
  assert.equal(hydrations.filter(binding => !httpBinding(binding)).length, 32);
  for (const binding of generatedBindings) {
    if (httpBinding(binding)) assert.ok(operation(generatedDocument, binding.operationId));
    else assert.equal(binding.operationId, null);
  }
  assert.deepEqual(auditContract(canonical, registry).counts, expectedCounts);
});

test('canonical bytes cannot change without intentionally updating the source lock', () => {
  assert.throws(() => buildArtifacts({...inputs, canonicalText: `${canonicalText}\n`}), /Canonical source hash changed/);
  assert.throws(() => buildArtifacts({...inputs, lock: {...lock, sha256: '0'.repeat(64)}}), /Canonical source hash changed/);
});

test('generator identity and OpenAPI lock mismatches fail before emitting artifacts', () => {
  assert.throws(() => buildArtifacts({...inputs, lock: {...lock, generator: 'unreviewed-generator'}}), /Generator lock mismatch/);
  assert.throws(() => buildArtifacts({...inputs, lock: {...lock, openapi: '3.0.3'}}), /OpenAPI lock mismatch/);
});

test('registry byte changes appear in the receipt even if binding meaning is unchanged', () => {
  const result = buildArtifacts({...inputs, registryText: `${registryText}\n`});
  assert.equal(result.source, artifacts.source);
  assert.notEqual(JSON.parse(result.report).registrySha256, JSON.parse(artifacts.report).registrySha256);
});

rejectsDocument('unreviewed OpenAPI version is rejected', d => { d.openapi = '3.0.3'; }, /OpenAPI lock mismatch/);
rejectsDocument('unreviewed schema keyword is rejected', d => { d.components.schemas.Empty.not = {}; }, /Unreviewed schema keyword/);
rejectsDocument('nested unreviewed schema keyword is rejected', d => { d.components.schemas.Profile.properties.displayName.unevaluatedProperties = false; }, /Unreviewed schema keyword/);
rejectsDocument('unreviewed schema format is rejected', d => { d.components.schemas.Profile.properties.displayName.format = 'email'; }, /Unreviewed schema format/);
rejectsDocument('unsupported boolean schema is rejected', d => { d.components.schemas.Empty = true; }, /Unsupported schema shape/);
rejectsDocument('invalid schema type is rejected', d => { d.components.schemas.Empty.type = 'impossible'; });
rejectsDocument('unused parameter component schemas are audited', d => {
  d.components.parameters.Unused = {name: 'unused', in: 'query', schema: {type: 'string', not: {const: 'x'}}};
}, /Unreviewed schema keyword/);
rejectsDocument('unused response component schemas are audited', d => {
  d.components.responses.Unused = {description: 'Unused response', content: {'application/json': {schema: {type: 'string', not: {const: 'x'}}}}};
}, /Unreviewed schema keyword/);
rejectsDocument('default response selector requires review', d => { operation(d, 'getMe').responses.default = {description: 'Unknown response'}; }, /Unreviewed response status selector/);
rejectsDocument('wildcard response selector requires review', d => { operation(d, 'getMe').responses['2XX'] = {description: 'Any success'}; }, /Unreviewed response status selector/);
rejectsDocument('204 response cannot gain a body', d => {
  operation(d, 'removePantryItem').responses['204'].content = {'application/json': {schema: {type: 'string'}}};
}, /204 cannot have response content/);
rejectsDocument('unreviewed response media is rejected', d => {
  operation(d, 'getMe').responses['200'].content['text/plain'] = {schema: {type: 'string'}};
}, /Unreviewed response media/);
rejectsDocument('response header schemas are audited', d => {
  operation(d, 'getMe').responses['200'].headers.ETag.schema.not = {};
}, /Unreviewed schema keyword/);
rejectsDocument('optional request bodies require review', d => { operation(d, 'createGuestSession').requestBody.required = false; }, /Optional request body needs review/);
rejectsDocument('unreviewed request media is rejected', d => {
  operation(d, 'createGuestSession').requestBody.content['text/plain'] = {schema: {type: 'string'}};
}, /Unreviewed request media/);
rejectsDocument('inline request schemas are audited', d => {
  operation(d, 'createGuestSession').requestBody.content['application/json'].schema = {type: 'string', not: {}};
}, /Unreviewed schema keyword/);

rejectsDocument('unresolved schema reference is rejected', d => { d.components.schemas.Empty = {$ref: '#/components/schemas/Missing'}; }, /Unresolved reference/);
rejectsDocument('remote schema references are rejected', d => { d.components.schemas.Empty = {$ref: 'https://example.invalid/schema.json'}; }, /Unsupported schema reference namespace/);
rejectsDocument('schema references outside the schema namespace are rejected', d => { d.components.schemas.Empty = {$ref: '#/components/parameters/Limit'}; }, /Unsupported schema reference namespace/);
rejectsDocument('cyclic schema alias references are rejected', d => { d.components.schemas.Empty = {$ref: '#/components/schemas/Empty'}; }, /Cyclic reference alias/);
rejectsDocument('unresolved parameter references are rejected', d => { operation(d, 'getMe').parameters.push({$ref: '#/components/parameters/Missing'}); }, /Unresolved reference/);
rejectsDocument('remote parameter references are rejected', d => { operation(d, 'getMe').parameters.push({$ref: 'https://example.invalid/parameters.json'}); }, /Only bundled local references/);
rejectsDocument('unresolved response references are rejected', d => { operation(d, 'getMe').responses['200'] = {$ref: '#/components/responses/Missing'}; }, /Unresolved reference/);
rejectsDocument('remote response references are rejected', d => { operation(d, 'getMe').responses['200'] = {$ref: 'https://example.invalid/responses.json'}; }, /Only bundled local references/);

rejectsDocument('unknown principal is rejected', d => { operation(d, 'getMe')['x-principal'] = 'owner'; }, /Unreviewed principal/);
rejectsDocument('operation cannot inherit an implicit default security policy', d => { delete operation(d, 'getMe').security; }, /Principal\/security alternatives mismatch/);
rejectsDocument('public security cannot grant anonymous access to a user operation', d => { operation(d, 'getMe').security = []; }, /Principal\/security alternatives mismatch/);
rejectsDocument('guest and user alternatives cannot become a combined requirement', d => { operation(d, 'getPreferences').security = [{UserBearer: [], GuestBearer: []}]; }, /Principal\/security alternatives mismatch/);
rejectsDocument('staff operation cannot use consumer credentials', d => { operation(d, 'adminListFlags').security = [{UserBearer: []}]; }, /Principal\/security alternatives mismatch/);
rejectsDocument('root default security drift requires review', d => { d.security = []; });
rejectsDocument('missing referenced security scheme is rejected', d => { delete d.components.securitySchemes.UserBearer; });
rejectsDocument('operation callbacks require explicit review', d => { operation(d, 'getMe').callbacks = {unreviewed: {}}; });
rejectsDocument('missing required device session is rejected', d => { operation(d, 'getMe').parameters = []; }, /Device-session policy/);
rejectsDocument('optional device session cannot weaken user-only operations', d => { operation(d, 'getMe').parameters[0].required = false; }, /Device-session policy/);
rejectsDocument('guest alternative retains conditional device-session policy', d => { operation(d, 'getPreferences').parameters[0].required = true; }, /Device-session policy/);
rejectsDocument('public operation cannot gain a device session requirement', d => { operation(d, 'createGuestSession').parameters.push({...d.components.parameters.DeviceSession}); }, /Device-session policy/);
rejectsDocument('device session name in query cannot satisfy the header requirement', d => { operation(d, 'getMe').parameters[0].in = 'query'; });
rejectsDocument('missing idempotency declaration is rejected', d => { delete operation(d, 'getMe')['x-idempotency-required']; }, /Missing idempotency declaration/);
rejectsDocument('idempotency declaration cannot contradict required header', d => { operation(d, 'createGuestSession')['x-idempotency-required'] = false; }, /Idempotency header mismatch/);
rejectsDocument('optional idempotency header cannot satisfy durable command requirement', d => { d.components.parameters.IdempotencyKey.required = false; }, /Idempotency header mismatch/);
rejectsDocument('idempotency name in query cannot satisfy the header requirement', d => { d.components.parameters.IdempotencyKey.in = 'query'; });

rejectsDocument('unexpected route namespace is rejected', d => { d.paths['/v2/me'] = d.paths['/v1/me']; delete d.paths['/v1/me']; }, /Unexpected route namespace/);
rejectsDocument('path-item fields cannot be silently interpreted as operations', d => { d.paths['/v1/me'].summary = 'Unreviewed path item'; }, /Unreviewed path-item field/);
rejectsDocument('path template and parameter names must match', d => { operation(d, 'removePantryItem').parameters[0].name = 'wrongId'; }, /Path parameters do not match template/);
rejectsDocument('path parameter must remain required', d => { operation(d, 'removePantryItem').parameters[0].required = false; }, /Path parameter must be required/);
rejectsDocument('operation IDs must be valid', d => { operation(d, 'getMe').operationId = 'get me'; }, /Invalid operation ID/);
rejectsDocument('operation IDs must be globally unique', d => { operation(d, 'getMe').operationId = 'updateMe'; }, /Duplicate operation ID/);
rejectsDocument('cookie parameter requires review', d => { operation(d, 'getMe').parameters.push({name: 'cookie', in: 'cookie', schema: {type: 'string'}}); }, /Unreviewed parameter location/);
rejectsDocument('header duplicate detection is case insensitive', d => { operation(d, 'getMe').parameters.push({...operation(d, 'getMe').parameters[0], name: 'x-device-session'}); }, /Duplicate operation parameter/);

rejectsDocument('unknown feature ID in an operation is rejected', d => { operation(d, 'getMe')['x-feature-ids'].push('F99'); }, /Unknown operation feature/);
rejectsRegistry('duplicate feature ID is rejected', r => { r.features[1].id = r.features[0].id; }, /Duplicate feature\/screen IDs/);
rejectsRegistry('duplicate screen ID is rejected', r => { r.screens[1].id = r.screens[0].id; }, /Duplicate feature\/screen IDs/);
rejectsRegistry('duplicate action ID is rejected without changing counts', r => { r.screens[0].actions[1].id = r.screens[0].actions[0].id; }, /Duplicate binding ID/);
rejectsRegistry('action IDs cannot collide with generated hydration IDs', r => { r.screens[0].actions[0].id = `${r.screens[0].id}.load.0`; }, /Duplicate binding ID/);
rejectsRegistry('HTTP action with unresolved operation is rejected', r => { action(r, httpBinding).operationId = 'missingOperation'; }, /Unresolved HTTP binding/);
rejectsRegistry('HTTP action with missing operation is rejected', r => { delete action(r, httpBinding).operationId; }, /Unresolved HTTP binding/);
rejectsRegistry('HTTP action with wrong method is rejected', r => { action(r, httpBinding).request = 'GET /v1/guest-sessions'; }, /Unresolved HTTP binding/);
rejectsRegistry('HTTP action with wrong path is rejected', r => { action(r, httpBinding).request = 'POST /v1/unreviewed'; }, /Unresolved HTTP binding/);
rejectsRegistry('swapping operation bindings is rejected even when all binding counts remain equal', r => {
  const first = action(r, httpBinding);
  const second = action(r, item => httpBinding(item) && item.operationId !== first.operationId);
  [first.operationId, second.operationId] = [second.operationId, first.operationId];
}, /Unresolved HTTP binding/);
rejectsRegistry('HTTP hydration with wrong operation is rejected', r => { hydration(r, httpBinding).operationId = 'createGuestSession'; }, /Unresolved HTTP binding/);
rejectsRegistry('LOCAL action cannot acquire an HTTP operation', r => { action(r, item => item.request.startsWith('LOCAL ')).operationId = 'getMe'; }, /Local\/external binding must not call an HTTP operation/);
rejectsRegistry('EXTERNAL action cannot acquire an HTTP operation', r => { action(r, item => item.request.startsWith('EXTERNAL ')).operationId = 'getMe'; }, /Local\/external binding must not call an HTTP operation/);
rejectsRegistry('LOCAL hydration cannot acquire an HTTP operation', r => { hydration(r, item => item.request.startsWith('LOCAL ')).operationId = 'getMe'; }, /Local\/external binding must not call an HTTP operation/);
rejectsRegistry('EXTERNAL hydration cannot acquire an HTTP operation', r => { hydration(r, item => item.request.startsWith('EXTERNAL ')).operationId = 'getMe'; }, /Local\/external binding must not call an HTTP operation/);
rejectsRegistry('unreviewed binding kinds are rejected', r => { r.screens[0].actions[0].request = 'RPC getMe'; }, /Unreviewed local\/external binding/);
