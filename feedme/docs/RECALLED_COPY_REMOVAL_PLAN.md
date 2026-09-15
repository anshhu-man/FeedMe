# Recalled private-copy removal

14 September 2026. **Local downloaded-copy branch verified in the tenth package, not whole-F19 acceptance.** This is the design/obligation document; consult the [tenth tracker](PARALLEL_RECALLED_COPY_DRAFTS.md) for full accepted evidence, retained focused failures and the cloud-only/stale-version gap. The [ninth-batch evidence](PARALLEL_COOKBOOK_PROCESSOR.md) remains an immutable prior baseline. Also see the [cookbook integration plan](COOKBOOK_CLIENT_INTEGRATION_PLAN.md).

## Outcome and existing contract

An owner who already has a verified local saved-copy identity should be able to explicitly remove that copy after its instructions become unavailable. Removal must not require fetching recalled instructions, release a recall fence, or turn a historical copy into permission to cook, copy or publish. A saved copy remains distinct from its source recipe, another saved copy and an existing cooking pin.

[F19](../../outputs/biteclub_blueprint/features/F19.md) requires ordinary removal and unavailable states independently of paid organization. The [canonical API](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json) already defines `deleteSavedRecipe`: DELETE `/v1/saved-recipes/{savedRecipeId}`, original Idempotency-Key, exact If-Match, no body, and a bodyless 204 success. There is no requirement to GET the content immediately before DELETE. Server execution, not a cached target or client button, makes the current ownership/version decision.

The current [SavedRecipeStore](../server/src/main/kotlin/com/feedme/server/memory/SavedRecipeStore.kt) deletion path locks the current principal, finds the exact owned ID and checks the original version. It deliberately does not call the content-access gate used by GET/LIST. [SavedRecipeAuthority](../server/src/main/kotlin/com/feedme/server/memory/SavedRecipeAuthority.kt) explicitly separates owned deletion from catalog/content permission. Existing real-PostgreSQL tests `recalledCopyFailsClosedReadPagesAndCachedSaveButOwnedDeletionStillWorks` and `sourceRetirementAndNewCopyDisableDoNotTrapPrivateRemoval` in [SavedRecipeStoreIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/memory/SavedRecipeStoreIntegrationTest.kt) prove this boundary. The current server's `RECIPE_RECALLED` status is **409**; the canonical operation also admits 410 Problems. Neither status alone supplies deletion-target evidence.

At the ninth baseline the client blocked this case: [CookbookController.prepareDelete](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/CookbookController.kt) called `getRemote` and required a non-null `SavedRecipeWire` and ETag. [SavedRecipeRepository](../shared/kitchen/src/commonMain/kotlin/com/feedme/kitchen/SavedRecipeRepository.kt) correctly returns a body-redacted projection after an exact recall or attribution-redaction fence. The tenth source adds a separate local removal-only proof while preserving that redaction; current acceptance is tracked separately.

## Task 1 — Mint an exact local removal target

Owner: shared kitchen. Proposed files: a new `SavedRecipeDeletionTarget.kt` and narrow changes to `SavedRecipeRepository.kt`, with repository tests.

Add a purpose-fixed, repository-minted `SavedRecipeDeletionTarget`, with internal construction and a redacted diagnostic representation. A suggested entry point is `prepareLocalDeletionTarget(lease, savedRecipeId)`. It performs private reads only. It is not a publicly constructible tuple or a general mutation capability.

Mint it only from the exact integrity-verified owned local body and metadata, under the existing index/body/metadata/tombstone and lease brackets. Pin:

- The actual scope/lease/repository ownership and exact saved-copy ID.
- The originally observed aggregate version and original validated ETag spelling; compare integer values without floating-point conversion.
- The immutable recipe-version identity and digest of the exact original canonical saved body.
- The local evidence revisions and payload fingerprints needed to reject intervening replacement or deletion.

Do not expose the canonical recipe body, ingredients, instructions, creator attribution or source content through the target. A UI-safe label may be separately derived only under the current disclosure policy; otherwise use a generic recalled-copy removal label. Never manufacture a title or an ID.

A recall/attribution fence must continue to hide the body but must not itself deny this removal-only target. Missing ETag, missing or damaged evidence, an unknown ID, mismatched scope, an existing tombstone or an inconsistent index remains unavailable. Do not mint a target from `SavedRecipeSnapshot.id` alone, a displayed title, a Problem's `currentVersion`, an error-response header, or a failed GET. An integrity-failure cleanup/recovery workflow is outside this bounded task.

For the normal readable path, use the same target abstraction minted from the exact successful canonical observation, preserving the existing uncached-copy behavior. A remote observation is not a downloaded local bundle. The new local route must not accidentally require a local slot for every normal remote deletion.

## Task 2 — Preserve exact consent and durable original intent

Owner: mealflow. Narrow files: [CookbookModels](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/CookbookModels.kt), [CookbookController](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/CookbookController.kt), [CookbookRecords](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/CookbookRecords.kt) and their tests.

On an explicitly selected recalled/unavailable local copy, `prepareDelete` obtains the local target without calling `getSavedRecipe`. The confirmation ticket binds that exact target to the current controller owner, generation and bounded consent time. Preparation does not enqueue or dispatch. No additional content permission is inferred from being able to show removal consent.

Revalidate the exact target and current lifecycle before allocating the command ID, before atomic enqueue and around suspended admission. Dismissal, Back, a replacement target, expiry, clock rollback, close or lease invalidation revokes the current confirmation. Confirmation creates exactly one DELETE with the original saved ID, original If-Match and original native command key; offline admission is pending, not removed.

Persist a versioned, body-redacted deletion identity proof atomically with the existing queue intent. Its decoder must be strict and scope-bound; deserializing fields alone must not mint a fresh current target or consent. Repository preparation/revalidation must correlate retained proof with actual local records where applicable, and the controller must compare the exact original queue intent, origin, path, key and If-Match. Retained evidence supports an already admitted original command; it does not authorize a replacement command.

Preserve existing schema-1 pending deletion records and their exact original bodies/keys. Decode or adapt their already authenticated evidence without silently rewriting an in-flight command, changing its ETag or allocating a new key. A 412/currentVersion response is a conflict, never permission to rebase. A fresh target and fresh consent, if supported by a later canonical read, is a distinct action subject to the existing unresolved-command gate.

Rendering, local restore, search and Back do not send. Deletion need not be blocked by pending meal preferences or withdrawn new-copy rights; current principal checks and the exact deletion precondition remain mandatory. Do not alter independent queue-lane behavior or drain cooking commands.

## Task 3 — Apply only the exact deletion receipt

Owner: shared kitchen plus the existing mealflow coordinator. Add a target-based overload or replacement for `prepareDeleteReceipt` and a negative-only deletion reply observer; no arbitrary caller-selected store mutations.

After the exact original queue intent and canonical 204 are verified, prepare a fixed atomic batch that removes only this matched local body/metadata and index membership, and adds the exact saved-ID tombstone. Check the original ID/version/digest and all applicable local revision/payload brackets. A newer local download or changed target must not be silently adopted. Uncached normal deletion still needs no cache reservation.

Retain recipe-version recall markers, other saved copies, unrelated memberships represented by other local domains, and independently authorized cooking records. The server removes this copy's memberships in its own transaction; the client must not infer a remote deletion from an empty page, 404, a readback or absent local files.

Reuse the existing `DurableCommandQueue.applyReceipt`, actual archive capture, exact domain/archive finalization proof, changed fresh-CAS acknowledgement and atomic caller-delivery ticket. An unknown 204 transport result or lost apply acknowledgement retains the original operation/key/precondition and existing reconciliation limits. Plain reads, navigation and controller recreation do not turn it into acknowledged success. Do not broaden same-lease process-local finalization authority into an unproven cross-process acknowledgement claim.

## Task 4 — Expose removal without exposing content

Owner: shared app and isolated progress-host acceptance. Narrow files: `CookbookScreen.kt`, `FeedMeMealFlow.kt`/`MealFlowExperience.kt` only where needed, app presentation tests and actual progress cookbook host tests.

An unavailable card/detail with an authentic target can offer **Review removal from cookbook**. The dialog identifies the owned copy only to the extent permitted by current disclosure, explains that removal does not delete its source or another cooking session, and requires the captured ticket. Keep Back and explicit original-command retry available as appropriate. Do not enable instructions, Cook, Save, Make Again, Share or attribution from the deletion target.

Extend the explicitly synthetic progress service with a controlled recall response for this acceptance path, keeping canonical status/body binding strict. Use actual `ProgressSessionOwner`, session access, encrypted repositories and the one existing queue. No fake UI-only success, alternate access factory, app-data clear or unknown-owner cleanup is evidence.

### Reviewed additive implementation seam

Keep the existing readable/remote `SavedRecipeWire` deletion path and its uncached-copy behavior. Add a separate local-downloaded target/evidence branch; do not require every normal remote deletion to reserve or download a local bundle. The earlier suggestion to share one target abstraction is optional, not a reason to migrate working remote intent records.

The kitchen owner can expose `prepareLocalDeletion(lease, id)`, an opaque live `SavedRecipeDeletionTarget`, detached redacted stored evidence, and target/evidence validation plus `prepareDeleteReceipt`/`observeDeleteReply` overloads. Decoded evidence is not a new live target or consent. The controller must retain exactly one branch in each command: the existing readable expected document, or the new redacted local evidence. Legacy schema-1 commands retain their original keys, bodies and preconditions.

The root-owned native acceptance seam belongs only to the explicitly synthetic progress service. A scoped, acknowledged service-side withdrawal must be learned through the existing canonical response and real repositories, not by editing client recall rows or replacing a selected UI object. It must preserve existing recipes, cooking records and original receipts, while refusing newly disallowed content disclosure/replay. The exact same-owner DELETE remains independent of positive content access. Reopening the synthetic service must not silently undo its withdrawal. Tests must distinguish a server-event simulation from production moderation or signed-manifest integration.

Current configured HTTP coverage already tests recall blocking GET/LIST/cached Save, and separate PostgreSQL coverage tests owned deletion during recall. Add an actual HTTP regression joining those boundaries: capture the successful original ETag, withdraw content, reject content reads, DELETE with the unchanged key/precondition, replay bodyless 204, and deny stale/foreign/revoked-principal requests. Do not add a new HTTP endpoint for this local-evidence task.

## Exact test obligations

| Boundary | Required assertion |
| --- | --- |
| Recalled local success | Actual Save/download, learn an exact bound recall, verify body redaction, prepare and confirm removal with **zero content GETs**, exact original key/If-Match, canonical 204 and acknowledged local removal. |
| Independent removal permission | Recalled/source-retired/new-copy-disabled/paid-downgraded cases do not require content-read or copy authorization; current revoked/foreign principal remains denied. |
| Authentic target | Wrong lease/store/ID, missing ETag, damaged digest/body/metadata, inconsistent index and existing tombstone cannot mint or apply a target; no transport or mutation follows failed preparation. |
| Stale consent/evidence | Suspended target read or ID allocation followed by changed version, replacement, Back, dismissed/expired ticket, close or account change cannot dispatch stale intent or publish private content. |
| Original retry | Lost DELETE reply, then 401/403/404/409/412 or timeout, preserves exact original ID/key/If-Match; `currentVersion` never rebases. Explicit retry does not create a new command. |
| Receipt/apply acknowledgement | 204 commit then lost acknowledgement, repeated fresh-ack loss, malformed/changed archive and domain proof, cancellation and final caller-return Back/close retain the exact proof; read-only restore/discard cannot release it. |
| Nonresurrection/isolation | Stale GET/page/download cannot recreate the deleted ID; a later authorized re-save has a distinct ID; another copy of the same recipe and an active cooking pin remain unchanged. Recall markers remain installed. |
| Compatibility | Existing schema-1 pending delete records replay their original intent; the readable remote-only path and local-cache capacity behavior remain unchanged. |
| Actual UI/native | Body stays absent in card/dialog/accessibility; real confirmation, dismissal, Back/recreation and original retry work on the retained host. Keep exact source/test/capture mapping and acknowledged fixture cleanup. |
| Server/HTTP | Extend the existing recalled-deletion PG tests and configured HTTP tests: current same-owner deletion works while content GET/LIST deny; exact stale If-Match, missing key, foreign ID, no-body framing, replay and principal revocation remain strict. |

These are required checks, not results from the ninth run. Focused tenth results and remaining failures are recorded in the linked tracker; a passing repository suite alone does not satisfy the native or whole-feature obligations.

## Cloud-only and changed-version gap — separate canonical work

The local-target task is useful but does **not** complete recalled-copy removal for every F19 user. The canonical `SavedRecipe` requires `snapshot`; `SavedRecipePage.items` contains only full `SavedRecipe` objects. Current server GET/LIST fails closed on recalled content, including a page containing a recalled copy. There is no current metadata/HEAD/removal-target operation, and `Problem` does not bind a resource ID or grant removal consent.

Consequently a device that never downloaded the recalled copy cannot discover and authenticate its deletion target through this local seam. A previously cached but now stale ETag can also receive 412 without a content-free way to obtain a fresh target. Do not fill either gap with a partial/fabricated `SavedRecipe`, an error `currentVersion`, a guessed ID, a fabricated ETag, or a recipe body returned despite recall.

The dependency-ready follow-on is an explicitly approved canonical **owned-summary/removal-target read contract**, with paged discovery of unavailable copies and exact owned ID/version/ETag lookup. It should expose no recipe instructions or disallowed attribution, authorize current principal plus ownership separately from content access, and preserve cursor scope/revision, revocation and monotonic lifecycle semantics. A safe optional display label must have an explicit disclosure policy. Root must coordinate that schema/registry/generated-contract/server/client change; this plan invents no enabled endpoint. Fresh deletion consent then binds the new authoritative target, while older uncertain commands remain exact and unre-based.

Signed manifests, saved-source Cook/Make Mine, Make Again, F30 social-copy rights, F45 organization, real provider/deployment, iOS/physical-device and complete F19/release acceptance remain separate gates. All 44 V1 features remain in scope; this plan removes none.
