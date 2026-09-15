# Events, jobs and client synchronization

Proposed contract dated 13 September 2026. Events communicate committed facts. API commands request changes; UI analytics measure behavior; neither is a substitute for a domain event. No consumer may treat an event payload as a continuing authorization grant.

## Canonical envelope

```json
{
  "eventId": "21d195fc-d76a-4472-9590-85e3c870ae23",
  "eventType": "social.post.published.v1",
  "schemaVersion": 1,
  "aggregateType": "post",
  "aggregateId": "9429c9ca-5fbb-4db2-951f-12a4f5047fab",
  "aggregateVersion": 1,
  "occurredAt": "2026-09-13T14:00:00Z",
  "producer": "social",
  "correlationId": "trace-opaque-id",
  "causationId": "client-command-uuid",
  "data": {
    "postId": "9429c9ca-5fbb-4db2-951f-12a4f5047fab",
    "authorUserId": "253c70e1-4997-4614-8796-3247fb032338",
    "aclVersion": 1,
    "expiresAt": "2026-09-14T14:00:00Z"
  }
}
```

All envelope fields are required. `data` follows a type-specific schema with additional fields allowed for forward-compatible consumers; required fields never change type in-place. IDs and dates validate strictly. `aggregateVersion` orders facts within one root, not across all users. `correlationId` is a tracing handle, not a session/access token. Avoid captions, ingredient exclusions, raw messages, email, signed URLs, receipts and model prompts in events. Consumers fetch minimum necessary data through an internal authorized module port.

## Publication and delivery contract

1. API locks idempotency and aggregate rows, applies domain mutation and inserts outbox event in the same PostgreSQL transaction. A failed transaction emits nothing.
2. Relay leases a bounded batch with `FOR UPDATE SKIP LOCKED`, sends to module queue, then marks published. Crashing after send but before marking causes a duplicate, which is expected.
3. Standard SQS can redeliver messages. Each consumer starts a transaction, inserts `(consumer_name,event_id)` into inbox, applies its database effect, records any follow-up outbox events, and commits. A duplicate inbox key exits successfully. Delete SQS message only after commit. [SQS delivery semantics](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html)
4. External delivery uses a separate durable `delivery_attempt` keyed by event+recipient+channel. Mark intent before call and store provider result. A timeout after external acceptance can still cause duplicate pushes; collapse IDs and clients dedupe by notification ID. Never promise exactly-once network side effects.
5. Visibility timeout exceeds measured p99 work duration with heartbeat extension. Proposed initial: text/reconciliation workers 60s, media workers 5m with 30s heartbeat, max 5 attempts then DLQ. Worker-initiated retries use exponential delay+jitter. Unknown permanent schema/type goes to quarantine, not endless retry.
6. Delayed jobs include expected aggregate version/status. On execution re-fetch current state. Scheduled expiry cannot reopen a post; cancelled pacts cannot send old reminders; deleted users cannot receive messages. A stale job acknowledges without effect.

## Domain event catalog

The following names are canonical; prose aliases in feature documents refer to these facts. Payload below lists required `data` fields in addition to envelope metadata. Optional fields are explicitly marked `?`.

| Event | Producer / transaction | Required payload | Consumers and idempotent effect |
|---|---|---|---|
| identity.account.bootstrapped.v1 | identity / new account | userId, deviceSessionId | initialize default private records once; activation counters |
| identity.guest.merged.v1 | identity / ownership transfer | userId, guestSessionId, mergeResultId | invalidate guest caches/tokens; no social invitations auto-sent |
| identity.session.revoked.v1 | identity / revoke | userId, sessionId | unregister push association and evict app-session cache |
| identity.account.deletion_requested.v1 | identity / hide+job | userId, deletionJobId | staged erasure, cancel user jobs, revoke tokens; redacted receipt |
| identity.account.deleted.v1 | identity / completed ledger | deletionJobId, erasedPrincipalHash | retention reconciler; avoid resurrecting identity in telemetry |
| profile.preferences.changed.v1 | profile / preferences update | principalId, preferenceVersion, changedFieldKinds | mark new suggestions stale; never replace active cooking snapshot silently |
| pantry.item.changed.v1 | pantry / upsert/delete | principalId, ingredientId, action | invalidate ingredient confirmation cache, not nutrition inference |
| catalog.recipe.submitted.v1 | catalog / review submission | recipeVersionId, contentHash | staff review queue; no public exposure |
| catalog.recipe.reviewed.v1 | catalog / decision | recipeVersionId, reviewId, decision | editorial state and audit projection |
| catalog.recipe.published.v1 | catalog / approved publish | recipeId, recipeVersionId, catalogRevision | rebuild search facets, warm authorized catalog cache |
| catalog.recipe.recalled.v1 | catalog / recall | recipeVersionId, recallId, reasonCode, effectiveAt | invalidate plans and saved copies; targeted content notice; CDN purge if recipe media affected |
| catalog.substitution.reviewed.v1 | catalog / independent review | substitutionId, reviewId, status | update matching graph revision; recall dependent plan proofs when removed |
| planning.plan.created.v1 | planning / immutable plan | principalId, planId, recipeVersionId?, status, rankingVersion | aggregate feasibility/latency metrics without constraint contents |
| cooking.session.started.v1 | cooking / session pin | principalId, sessionId, planId | optional resume projection |
| cooking.session.progressed.v1 | cooking / accepted sync | principalId, sessionId, deviceSequence | user-device sync hint only; no notification for every step |
| cooking.session.completed.v1 | cooking / terminal transition | principalId, sessionId, planId | expose optional feedback prompt and reuse eligibility; no automatic social post |
| memory.feedback.changed.v1 | memory / add/edit/delete | principalId, feedbackId, action | recompute only affected memory keys, respecting user override/suppression |
| memory.preference.changed.v1 | memory / correction/forget | principalId, memoryId, action | invalidate ranker memory projection |
| memory.recipe.saved.v1 | memory / snapshot+grant | principalId, savedRecipeId, sourceType | private cookbook indexing; save counts only where user-safe |
| memory.recipe.deleted.v1 | memory / exact saved-copy tombstone | principalId, savedRecipeId | remove only this saved identity from private projections; never erase a later re-save incarnation or source recipe; aggregateType=saved_recipe and committed tombstone version |
| memory.collection.changed.v1 | memory / organize | principalId, collectionId, action | private collection sync, entitlement-aware tool projection |
| platform.media.upload_completed.v1 | platform / verified upload | mediaId, quarantineVersionId, checksum | scan/transcode pipeline; read this exact version |
| platform.media.ready.v1 | platform / sanitized assets ready | mediaId, derivativeSetVersion | publish composer status; not automatic publish |
| platform.media.rejected.v1 | platform / validation failure | mediaId, safeReasonCode | owned composer error and restricted moderation queue if warranted |
| social.post.published.v1 | social / post+audience+attachment | postId, authorUserId, aclVersion, expiresAt | visible feed projection, optional opted-in notification, expiration schedule |
| social.post.audience_changed.v1 | social / ACL change | postId, aclVersion | evict previews, invalidate delivery where needed, cancel pending unauthorized notifications |
| social.post.plate_changed.v1 | social / keep flag | postId, keepOnPlate, aclVersion | update plate index; never expand audience |
| social.post.expired.v1 | social / idempotent expiry observation | postId, expiresAt, keepOnPlate | clear Today projection, schedule orphan derivative purge; API already enforces time |
| social.post.deleted.v1 | social / tombstone | postId, aclVersion, deletionReasonCode | purge media, scrub lineage attribution, cancel pushes; keep independent authorized recipe-only copies subject to recall |
| social.recipe_save_policy.changed.v1 | social / future grants | postId, recipeHash, policyVersion, allowFutureSaves | update capability projections; no retroactive media or recipe expansion |
| social.recipe.copy_granted.v1 | social+memory / copy | postId, grantId, recipientPrincipalId, recipeHash | private provenance audit; do not send viewer private collection names |
| social.remix.created.v1 | social / own take+edge | childPostId, parentPostId?, lineageVersion | update visible remix trail and author notification only if current visibility allows |
| social.reaction.changed.v1 | social / set/remove | postId, actorUserId, kind?, action | recompute aggregate count; coalesce author notification |
| circles.membership.changed.v1 | circles / accept/remove/leave | circleId, userId, action, membershipVersion | deny/restore new reads per policy, cancel invalid notifications, invalidate circle feed |
| circles.circle.changed.v1 | circles / acknowledged name or description update | circleId, action=updated | invalidate authorized circle summaries; no circle name, description, membership list or invitation token in the event |
| circles.ownership.transferred.v1 | circles / locked two-role change | circleId, fromUserId, toUserId | refresh member controls and staff audit |
| circles.invitation.changed.v1 | circles / issue/revoke/accept | invitationId, targetType, targetId, action | local invitation status; token is never in event |
| conversations.message.created.v1 | conversations / persisted message | threadId, messageId, senderUserId | recipient inbox and generic push after membership/block checks |
| conversations.recipe_request.responded.v1 | conversations / fulfill/decline | requestId, threadId, status | authorized sender inbox; attached recipe access remains separately checked |
| coordination.sos.created.v1 | coordination / request | sosId, authorUserId, expiresAt | opted-in circle prompt, deadline job |
| coordination.sos.changed.v1 | coordination / reply/resolve | sosId, action, replyId? | request thread status, cancel reminder on close |
| coordination.pact.changed.v1 | coordination / create/respond/reschedule/cancel | pactId, action, scheduleVersion | thread, invitations and versioned reminders; reschedule requires member reconfirmation |
| coordination.potluck.changed.v1 | coordination / gathering update | potluckId, action, planVersion | participant view; confirm only after ingredient/attendance recheck |
| coordination.contribution.changed.v1 | coordination / volunteer/claim/withdraw | potluckId, contributionId, action, contributionVersion | availability projection and relevant participant notice |
| coordination.shortcut.changed.v1 | coordination / publish/remove | shortcutId, action, reviewStatus | tip list; no automatic catalog-step mutation |
| coordination.poll.changed.v1 | coordination / create/vote/remove/close | pollId, action, closesAt | fresh count projection; actor vote remains private as configured |
| commerce.purchase.received.v1 | commerce / deduped ingress | purchaseEventId, providerCustomerId, environment | reconcile authoritative subscriber state; sandbox events never unlock production |
| commerce.entitlement.changed.v1 | commerce / authoritative sync | userId, entitlementKey, status, verifiedAt | feature access refresh, household capacity state, no saved-recipe deletion |
| commerce.household.changed.v1 | commerce / accepted member/settings/delete | householdId, action, householdVersion | joint planning preferences and entitlement re-evaluation |
| commerce.pack.changed.v1 | commerce / publish/withdraw | packId, action, revision | store metadata cache and new-purchase visibility |
| safety.block.changed.v1 | safety / block/unblock | blockerUserId, blockedUserId, action | immediate authoritative deny already committed; clear queued pushes and cache |
| safety.report.created.v1 | safety / complaint | reportId, caseId, priority | restricted moderation queue; reporter details fetched only by assigned staff |
| safety.case.actioned.v1 | safety / decision | caseId, actionId, action, targetType, targetId | enforce restriction, evidence retention and reporter-safe status |
| platform.flag.changed.v1 | platform / audited change | flagKey, revision, enabled | invalidate API gate cache and client safe config |
| platform.export.ready.v1 | platform / export object completed | jobId, principalId, expiresAt | in-app receipt; download signed on access, not embedded in push |

## Notification policy

Create one notification row only if candidate recipient is active, still belongs to the relevant audience/thread, is unblocked, has enabled the event, and is not the actor. Before external send recheck these conditions and expiry; pending events are not permission snapshots. Use generic lock-screen body ("You have a FeedMe update") by default, object ID and schema version only. APNs/FCM failures do not roll back the message or post. Disable invalid device tokens and allow foreground inbox pull to reconcile missed notifications.

Proposed caps: coalesce reactions to one post-author push per 15 minutes; maximum 10 social pushes per user/day; quiet hours delay only still-relevant events; no reminders for an expired Today post. Account security notifications use a separate transactional policy, never promotional opt-in as a gate. Cooking timers are local OS notifications and do not depend on remote pushes.

## Offline command queue

| Command family | Local behavior | Reconnect behavior |
|---|---|---|
| Cook steps/timers/completion | Local database update + queue, pinned plan remains usable | Send in session/device sequence order; same key; resolve 412 without overwriting completed state |
| Preferences/pantry/memory | Private draft or marked pending change | Merge only explicitly independent fields; stale whole-record ETag requires fresh representation and conflict choice |
| Collection organization | Queue owner-only item add/remove using stable save IDs | Deleted source/expired tool entitlement returns actionable state; existing basic saves remain |
| Post drafts | Save metadata and edits locally, optional server draft while online | Resume upload with fresh reservation; user must explicitly press Share after preflight |
| Publish/replies/invites/polls/claims/purchases/delete | No claimed remote success while offline | Show pending draft or blocked action; reauthorize at execution; time-sensitive vote/claim is not silently auto-submitted later |

Queue records contain commandId, operationId, ownerPrincipalId, targetId, expectedVersion, redacted payload, dependencyCommandIds, attempts and lastErrorCode. Commands belong to one principal; logout asks whether to discard unsent drafts and clears authenticated queue encryption keys. Never replay one user's draft after another account logs in. Retry transport faults and 429/503 only; permanent 403/404/409/410/422 require a user-visible resolution.

## Telemetry taxonomy and release checks

Client analytics include `screen_viewed`, `action_attempted`, `action_succeeded`, `action_failed`, `plan_shown`, `cook_started`, `cook_completed`, `make_mine_started`, `your_take_published`, `offline_conflict_shown`. Properties are featureId, screenId, actionId, resultCode, appVersion, OS major version, connection class, durationBucket and opaque daily session. No ingredients, captions, private messages, exclusions, photos, passwords, handles or raw links. Analytics choice is separate from necessary reliability logging.

Metrics count unique command IDs for attempt-success funnels, dedupe retries, and separate guest/registered cohorts without exposing individual identity. Proposed core outcome: a user accepts a feasible meal and later voluntarily marks it manageable; social post volume is not the sole success measure.

Before release run duplicate delivery, out-of-order aggregate version, unknown additive field, poison event, deleted recipient, stale invitation, recalled recipe, provider timeout after acceptance, and restore/replay tests. Replay administrative UI permits only known consumer+event IDs, dry-run preview first, bounded batch ≤100, immutable audit reason; it never accepts arbitrary SQL, shell commands or webhook URLs.
