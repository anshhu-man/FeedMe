# FeedMe — Shipaton 2026 release plan

Prepared 13 September 2026. Main competition confirmed by the user. Status: proposed competition scope and conditional schedule; scope approval and store-account readiness pending. A local Android demo builds and has passed 26 shared-domain tests plus 14 emulator checkpoints/behavior checks; the iOS host exists but has not been compiled because full Xcode is unavailable. This is a development foundation, not a production release. No production backend, RevenueCat integration, store submission or contest registration is claimed complete. See [native build evidence](../../feedme/docs/BUILD_STATUS.md).

## Outcome and relationship to the full blueprint

**Identity boundary confirmed by the user:** FeedMe stays the Gen Z smart-cooking/social concept defined in [the product strategy](01_Product_Strategy.md). It is not a continuation or merger of TasteEcho's earlier additive-meal idea. Do not import that concept's primary category ranking, audience, feedback loop or product promise as FeedMe requirements. The separate Devpost "Bite Club" entry is not the user's; the working name remains FeedMe. Contest scope below is proposed, not approved merely because the deadline is fixed.

Ship a small, genuinely working FeedMe: **see a plate → make it fit your kitchen → cook → optionally share your version**. Keep Android and iOS sharing Kotlin. Preserve the approved visual direction and the larger blueprint as the post-competition product backlog.

The existing roadmap estimates 106–178 engineering person-weeks for the whole design, not a September delivery commitment. This plan is a new, much narrower release slice, not accelerated completion of P0/P1 or all 98 screens. The calendar below is an aggressive target that must be revalidated after the first native build and account checks; it is not a guarantee.

## Verified event constraints

- Submission closes **30 September 2026, 11:45 p.m. PDT**, equivalent to **1 October, 12:15 p.m. Asia/Kolkata**. Use **29 September IST** as the internal entry-completion target. A pending store review does not qualify for the main competition. [Shipaton FAQ](https://www.shipaton.com/faq)
- Standard entries need a newly released, working store app accessible in the US and a functioning RevenueCat purchase or ads integration. The user selected the main competition; do not substitute a student/demo-only route. [Official rules](https://revenuecat-shipaton-2026.devpost.com/rules)
- Category selection must follow FeedMe's actual shipped experience, not TasteEcho's historical ranking. Assess Design against the existing interaction/visual concept; assess Nutrition & Healthy Eating only if FeedMe genuinely meets its brief without a product or audience pivot. Category selection remains a proposal, not a prediction of selection. [Category criteria](https://revenuecat-shipaton-2026.devpost.com/rules)
- The JetBrains Kotlin award additionally needs both Android and iOS publication. Do not delay an otherwise eligible entry to chase additional awards. [Submission guide](https://www.revenuecat.com/blog/engineering/how-to-submit-your-app-for-shipaton)
- Apple uploads have required **Xcode 26 or later with the iOS 26 SDK** since 28 April 2026. Installing full Xcode and proving a compliant iOS build are release dependencies; this upload requirement does not by itself require an iOS 26 minimum deployment target. [Apple SDK requirements](https://developer.apple.com/news/upcoming-requirements/)

Recheck the official rules before submitting; they take precedence over this planning snapshot. The rules and promotional pages differ slightly on the historical opening date; a new September release avoids that boundary issue.

## Scope for approval

| Area | Actual launch behavior | Existing design anchors |
|---|---|---|
| Access and preferences | Guest cooking; one account-authentication path for sharing and account-bound purchases. Explicit ingredients, effort, servings and supported exclusions. Recovery, logout and deletion remain real. | F03–F05, F39, F47–F49 |
| Small reviewed catalog | A limited, licensed and reviewed launch set. Named review owner approves ingredient data, instructions and supported substitutions. No fabricated review badges or medical-benefit claims. | F13, F51 |
| Make Mine | Choose a catalog recipe or a permitted recipe-linked plate. Deterministically select a reviewed variant matching supported inputs; explain differences. No match means a clear alternative, not an invented safe adaptation. | F01, narrowed F02/F08/F11/F17 |
| Cook and save | Readable steps, reliable local progress/resume, basic timer if device-tested, private saved recipes and Make Again. No learning-memory subsystem. | Narrowed F12/F14/F19/F53 |
| Core social loop, proposed small implementation | Photo only; an invited circle; attach a reviewed catalog recipe/version; choose Today expiry or Keep on Plate. Another authorized member opens the attachment and uses Make Mine. No arbitrary user-authored recipe copying. | Narrowed F21–F25/F31/F40/F43 |
| One paid offering | Proposed one-time reviewed Situation Pack via RevenueCat. Preview its contents; localized store price; real purchase, restore and entitlement handling. Basic cooking, privacy and safety controls remain free. Owner approves content rights, price and configuration. | Narrowed F46/F50 |
| Release controls | Authorization, report/block/delete, effective moderation, support contact, privacy/deletion resources, error recovery and crash reporting. Staff may use minimal protected tools rather than the full designed console. | Necessary slices of F42/F49/F51–F54 |

The proposed paid pack moves a narrow portion of F46/F50 forward from the full roadmap, where paid activation was deferred. Installing an SDK without a working monetization path is not sufficient. Do not offer a subscription without a defined ongoing service. A test-store demonstration is engineering evidence, not proof of a live store purchase configuration.

Social release requires all its safety gates; social remains part of FeedMe's identity. Photo storage must remain private with sanitization, membership checks and tested expiry/deletion. A public store download does not mean a public social feed: guests must get useful cooking immediately and can create/join private circles. Judges must have a documented way to exercise the real two-user flow. An individual's choice not to post is distinct from removing social from the product.

### Proposed competition deferrals — approval pending

Video, DMs, replies, notifications beyond essential account communication, open discovery, recursive remix graphs, social recipe-request/copy grants, household seats, Dinner Pact/Vote/Bring a Bit, learned taste memory, pantry scanning, grocery integrations, subscriptions, additional paid products and polished multi-role admin screens. Deferred controls must not appear as working buttons in the store binary or demo.

There is **no automatic cooking-only fallback**. If the social loop cannot pass its gates, report a release blocker and ask the owner to decide whether to change scope or timing. Do not silently substitute a catalog feed for real social, relabel a private recipe utility as the completed FeedMe concept, or revive TasteEcho to meet the contest date. All larger features remain in the full blueprint even if an explicitly approved first release defers them.

## Conditional calendar — all working dates are IST

| Dates | Work and parallel owner tasks | Exit gate |
|---|---|---|
| **Sep 13–14** | Freeze scope; confirm entrant category, accounts, reviewers, budget and available engineering capacity. Establish shared Kotlin workspace, build both targets, verify signing, create approved backend/auth configuration, spike RevenueCat. Start content review and submission copy. | Android and iOS run on devices/simulators; a sandbox purchase is observed; at least one credible public-store route exists; content owner and capacity confirmed. |
| **Sep 15–17** | Implement catalog → preferences → deterministic Make Mine → guided cooking → save. Complete the real pack purchase/restore path with provider-backed entitlement checks. Reviewer validates launch recipes in parallel. | End-to-end cooking and purchase evidence; no placeholder services on the release path; both native targets still build. |
| **Sep 18–20** | Integrate the smallest two-user photo loop with the stable cooking path. Test private media, expiry, membership, reports, blocks, deletion and recovery. Finish store assets, policy/support pages and device accessibility checks. | Feature freeze. Defer only approved optional scope. Candidate passes cooking, social, safety, content and billing gates; a human can operate moderation. |
| **Sep 21** | Submit the complete launch candidate to the primary ready store; submit the second when its own gates pass. Include reviewer access and monetization instructions. | Actual review submission recorded, not merely a locally built artifact. |
| **Sep 22–26** | Address review feedback and bugs, test fresh installs/restores, complete second-store review when possible. Gather real beta feedback; release publicly as soon as approved. | At least one live US-accessible listing is verified by fresh download; second listing is a separate dependency. |
| **Sep 27–28** | Record the actual released app, finish category explanations and English testing instructions; verify premium reviewer access and prepare the Devpost entry. | Video, screenshots, description and installed app agree. No roadmap features represented as shipped. |
| **Sep 29** | Owner authorizes and completes final Devpost submission. Verify entry status and public links. | Entry shows submitted, with functioning store/video links and correct team/category details. |
| **Sep 30** | Contingency for submission defects or review issues; no planned feature development. | Recheck eligibility and entry before the official deadline. |

Sep 21 deliberately aims ahead of the organizer's suggested one-week store-review buffer. Approval timing remains external. [Organizer review guidance](https://www.shipaton.com/faq)

### Account-dependent branches

- **Both production routes ready:** keep both releases in the target schedule; account readiness alone does not prove feature or review readiness.
- **Only one ready:** propose that as the primary store while retaining the shared codebase and testing both targets. Any decision to defer the other public release needs user approval and forfeits the dual-store award until its conditions are met.
- **New personal Google Play account/app still subject to testing:** accounts created after 13 November 2023 require a closed test with at least 12 continuously opted-in testers for 14 days before applying for production access. Google says that access review usually takes seven days or less, sometimes longer; additional testing can be required. If all 12 begin Sep 13, the earliest test-duration gate is around Sep 27 at the corresponding time. A seven-day access review could then extend to Oct 4, before any further publication delay. This timing is an inference, not a promised review duration. Confirm this app's actual Console eligibility and tester engagement immediately; this is a serious schedule risk, not a guaranteed route. [Google Play requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
- **Neither store route ready:** do not promise competition eligibility. Immediately resolve account enrollment and review lead times. If a compliant public release is not achievable in time, report that constraint; a demo-only entry is not a fallback for this main-competition plan.

## Implementation approach

Reuse the existing screen designs and domain contracts selectively; do not implement every documented endpoint. Record the narrowed routes and acceptance tests in a competition feature manifest before coding them. This scope table is not that executable manifest and does not mutate the full registry.

- Shared Kotlin/Compose UI, domain models, matching and persistence; native adapters for identity callbacks, photo selection, purchase integration and verified device behavior.
- Keep the proposed Ktor modular-monolith direction with a small database/storage footprint; avoid adding infrastructure platforms or microservices for contest categories. Exact provider configuration and spending remain owner-approved.
- Use the supported RevenueCat Kotlin Multiplatform integration and prove compatibility in the initial spike rather than inventing dependency pins. [RevenueCat KMP documentation](https://www.revenuecat.com/docs/getting-started/installation/kotlin-multiplatform)
- No language-model dependency for the critical cooking path. Private recipes and preferences must not leak into social payloads. Purchase protection is enforced on the backend for protected content, with authenticated reconciliation and idempotent webhook processing.
- Separate staging and production. Read-only offline access is limited to actually cached authorized content; protected mutations require connectivity. Hide optional features whose server capability is unavailable.

## Gates that the deadline cannot waive

1. Fresh install and core cooking work on every platform claimed in the entry.
2. Reviewed recipes and supported substitutions exist; exclusions are never silently relaxed. State ingredient/cross-contact limitations clearly.
3. Purchase, pending/cancel, restore, entitlement loss and account ownership are tested. Premium content is genuinely delivered; judge access works.
4. No cross-account reads, private-media leakage or destructive account-merge behavior. Account deletion and required policy resources are functional.
5. For the planned social release: sanitizer, report/block/delete, expiry and a responsible moderation operator are working. Without these, do not release social; escalate the scope/timing decision rather than silently removing the core loop.
6. Essential labels, contrast, touch targets and screen-reader navigation pass on representative devices. Cooking does not rely on an untested background-timer promise.
7. Crash visibility, backups/recovery, support and rollback/feature-disable procedures exist in proportion to the shipped surface.

Reassess at Sep 14, Sep 17 and Sep 20. Remove optional features before compromising these gates. If core, monetization or the store route is still unproven, report the schedule as at risk rather than relabeling a prototype as production.

## Submission package

Prepare an English description and test instructions, a public YouTube/Vimeo device demo under two minutes, a 1024×1024 icon, and at least one unframed 1179×2556 screenshot. Include the live store URL, premium trial/promo access, and required category answers. Verify Devpost actually shows submitted. [Submission requirements](https://www.revenuecat.com/blog/engineering/how-to-submit-your-app-for-shipaton)

Suggested 100-second story: 0–12s a tired person's dinner problem; 12–42s Make Mine adapts a reviewed recipe; 42–65s cook/save; 65–85s the real friend-circle version; 85–100s the paid pack and product payoff. Show recorded functionality, not the HTML prototype. Rework this story only after an explicit approved scope change; do not conceal a missing social loop with prototype footage or a different product promise.

Use original FeedMe copy and assets. Do not put Fight Club footage, film branding, copyrighted music or an influencer's likeness into the product or entry without permission. Record genuine feedback and post-launch usage if available; never manufacture traction.

## Owner inputs needed immediately

1. Apple and Google developer-account status, including this app's production/testing eligibility.
2. Devpost registration status, team members and representative; main competition is already confirmed.
3. Available engineering time/team through September, build budget and monthly operations cap.
4. Approval of the reduced scope and proposed one-time paid pack; reviewer/content source.
5. Permissioned repository access or confirmation of a fresh FeedMe implementation; any actual Smart Kitchen code to assess.
6. Launch language/markets including US availability, legal operator, support/moderation owner and final UI approval.

Account credentials stay out of chat. Use limited collaborator roles and a secrets manager. This document does not authorize spending, accepting contest terms, public posting, deployments or store submission; those need the owner's applicable approval and access.
