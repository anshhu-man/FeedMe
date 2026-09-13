# Client command policy source audit

Source audit and transport validation seam dated 2026-09-13. This note interprets the pinned blueprint for client implementation; it does not modify its 54 features, 98 screens, operations, or schemas. The operation names below are exact `operationId` values from `../outputs/biteclub_blueprint/architecture/04_API_Contract.json` relative to the repository root.

## Scope of the canonical policy

The canonical offline policy is [05_Events.md](../../outputs/biteclub_blueprint/architecture/05_Events.md#offline-command-queue), lines 105–115. It is a command-family table, **not an exhaustive automatic-replay allowlist**. `x-idempotency-required` describes safe identity for a logical command; it does not authorize initial submission of every mutation after an offline delay.

Separate these cases:

- A new local intention that has never been submitted, which needs its feature's offline policy and any confirmation/current-state prerequisites.
- An explicitly submitted logical command whose response was lost, which must keep its original key and intent while reconciling its possible committed result.
- A rejected command requiring changed input or a new user decision, which must not silently become a different command under the old key.

The generic queue can conservatively support the established private subset while returning an explicit unsupported/confirmation-needed state for operations requiring a feature-specific adapter. That is an implementation restriction, not a claim that the remaining features forbid all queued intentions.

## Exact operation mappings and required distinctions

API line references below identify each operation declaration. Features are under `../outputs/biteclub_blueprint/features` relative to the repository root.

| Exact operation IDs | Canonical source support | Constraint on automatic processing |
|---|---|---|
| `updateCookSession` (2555), `completeCookSession` (2677) | Events:109; F12:16–19,27,35 | Existing owned, verified pinned cook; persist locally first; ordered session/device commands; preserve conflicts and completed state. F36:19 also permits existing private notes through this session queue. |
| `updatePreferences` (664) | Events:110; F04:35; F06 | Pending private edits. Never use stale permissive exclusions; whole-record stale ETag requires current representation and user conflict choice. |
| `upsertPantryItem` (976) | Events:110; F03:35 | Per-item stable mutation identity; show both conflicting values. Unknown ingredients stay drafts. |
| `removePantryItem` (1093) | F03:35,50 explicitly covers queued pantry edits and offline deletion/reconnect | Specific feature evidence exists despite the generic Events:113 `delete` family wording. Do not infer that every DELETE shares this exception. |
| `createFeedback` (2798), `updateFeedback` (2917) | F15:23–27,35; Events:110 | Explicit private feedback sync; preserve pending state and stale edit for user choice. Local schema validation cannot establish target ownership or cross-field domain consistency. |
| `deleteFeedback` (3038) | F15:19,27 and F18 source-removal semantics | Private retraction is supported, but there is no separate unambiguous automatic offline-delete sentence. The generic delete row also applies. Conservative generic queue can require explicit handling. |
| `updateMemory` (3340), `deleteMemory` (3460) | F16:35; F18:19,23–27,31,35; Events:110 | Offline edits/forget suppress affected local ranking immediately. Preserve stricter suppression until revision resolution; no definitive remote “Forgotten” while still pending. F18 explicitly discusses queued edits and local forget operations. |
| `addCollectionItem` (4498), `removeCollectionItem` (4615) | Events:111; F45:23–27,35 | Only stable already-owned save IDs; collection removal changes membership only. Entitlement/deleted-source rejection is actionable; no loss of existing basic saves. |
| `saveRecipe` (3658) | F14:35; F19:33 | Conditional: eligible downloaded catalog content can save locally pending sync. Operation also accepts own plans and optional collection IDs. Operation ID alone does not prove eligibility or paid-tool entitlement. |
| `createCollection`, `updateCollection`, `reorderCollection` (20881) | F45:23–27,35 | Local organization drafts; new entitled writes require current server authorization. Events:111 specifically authorizes item add/remove, not a silent arbitrary order/whole-record merge. Reorder requires the exact unique current item set, and stale order requires a user decision. |
| `markNotificationRead` (8442), `markNotificationsRead` (18324) | F41:10,19 | Account-scoped in-app read changes queue. F41's bulk prose iterates explicit loaded IDs, whereas API also exposes a timestamp watermark endpoint; do not manufacture IDs or advance an unseen watermark. |
| `updateNotificationSettings` (8757) | F41:19; Events:110 preference family | Pending preferences; time-zone changes have user-confirmed reminder implications. Preserve ETag conflicts. |
| `updatePrivacySettings` (20675) | F39:10,19 | Queued contact-setting writes are explicit in F39:19 and canceled at logout. Show pending privacy effects until acknowledged; merge only nonconflicting versioned fields. This operation also exposes future audience defaults, so a generic field-blind exception needs care. |
| `updateHouseholdPreferences` (13611) | F44:25,35 | Own private requirements/consent queue and suppress affected local planning until validated. Does not extend to `updateHouseholdKitchen` or shared membership/planning. |
| `setReaction` (6046), `removeReaction` (6162) | F27:19 | Feature-specific latest-intent-only queue per post, with execution-time reauthorization. Do not replay obsolete toggle history or coalesce an uncertain already-submitted command into different payload under its key. |
| `sendMessage` (8009) | F28:19 | Feature explicitly describes queued sends and rechecking eligibility. Never send after draft deletion, logout, or account switch. This is not authority for silent delayed general replies/invitations. |
| `savePostRecipe` (5924) | F30:19; F14:35; F19:33 | Offline tap is pending intention only; new community copy requires current grant authorization. A prior cached post/grant never authorizes the copy. |
| `muteTarget` (9254), `unmuteTarget` (9361), `blockUser` (8959), `unblockUser` (9066) | F42:10,19 | Mute can hide locally; server effects remain pending. Block must not claim remote enforcement offline. The feature does not give a blanket silent delayed execution rule for every safety reversal. |
| `createReport` (9457) | F42:19; F52:19 | Preserve unsent reports privately; clear account-bound evidence references at logout. A submitted report with a lost response reuses its original key. `ReportWrite` has no `clientReportId`. |

Important excluded/confirmation-sensitive mappings:

- `createCookSession` (2341) is an online authorization/recall-checked pin operation before offline readiness (F12:15–16). Existing offline progress does not itself establish permission to create a new server cook.
- `createPlan`, `adaptPlan`, `simplifyPlan`, `nextPlan`, `createReuseOptions` require current matching/reviewed content. F01:35 explicitly forbids queueing an adaptation that silently changes the displayed dish. F02:35 and F08:35 permit same-key timeout retry of a submitted request; that does not authorize initial offline matching.
- `createPostDraft`, `updatePostDraft` represent optional online server drafts; local draft persistence needs no network command. `prepareMediaUpload` and `completeMediaUpload` require fresh authorized reservation/media state. `publishPost` requires an explicit Share press after preflight (Events:112); a *previously pressed* Publish with uncertain outcome reconciles the draft or replays its same key (F31:19).
- F24:19 permits local recipe-attachment draft editing, with server eligibility and no silent version substitution; F38:19 permits local media editing and renewed-slot upload retries. Neither authorizes automatic publication or restoring an expired upload capability.
- `updatePost` includes caption, audience, retention and future-save policy. F22:19/F40:19 require current version and explicit review on conflicts; F53:10 requires online authorization and receipt for audience changes. An operation-wide metadata-only exception would also admit privacy changes.
- `createInvitation`, `acceptInvitation`, `revokeInvitation`, circle membership/ownership operations, and household creation/membership/shared-kitchen operations need the feature's fresh rights/state checks (F23/F44); generic pending state cannot claim acceptance/removal.
- `createSOS`, `updateSOS`, `replyToSOS`, `resolveSOS`, `deleteSOSReply`: offline requests/replies are drafts; F32:19 requires reconfirmation for an intended SOS send over 15 minutes old. A generic durable queue should not derive a fresh send deadline from reconnect time.
- Pact/potluck operations (`createPact`, `reschedulePact`, `respondToPact`, `cancelPact`, `createPotluck`, `updatePotluck`, `respondToPotluck`, `addContribution`, `claimContribution`, `claimPotluckContribution`, `removeContribution`, `planPotluck`, `confirmPotluck`, `cancelPotluck`) are coordination with current membership, version, schedule, contribution, or claim checks (F34/F35). Votes/claims cannot silently auto-submit later (Events:113).
- Poll operations (`createPoll`, `voteInPoll`, `removePollVote`, `closePoll`) need current deadline/state; a new vote choice uses a new key, and stale offline selection cannot silently submit (F37:19; Events:113).
- `requestRecipe`, `respondToRecipeRequest`, `cancelRecipeRequest` preserve unsent drafts and require current request rights/status (F29:19). `createShortcut`/`saveShortcut` require access validation; private session notes have their own explicit queue permission (F36:19).
- Native purchases and `reconcileEntitlements` require the commerce workflow; no signed offline entitlement lease exists (F50:19). No queue payload can assert paid access.
- `requestAccountDeletion` requires recent authentication and explicit confirmation; typed confirmation is not retained beyond the session (F49:7,19). Other deletion/security/admin/webhook operations do not acquire automatic queue eligibility just because they are idempotent.
- `updateMe` (456) keeps offline onboarding as a local draft, while a lost submitted save response reuses its key (F48:19). `mergeGuest` (154) requires explicit cross-account ownership transfer and retains original guest data until server receipt (F43:19). Neither follows automatically from a successful login or restored queue.
- `markThreadRead` (8125) has a canonical monotonic watermark operation, but F28's offline paragraph explicitly queues sends, not an arbitrary thread-read watermark. F41's offline-read authorization is scoped to notification activity. Do not infer unseen thread read progress from that adjacent feature.

## Idempotency, ETags, ordering and retention

[API IdempotencyKey](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json:21025) requires a UUID per logical durable command, scoped to principal + operation, with a checked request hash and original result retained **7 days**. Preserve key and original request bytes/parameters throughout retry and restart. There is no generic receipt-query endpoint in this contract. Seven days is not a promise that an older uncertain command is safe to replay, and it is not a queue-wide discard deadline for never-submitted drafts. An uncertain command beyond the receipt guarantee needs reconciliation/user-visible resolution; generating a fresh key is not reconciliation. The exact hash scope and retention start timestamp are not stated here, so any conservative client replay cutoff must be identified as client policy.

The canonical mismatch code is `409 IDEMPOTENCY_MISMATCH` (API:21025). F31:13 calls it `IDEMPOTENCY_CONFLICT`; use the canonical contract name for a fixed local category, while preserving no arbitrary server text in diagnostics.

[API IfMatch](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json:21035) is the latest representation's exactly quoted decimal ETag, such as `"7"`. Missing is 428, stale is 412. Do not blindly replace an ETag after 412, synthesize the next version, merge timer deadlines, or replay a changed whole record. A missing delete target is idempotent only for the same recorded command.

Do not add `If-Match` to operations that do not declare it:

| Operation | Version source |
|---|---|
| `updateCookSession` | Required `If-Match`; required `CookPatch.deviceSequence` |
| `completeCookSession` | **No If-Match**; required `Completion.deviceSequence` and `makeAgain` |
| `upsertPantryItem` | **No If-Match**; optional `PantryWrite.expectedVersion` (minimum 1) |
| `addCollectionItem` | **No If-Match** |
| `removeCollectionItem` | Required `If-Match` |

Events:109 requires session/device sequence order; F12:35 requires ordered replay with expected revision. `CookPatch` (API:22813) and `Completion` (22916) require nonnegative integer `deviceSequence`; `CookStart` allows an optional initial sequence. The contract does not define a cross-device/global sequence algorithm, sequence-gap response code, or a client permission to rebase versions. Keep commands scoped to their owner/session/device intent and block dependent work behind conflicts; do not resurrect completed state with an old active/paused update.

[API DeviceSession](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json:21045) is registered by bootstrap and checked for revocation and access-token subject binding. A queue is not a place to persist credentials or substitute a synthetic device ID. The transport obtains fresh current credentials/session data at execution. Queue owner scope and active lease must still match after every suspension. Logout clears queue encryption keys; an old principal's command can never replay into another account (Events:115; F47:23).

## Canonical Problems and retry categories

`Problem` (API:21083) has required `type`, `title`, `status`, `code`, `traceId` and optional `detail`, `fieldErrors`, `retryAfterSeconds` (integer >= 0), `currentVersion` (integer >= 1). Its `code` is a string, **not an enumerated retryability catalog**, and it has no `retryable` boolean. Validation/typing is not permission to log free-form code/detail/trace/field messages; queue-visible failure metadata should use bounded local categories.

| Outcome | Canonical meaning / queue treatment |
|---|---|
| Transport fault / `OUTCOME_UNKNOWN` | Retry/reconcile only the same eligible logical command and key; a lost/malformed receipt does not prove rollback. |
| 429 (API:28825) | Rate limit; respect `Retry-After`, apply bounded client backoff. |
| 503 (28861) | Dependency unavailable; description says “retry only according to retryable error.” Events:115 includes 503, but no complete retryable code enum exists. Do not invent provider/domain code predicates. |
| 403,404,409,410,422 | Events:115 explicitly requires user-visible resolution; no automatic retry loop. |
| 412 | Fetch latest and resolve; preserve both states and dependent commands. |
| 428,400 | Missing precondition/malformed input; repair before a new explicit attempt. |
| 401 | Missing/expired/revoked principal; authentication/session gate, not blind network backoff. F47:23 stops retries on refresh-token failure. |
| 500 | Unexpected error; it is declared, but Events:115 does **not** authorize automatic 500 retry. Do not generalize to all 5xx. |

Every declared error response includes an optional integer `Retry-After` header (minimum 1), including permanent errors. Its presence alone cannot make 403/409/412/etc. retryable. Header versus `Problem.retryAfterSeconds` precedence and maximum client delay are not canonically specified; document any bounded scheduling choice as client policy. Transport already validates numeric header form. Preserve success/receipt uncertainty on cancellation or owner change; never claim cancel rolled back a dispatched write.

## Implemented pure request-validation seam

[RequestPreparation.kt](../shared/transport/src/commonMain/kotlin/com/feedme/transport/RequestPreparation.kt:36) compiles bundled mobile operation, principal, path/query/header rules, exact scalar checks and resource budgets. Its final `prepare` requires the real account device header and preserves request bytes; its body checks remain syntax/resource checks. [CanonicalBodyValidator.kt](../shared/contracts/src/commonMain/kotlin/com/feedme/contracts/CanonicalBodyValidator.kt:30) supplies complete pinned request-body validation.

[MobileRequestValidator.kt](../shared/transport/src/commonMain/kotlin/com/feedme/transport/MobileRequestValidator.kt) exposes `MobileRequestValidator(catalog: ContractCatalog = ContractCatalog.bundled()).accepts(call: ApiCall, principal: PrincipalClass): Boolean`. It composes the body validator with preparation's internal pure intent check:

1. Snapshot operation/path/query/body/key/If-Match; validate mobile surface, caller class, exact parameter keys/requiredness/scalars/budgets and complete body without secure storage/network.
2. Defer **only** retrieving/validating the transport-supplied device-session header. No fake UUID is supplied, declared caller headers are not relaxed, and the pure seam returns only a Boolean. Its intermediate validation object is private and is not a prepared network call.
3. Keep final `prepare(call, principal, actualDevice)` strict after secure credential read and current-lease checks. It still requires and validates the real registered device session, including the full actual parameter resource budget.
4. [FeedMeTransport.kt](../shared/transport/src/commonMain/kotlin/com/feedme/transport/FeedMeTransport.kt) invokes the pure seam before account secure-store reads and for public calls. It shares the compiled validator/preparation internally. Queue enqueue/restore can call the same public constructor; queue policy separately decides offline eligibility, sequencing, owner/dependencies, confirmation and retry state.

Successful pure validation is neither server authorization nor full domain validation. For example, `FeedbackWrite` describes same-owned-target and at-least-one-signal service checks beyond its structural `anyOf`; `SaveRecipeRequest` structure cannot verify ownership/rights/entitlement. Unsupported bundled metadata must still fail visibly at initialization. Unknown operations, future schema versions and malformed restored intents must fail closed before secure or network access.

[MobileRequestValidatorTest.kt](../shared/transport/src/commonTest/kotlin/com/feedme/transport/MobileRequestValidatorTest.kt) adds 12 tests covering pure validation, canonical body assertions, malformed/unsafe parameters, key/ETag requirements, deferred device binding, exact byte preservation, zero credential/network access for invalid account/public intent, and actual transport rejection of missing/invalid devices. Build execution is coordinated by the parent task to avoid simultaneous Gradle runs.
