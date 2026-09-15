# FeedMe UI/UX coverage and execution plan

15 September 2026. This is a complete ownership and gap map, **not a claim that 98 pages are built**. The [coverage JSON](UI_UX_COVERAGE.json) preserves every canonical screen, input, hydration declaration, action and conditional transition, with one accountable role per item. It does not enable features or change production authorization.

## Current bounded implementation — 15 September, 09:53 IST

The [current accepted Android preview](verification/ui-ux/README.md) now connects the progress host to real encrypted text/optional-alt drafts, private create/reviewed Save and separately confirmed direct-local/saved-draft publication using a synthetic self-only identity/service. Exact original retry, explicit cancellation and restored-local retention are covered within the [63-method native gate](verification/ui-ux/attempts/2026-09-15T04-04-52.588Z-connected-native/report.json), with 76 actual screenshots reviewed. Older formats remain read-only in this preview route. This does not enable live accounts/providers, photos/uploads, broader audiences, feeds or iOS. The initial screen classifications and all98/900 ownership counts below are preserved, not re-audited or promoted by this bounded evidence.

## Exact coverage

| Registry item | Count |
| --- | ---: |
| Screens retained | 98 |
| V1-associated screens | 81 |
| V1-only / mixed-reference screens | 70 / 11 |
| Deferred-only screens | 17 |
| Registered actions | 900 |
| Screen actions / Back / shared navigation | 446 / 94 / 360 |
| Default destination / in-place result actions | 782 / 118 |
| Conditional branches | 52 across 22 actions |
| Confirmation boundaries / destructive actions | 22 / 13 |
| Included / deferred feature identities | 44 / 10 |

Counts derive from [the canonical registry](../../outputs/biteclub_blueprint/registry/screen_registry.json), its [button CSV](../../outputs/biteclub_blueprint/registry/button_actions.csv), and the actual [V1 manifest](V1_RELEASE_SCOPE.json). A shared association is **not** a runtime action or payload allowlist. All 17 later-only screens and later variants on shared screens remain withheld.

### Four accountable lanes

| Role | Screens | Actions | Responsibility |
| --- | ---: | ---: | --- |
| Root · design system/navigation | 26 | 558 | Shared tokens/components, entry/account/commerce UX, all Back/navigation contracts, central build and native evidence. |
| Cooking experience | 16 | 70 | Kitchen requests, pantry/preferences, adaptation, recipe, guided cooking and timers. |
| Social/library | 42 | 222 | Cookbook, social/publishing/Circles/conversations and honest demo-shell presentation. |
| UX coverage/QA | 14 | 50 | Complete coverage, system/fallback/confirmation and staff-web UX specification, evidence/oracle review. |

A page's functional owner and a file's editing owner can differ: the shared demo file remains in one editing lane, with root reviewing its navigation changes. Every navigation transition is owned by root; each functional button is owned by its screen lane. UX/QA reviews acceptance without silently becoming a second implementation owner. Operations screens are **staff web**, not extra member-native destinations or privileges.

## Historical source-only baseline — 15 September 2026, before connected preview

The initial source inspection found 9 retained partial screens, 3 embedded partial screens, 8 demo-only screens, 2 screens with related private-draft groundwork, 3 with related inline/modal components, 56 with no corresponding audited native/shared page, and 17 deferred-only pages. These are source classifications, not visual or functional acceptance percentages.

- The Android progress host has real retained meal, pantry/preferences, cooking/timer and cookbook controllers with native storage, but synthetic account/content/service. Current source startup says **Start preview**, and its header says **Preview · demo data**. This is not signup, a live backend or a production account.
- The retained journey starts at REQUEST. The complete AUTH_WELCOME → signup/login/provider callback → optional setup → HOME journey is still a required integration task. Guest cooking must remain available; social intent can be preserved for later authorized return.
- The separate DemoKitchenRuntime shell reaches sample Home, Today/My Plate, post detail, Make Mine, recipe, cooking, saves and sharing states. Its sign-in control honestly says authentication is unconnected. Its four-tab shell does not implement the canonical five-tab Today / Cook / Cookbook / Inbox / My Plate structure.
- At that initial audit, private post-draft UI had optional retained caption/alt, local/server Save/read/list/delete and original retry controls, but ProgressSessionOwner did not yet supply draftPolicy. That unexposed-host observation is historical; the bounded current connection is described above. Camera/upload, broader audience and attachment flows were not established by this baseline.
- iOS currently constructs the demo runtime. Shared Kotlin/Compose does not prove retained iOS startup, Back gestures, accessibility, storage or release readiness.
- Existing tests listed in JSON are source leads only. The initial source-only audit ran no Gradle build, app, emulator, screenshot or accessibility session. Later bounded execution is linked above; no one of the 900 registry actions is individually promoted by this document update.

At the initial audit, blue/lime native visual tokens and the older earthy blueprint palette differed. Root owns resolving the native token system; the prototype remains a reference, not a second silently active theme.

### First native refresh — separate implementation evidence

The shared native direction is now cobalt blue, lime and warm white. The first implementation batch updates the existing preview entry/navigation, meal request, pantry/preferences, recipe/cooking, timer, cookbook, private-draft and demo-shell presentation. Each lane cross-reviewed its changes; root retains build/device evidence in the [UI verification log](verification/ui-ux/README.md). That log distinguishes failed attempts, fixes, actual test-host versus preview captures and untested accessibility/platform cases. It does not retroactively change the source-only classifications or mark any of the 900 registry actions individually accepted.

## Execution strategy: small verifiable slices

### P0 — shared language, entry and navigation

1. Root finishes one set of colors, type, spacing, cards, buttons, states and branded illustration. Keep useful content first; make technical preview details secondary but explicit. Do not use decoration to conceal pending, safety or error states.
2. Root defines typed root/tab/child/modal/external return destinations. Catalogue missing routes without adding empty callbacks. Implement canonical member tabs only when each visible destination has a real screen or an explicitly labelled unavailable preview outcome; never imply live Inbox/social capability.
3. Root binds AUTH_WELCOME onward to the actual approved identity owner/provider configuration. Before providers exist, keep demo entry visibly separate and do not collect unusable credentials.
4. UX/QA walks one novice journey: entry → request → meal → cooking → finish → private save → Back/reopen. Confirm the actual saved object, not just a toast. A separate social journey is not accepted until real social owners are connected.

### P1 — cooking-experience lane

1. Request: show a clear situation field, mode, effort/time choices and one Find a meal action; collapse secondary settings without hiding allergens or unknown ingredients.
2. Pantry/preferences: labelled selection, exact item removal, dirty-state recovery and no unsupported allergy/freshness guarantees. Resolve standalone blueprint pages versus embedded controls consistently.
3. Recommendation/recipe: preserve selection/revision through alternative, Previous, Make Mine, Save and Cook; explain what changed and show effort plainly.
4. Cooking/timers: readable current step, previous/next progress, foreground timer controls, exact finish/stop/remove confirmations and honest background limits.
5. Completion: only connected actions are usable. Make Again, optional feedback, reuse and sharing require their actual owner integrations; no demo shortcut counts as delivery.

### P1 — social/library lane

1. Polish actual cookbook list/detail/search/download/removal with distinct local, server, offline, stale and recalled states. Keep basic F19; do not expose F45 custom collections.
2. Polish private draft list/editor and its local/server distinction. Validation must explain rejected text, preserve prior content, and clear on valid input. Retry shows the actual original and unknown outcome; Back never sends.
3. Implement Today, My Plate and post detail with actual authorized content and finite/retained semantics, not fictional freshness or live reactions. Make Mine retains recipe/source permission context.
4. Build a photo-only composer in small connected slices: capture/import permission → edit → recipe attachment/review → audience/save permission → explicit Publish outcome. A private Save is not Publish; upload and public publication remain separate missing integrations.
5. Add Circles, invitations, recipe requests, replies and block/report/delete with typed safe return and full empty/error/offline states. Deferred social modes are not slipped into generic pickers.

### P1 — account, commercial and staff dependencies

Root owns account/profile/privacy/notifications/export/deletion/session UX and actual provider integration. F50 remains in V1, but U07 must approve real V1 paid value before any purchase is enabled; deferred packs, households and advanced-library offers are not substitutes. UX/QA owns staff-web screen specifications and permission/error test matrices; server authorization and a real staff host remain dependencies, not mobile route features.

### P2 — acceptance, not extra feature scope

UX/QA traces each JSON action ID to an actual callback, typed owner outcome and executable assertion. Root centrally executes the appropriate build/test/native checks. Owners fix concrete issues, then attach exact source/test/platform/screenshot evidence. Deferred screens remain backlog, not test-count padding.

Every screen has three small tasks in JSON (surface, navigation, evidence), and every action has its own trace task. Task priority is execution order; P2 here means a verification batch, not permission to enable canonical P2 features.

## Usability acceptance checklist

- **UX01 — One obvious next step.** Clear heading and one visually primary action per state; plain-language labels, secondary detail progressively disclosed. No guilt, nutrition moralizing or required social participation.
- **UX02 — Predictable Back and resume.** Toolbar/system Back/gesture dismiss the top modal, preserve draft and valid selection, and return to authorized source. Dirty exit and external/provider cancellation tested; no duplicate sends.
- **UX03 — Reachable touch controls.** At least 48dp Android interactive targets, non-overlapping hit areas, safe insets and keyboard reachability; separately verify native iOS accessibility sizing.
- **UX04 — Readable visual system.** Normal text contrast at least 4.5:1, large text and relevant control boundaries at least 3:1 against actual backgrounds. Test system text scaling, narrow width, focus and long labels. Token-only checks are not blanket WCAG conformance.
- **UX05 — Accessible semantics.** Meaningful roles, labels, selected/expanded state and traversal order; decorative images excluded, actual food photos labelled; text validation announced and corrected via actual callbacks. TalkBack/VoiceOver checks required.
- **UX06 — All non-happy states.** Loading, empty, offline, denied/expired/recalled, validation, rate-limit and retry outcomes retain eligible input. Busy disables only conflicting actions; local/pending/unknown/acknowledged are distinct.
- **UX07 — Exact destructive consent.** Name exact affected item and consequences; Cancel/Back sends nothing; stale selection/revision invalidates consent; async success requires real current receipt and caller delivery.
- **UX08 — No fake navigation or success.** Every visible production action has typed destination or honest in-place outcome and real handler. No empty callbacks, invented account/publication/payment success, synthetic content masquerading as live data, or GET-as-ACK.
- **UX09 — V1 guard at every entry.** Test button, deep link, restored route, picker mode and direct operation refusal for deferred variants; shared screens are not unrestricted. Member and staff entry are separated.
- **UX10 — Evidence before acceptance.** Attach exact source revision, test/method identity, platform/device/configuration and uncropped captured UI. Review loading/error/offline/empty, rotation/process return and accessibility; no build count used as whole-screen proof.

Use [Android Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) for minimum 48dp interaction targets and semantics. Contrast/text-resize acceptance follows [WCAG 2.2](https://www.w3.org/TR/WCAG22/): normal text at least 4.5:1; relevant large text/control contrast at least 3:1. A small token contrast test is not blanket app conformance. Native TalkBack/VoiceOver, actual backgrounds, text scaling, keyboard/focus and narrow layouts need separate evidence.

## Shared-screen guard decisions

- Cookbook/collection/library controls: retain basic saves; withhold later collection creation/edit/reorder, Tonight and expanded-library upsells.
- Media: photo-only V1. No short clips through a generic capture, upload, status or admin flag.
- Picker returns: keep authorized V1 attachment, audience, invite and reply contexts; reject SOS/pact/potluck/poll and household modes at every entry.
- Packs, plan management and billing: shared F13/F50/F51 references do not enable F46 pack content/admin routes. Resolve safe Back from shared pages without entering a withheld destination.
- Flags: real staff authorization plus approved V1 allowlist; no hidden enabling of later features.
- The JSON preserves all default and conditional destinations even when withheld, so an unavailable branch is visible work rather than quietly deleted reference.

## Complete screen ownership — initial audit classifications retained

“Retained partial” is not “complete.” All states/actions and both platforms still need their exact evidence. Every row has full paths, gaps, tasks and action ownership in the JSON.

| Screen ID | Release association | Owner | Initial observed UI coverage | Actions |
| --- | --- | --- | --- | ---: |
| AUTH_WELCOME | V1 | Root | demo only | 6 |
| AUTH_SIGNUP | V1 | Root | not found | 4 |
| AUTH_LOGIN | V1 | Root | not found | 6 |
| AUTH_VERIFY | V1 | Root | not found | 4 |
| AUTH_RESET | V1 | Root | not found | 3 |
| AUTH_RESET_CONFIRM | V1 | Root | not found | 3 |
| AUTH_CALLBACK | V1 | Root | not found | 3 |
| LEGAL | V1 | Root | not found | 3 |
| PROFILE_SETUP | V1 | Root | not found | 3 |
| FOOD_PREFS | V1 | Cooking | retained partial | 8 |
| EQUIPMENT | V1 | Cooking | embedded partial | 8 |
| NOTIFICATION_PERMISSION | V1 | Root | not found | 9 |
| INVITE_ACCEPT | V1 | Social/library | not found | 3 |
| HOME | V1 | Root | demo only | 13 |
| REQUEST | V1 | Cooking | retained partial | 10 |
| PANTRY | V1 | Cooking | retained partial | 10 |
| EFFORT | V1 | Cooking | embedded partial | 9 |
| TASTE | V1 | Cooking | embedded partial | 8 |
| RECOMMENDATIONS | V1 | Cooking | retained partial | 12 |
| RECIPE | V1 | Cooking | retained partial | 16 |
| ADAPT | V1 | Cooking | demo only | 13 |
| VARIANT | V1 | Cooking | not found | 10 |
| COOK | V1 | Cooking | retained partial | 7 |
| TIMER | V1 | Cooking | retained partial | 7 |
| MEAL_DONE | V1 | Cooking | retained partial | 11 |
| FEEDBACK | V1 | Cooking | not found | 10 |
| REUSE | V1 | Cooking | not found | 8 |
| COOKBOOK | V1 · shared | Social/library | retained partial | 15 |
| COLLECTION | V1 · shared | Social/library | not found | 12 |
| COLLECTION_EDIT | V1 · shared | Social/library | not found | 12 |
| MEMORY | V1 | Social/library | not found | 10 |
| MEMORY_DETAIL | V1 | Social/library | not found | 9 |
| TODAY | V1 | Social/library | demo only | 11 |
| STORY | V1 | Social/library | not found | 10 |
| PROFILE_PLATE | V1 | Social/library | demo only | 11 |
| POST | V1 | Social/library | demo only | 23 |
| CAPTURE | V1 · shared | Social/library | demo only | 11 |
| EDIT_MEDIA | V1 | Social/library | related groundwork | 12 |
| ATTACH_RECIPE | V1 | Social/library | not found | 10 |
| REVIEW_ATTACHMENT | V1 | Social/library | not found | 13 |
| AUDIENCE | V1 | Social/library | not found | 13 |
| SAVE_PERMISSION | V1 | Social/library | not found | 10 |
| PUBLISH_STATUS | V1 · shared | Social/library | related groundwork | 11 |
| REMIX_TRAIL | V1 | Social/library | not found | 10 |
| CIRCLES | V1 | Social/library | not found | 12 |
| CIRCLE | V1 | Social/library | not found | 15 |
| CIRCLE_CREATE | V1 | Social/library | not found | 8 |
| CIRCLE_MEMBERS | V1 | Social/library | not found | 10 |
| INVITE | V1 | Social/library | not found | 10 |
| INBOX | V1 | Social/library | not found | 11 |
| THREAD | V1 | Social/library | not found | 13 |
| RECIPE_REQUEST | V1 | Social/library | not found | 14 |
| SETTINGS | V1 | Root | demo only | 18 |
| PRIVACY | V1 | Root | not found | 13 |
| BLOCKED | V1 | Social/library | not found | 8 |
| REPORT | V1 | Social/library | not found | 8 |
| DELETE_POST | V1 | Social/library | not found | 8 |
| DELETE_ACCOUNT | V1 | Root | not found | 9 |
| NOTIFICATIONS | V1 | Root | not found | 9 |
| SESSIONS | V1 | Root | not found | 8 |
| SOS_CREATE | Later | Social/library | deferred not enabled | 9 |
| SOS_DETAIL | Later | Social/library | deferred not enabled | 13 |
| TONIGHT | Later | Cooking | deferred not enabled | 9 |
| PACT_CREATE | Later | Social/library | deferred not enabled | 10 |
| PACT_DETAIL | Later | Social/library | deferred not enabled | 15 |
| POTLUCK_CREATE | Later | Social/library | deferred not enabled | 10 |
| POTLUCK_DETAIL | Later | Social/library | deferred not enabled | 15 |
| CONTRIBUTION | Later | Social/library | deferred not enabled | 8 |
| SHORTCUT_CREATE | Later | Social/library | deferred not enabled | 8 |
| SHORTCUT_DETAIL | Later | Social/library | deferred not enabled | 13 |
| POLL_CREATE | Later | Social/library | deferred not enabled | 9 |
| POLL_DETAIL | Later | Social/library | deferred not enabled | 12 |
| VIDEO_EDIT | Later | Social/library | deferred not enabled | 11 |
| HOUSEHOLDS | Later | Root | deferred not enabled | 12 |
| HOUSEHOLD_MEMBER | Later | Root | deferred not enabled | 10 |
| HOUSEHOLD_PREFS | Later | Root | deferred not enabled | 9 |
| PACK_STORE | Later | Root | deferred not enabled | 9 |
| PACK_DETAIL | V1 · shared | Root | not found | 10 |
| PAYWALL | V1 · shared | Root | not found | 10 |
| PURCHASE_STATUS | V1 | Root | not found | 9 |
| MANAGE_PLAN | V1 · shared | Root | not found | 11 |
| EXPORT | V1 | Root | not found | 9 |
| SUPPORT | V1 | Root | not found | 10 |
| UNAVAILABLE | V1 | UX/QA | related components | 10 |
| OFFLINE | V1 | UX/QA | related components | 9 |
| ADMIN_LOGIN | V1 | UX/QA | not found | 2 |
| ADMIN_HOME | V1 | UX/QA | not found | 8 |
| ADMIN_RECIPE | V1 | UX/QA | not found | 4 |
| ADMIN_REVIEW | V1 | UX/QA | not found | 5 |
| ADMIN_SUBSTITUTION | V1 | UX/QA | not found | 4 |
| ADMIN_REPORTS | V1 | UX/QA | not found | 4 |
| ADMIN_CASE | V1 | UX/QA | not found | 6 |
| ADMIN_AUDIT | V1 | UX/QA | not found | 3 |
| ADMIN_FLAGS | V1 · shared | UX/QA | not found | 4 |
| ADMIN_PACK | V1 · shared | UX/QA | not found | 4 |
| ADMIN_INCIDENT | V1 | UX/QA | not found | 6 |
| PEOPLE_PICKER | V1 · shared | Social/library | not found | 3 |
| CONFIRM_ACTION | V1 | UX/QA | related components | 3 |

## Runtime file/function ownership

The JSON lists named function snapshots and the accountable owner of **all** functions in each file. These are code ownership assignments, not permission to modify unassigned backend/session logic. Existing presentation/state/host dependencies stay read-only unless root explicitly opens them.

| Runtime file | Owner | Editing scope |
| --- | --- | --- |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressActivity.kt` | Root | Current UI lane |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressApplication.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressCanonicalService.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressCatalog.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressCookbookLedger.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressForegroundHost.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressHostState.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressIdentity.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressNativeInventory.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressNativeStage.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressServiceLedger.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressSessionOwner.kt` | Root | Read-only dependency; new assignment required |
| `apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressTimerCancellationReceiver.kt` | Root | Read-only dependency; new assignment required |
| `shared/app/src/commonMain/kotlin/com/feedme/app/App.kt` | Social/library | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/FeedMeScreens.kt` | Social/library | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/FeedMeTheme.kt` | Root | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/FeedMeUi.kt` | Root | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/CookbookScreen.kt` | Social/library | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/CookingFlowPresentation.kt` | Cooking | Read-only dependency; new assignment required |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/CookingFlowScreen.kt` | Cooking | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/CookingTimerPresentation.kt` | Cooking | Read-only dependency; new assignment required |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/CookingTimerScreen.kt` | Cooking | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/CookingUiOwner.kt` | Cooking | Read-only dependency; new assignment required |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/FeedMeMealFlow.kt` | Cooking | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/KitchenInputScreen.kt` | Cooking | Current UI lane |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/MealFlowExperience.kt` | Cooking | Read-only dependency; new assignment required |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/MealPlanPresentation.kt` | Cooking | Read-only dependency; new assignment required |
| `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/PostDraftScreen.kt` | Social/library | Current UI lane |
| `shared/app/src/iosMain/kotlin/com/feedme/app/MainViewController.kt` | Root | Read-only dependency; new assignment required |

Root owns the shared design system and navigation contract even where current file editing belongs to a peer. Root alone coordinates test execution; no agent enables staged controllers while restyling.

## Verification and evidence rules

Run `node scripts/verify-ui-ux-coverage.mjs` and `node --test scripts/verify-ui-ux-coverage.test.mjs`. This read-only structural check validates exact 98/900 identities, canonical action projections/CSV parity, feature-derived 81/17 classification, every branch/owner, real source paths, full current UI-file ownership, and honest planned/implemented/evidence distinctions. It does not build or run the app.

Promote an action only with actual handler location plus executable test identity and retained result/capture; promote a screen only after its required state/action/platform criteria are met. Related source/test files and backend receipts are not proof of a button. Runtime source can change during visual work: rerun this check and refresh paths/function ownership before accepting the final native screenshots.

## Staged backend handoff — not finished UI

Prior reviewed-publication work remains separate, unapplied/unexecuted in this UI checkpoint. Preserve the existing staged packages; do not fold them into UI completion claims:

- Natural draft-owner successor: `/private/tmp/feedme-draft-owner-natural-publication.yEPLjk`, owner SHA-256 `9ac985a0067ef95703c3092937a5fbd3a2434059390eee24d773ddf822772905`; 24 proposed tests, not executed as a UI acceptance.
- Latest reviewed-Save successor: `/private/tmp/feedme-reviewed-save-entry-guards.q24Hkb`, controller SHA-256 `ef4b4990304d15dd299362139ea25e3fe7779a9dd4eb5d8a99d47818dac86abe`; 48 proposed new tests are not compiled or executed. This adds fresh-root entry guards before clearing genuine delivered markers. The preceding `/private/tmp/feedme-reviewed-save-registration.dkoeOy` remains historical staging, not native composer availability.
- Publication lifecycle/allocator: `/private/tmp/feedme-reviewed-publication-lifecycle.Z6QMs2`; frozen allocator SHA-256 `3e759991b70678f94b541e56d7f4412e58e5e606f3ac55ff6e0e65664e64b8dc`. Owner-facing API stable; retirement transfers to the actual private Attempt, and peer handoff still lists missing regressions.

These local temporary paths are handoff references, not durable release artifacts. Root must retain dependency-closed source/evidence before later backend integration. No migration, provider, upload, public Publish or store-ready acceptance is implied by the UI polish.
