# FeedMe — pantry and preference editing component

14 September 2026. **VERIFIED BOUNDED COMPONENT; not release acceptance.** F03/F04 remain partial. Canonical API/schema definitions override historical prose (for example listPantry does not promise a collection ETag).

## Actual implementation

The retained `MealFlowExperience` owns `KitchenInputController` on the same serialized dispatcher and borrows the actual `PrivateSessionAccess`. PANTRY and FOOD_PREFS are child pages; opening, rendering, returning and Activity recreation never send a command. Ingredient labels come from controlled lookup, never a fabricated pantry name. Neither a pantry report nor a saved preference establishes food freshness or allergy safety.

The editor keeps server observations, local draft patches and original pending commands separate. Local edits use acknowledged encrypted storage. Save admits one canonical command to the existing `DurableCommandQueue`; the host then explicitly attempts only that exact saved target. `updatePreferences` pins its original If-Match, `upsertPantryItem` pins the observed expectedVersion in its body, and removal pins the item version and requires visible confirmation. No second queue, automatic new key, implicit overwrite or pantry-to-current-meal selection exists.

Pending preference drafts/commands block new planning and continuation. A same-lease memory fence also protects a stricter draft whose local write was not acknowledged. Closing the editor does not grant permission to release that fence. Receipt application is atomic with journal/domain changes and preserves newer drafts. Private state is synchronously redacted on session invalidation.

A replacement editor retains an unacknowledged draft visibly and blocks Save until an acknowledged edit or explicit fresh discard. If an applied write loses its acknowledgement, finalization requires the exact original command, domain payload and archived receipt, then a fresh changed-CAS acknowledgement and unchanged evidence. Merely seeing an APPLIED journal row or discarding the draft cannot release the planning guard. UI copy explicitly distinguishes this unresolved finalization from acknowledged success. A separate operation-owner token prevents late cancellation of an older caller return from cancelling a newer operation.

## Deliberate integration limits

- Attempted 412/422 or other permanent conflicts retain original evidence and show base/current values; a verified user-chosen conflict-resolution API is still required. These are not completed offline-conflict features.
- F03's original V1 design leaves quantity/unit unset. Rough reports do not infer confirmation times, quantities, freshness or automatic consumption.
- Existing dietary IDs and defaults remain visible. Adding/expanding dietary presets and explicit consent are not invented in this editor; approved reviewed configuration/workflows remain required.
- The required `KitchenInputPolicy.maxResponseBytes` is a selected integration profile, not a canonical global server limit. The native fixture explicitly uses65,536 bytes; actual transport/server integration must agree and preserve reservation/receipt bounds.
- Production pantry/preference HTTP authority is not connected. Synthetic fixture replies are labelled test-only. Provider, bootstrap, catalog review, cross-device conflict resolution, iOS and whole-feature/release gates remain open.

## Acceptance

The [fourth-batch frozen run](PARALLEL_KITCHEN_SOCIAL_HOST.md) passed at2026-09-14T00:45:13.550Z:129 mealflow tests (53 request,30 picker,46 kitchen-input),108 sync tests and57 app tests, including21 kitchen presentation regressions. Nine actual Android retained-host methods include preference draft/guard/save and pantry rough-report/save/confirmed-removal journeys using real native persistence with labelled synthetic account/transport. The complete regression includes2,117 Kotlin/server/database,181 Node and345 native successes. This does not close F03/F04 or the integration limits above.
