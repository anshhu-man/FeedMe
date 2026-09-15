# Kitchen acknowledgement and lifecycle foundations

## Status and scope

The focused `:shared:kitchen:jvmTest --rerun-tasks` run (execution `54437`) and subsequent complete frozen run pass **171 kitchen JVM tests**, preserving the142-test baseline and adding29 regressions. [Fifth-batch bounded acceptance](PARALLEL_KITCHEN_COOKING.md), completed2026-09-14T01:54:18.106Z, passes2,266 Kotlin/server/database,181 Node and345 native tests plus seven separately witnessed interrupted starts. Independent source/evidence/native audits pass; earlier receipts remain historical.

This package hardens the existing private cooking/saved-recipe repositories and their actual durable-command-queue composition. It does not create an authenticated session, own or close native stores, initialize storage, schedule work, or provide a production login provider. It is not an Android UI, iOS, physical-power-loss, or release-readiness claim.

## Exact write acknowledgement

[KitchenContext](../shared/kitchen/src/commonMain/kotlin/com/feedme/kitchen/KitchenContext.kt) applies the same contract to direct repository writes and to the private store wrapper passed to the queue:

- Snapshot the mutation list before suspension; reject empty batches, duplicate keys, and exhausted expected revisions before calling storage.
- Invoke the supplied store once. A returned `Failure`, including `OUTCOME_UNKNOWN`, remains a failure; visible matching bytes do not promote that result to an acknowledgement.
- Require the successful receipt to contain exactly the requested keys. For a put with a known predecessor, require the returned revision to be exactly predecessor + 1. Every put revision must be positive. A delete must return a null revision.
- Read back each requested key. Puts must match the acknowledged revision, schema version, and exact payload bytes; deletes must be absent. Missing, extra, unchanged, skipped, or mismatched evidence fails closed. The returned receipt is detached from a store-owned mutable map.

An absent-record put has a null expected revision, **not an assumed revision of zero**. Native storage can retain a tombstone counter after deletion. Such a create therefore accepts the store's positive revision and verifies its exact readback; it does not force revision 1 or reset that counter.

This is validation of the lower store's changed-CAS acknowledgement and exact readback, not a new transaction manager or independent filesystem-sync proof. The lower store remains responsible for its atomic/durability contract. Sequential readbacks do not grant authority over arbitrary concurrent writers. There is no automatic rewrite, rollback claim, resend, or repair after failed acknowledgement.

## Queue and operation-policy composition

[PrivateKitchenSession](../shared/kitchen/src/commonMain/kotlin/com/feedme/kitchen/PrivateKitchenSession.kt) now gives the real `DurableCommandQueue` the context-validated store wrapper. Queue reservation, in-flight, receipt-application, and associated domain batches therefore use the same acknowledgement checks. The wrapper refuses scope erasure; it does not replace session-retirement ownership.

The existing `updateCookSession` and `completeCookSession` execution/reply hooks remain wired to the cooking repository. The optional constructor parameter `createExecutionGate: CommandExecutionGate? = null` applies **only** to `createCookSession`. Without it, starts wait with `NOT_CONFIGURED`. Supplying it does not configure unrelated commands, bypass the queue's explicit-confirmation policy, or establish account authority. The trusted starting controller must supply the exact domain gate; its separate bounded implementation and recovery evidence are recorded in [COOKING_FLOW.md](COOKING_FLOW.md), not implied by this foundation alone.

## Cancellation, lease lifetime, and recall

Context reads, commits, and permitted fetches check cancellation and the exact current lease before and after external suspension. Guarded repository calls also recheck on return from the repository dispatcher, preventing a private successful result from escaping after lease invalidation in that handoff gap. A port that swallows cancellation cannot thereby authorize the next commit, cache a late transport reply, or publish a late receipt. These checks retain the existing **serialized application-owner dispatcher** contract; they are not a general concurrent-thread ownership mechanism.

[RecallFence](../shared/kitchen/src/commonMain/kotlin/com/feedme/kitchen/RecallFence.kt) separates acknowledgement from one-way blocking evidence. An exact learned recall enters a process fence before its marker write. Failed or uncertain persistence must not become permission to cook, and replacing a repository must not erase that blocking observation. A matching one-way marker observed after a raced/uncertain create can still block content; it is not an acknowledged successful effect.

The process fence is bound to the exact `SessionBoundary` and `SessionLease`. Synchronous invalidation clears only that lease's memory entry, without affecting another live boundary or a successor lease. Independently persisted markers are not deleted by this callback and must still be read by a later lease. Cancellation before learning persisted evidence cannot install a late memory fence. Failed persistence alone does not promise survival across process death, offline recall propagation, or automatic retry/owner erasure.

## Read-only observations, not capabilities

[CookingSnapshot.originMatches](../shared/kitchen/src/commonMain/kotlin/com/feedme/kitchen/CookingRecords.kt) compares the persisted cooking bundle's origin binding with the repository's current origin. It supports truthful read-only presentation of historical bundles. A true value does not replace the repository's lease, recall, revision, and edit checks.

[CookingRepository.hasRecipeRecall](../shared/kitchen/src/commonMain/kotlin/com/feedme/kitchen/CookingRepository.kt) returns known local persisted/process blocking evidence for an exact recipe-version ID under the guarded lease. It performs no content download and creates no new recall authority. **False is not current safety, publication, or access-rights approval.**

## Focused regression evidence

The new regressions use scoped adversarial common/JVM ports, not a claimed native sync or provider-authentication harness:

- [KitchenAcknowledgementTest](../shared/kitchen/src/commonTest/kotlin/com/feedme/kitchen/KitchenAcknowledgementTest.kt): 16 tests covering exact receipts/readback, tombstones, malformed acknowledgements, visible-success/failed-ack separation, cancellation, invalidation, return handoff, and detached evidence.
- [RecallFenceLifecycleTest](../shared/kitchen/src/commonTest/kotlin/com/feedme/kitchen/RecallFenceLifecycleTest.kt): 6 tests covering repository replacement, exact lease invalidation, independent boundaries, persisted rehydration, and cancellation.
- [PrivateKitchenSessionReliabilityTest](../shared/kitchen/src/commonTest/kotlin/com/feedme/kitchen/PrivateKitchenSessionReliabilityTest.kt): 6 tests covering create-only policy, explicit confirmation, unrelated-command denial, queue write/readback failure, and cancellation before transport.
- [CookingRepositoryTest](../shared/kitchen/src/commonTest/kotlin/com/feedme/kitchen/CookingRepositoryTest.kt): 1 added explicit-recall-observation test; the existing origin-change regression also checks the new observation. [KitchenContextTest](../shared/kitchen/src/commonTest/kotlin/com/feedme/kitchen/KitchenContextTest.kt) retains its existing inventory with exact-readback fixture alignment.

The shared adversarial fixtures are in [KitchenReliabilityFixtures](../shared/kitchen/src/commonTest/kotlin/com/feedme/kitchen/KitchenReliabilityFixtures.kt). The complete source/artifact/native evidence run accepts these components, not an integrated native cooking journey. Native cooking screens, server operations, timers, live identity/content and both-platform release acceptance remain separate gates.
