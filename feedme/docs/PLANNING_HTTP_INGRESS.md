# Explicitly configured planning HTTP ingress

14 September 2026. Four canonical planning operations now have a real Ktor HTTP adapter over the transactional PostgreSQL `PlansStore`. This is a bounded integration foundation, **not an enabled production service or working provider login**. Focused central verification passed **88 server unit tests and 119 real-PostgreSQL integration tests**; the current batch's full combined source-bound verification is still pending at this handoff.

The implementation is in [PlanningHttpConfiguration](../server/src/main/kotlin/com/feedme/server/http/PlanningHttpConfiguration.kt), [PlanningHttpRoutes](../server/src/main/kotlin/com/feedme/server/http/PlanningHttpRoutes.kt), and [FeedMeApplication](../server/src/main/kotlin/com/feedme/server/http/FeedMeApplication.kt). It exposes the existing [planning persistence service](SERVER_PLANNING_PERSISTENCE.md); it does not replace its authority, evidence, cursor or transaction checks.

## Four operations, no implicit activation

Subsequent acceptance: the [full third-batch run](PARALLEL_UI_HTTP_PROCESS.md) passed at2026-09-13T23:30:01.566Z, including all88 server and119 real-PostgreSQL methods. The focused handoff/pending-run wording above and below records the earlier checkpoint; full component verification is now accepted, without enabling default Main or providing production authority adapters.

| Operation | HTTP request | Successful response |
| --- | --- | --- |
| `createPlan` | `POST /v1/plans` | `201`, canonical Plan, version ETag |
| `getPlan` | `GET /v1/plans/{planId}` | `200`, canonical owned Plan, version ETag |
| `getPlanExplanation` | `GET /v1/plans/{planId}/explanation` | `200`, canonical explanation page, no ETag |
| `nextPlan` | `POST /v1/plans/{planId}/alternatives` | `200`, canonical next Plan, version ETag |

`feedMeLocalService(..., planning = configuration)` is the explicit integration point. `PlanningHttpConfiguration` requires an environment, actual `PlansStore`, mandatory `PlanningHttpVerifier` and caller-owned database dispatcher. It has no accepting default. The environment is a bounded lower-case deployment identifier and must match both verified principals and the separately configured store.

[Main](../server/src/main/kotlin/com/feedme/server/Main.kt) supplies no planning configuration. It remains loopback-only, returns degraded health, and leaves all product operations at `503 OPERATION_NOT_IMPLEMENTED`. Supplying planning configuration enables only these four routes: preference, recipe, social, configuration and other product operations remain unavailable. There is no environment-variable production-enable switch or deployment change in this package.

The adapter does not broaden the store's supported product inputs. For example, unsupported social/saved sources, nonempty natural language, tonight intent and household inputs remain closed through the store's existing `NOT_CONFIGURED` gate. Alternatives currently accept the bounded canonical alternative path, not arbitrary new planning modes or mutable parent replacement.

## Identity must come from a real verifier and current authority

`PlanningHttpBearer` carries secret input, not a verified principal. Every configured operation requires a single Bearer Authorization header. The adapter does not infer account/guest kind from token spelling or from the presence of `X-Device-Session`.

The mandatory verifier contract requires independently checking the actual account token's issuer, signature, audience/client, token use, scope and expiry, or the actual authenticated bounded guest session. Its result must bind the verified environment, principal kind, subject and device. Account requests require a canonical UUID device header matching the verified account device; guests must have neither a device header nor a verified device. Environment/kind/device mismatch is `401 UNAUTHENTICATED`. Constructing a `VerifiedPlanningPrincipal` is not by itself authentication.

Verification occurs after HTTP metadata parsing and before body decoding or store access. The store then rechecks current principal eligibility, device/revocation or guest expiry/merge inside the PostgreSQL transaction, **before a cached command reply can be disclosed**. Private plans are scoped by environment, actor kind and principal. A foreign plan and an absent plan receive the same private `PLAN_UNAVAILABLE` response; a path UUID or cursor is never an authorization grant.

`PlanningAuthority` remains mandatory production integration. Its implementation must lock current eligibility and authoritative preferences, pantry, authorized catalog/taxonomy, immutable recipe material and independent editorial evidence. Principal, receipt, lineage and policy/input lock ordering must agree with lifecycle writers. These callbacks perform database work on the owning transaction, not remote provider calls or independent commits. No production provider verifier or accepting authority adapter was added here.

## Request framing and bounded parsing

The service profile permits at most **65,536 received bytes** for a planning request, JSON depth **32**, and at most **262,144 bytes** for a successful serialized planning response. These are service bounds, not changes to the canonical API schemas.

- The adapter rejects duplicate consumed security/framing headers, control characters, malformed canonical UUIDs and ambiguous values. Bearer token content is bounded to 16,384 characters. External UUID hex case is accepted; Java's abbreviated UUID forms are not.
- Both POST operations require exactly one canonical UUID `Idempotency-Key`. GET operations reject that header and any nonempty body. They also reject Content-Type, transfer encoding, and a nonzero declared body length.
- POST accepts `application/json`, optionally one UTF-8 charset parameter. Other or ambiguous media types return `400 UNSUPPORTED_MEDIA`; encoded bodies other than identity are rejected. No decompression or charset guessing is performed.
- Content-Length must be a bounded nonnegative decimal integer and must match actual received bytes. If transfer encoding is supplied, only chunked POST framing without Content-Length is accepted. Streaming also stops at the actual size bound, including when a length was omitted.
- Only explanation reads accept query parameters: unique `cursor` and `limit`. Cursor text is bounded to 2,048 characters; the limit defaults to 20 and accepts canonical decimal integers from 1 through 50. All other query names and duplicate values are rejected.
- All four operations reject `If-Match` and `If-None-Match`; there is no invented conditional GET/304 behavior or alternatives If-Match contract. The alternatives body's exact continuation cursor pins the parent instead.

Original bytes are parsed and validated before conversion to the service JSON API. Malformed UTF-8/Unicode, duplicate JSON names, malformed JSON, invalid framing and oversize bodies are `400 INVALID_REQUEST`. Structurally valid JSON that violates the canonical request schema is `422 INPUT_INVALID`. Numeric lexemes are preserved without passing through Double, including precise serving quantities. Body/read cancellation propagates; it is not converted into a successful parse or an ordinary input failure.

These byte/depth/header/page limits are **not request-rate quotas**. New create/alternative mutations call the mandatory `requireNewPlanningEnabledAndQuota` authority hook. Actual production quota accounting, deployment limits and the feature-enable decision are still required; the HTTP fixture's authority deliberately does not implement them. A verifier may return a typed rate-limit result, but the existence of `429` mapping does not prove a production limiter.

## Durable replies, ETags and reconciliation

The store commits plan/lineage changes, durable command receipt and outbox event atomically. Repeating the same owner/operation/key and matching canonical request returns the original response rather than creating another plan or advancing an alternative twice. Request identity includes the operation and relevant path as well as the body. Reusing a key with a different request is `409 IDEMPOTENCY_MISMATCH`; an expired receipt is `410 IDEMPOTENCY_EXPIRED`; an incomplete receipt is `409 COMMAND_INCOMPLETE`. HTTP ingress never generates a replacement idempotency key or automatically repeats a mutation.

Replay still requires current authority. Revocation, expired guest sessions, recall and changed selection inputs can deny a formerly successful ready reply. Historical owned reads and new ready selection have different store rules: immutable historical material is not rewritten, and current recall remains a barrier. Original ordering/provenance, exact parent cursor and the single lineage head remain the persistence service's responsibility, not client-controlled fields.

Successful responses are validated against the exact operation/status schema before publication. Plan responses require a quoted numeric ETag matching the Plan's version; explanation responses must not carry one. Returning an ETag does not enable an undeclared conditional request. Malformed stored response bodies or version/ETag disagreement cannot be promoted to canonical HTTP success.

`runInterruptible` dispatches blocking JDBC work to the supplied dispatcher. Cancellation is propagated and can interrupt database work, but cancelling an HTTP caller cannot undo an already committed transaction. An uncertain COMMIT receipt maps to `503 OUTCOME_UNKNOWN`, not rollback or success. The client must retain the original command identity for reconciliation. Tests inject application-level response loss **after an actual successful COMMIT** and recover one durable result with exact retry; this is not evidence of surviving a physical fsync failure. A separately tested pre-commit outbox failure rolls back plan, receipt and outbox together.

## Problems and private response handling

Typed ingress and service failures are canonical `application/problem+json` responses. Missing/invalid authentication is 401; verifier forbidden is 403; verifier rate limit is 429; unavailable/thrown verifier failures are sanitized as `503 AUTHENTICATION_UNAVAILABLE`. Store failures retain their typed codes, including private not-found, expiry, changed inputs, recall, unavailable storage and unsupported configuration. Other unexpected exceptions become a fixed `500 INTERNAL_ERROR`; cancellation is not swallowed.

Trusted 429/503 verifier retry metadata is preserved: a nonnegative `retryAfterSeconds` appears in the Problem body when supplied, and only a positive value becomes the Retry-After header. Zero remains exact in the body without inventing a positive delay. Unconfigured operations do not promise a retry time.

Every response receives a server-generated `X-Trace-Id`, `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. Incoming trace IDs are ignored. Explicit operation Problems such as private `PLAN_UNAVAILABLE` survive the routing fallback; unknown routes use a generic `ROUTE_NOT_FOUND`. This layer does not log or echo raw exceptions, request URIs, bodies, bearer tokens or principals. It is not a claim about arbitrary surrounding proxy/server logging configuration.

## Focused evidence and remaining release gates

[PlanningHttpInputTest](../server/src/test/kotlin/com/feedme/server/http/PlanningHttpInputTest.kt) adds **17 unit methods** covering metadata/header/query/body bounds, strict canonical formats and numeric/Unicode handling, cancellation and invalid reply rejection. [PlanningHttpIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/http/PlanningHttpIntegrationTest.kt) adds **18 real-PostgreSQL methods** covering all four routes, exact replay and alternatives, account/guest separation, current revocation/expiry/recall, no-effect rejection, private errors, response-loss/cancellation and default-closed behavior. One method starts an actual loopback CIO socket and checks successful planning plus duplicate Authorization rejection without a second durable effect; the other HTTP cases use Ktor's test application against real PostgreSQL.

The verifier in these tests recognizes explicitly synthetic fixture tokens. Their principal/input tables and authority are test-only. They prove HTTP-to-transaction integration and protocol denial behavior, **not real login, provider availability, production quota enforcement or editorial/catalog integration**. The focused 88-unit/119-PG pass does not replace the pending full batch receipt.

Production provider/bootstrap configuration, current session and catalog authority adapters, cross-module lock-order review, real quotas, deployment/TLS/proxy policy, application UI/client integration and full source-bound release verification remain gates. Android process-interruption evidence and iOS/Xcode acceptance are separate. This package does not deploy, publish, submit, alter the approved V1 feature deferrals or declare the included features release-ready.
