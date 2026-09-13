# FeedMe — feature delivery matrix

Baseline: 13 September 2026. Scope: all **54 features and all 98 registered screens**, including staff operations. This is an implementation and acceptance backlog, not a claim that these features are live. The Android local demo is evidence for a small shared-Kotlin foundation only; the [build status](BUILD_STATUS.md) records its actual tests and limitations. The [release plan](RELEASE_PLAN.md) owns sequencing and release decisions; [user actions](USER_ACTIONS.md) owns requests requiring the project owner's involvement.

The product remains FeedMe: effortless private cooking plus optional Today, My Plate, Kitchen Circles and Make Mine. Taste/texture controls are FeedMe features, not authorization to merge TasteEcho. All existing feature IDs are retained. A smaller competition release, if proposed, must be an explicit owner-approved release subset; it does not silently complete or delete the remaining scope.

## How to turn this matrix into work

Each feature has three small implementation work packages and specific production evidence. Split a package further whenever it changes more than one transaction, native integration or independently testable interaction. Use ticket IDs such as `F01.1`, `F01.2` and `F01.3`; record the implementing commit and evidence when closing each ticket.

For every package, attach the canonical screen/action IDs, API operation IDs, data owner, authorization rule, event, migration, failure/offline state and tests. The [screen registry](../../outputs/biteclub_blueprint/registry/screen_registry.json), [action bindings](../../outputs/biteclub_blueprint/registry/button_actions.csv), [feature traceability](../../outputs/biteclub_blueprint/registry/feature_traceability.csv), [OpenAPI contract](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json) and [individual feature specifications](../../outputs/biteclub_blueprint/features/) remain the detailed contract. Resolve conflicts against those sources before implementing; do not create another endpoint convention in this document.

**Initial status of every F01–F54: not production-accepted.** No prototype screenshot, simulated button or in-memory fixture counts as a server implementation. Each feature closes only after the full feature spec and the shared completion contract below pass. Feature flags control exposure; a disabled or deferred feature is not shipped.

The [private kitchen repository slice](PRIVATE_KITCHEN_REPOSITORIES.md) advances F12/F13/F14/F19/F30 dependencies with owner-scoped persisted pins, ordered cooking actions and recall handling. It does not check off their feature packages: actual providers/backend/UI, lifecycle/conflict resolution, native parity and release evidence remain required.

### Milestone ownership and overlap

| Milestone | Primary feature count | Delivery responsibility |
| --- | ---: | --- |
| M0 — Foundation and baseline | 1 | F53 plus shared contracts, build pipeline and platform feasibility. Local foundation exists; M0 is not complete. |
| M1 — Identity, backend and private data | 4 | F39, F43, F47, F48; durable repositories and ownership enforcement used by later groups. |
| M2 — Cooking, catalog, planning and Make Mine | 16 | F01–F13, F17, F20, F33. |
| M3 — Core social, circles and media | 11 | F21–F27, F30, F31, F38, F40. |
| M4 — Communication and coordination | 8 | F28, F29, F32, F34–F37, F41. |
| M5 — Memory, library and households | 7 | F14–F16, F18, F19, F44, F45. |
| M6 — Commerce | 2 | F46, F50; also gates paid activation of F44/F45. |
| M7 — Trust, privacy, administration and operations | 5 | F42, F49, F51, F52, F54. **Begins alongside M1**, not after social delivery. |
| M8 — Platform parity, accessibility and release candidate | All 54 | Cross-feature certification; no duplicate feature ownership. |
| M9 — Stores and Shipaton delivery | All release-enabled features | Signed public release and submission evidence; full-product completion still tracks all 54. |

Numbers are delivery groups, not a strictly serial dependency chain. Pull F19's basic durable save work into M1/M2, F51 review/catalog administration into M1/M2, and F42/F49/F52/F54 trust controls into M1 before opening M3 social access. Start F41 preference/token foundations before any notification-producing feature. Build F50 sandbox integration early to expose store/provider risks, but do not activate F44/F45/F46 sales until the relevant content, rights and billing gates pass. These overlaps do not count a feature twice.

### Shared production completion contract

- Implement all registered loaders, button commands, safe Back/Cancel routes and loading/empty/pending/error/forbidden/conflict/offline states for the listed screens. An optional button may be hidden only behind a recorded capability decision, never a dead end disguised as implementation.
- Prove server object authorization, idempotency with payload binding, revision conflicts and transactional events against a real test database. Test duplicates, account changes, revocations, worker replay and partial failure—not only happy paths.
- Persist eligible private state durably; distinguish local draft, pending receipt and confirmed remote success. Never claim offline enforcement of a remote privacy change.
- Record Android **and** iOS build/device evidence for member flows, and supported-browser evidence for staff screens. Include accessibility, process-death, denied native permissions and network interruption where applicable. Shared Kotlin tests alone do not establish native parity.
- Record approved content/rights/policy evidence and actual operational ownership where required. Runbooks, measured resource limits, feature-specific rollback, observability and deletion/export coverage are part of delivery.
- Attach commit, build ID, test environment, fixture seed, report paths and unresolved defects to the release ledger. A local test environment is not evidence of a deployed production service; production deployment and store gates are separately recorded in M8/M9.

## F01 — Make Mine

Primary milestone: **M2**, integrating M3 source permissions and M5 saving. Screens: `RECIPE`, `ADAPT`, `VARIANT`, `UNAVAILABLE`.

- [ ] F01.1: Implement authorized source resolution and immutable constraint snapshots, including reviewed recipe/substitution eligibility.
- [ ] F01.2: Materialize deterministic supported variants with full ingredients, steps, effort and source provenance; return confirmation/no-match without relaxing exclusions.
- [ ] F01.3: Connect comparison, Cook this, Save, easier and Back flows; retain inputs and revalidate at cook/save commit.

Acceptance evidence: excluded-substitute, unsupported-photo, stale-revision, recalled-recipe, inaccessible-source and replay fixtures; identical inputs produce stable eligible results. Both devices complete Story → Make Mine → cook privately without publishing. Existing licensed saves work after source expiry without restoring source media.

## F02 — Smart Meal Helper

Primary milestone: **M2**. Screens: `HOME`, `REQUEST`, `RECOMMENDATIONS`.

- [ ] F02.1: Implement guest/account plan ownership, request limits and schema-bound text interpretation with editable confirmation.
- [ ] F02.2: Build filter-before-rank matching over reviewed versions and a provider-independent manual form path.
- [ ] F02.3: Connect one useful recommendation, factual missing-item/effort summary, retry and no-match recovery while preserving the request.

Acceptance evidence: adversarial text cannot bypass exclusions or invent recipe bodies; interpreter outage leaves manual matching useful; another guest cannot read the plan. Device journeys cover ambiguous input, timeout replay and restored drafts. Measure meal acceptance separately from taps.

## F03 — Use What I Have

Primary milestone: **M2**, persistence foundation in M1. Screens: `REQUEST`, `PANTRY`.

- [ ] F03.1: Add canonical ingredient aliases and private, revisioned rough-pantry records distinct from today's availability snapshot.
- [ ] F03.2: Build search, selection/removal, Usually have and essential-item confirmation with reliable picker return.
- [ ] F03.3: Persist offline item edits and reconcile conflicts; disclose every missing essential before cooking.

Acceptance evidence: two-device conflicts, stale staples, unknown composite foods, offline deletion/reconnect and cross-owner IDs pass. Completing cooking does not decrement imagined quantities or infer food freshness. No social payload contains unselected pantry data.

## F04 — Food Preferences

Primary milestone: **M2**, privacy/storage foundation in M1. Screens: `FOOD_PREFS`, `EQUIPMENT`.

- [ ] F04.1: Implement versioned explicit exclusions, patterns and soft dislikes using reviewed ingredient/component mappings.
- [ ] F04.2: Build preset preview, controlled selection, review/Save and pending-conflict states without converting dislike into exclusion.
- [ ] F04.3: Invalidate plan caches and recheck current exclusions before new cooking, including stricter unsynced local edits.

Acceptance evidence: compound ingredients, preset expansion, contradictory text and substitute chains cannot introduce exclusions. Changed preferences mark old plans stale; personal settings are absent from social serializers/logs. Reviewer evidence supports taxonomy coverage; filtered results do not claim allergy safety.

## F05 — Match My Energy

Primary milestone: **M2**. Screens: `EQUIPMENT`, `EFFORT`, `ADAPT`.

- [ ] F05.1: Define reviewed operation metadata and versioned assemble/little/happy effort policy with total/active-time limits.
- [ ] F05.2: Build labelled energy controls, custom numeric validation and equipment selection retained across navigation.
- [ ] F05.3: Apply today's explicit bounds to initial plans, alternatives and adaptations; explain the actual unmet limit.

Acceptance evidence: hidden heat, long waiting, excessive cleanup, missing equipment and incomplete effort metadata fixtures fail correctly. No fallback loosens a selected hard bound. Practical recipe tests support displayed estimates; choices never infer wellbeing.

## F06 — Taste and Texture

Primary milestone: **M2**. Screens: `TASTE`.

- [ ] F06.1: Add reviewed sensory tags and deterministic match rules subordinate to food/effort constraints.
- [ ] F06.2: Build optional Crunch/Fresh/Creamy and reviewed-content-gated Heat choices, No preference and stable return state.
- [ ] F06.3: Display exact/related sensory fit and honest no-match; enable each category only with reviewed coverage.

Acceptance evidence: Crunch never overrides exclusions; Fresh never certifies freshness; clearing taste preserves other inputs and memories. All enabled choices have approved finished-recipe examples. This is a FeedMe-owned optional choice, not a TasteEcho integration.

## F07 — Cook, Assemble, Improve

Primary milestone: **M2**. Screens: `HOME`, `REQUEST`.

- [ ] F07.1: Implement explicit/confirmed mode selection with reviewed recipe, addition and compatibility types.
- [ ] F07.2: Validate base-meal composition and mode constraints; retain ingredients when the user changes starting point.
- [ ] F07.3: Render full-meal versus addition results and route both into the existing pinned preparation flow.

Acceptance evidence: unknown composition requires confirmation, assembly never introduces heating, and unsupported additions return no-match. Mode switching preserves inputs. No addition is described as automatically making an arbitrary meal nutritionally complete or safe to store.

## F08 — Make It Easier

Primary milestone: **M2**. Screens: `EFFORT`, `VARIANT`.

- [ ] F08.1: Implement less-prep/cleanup/time goals and reviewed before/after comparisons.
- [ ] F08.2: Create immutable child plans only when the selected burden improves and other hard bounds still hold.
- [ ] F08.3: Build comparison, Keep original and no-match; require an explicit new-session decision during an active cook.

Acceptance evidence: fewer words with identical work cannot qualify; unavailable equipment and excluded replacements are rejected. Concurrent adaptations conflict safely. Active instructions/timers never change automatically, and any burden tradeoff is disclosed on both clients.

## F09 — Something Else

Primary milestone: **M2**. Screens: `RECOMMENDATIONS`.

- [ ] F09.1: Implement deterministic bounded candidate history and opaque owner/plan/revision-bound continuation cursors.
- [ ] F09.2: Add idempotent next-candidate selection and publication/recall revalidation without changing constraints.
- [ ] F09.3: Build next/previous, exhausted and retry states with restored position and accessible result announcements.

Acceptance evidence: rapid taps and replay return one next result, tampered/cross-account cursors fail, exhaustion cannot loop forever, and recalled candidates are skipped safely. Browsing rejection produces no lasting dislike memory.

## F10 — Keep the Vibe

Primary milestone: **M2**, editorial dependency M7/F51. Screens: `TASTE`, `ADAPT`, `VARIANT`, `ADMIN_SUBSTITUTION`.

- [ ] F10.1: Author and validate versioned substitution edges with serving, quantity, step and equipment compatibility.
- [ ] F10.2: Implement ingredient-unavailable adaptation that preserves sensory intent and rejects incomplete/chained unsupported rewrites.
- [ ] F10.3: Show complete replacement comparison and explicit acceptance; pantry changes remain a separate confirmation.

Acceptance evidence: hidden components, unsupported conversion, added heat and multiple unavailable substitutes fail without loops. Review signs off the entire resulting preparation. Withdrawing one edge disables affected proposals without erasing the original recipe or preference memory.

## F11 — Effort Preview

Primary milestone: **M2**. Screens: `EFFORT`, `RECOMMENDATIONS`, `RECIPE`, `VARIANT`.

- [ ] F11.1: Validate pinned active/total/waiting/cleanup/equipment metadata and estimate provenance.
- [ ] F11.2: Build compact, expanded and comparison views with approximate labels and explicit unknowns.
- [ ] F11.3: Make the matching evaluator and UI consume exactly the same reviewed fields; update estimates with variants.

Acceptance evidence: parallel tasks are not double-counted, unknown is never zero, and creator-reported numbers remain distinct from reviewed estimates. Device text-scaling and comparison checks pass; practical testing supports metadata before matching uses it.

## F12 — Guided Cooking

Primary milestone: **M2**, durable/session ports start M1. Screens: `RECIPE`, `COOK`, `TIMER`, `OFFLINE`.

- [ ] F12.1: Create owner-scoped pinned sessions, atomically download verified bundles and persist progress before rendering success.
- [ ] F12.2: Implement multiple timer deadlines/pause/resume/cancel and native alert scheduling/reconciliation on both platforms.
- [ ] F12.3: Sync ordered revisioned commands, recover conflicts, and make complete/abandon explicit and idempotent.

Acceptance evidence: physical-device app kill/restart, clock/time-zone changes, two timers, denied alerts, incomplete downloads and two-device conflicts. Offline completion emits once after replay; a known recall blocks use. Timer expiration never certifies food readiness or automatically advances a step.

## F13 — Reviewed Recipe Library

Primary milestone: **M2**, F51 begins in M1/M7. Screens: `RECIPE`, `PACK_DETAIL`, `ADMIN_RECIPE`, `ADMIN_REVIEW`, `ADMIN_SUBSTITUTION`.

- [ ] F13.1: Create immutable recipe/ingredient/step/version schemas and reviewed publication eligibility.
- [ ] F13.2: Implement bounded authorized reads and hash-verified atomic native bundle caching.
- [ ] F13.3: Propagate retirement, supersession and recall independently through planning, saves, sessions and packs.

Acceptance evidence: real reviewer/rights records cover the launch catalog; unreviewed drafts cannot enter matching. Mixed-version downloads, paid IDs, index lag, recalled dependencies and retired authorized saves behave as specified. Prototype recipes are not treated as completed professional review.

## F14 — Make Again

Primary milestone: **M5**, basic save/signal transaction integrated during M2. Screens: `MEAL_DONE`, `COOKBOOK`.

- [ ] F14.1: Implement atomic authorized recipe save plus explicit make-again signal, deduplicated by content/source.
- [ ] F14.2: Build truthful local/pending/saved states and Undo that cannot erase a reused or separately edited record.
- [ ] F14.3: Reconcile guest/account saves and retract only this action's memory contribution when undone.

Acceptance evidence: lost response, duplicate taps, existing saves, revoke races and undo after collection edits produce correct durable state. No source media is copied, no social notification is emitted and a generic Save does not infer enjoyment/completion.

## F15 — Optional Feedback

Primary milestone: **M5**. Screens: `MEAL_DONE`, `FEEDBACK`.

- [ ] F15.1: Validate explicit signal targets against the owned recipe/plan/session and keep ambiguous negatives recipe-specific.
- [ ] F15.2: Implement create/edit/retract with outbox and local pending queue.
- [ ] F15.3: Build skippable feedback, precise scope confirmation and later edit access without blocking Done.

Acceptance evidence: skip leaves ranking unchanged; edited/replayed signals recompute once; private notes never reach analytics or creators. Offline sends after deletion do not recreate data. Negative feedback cannot become a hard exclusion automatically.

## F16 — Taste and Effort Memory

Primary milestone: **M5**. Screens: `FEEDBACK`, `MEMORY`, `MEMORY_DETAIL`.

- [ ] F16.1: Implement versioned deterministic rules from explicit feedback/save sources with deduplicated provenance.
- [ ] F16.2: Build inspectable memory projections, rebuild/retract handlers and cache invalidation.
- [ ] F16.3: Apply optional soft ranking only after hard filtering; expose pending projection and personalization-off fallback.

Acceptance evidence: replay/rebuild and guest merge cannot duplicate signals; absent ingredients, scrolling and missed days create none. Today's explicit energy overrides old memory. Personalization-off still delivers useful reviewed meals and working inspect/forget controls.

## F17 — Explain My Suggestion

Primary milestone: **M2**, memory evidence added in M5. Screens: `RECOMMENDATIONS`, `VARIANT`, `MEMORY_DETAIL`.

- [ ] F17.1: Persist filter/rank decision facts and source revisions inside plan generation.
- [ ] F17.2: Serve owner-authorized reason pages and approved localized templates without post-hoc invented explanations.
- [ ] F17.3: Connect correction destinations and label stale or unavailable source facts without breaking the recipe.

Acceptance evidence: every displayed reason agrees with the stored evaluator result; changed pantry/deleted memory/unknown codes recover safely. No private explanation is copied to a caption, household roster, notification or telemetry payload.

## F18 — Editable Memories

Primary milestone: **M5**; mandatory before enabling personalization. Screens: `MEMORY`, `MEMORY_DETAIL`.

- [ ] F18.1: Implement owner/version-bound edit/forget with source contribution detachment and replay suppression.
- [ ] F18.2: Recompute dependent effects and invalidate ranking caches before acknowledging final removal.
- [ ] F18.3: Build inspect/edit/forget and conflict UI; locally suppress forgotten effects while sync remains pending.

Acceptance evidence: forget followed by outbox replay, full rebuild or guest merge cannot resurrect the old source contribution. Shared feedback sources retain unrelated effects. Exports/new plans reflect final state; saving and memory deletion remain separate actions.

## F19 — Private Cookbook

Primary milestone: **M5**, basic durable saves/read/delete pulled into M1/M2. Screens: `RECIPE`, `COOKBOOK`, `COLLECTION`, `COLLECTION_EDIT`.

- [ ] F19.1: Implement authorized immutable recipe-only snapshots, default collection membership and deduplication; integrate F30 for social grants.
- [ ] F19.2: Build private search/open/sort/remove with atomic downloadable bundles and readable unavailable states.
- [ ] F19.3: Process recall/redaction, membership removal and local cache cleanup independently of premium organization.

Acceptance evidence: source expiry/future grant revocation and paid downgrade preserve established permitted basic access; cross-owner IDs and corrupt bundles fail. Snapshot field inspection proves no source media/comments. Basic saves ship only with applicable grant/race tests, not because the local demo has cards.

## F20 — Use It Again

Primary milestone: **M2**, saved follow-on integration with M5. Screens: `MEAL_DONE`, `REUSE`.

- [ ] F20.1: Add reviewed reuse relationships and supported portion variants based on explicitly selected ingredients/portions.
- [ ] F20.2: Build one next-use proposal separating extra work now from requirements later, with No thanks.
- [ ] F20.3: Apply an extra-portion change only after acceptance; save/start later through ordinary revalidated plans.

Acceptance evidence: unknown quantities and unsupported scaling require confirmation/no-match; declining does not alter tonight's plan. Reopened suggestions recheck current limits. No reminder, pantry decrement, expiry estimate or storage-safety inference is created automatically.

## F21 — Today

Primary milestone: **M3**. Screens: `TODAY`, `STORY`, `CAPTURE`, `PUBLISH_STATUS`, `DELETE_POST`, `UNAVAILABLE`.

- [ ] F21.1: Implement chronological deduplicated feed queries with current audience/block checks and owner-bound cursors.
- [ ] F21.2: Enforce server-assigned 24-hour expiry at every read/media request; use a worker only for projections/cleanup.
- [ ] F21.3: Build manual-advance stories, account-local viewed markers, expiry cancellation and meaningful empty states.

Acceptance evidence: exact expiry, late worker, overlapping circles, removed members, reciprocal blocks and stale cursors. An open story stops playback when invalidated; retained Plate eligibility is checked independently. Private source media cannot be replayed offline from a stale cache.

## F22 — My Plate

Primary milestone: **M3**. Screens: `PROFILE_PLATE`, `POST`, `DELETE_POST`.

- [ ] F22.1: Implement retained-post predicates and owner management reads independent of Today expiry.
- [ ] F22.2: Add revisioned keep/unkeep mutation plus access invalidation without changing the audience.
- [ ] F22.3: Build owner/viewer grid/list, retention explanations, audience/delete links and conflict recovery.

Acceptance evidence: Today-only, Today-plus-Plate, expired-on-Plate and expired-unretained states pass. Retention cannot resurrect deletion or widen visibility. Offline unkeep is explicitly pending; existing authorized recipe copies are independent of post placement.

## F23 — Kitchen Circles

Primary milestone: **M3**, identity/link ports in M1. Screens: `INVITE_ACCEPT`, `CIRCLES`, `CIRCLE`, `CIRCLE_CREATE`, `CIRCLE_MEMBERS`, `INVITE`, `PEOPLE_PICKER`.

- [ ] F23.1: Implement role/membership-generation invariants and atomic create/join/capacity/owner-transfer/dissolve transactions.
- [ ] F23.2: Add hashed expiring single-use invitations, minimal preview, explicit acceptance and revocation.
- [ ] F23.3: Build member/owner screens and native links; invalidate content/media access on removal/leave/dissolve.

Acceptance evidence: token/capacity races, expired/revoked/forwarded links, owner transfer, multi-circle access and departure/rejoin. Preview never redeems an invite or exposes the roster. Removed users cannot refresh posts or obtain new media URLs.

## F24 — Recipe Attachments

Primary milestone: **M3**. Screens: `POST`, `EDIT_MEDIA`, `ATTACH_RECIPE`, `REVIEW_ATTACHMENT`, `PUBLISH_STATUS`.

- [ ] F24.1: Implement server-derived attachability, immutable references and one-active-attachment constraints.
- [ ] F24.2: Build eligible recipe picker, structured personal recipe entry and complete provenance/save-policy preview.
- [ ] F24.3: Revalidate rights/content at publication and version replacement so only future access/saves use the new attachment.

Acceptance evidence: incomplete/recalled/private-copy-only recipes fail attachment; a photo never supplies verified ingredients. Replacement/save and rights-withdrawal/publish races serialize. Reviewed labels derive from actual review records; snapshots contain no source media/preferences.

## F25 — Your Take

Primary milestone: **M3**, relies on M2/F01. Screens: `MEAL_DONE`, `POST`, `CAPTURE`, `REVIEW_ATTACHMENT`, `REMIX_TRAIL`.

- [ ] F25.1: Validate owned variants and server-derived provenance/redistribution rights.
- [ ] F25.2: Build optional post-cook own-media/change-note draft with newly chosen audience and save policy.
- [ ] F25.3: Commit post plus acyclic lineage once and render source chips only with viewer-specific access.

Acceptance evidence: deleted/blocked source and broader audience never leak source identity/media. Private-copy rights do not permit recipe-text republication. Interrupted publishing retains the private variant and draft, requiring a new explicit preview for a standalone take.

## F26 — Remix Trail

Primary milestone: **M3**. Screens: `POST`, `REMIX_TRAIL`.

- [ ] F26.1: Implement bounded acyclic lineage traversal and viewer/start-bound cursors.
- [ ] F26.2: Sanitize every node independently and collapse inaccessible segments without hidden IDs or counts.
- [ ] F26.3: Build accessible trail navigation and per-node media reads with revocation/deep-link recovery.

Acceptance evidence: alternating private/visible nodes, hidden root, deleted account, block, malformed cycle and maximum depth. Gap markers/cursors cannot reveal identities, dates or media. Seeing a child never authorizes its parent.

## F27 — Reactions

Primary milestone: **M3**, delivery controls from F41. Screens: `STORY`, `POST`.

- [ ] F27.1: Implement one actor/post reaction row with idempotent replace/remove and current access checks.
- [ ] F27.2: Build labelled accessible picker and pending/server reconciliation, retaining only the latest offline intent.
- [ ] F27.3: Restrict aggregates/actor lists to the author and coalesce authorization-checked notifications.

Acceptance evidence: rapid replacement, duplicate events, two-device removal and expiry races yield one intended row. Blocks suppress actors/delivery. Viewer sees only their own reaction; reactions never affect food memory or feed ranking.

## F28 — Private Replies

Primary milestone: **M4**. Screens: `INBOX`, `THREAD`, `PEOPLE_PICKER`.

- [ ] F28.1: Implement unique direct threads, ordered idempotent messages and per-read/per-send participant/contact authorization.
- [ ] F28.2: Build encrypted local drafts, delivery state, pagination/read reconciliation and unavailable source cards.
- [ ] F28.3: Add accepted-context pact/potluck threads, notification suppression, mute/block/report and account-switch cleanup.

Acceptance evidence: block/send races, last-circle removal, replayed messages and account switches cannot deliver to unauthorized recipients. Departed group members lose scoped history; direct history becomes read-only as specified. No private text appears in default push previews or logs; do not claim end-to-end encryption.

## F29 — Ask for Recipe

Primary milestone: **M4**. Screens: `STORY`, `ATTACH_RECIPE`, `THREAD`, `RECIPE_REQUEST`.

- [ ] F29.1: Implement rate-limited one-pending-request state machine, cancellation and server-time expiry.
- [ ] F29.2: Build requester preview/status and author fulfill/decline with preserved drafts and explicit response.
- [ ] F29.3: Validate redistribution rights and participant access; distinguish direct reference from separate public attachment/save grants.

Acceptance evidence: double answer, expiry/cancel race, disabled contact and private-copy redistribution fail correctly. Fulfillment never changes post audience or enables copying automatically. No automatic reminders or duplicate requests on retry.

## F30 — Recipe Saving Permissions

Primary milestone: **M3**, prerequisite for social copies. Screens: `STORY`, `POST`, `SAVE_PERMISSION`, `DELETE_POST`.

- [ ] F30.1: Implement attachment grant policies/receipts and a locked save transaction with actor/source-version deduplication.
- [ ] F30.2: Build view-only default, persistent-copy disclosure, online pending/save/error states and revocation controls.
- [ ] F30.3: Propagate attachment replacement, identity redaction and safety/legal recall separately from future-grant revocation.

Acceptance evidence: save/revoke, save/delete, replacement and cross-account collection races produce one authorized outcome. Recipe copies are demonstrably media/comment-free; offline unverified taps never become confirmed saves. Final rights/erasure policy must resolve when personal recipe copies may lawfully persist versus require recall.

## F31 — Share Composer

Primary milestone: **M3**, M7 trust/media gates required before public use. Screens: `CAPTURE`, `EDIT_MEDIA`, `AUDIENCE`, `SAVE_PERMISSION`, `PUBLISH_STATUS`.

- [ ] F31.1: Implement durable draft states and exactly-once database publication per draft with revision/payload binding and outbox.
- [ ] F31.2: Implement owner-bound quarantine upload, checksum/type validation, sanitization, scanning and readiness polling.
- [ ] F31.3: Build native optional camera/picker/text-only creation, explicit audience/24-hour/retention/grant preview and crash recovery.

Acceptance evidence: double Publish, crash after commit, expired upload, failed scan, revoked circle and token refresh cannot duplicate or widen publication. Raw uploads are inaccessible. Pending draft survives process death; post-commit cancel leads to explicit deletion, not a false unpublished state.

## F32 — Fridge SOS

Primary milestone: **M4**. Screens: `SOS_CREATE`, `SOS_DETAIL`.

- [ ] F32.1: Implement selected-field circle requests with server-time expiry, response locking and close/resolve state.
- [ ] F32.2: Build preview/create/detail/reply/try-recipe and immediate private-helper fallback for unanswered requests.
- [ ] F32.3: Add contact/notification checks, cleanup and stale-offline-draft reconfirmation before sending.

Acceptance evidence: close/respond races, late workers, stale sends, circle departure and inaccessible recipe links. No entire pantry/preferences are shared; no fake responses appear. Expiry begins at commit and nobody replying never blocks dinner.

## F33 — Tonight

Primary milestone: **M2**, basic saved-source support from F19. Screens: `COOKBOOK`, `TONIGHT`.

- [ ] F33.1: Create fresh ordinary plans with Tonight intent and current rights/constraint revalidation.
- [ ] F33.2: Persist owner-local selected-plan/checklist revisions; apply replacement only after its successful receipt.
- [ ] F33.3: Connect cooking recovery, clear-selection and separately confirmed pact invitation entry.

Acceptance evidence: out-of-order responses, app kill, account switch, recalled copies and expired source links preserve safe prior state. Checklist is explicitly device-local and does not imply pantry, shopping, cross-device calendar or reminder actions.

## F34 — Dinner Pact

Primary milestone: **M4**. Screens: `PACT_CREATE`, `PACT_DETAIL`, `PEOPLE_PICKER`.

- [ ] F34.1: Implement host/invite/participant lifecycle, atomic capacity checks and schedule-version acceptance.
- [ ] F34.2: Build recipient preview, join/decline/leave/cancel and reconfirmation after schedule changes.
- [ ] F34.3: Attach privately owned plans, participant-scoped conversation and voluntary result sharing; expire/coalesce reminders safely.

Acceptance evidence: final-seat races, host-edit/accept races, time-zone changes, blocks and cancellation. Other participants never receive personal plan IDs or dietary settings. Joining does not start a call, camera, purchase or publication; cancellation preserves independent private recipes.

## F35 — Bring a Bit

Primary milestone: **M4**. Screens: `POTLUCK_CREATE`, `POTLUCK_DETAIL`, `CONTRIBUTION`, `PEOPLE_PICKER`.

- [ ] F35.1: Implement accepted-participant access, explicit ingredient quantities/units and one-active-claim locking.
- [ ] F35.2: Build contribution/claim/release/brought/withdraw forms and revision-bound reviewed group-plan confirmation.
- [ ] F35.3: Handle contribution invalidation, schedule/location reconfirmation, departure and cancellation with durable receipts.

Acceptance evidence: competing claims, claimed-item withdrawal, incompatible units and plan-confirmation races cannot certify absent ingredients. Exact location is participant-only and absent from pushes/logs. No automatic pantry sharing, unit guessing or purchasing occurs.

## F36 — Shortcut Swap

Primary milestone: **M4**. Screens: `SHORTCUT_CREATE`, `SHORTCUT_DETAIL`.

- [ ] F36.1: Implement original-tip provenance, audience/rights checks and permitted private text-only snapshots.
- [ ] F36.2: Build author/detail/save/helpful/withdraw and clearly separate private cooking notes from instructions.
- [ ] F36.3: Add note conflict/retry, report/recall handling and independent reviewed Make Mine navigation.

Acceptance evidence: peer tips cannot mutate recipe steps, ingredients, timers or review labels. Source revocation, duplicate saves, conflicting notes and private-copy republication are covered. Helpful acknowledgement cannot promote content to professionally reviewed.

## F37 — Dinner Vote

Primary milestone: **M4**. Screens: `POLL_CREATE`, `POLL_DETAIL`.

- [ ] F37.1: Implement immutable supported options, one vote/member, revisioned replacement/removal and server-time closure.
- [ ] F37.2: Freeze eligible aggregate totals once; handle ties and membership changes without voter disclosure.
- [ ] F37.3: Build audience preview, voting/results/cancel and separate personal Tonight/pact-draft actions.

Acceptance evidence: close-time equality, delayed worker, concurrent votes, removed members and inaccessible recipes. No late votes count; no hidden voter names or claimed anonymity. Choosing an option never accepts a pact or sends an invitation.

## F38 — Short Clips

Primary milestone: **M3**, video-specific activation after M7 moderation/cost approval. Screens: `CAPTURE`, `PUBLISH_STATUS`, `VIDEO_EDIT`, `ADMIN_FLAGS`.

- [ ] F38.1: Implement native selection/trim/mute/caption/accessibility with explicitly tested duration/size bounds.
- [ ] F38.2: Validate actual uploaded format/checksum/duration, sanitize/scan/transcode idempotently and keep originals private.
- [ ] F38.3: Deliver separately authorized short-lived playback capabilities; recover upload failures and stop/clear playback on invalidation.

Acceptance evidence: forged MIME, oversized clips, rotation/audio variants, denied permissions, process death and worker redelivery on both platforms. Measure the documented residual signed-URL lifetime (at most 60 seconds, further bounded by surface expiry); never claim immediate erasure of transferred frames. Still/text alternatives work.

## F39 — Private Cooking Preferences

Primary milestone: **M1**, enforced across M2–M7. Screens: `FOOD_PREFS`, `PANTRY`, `MEMORY`, `SETTINGS`, `PRIVACY`.

- [ ] F39.1: Separate private cooking settings, public profile DTOs and deliberately shared cooking context with allowlist serializers.
- [ ] F39.2: Implement revisioned contact preferences and current-policy checks before sends/notification delivery.
- [ ] F39.3: Build privacy explanations, shared-field previews and accurate pending/conflict/account-switch behavior.

Acceptance evidence: forbidden-field tests cover every social/coordination/profile/push/log payload. Policy-toggle/send races and stale-plan exclusion checks pass. Circle/household membership never itself grants another person's private settings; blocks take precedence.

## F40 — Audience Controls

Primary milestone: **M3**, server policy foundation starts M1/M7. Screens: `AUDIENCE`, `CIRCLE`, `CIRCLE_MEMBERS`, `PRIVACY`.

- [ ] F40.1: Implement SELF/CIRCLES access predicates using status, placement, membership generation and bidirectional blocks.
- [ ] F40.2: Build explicit picker/default/post-edit previews with no silent widening or retroactive default application.
- [ ] F40.3: Commit audience revisions/invalidation and require fresh authorization for all content/media reads.

Acceptance evidence: author/viewer departure-rejoin, multiple circles, concurrent scope changes and expired-Today/retained-Plate tests. Offline narrowing remains pending until receipt. Residual media capabilities are measured/disclosed; inaccessible alternate circles are never serialized.

## F41 — Notifications

Primary milestone: **M4**, preference/device foundations before M3 event delivery. Screens: `NOTIFICATION_PERMISSION`, `INBOX`, `SETTINGS`, `NOTIFICATIONS`.

- [ ] F41.1: Implement category/quiet-hour settings and secure installation/session-bound APNs/FCM registration/revocation.
- [ ] F41.2: Create deduplicated notification/delivery jobs with reauthorization immediately before sending, retry limits and expiry suppression.
- [ ] F41.3: Build contextual native permission, in-app activity/read state, safe deep links and denied-permission settings route.

Acceptance evidence: logout/token reuse, provider rejection, disabled categories, send races, DST quiet hours, cancelled plans and repeated outbox events. Lock-screen payloads omit sensitive names/content/location; provider acceptance is not read status or exactly-once delivery. In-app use remains available without push.

## F42 — Delete, Mute, Block, Report

Primary milestone: **M7**, beginning alongside M1; required before M3/M4 exposure. Screens: `PRIVACY`, `BLOCKED`, `REPORT`, `DELETE_POST`, `SUPPORT`, `UNAVAILABLE`, `ADMIN_REPORTS`, `ADMIN_CASE`, `CONFIRM_ACTION`.

- [ ] F42.1: Implement distinct owner tombstone/mute/block/report transactions and immediate access-policy changes.
- [ ] F42.2: Build accurate confirmations, pending states, unblock cleanup and private report receipts/evidence references.
- [ ] F42.3: Add recoverable media/cache purge, cross-module invalidation, abuse limits and moderation handoff.

Acceptance evidence: block/send, delete/save and report/delete races; reciprocal denial, muted-but-authorized content and unblock without restored invites. Cleanup failure cannot permit new media URLs. Kill switches retain safety controls; ordinary deletion and exceptional recipe recall remain distinct.

## F43 — Optional Participation

Primary milestone: **M1**, validated throughout all milestones. Screens: `AUTH_WELCOME`, `HOME`, `TODAY`, `OFFLINE`.

- [ ] F43.1: Implement bounded real guest capabilities and explicit receipt-based guest/account merge.
- [ ] F43.2: Build Try cooking first, zero-friends help, Skip/Done routes and social-discovery presentation preference.
- [ ] F43.3: Isolate social/billing/provider failures from eligible private cooking and retain safe guest recovery.

Acceptance evidence: no account/friends/push/camera/payment is required for useful eligible cooking and existing basic saves. Expired guest and failed merge preserve recoverable data. Hiding social UI does not falsely claim to unpublish posts or revoke audience grants.

## F44 — Household Preferences

Primary milestone: **M5**, production paid activation gated by M6/F50. Screens: `HOUSEHOLDS`, `HOUSEHOLD_MEMBER`, `HOUSEHOLD_PREFS`, `PAYWALL`, `MANAGE_PLAN`, `ADMIN_FLAGS`.

- [ ] F44.1: Implement owner/member/seat/invitation invariants and separate shared defaults from private consented requirements.
- [ ] F44.2: Build explicit join/consent/withdraw/remove/leave/dissolve and online household planning snapshots.
- [ ] F44.3: Revalidate entitlement, membership, consent and serving bounds at plan creation; preserve individual access on lapse.

Acceptance evidence: final-seat races, withdrawn consent, removal mid-plan, expired entitlement, unsupported servings and cross-household IDs. No result reveals which member supplied a specific exclusion. Household membership is not store-family sharing; dissolution never deletes personal cookbooks.

## F45 — Expanded Personal Library

Primary milestone: **M5**, premium writes gated by M6/F50. Screens: `COOKBOOK`, `COLLECTION`, `COLLECTION_EDIT`, `PAYWALL`, `MANAGE_PLAN`.

- [ ] F45.1: Implement owner-scoped custom collection metadata, membership and bounded complete-order transactions.
- [ ] F45.2: Build accessible editor/reorder/filter with explicit conflict resolution and contextual paywall return.
- [ ] F45.3: Enforce current server capability for advanced writes while preserving basic/read/delete/export access on lapse.

Acceptance evidence: unpaid direct calls, pending purchase/refund, two-device reorder, duplicate membership and invalid IDs. Removing a grouping never deletes recipe copies; default cookbook cannot be deleted. Existing saved recipes remain searchable/openable without an upgrade or new cap.

## F46 — Reviewed Situation Packs

Primary milestone: **M6**. Screens: `PACK_STORE`, `PACK_DETAIL`, `PAYWALL`, `MANAGE_PLAN`, `ADMIN_PACK`.

- [ ] F46.1: Implement reviewed versioned pack manifests, content/rights evidence and canonical store-product mappings.
- [ ] F46.2: Build sample/preview/localized-offer/owned collection flows without exposing protected recipe bodies.
- [ ] F46.3: Authorize atomic downloads and manage publish/withdraw/update/recall separately from established ownership.

Acceptance evidence: pending/duplicate purchases, restore conflicts, product rename, withdrawn offers and partial downloads. Unreviewed content cannot enter a sold manifest. Real review, licensing, localized terms and billing acceptance are required; no guessed price or automatic sale follows from code completion.

## F47 — Signup, Login and Recovery

Primary milestone: **M1**. Screens: `AUTH_WELCOME`, `AUTH_SIGNUP`, `AUTH_LOGIN`, `AUTH_VERIFY`, `AUTH_RESET`, `AUTH_RESET_CONFIRM`, `AUTH_CALLBACK`, `LEGAL`, `INVITE_ACCEPT`, `SETTINGS`, `DELETE_ACCOUNT`, `SESSIONS`, `ADMIN_LOGIN`.

- [ ] F47.1: Configure approved environment identities/callbacks; implement native provider/email challenge states, PKCE/state/nonce validation and secure credentials.
- [ ] F47.2: Add JWT plus application-account/device-session gates, unique transactional bootstrap and explicit guest merge.
- [ ] F47.3: Build signup/verify/login/reset/logout/revocation and authorized deferred-route recovery; keep staff workforce identity separate.

Acceptance evidence: both-device provider/email end-to-end checks, tampered callbacks, duplicate bootstrap, lost responses, cancellation and revoked sessions. Unknown-email recovery looks equivalent. No secrets enter clients/logs/screenshots; no accounts are linked merely because an email string matches.

## F48 — Profile and Onboarding

Primary milestone: **M1**, sanitized avatar completion with M3 media. Screens: `PROFILE_SETUP`, `FOOD_PREFS`, `EQUIPMENT`, `NOTIFICATION_PERMISSION`, `PROFILE_PLATE`, `SETTINGS`.

- [ ] F48.1: Implement canonical unique handles, revisioned profile writes and explicit onboarding checkpoints/required gates.
- [ ] F48.2: Build name/optional avatar, skippable kitchen setup and confirmed guest-save merge with native form behavior.
- [ ] F48.3: Separate Settings editing from first-run routing and recover conflicts/avatar rejection without blocking name-only entry.

Acceptance evidence: duplicate-handle races, provider-first account, guest conversion, lost response and rejected avatar. Skip does not imply consent/preferences; delayed invite still needs explicit acceptance. A zero-friends profile reaches meal help.

## F49 — Account Data and Privacy Operations

Primary milestone: **M7**, beginning alongside M1; not a post-launch task. Screens: `LEGAL`, `SETTINGS`, `PRIVACY`, `DELETE_ACCOUNT`, `SESSIONS`, `EXPORT`, `SUPPORT`, `ADMIN_AUDIT`, `CONFIRM_ACTION`.

- [ ] F49.1: Approve data/rights/retention inventory and implement recent-auth account/session gates plus per-module export/delete interfaces.
- [ ] F49.2: Add durable cursor-based jobs, encrypted expiring exports, immediate deletion suspension/revocation and safe receipts.
- [ ] F49.3: Build member controls, redaction/recall rules and backup-restore replay of deletion tombstones.

Acceptance evidence: every owned-data table has a handler; worker death/replay, expired download, cross-owner IDs and delayed purchase webhooks cannot leak/recreate data. Approved retention exceptions are disclosed. Deleting the app account is not presented as cancelling store billing, and deleted sessions cannot poll protected jobs.

## F50 — Purchases and Entitlements

Primary milestone: **M6**, sandbox risk spike in M0/M1. Screens: `SETTINGS`, `PAYWALL`, `PURCHASE_STATUS`, `MANAGE_PLAN`, `ADMIN_FLAGS`, `ADMIN_PACK`.

- [ ] F50.1: Configure owner-approved sandbox products/mappings/account-association policy and native RevenueCat adapters.
- [ ] F50.2: Implement authenticated deduplicated webhook ingress, provider reconciliation and server-owned capability enforcement/drift repair.
- [ ] F50.3: Build localized purchase/pending/cancel/restore/manage flows with explicit account conflicts and sales kill switches.

Acceptance evidence: both store sandboxes cover pending/cancel/refund/renewal/restore/account switch; forged client flags and reordered/replayed events never grant false access. Free cooking and established basic saves survive outage/lapse. Actual live products, paid terms and signing are separate owner/release gates.

## F51 — Content Administration and Professional Review

Primary milestone: **M7**, beginning alongside M1, blocking M2 reviewed matching. Screens: `ADMIN_LOGIN`, `ADMIN_HOME`, `ADMIN_RECIPE`, `ADMIN_REVIEW`, `ADMIN_SUBSTITUTION`, `ADMIN_AUDIT`, `ADMIN_PACK`.

- [ ] F51.1: Implement workforce roles, review/rights records and content-hash-bound recipe/substitution/pack revisions.
- [ ] F51.2: Build author/reviewer/publisher UI with independent approval, version conflicts and audited publication/recall.
- [ ] F51.3: Seed only actually reviewed/licensed content and propagate lifecycle changes through matching, cooking, saves and packs.

Acceptance evidence: draft → reject → revise → approve → publish → recall is exercised by authorized roles; self-approval and stale approvals fail. Real reviewer credentials/evidence are recorded. Recalled content is immediately ineligible online even if workers lag; offline awareness limits remain explicit.

## F52 — Moderation Operations

Primary milestone: **M7**, beginning alongside M1, blocking M3 public UGC. Screens: `REPORT`, `ADMIN_LOGIN`, `ADMIN_HOME`, `ADMIN_REPORTS`, `ADMIN_CASE`, `ADMIN_AUDIT`.

- [ ] F52.1: Approve community/severity/evidence-retention/escalation policy and assign real moderation/appeal coverage.
- [ ] F52.2: Implement private evidence receipts, role-scoped queues/leases and audited remove/dismiss/restrict/appeal transactions.
- [ ] F52.3: Build staff case UI, revocation integration, conflict/retry handling and overload publication-pause controls.

Acceptance evidence: reporter identity stays private; reporters cannot read other cases; staff cannot self-review appeals. Duplicate/conflicting actions, restricted-user login, failed evidence capture and delayed media jobs are covered. A staffed synthetic/pilot drill demonstrates actual queue handling before invitations broaden.

## F53 — Navigation, Offline Use and Deep Links

Primary milestone: **M0**, incrementally completed across M1–M8. Screens: `AUTH_WELCOME`, `AUTH_CALLBACK`, `INVITE_ACCEPT`, `COOK`, `TIMER`, `INVITE`, `UNAVAILABLE`, `OFFLINE`, `CONFIRM_ACTION`.

- [ ] F53.1: Generate typed routes/action bindings/API transport from reviewed registries; define shared repositories and native ports.
- [ ] F53.2: Implement account-scoped durable SQLite migrations/command queue, safe picker return and secret-free process restoration.
- [ ] F53.3: Parse allowlisted native links, retain bounded pending routes through authentication and reauthorize objects before display.

Acceptance evidence: all 98 screens and 900 action bindings resolve to native/staff/explicit external implementations with safe exits; all 201 API operations retain contract ownership. App-killed timers, interrupted Make Mine, picker cancel, expired invite, unknown route and account-switch tests pass on both platforms. The current memory-only demo does not close this feature.

## F54 — Telemetry, Reliability and Release Control

Primary milestone: **M7**, beginning alongside M1; foundations in M0 and final certification M8/M9. Screens: `ADMIN_HOME`, `ADMIN_AUDIT`, `ADMIN_FLAGS`, `ADMIN_INCIDENT`.

- [ ] F54.1: Implement telemetry allowlist, support-safe request IDs, transactional outbox/inbox and observable retry/dead-letter queues.
- [ ] F54.2: Build role/audit-protected flags, incidents, bounded replay and feature-isolated outage/rollback controls.
- [ ] F54.3: Instrument measured pilot load, provider/worker/database failure, backup/restore and deployment-compatibility drills with real incident ownership.

Acceptance evidence: trace samples contain no prompts, exclusions, captions, messages, emails or signed URLs. Replay cannot resurrect deleted objects or blindly re-notify users; restore applies deletion tombstones before access opens. Measured capacity/latency evidence—not proposed targets—determines rollout; telemetry failure cannot block cooking.

## M8 — Cross-feature release-candidate checklist

M8 verifies the union of the 98 screens above; it does not add unregistered screens or mark deferred ones complete.

1. Reconcile registry, full feature specs and OpenAPI; generate a route/action test inventory with no orphan screens or unimplemented enabled controls. Resolve semantic mismatches before generating clients (for example Tonight uses a new plan command even where older traceability lists adaptation reads).
2. Compile Android and the shared iOS framework; run real device/simulator journeys with the actual backend, not demo fixtures. Full Xcode and signing/access are external prerequisites recorded in USER_ACTIONS.md.
3. Audit all member screens for TalkBack/VoiceOver, focus, text scaling, contrast, touch targets, keyboard, reduced motion, safe areas, denied OS permissions and correct Back/Cancel. Audit staff screens with keyboard/screen reader and supported browsers.
4. Run contract/security/access/race, migration N/N−1, process-death/offline, media, commerce and full-account export/deletion tests. Rehearse feature rollback and recovery against the exact release candidate.
5. Inventory assets/fonts/content licenses and software dependencies; remove development fixtures, placeholder identities, misleading health claims, secrets and inaccessible store-review paths from the release configuration.
6. Record enabled capabilities, supported devices/locales, signed candidate hashes, measured performance, moderation/on-call coverage and every outstanding defect. No critical unresolved safety/access/billing defect is waived by a deadline.

## M9 — Store and Shipaton delivery checklist

1. Verify current competition/store rules, eligible account status, target markets and review lead times; record authoritative evidence in RELEASE_PLAN.md. Design or emulator completion is not competition delivery.
2. Owner confirms production app identifiers, listing identity, domains, signing teams, privacy/community/support documents, age/market declarations and actual monetization configuration. Request approvals/secrets through the documented user-action process, never in repository text or logs.
3. Build signed release artifacts only after release guards are deliberately replaced by audited readiness gates. Upload via authorized accounts; supply reviewer access/instructions and truthful data/permission/billing declarations.
4. Complete store review/testing/production-access requirements and resolve review feedback. Verify public eligible store availability and functioning production purchase/ads requirement using the selected compliant offering, without making a real purchase absent authorization.
5. Prepare actual app demo, store URLs, project description, screenshots/video, attribution, architecture/feature narrative and required Shipaton submission fields; obtain final owner review and explicit submission authority before submitting.
6. Confirm accepted submission and public listing evidence, activate monitoring/support coverage, and record release/rollback identifiers. A full FeedMe-complete claim additionally requires production acceptance for every F01–F54; an approved narrower release reports the remaining features honestly.

## Coverage reconciliation and critical-path concerns

The primary milestone assignment covers **54 unique feature IDs exactly once**. Their canonical screen lists cover **98 unique screen IDs**; shared screens intentionally appear under multiple features. The registry remains the source for the 900 action bindings and 201 API operations: these totals describe intended coverage, not passing implementation tests. Acceptance here supplements rather than replaces every scenario and lifecycle rule in the 54 source specifications.

The full blueprint's reference estimate is **106–178 engineering person-weeks**, excluding external account/review waits and editorial/moderation effort. It cannot credibly be compressed into the remaining competition calendar by changing milestone labels. Re-estimate from actual delivery capacity and evidence; do not silently cut the optional-social identity to fit a date.

Critical dependencies are: approved identity/provider setup and both-platform tooling → real private ownership/persistence → professionally reviewed bounded catalog and exact matching → durable cooking/saves → authorized circles/quarantined media → Make Mine/Your Take/social rights → staffed trust/privacy operations → sandbox/live commerce and platform certification → store approval/public availability → submission. Catalog review, store eligibility, Xcode/signing and moderation coverage run early in parallel because waiting for code completion to start them would delay release.

Resolve rights/erasure specifics before granting social copies: F30 describes persistence after ordinary source removal, while F49 requires recall of personal user-authored copies when continued retention is not demonstrably permitted. The implementation needs a single approved rights/retention policy and fixtures, not competing interpretations. No unverified content license or legal sufficiency is assumed by this matrix.

Alarms and owner decisions are handled by RELEASE_PLAN.md/USER_ACTIONS.md and the thread's configured follow-up mechanism. A blocked external prerequisite does not authorize creating accounts, spending money, selecting a production identity, publishing content or accepting legal terms on the owner's behalf. Continue independent in-scope work while reporting the exact dependency and evidence needed to unblock it.
