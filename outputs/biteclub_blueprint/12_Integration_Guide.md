# Integration guide: from blueprint to working software

## Repository layout to implement

```text
feedme/
  apps/android/                 Android shell, OS integrations, signing
  apps/ios/                     iOS shell, OS integrations, signing
  shared/core/                  Result/error types, clock, IDs, navigation contracts
  shared/design/                Theme tokens and accessible components
  shared/data/                  Generated transport DTOs, repositories, SQLite cache
  shared/features/{domain}/     ViewModels and feature UI; no direct vendor coupling
  server/api/                   Ktor routing, auth, request validation
  server/modules/{domain}/      Use cases and domain authorization
  server/persistence/           PostgreSQL migrations and transactional repositories
  server/workers/               Outbox dispatch, media, notifications, reconciliation
  staff/                        Workforce-authenticated review/moderation UI
  contracts/                    OpenAPI, events, content schemas, generated fixtures
  infra/                        Environment-specific IaC and deployment pipelines
  tests/{contract,device,e2e}/   Cross-module and native acceptance suites
```

This tree is a proposed implementation structure, not an existing cloned Smart Kitchen repository. Inspect that code before deciding which modules are reusable. Pin Kotlin, Compose, Ktor, Gradle, Xcode and platform minimum versions together in a compatibility spike; the blueprint does not claim an untested version combination is production-ready.

## Common service boundaries

Feature UI emits a typed intent → ViewModel validates presentation state → domain use case checks local preconditions → repository calls a versioned API or native adapter → server independently authorizes and validates → transaction writes domain state and outbox → receipt updates UI → worker handles asynchronous effects. Cross-module calls inside the monolith use interfaces and a shared transaction boundary only when the invariant requires atomicity. Do not turn every feature into a network microservice.

The LLM adapter returns only a strict interpreted request. It cannot write pantry/profile data, choose unknown ingredients as certain, grant access, approve recipes or override exclusions. The reviewed-catalog planner owns supported matching and produces reproducible versioned receipts. Keep provider keys, prompts and request processing server-side with documented retention/redaction.

## Transport conventions

Treat architecture/04_API_Contract.json as the HTTP source of truth. Generate Kotlin DTO/client bindings in a dedicated implementation step and compile both app/server against the generated types. Stable operation IDs map to screen button records. Validate request bodies strictly, reject unknown fields where specified, and use discriminators for polymorphic resources. Every resource ID is an opaque identifier, never a permission grant.

After `POST /v1/account/bootstrap`, save `Bootstrap.sessionId` with the account's secure session state and attach it as `X-Device-Session` alongside the access token on registered-user API calls. Bootstrap itself has no prior session header; explicitly guest-enabled calls use the guest token instead. The backend validates that session's subject binding and revocation so a signed but revoked JWT session cannot keep calling APIs. Profile routing reads `Bootstrap.profile.onboardingStep` and `requiredGates`; resource versions are named `version` and transported as quoted ETags.

Durable writes carry a local command UUID as the `Idempotency-Key` header and, when specified by the operation, the current quoted version as `If-Match`. Do not add an invented `clientOperationId` or `clientReportId` body field to a schema that does not define it. Domain-specific body IDs such as `clientMessageId` and `clientDraftId` are additional explicit contracts. The server binds idempotency to actor+operation+payload hash, returns the previous receipt on same-intent replay, and rejects a reused key with different payload. Database unique constraints enforce invariants independently of UI debouncing. Concurrent preference/exclusion/audience edits require reconciliation; do not hide them with unconditional last-write-wins.

Use a common typed error envelope (see API schema), a safe request ID and actionable error categories. Authentication challenge, domain rejection, permission loss, conflict, rate limit and transient provider failures remain distinct. A timeout after a mutation is an unknown receipt state: query/retry with the same key before claiming success or creating another operation.

## Model-to-API integration

`spec/model.mjs` is the human-authored product inventory. `spec/refinements.mjs` applies canonical route alignment, hydration, picker return and lifecycle actions. `scripts/build.mjs` produces JSON/Markdown/CSV diagrams and the local explorer. `scripts/validate.mjs` checks feature/doc coverage, graph targets/reachability, button IDs and API references. Generated artifacts must not be hand-edited; update their source and regenerate.

The registry explicitly distinguishes `LOCAL` draft/navigation intents, `EXTERNAL` native/provider operations and HTTP methods. A native OAuth/purchase/share-sheet interaction must not be invented as a FeedMe API call. On-screen confirmation for a destructive action dispatches the exact stored original command. Production navigation uses typed contexts so a picker selection returns to its correct composer, poll, pact or SOS draft.

## Cross-feature recipes and rights

Recipes have immutable revision identity, content rights and review state. A source post may offer private recipe copying without redistribution rights. Automatic Make Mine matching requires an authorized reviewed catalog revision or a supported reviewed variant; permission to read a personal user recipe alone does not satisfy review eligibility. It cannot infer a complete safe recipe from a photo. Sharing a variation may include the user's own photo/change note and a permitted source chip without republishing private source media/text.

Private saved recipes, collection membership and explicit Make Again feedback are separate domain concepts. `POST /v1/saved-recipes` creates the save; attaching an existing save to a collection does not recreate content. A source-post grant save uses the dedicated atomic post recipe-save command. Make Again is explicitly tagged; a neutral Save must not be treated as proof the user tried or enjoyed a meal.

## Offline and account lifecycle contract

The API does not issue signed offline entitlement/content/recall leases. Previously verified owner-scoped pinned cooking and permitted basic cached recipe reads can continue with last-sync/last-verification time visible. A locally known recalled recipe is unavailable, and a disconnected client cannot guarantee knowledge of later recalls or access changes. New protected actions, premium tools, social effects and shared mutations require live authentication and server authorization. Account logout/switch clears old account caches and app-session state.

`POST /v1/account/exports` returns `Accepted.jobId`; `GET /v1/jobs/{jobId}` reports status and a bounded owner-authorized download capability when ready. Normal active-data deletion objective is 24 hours after immediate access suspension, with separately justified retention exceptions and truthful stage status. Provider-account linking is not implemented in this release; recovery directs users to the provider already associated with the account.

## Shared state and integration events

AccountRestricted/Deleted, CircleMembershipChanged, PostAudienceChanged, PostExpired, PostDeleted, MediaReady/Rejected, RecipePublished/Recalled, SaveGranted, CookCompleted, FeedbackChanged, EntitlementChanged and coordination state changes have specific producer and consumer ownership. Exact event schemas and retry semantics are in architecture/05_Events.md. Consumers store processed event IDs; delete/recall events outrank stale publish and notification jobs.

Keep user food context out of fan-out events. Consumers re-read authorized current state when needed. Feed/notification workers are projections, not access-control sources. A response cache key includes actor or audience scope and relevant revision/epoch. Membership leave/rejoin generations prevent accidental restoration of old audience authority.

## Environments and deployment workflow

Use separate dev/staging/prod identities, databases, storage, providers, signing keys and billing sandboxes. Dev uses synthetic data. Staging runs production-like IAM boundaries and migrations. Secrets come from managed storage and are scoped by workload. Staff tooling and member API share core domain rules but use separate audiences/roles. Do not expose internal staff roles via a client flag.

Implement expand→migrate/backfill→contract database changes and N/N-1 client contract compatibility. Deploy migration/backfill independently from irreversible schema removal. Feature flags gate both client visibility and server commands, with cohort percentages, dependency gates and a rollback revision. Idempotency/consumer state is retained through rollback. Do not rebuild or reset production data to recover a bad app release.

## Verification before enabling a feature

For each feature, require its unique acceptance cases, API/schema contract validation, backend authorization tests, native UI/accessibility checks, offline/lost-response tests, event replay tests and operational owner. For social/commerce/media, use provider sandboxes and real devices; a local navigation simulation is not sufficient. Keep a route/action ID in each E2E journey so coverage connects directly to the screen map.

Treat review staffing, catalog rights, native provider setup, store offerings, approved legal/retention policy, notification permission behavior, media quarantine testing, restore drills and secret management as concrete release dependencies. They are planned and owned here, not completed simply because they appear in this document.
