# FeedMe product and interaction design

## Design direction

The brand is FeedMe: **See a plate. Make it yours.** Its personality is informal, inviting and lightly irreverent. Food, effort and friendship are the visible subjects. The playful rulebook remains part of the established personality; the user-approved FeedMe rename does not change the concept. Use original assets, copy and visual identity. The core question is “What can we make easy today?” and the main social conversion action is **Make Mine**.

## Information architecture

New users start at AUTH_WELCOME. Email/signup verification or Apple/Google sign-in leads through optional profile/kitchen setup to HOME. Guest cooking is an alternative on that same entry screen; social entry returns the user to authentication and then to the authorized intended destination. Main tabs are **Today**, **Cook**, **Cookbook**, **Inbox**, **My Plate**. HOME is the Cook tab. Settings is reached from My Plate. Staff operations have a separate login and desktop workspace; they are never a hidden member privilege.

Today is a finite recent-post collection. My Plate is an author-selected retained profile grid. The private Cookbook stores permitted recipe content; it does not silently preserve another person's photo or conversation. Keep this distinction visible at sharing and deletion. Reuse one post composer with explicit audience, Keep on my Plate and recipe-save permission; do not ask a user to choose between incompatible story and post products.

## Visual tokens

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| Background | #FFF9EF | #17231E | Main product surface |
| Surface | #FFFFFF | #23382C | Sheets and grouped controls |
| Text | #1E3328 | #F5F4DC | Primary text and icons |
| Secondary text | #52604D | #C5CCB8 | Supporting text, validated against background |
| Action | #B93E20 | #FFA16E | Primary action only |
| Action text | #FFFFFF | #17231E | Labels on action fill |
| Soft highlight | #D5E87B | #42532D | Optional accent surfaces, with paired readable text |
| Divider | #D9DED0 | #52634E | Structural boundaries |

Use native/system sans-serif for functional UI, with an optional restrained editorial serif in marketing/empty-state headings. Body 16sp, supporting text 14sp, navigation and controls at least 14sp; scale with OS text size. Small badges may use 12sp but never carry the only essential instruction. Headings 24–32sp, weight 500–650 as supported. Spacing scale: 4, 8, 12, 16, 24, 32dp. Component corner radii: 12dp controls, 20dp content groups, 28dp sheets. Theme tokens are semantic; features do not invent new palettes.

## Layout and reusable components

Phone width reference 390dp; test 320dp through tablets and landscape. Use safe areas and IME insets; a keyboard must not hide the active field or primary save action. Member pages use app bar → context → content/form → primary action → supporting actions. Focused cooking and story views use their own clear exit and omit crowded bottom chrome. Adaptive tablet layouts put recipe/context beside current steps, and do not simply stretch a phone form.

Reusable components: AppScaffold, AuthForm, ConstraintChips, IngredientConfirmationList, MealCard, RecipeDiff, EffortSummary, CookStepCard, TimerPanel, OptionalFeedback, SourceChip, PostCard, RecipeAttachment, AudiencePicker, PermissionSummary, ProcessingReceipt, MemberPicker, EmptyState, InlineError, ConfirmActionSheet and PendingOperationBanner. All accept typed domain values and emit intents. They never directly call a backend or vendor SDK.

The canonical screen registry is spec/model.mjs plus refinements.mjs. It declares each screen's purpose, input controls, entry data loads, buttons, next destination, server/native command, side effects, guard, offline policy and failure routing. Generated screen documents and the navigation prototype are the detailed wireframe specification for every registered surface.

## Input, button and state rules

Text/select/checkbox changes update only a local draft until an explicitly labelled action commits. Every field has an associated label and validation message. Required controls are marked in the registry; examples are synthetic fixture values. Buttons have stable action IDs for test automation. The first app button is not universally “primary”: primary styling is assigned by the screen's intended action, while deletion remains separately confirmed.

Each screen supports loading, ready, empty, invalid-input, unauthorized/unavailable, conflict, retryable error and offline states where applicable. Preserve geometry on load and the user's non-secret input on errors. A spinner without a bounded timeout and recovery is unacceptable. Action success is based on a durable server receipt, not the user tap. Retry uses the same operation ID after a lost response; changed intent uses a new ID. Disable only the conflicting action while a request is in flight. Never block Back merely because an optional request failed.

## Navigation and picker behavior

Every app route carries typed IDs, an optional entry mode and an allowlisted return context. Meal cards opened from Today, a save, or a friend request retain source identity/rights and meal constraints. Selecting a recipe for a post/poll/pact/SOS returns the reference to that draft; it does not accidentally start a separate cooking session. PEOPLE_PICKER similarly returns chosen eligible IDs. Back/cancel discards only uncommitted selection changes. Profile edit from Settings returns to Settings; first-run profile setup continues onboarding.

Use CONFIRM_ACTION for scoped destructive/permission-impacting actions, except dedicated DELETE_POST and DELETE_ACCOUNT screens with richer explanations. The sheet names the target, changes and recovery limits. Its confirm button dispatches the originating command with the same target/version. The app cannot complete a destructive command merely because a navigation button was tapped.

## Authentication and permissions

Offer email/password and provider buttons consistently with available platform configuration. Password-manager and accessibility support are part of delivery. Native camera, photo picker, push, share sheet, browser sign-in and billing interfaces are external system surfaces; registry actions identify the adapter boundary and the success/cancel/error returns. Never imitate a system credential prompt inside the product.

Request media permission only when the user chooses capture/import. Use the limited/system picker where possible. Explain push value before the OS prompt and keep Maybe later visible. A denied OS permission provides an appropriate settings link without repeated prompts. A guest choosing a social action sees why a member identity is needed, signs in, and resumes with authorization rechecked.

## Cooking and content trust

Ingredient uncertainty is visible and confirmable. Hard exclusions, unavailable equipment and time limits are never quietly relaxed. Distinguish reviewed catalog instructions, a creator's confirmed recipe information, and community tips. Show changes and source for Make Mine. A photo without enough recipe data offers Ask for recipe or a clearly labelled related reviewed meal. Actual preparation/cleanup estimates require recipe testing; the prototype's examples are not measured outcomes.

During cooking, ingredient quantities belong on the step that uses them. Timers use persisted deadlines, support pause/reset and survive app backgrounding; delivery of a native alarm is best effort. Backward step navigation is a view action and does not undo food preparation. Completion, feedback, personal save and social share are separate optional commands.

## Social safety and access

Every post shows a clear audience in its composer and author management view. Invited circles start as the distribution model. Current membership and blocks are rechecked for reads and writes. Cached media and pre-issued signed URLs have a bounded residual lifetime; the interface must not promise perfect erasure after story expiry. New members' ability to see still-eligible Today/retained Plate posts is disclosed at invitation acceptance. A former author's membership generation does not reactivate prior grants automatically after rejoining.

Private replies are conversation-scoped; source post access is still checked separately. Group meal threads only admit accepted current participants. Source credit never exposes a private original photo to a larger remix audience. Community reactions carry no public health, diet or body score. Quiet participation, private saves and leaving a group remain supported user choices.

## Accessibility and localization

Meet WCAG AA contrast as a design acceptance target and test actual token/component combinations. Minimum effective targets are 48dp Android and 44pt iOS. Provide semantic reading order, labels for icons, meaningful food-image descriptions, focus restoration after sheets and announce async results without reading every timer tick. Support VoiceOver, TalkBack, 200% font scaling, reduced motion, dark mode and RTL layouts. Long translated labels wrap without clipping. Native date/time/number/currency formatting is locale-aware; duration and serving amounts are separate from units. Region/cuisine vocabulary is user-controlled; no cultural food choices are treated as a score.

## Motion and performance

Use short state transitions (roughly 150–250ms) to communicate a selected action or saved receipt. Respect reduced motion. Pause clips offscreen and allow static posters; photo-first is P1. Avoid loading an entire circle's full-resolution media. Use sanitized thumbnails, lazy loads, bounded cursors and strict cache/account scopes. Pending uploads are resumable but cannot auto-publish when network returns.

## Design verification and implementation handoff

Each feature specification has unique acceptance gates. Cross-feature device journeys cover signup→first meal, friend post→Make Mine→cook→Your Take, consented save after Today expiry, permission loss mid-upload, guest upgrade with picker return, account switching offline, native purchase pending/restore and member departure during shared planning. The local explorer tests navigation structure and documented branches with synthetic data; native screen rendering, server behavior, security and actual performance remain implementation gates.

Deliver design tokens and shared components before building independent feature screens. Implementation PRs update the same screen/action IDs and OpenAPI contracts. A changed button must update the registry, tests and generated maps together, preventing the design document from drifting away from the app.
