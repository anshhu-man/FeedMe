# FeedMe — when I need you

Updated: 15 September 2026 IST. This is the shared user-action register. Ask for status/decisions, never passwords, signing keys, API secrets, payment-card data or banking/tax records in chat. Use limited collaborator roles and approved secret storage when integrations begin.

## Asked now

The first three questions were raised in this task on 13 September 2026 around 09:14 IST. U01 is resolved for the existing phase boundary. U02 was answered on 15 September: only Google Play production access is ready; Apple Developer access and full Xcode are not ready. U03 remains **awaiting response**, not a failed approval. Provider research was requested for approval, not provider provisioning.

| ID | Action needed | Why / timing impact | Status | Last raised |
| --- | --- | --- | --- | --- |
| U01 | Exclude all existing later-phase features from the first version | User explicitly deferred P2/P3. V1 keeps the existing 44 P1 features; F32–F38/F44–F46 remain later backlog. The previously suggested paid pack is not a V1 exception. This is not approval for extra feature cuts or a September completion promise. [Scope](V1_RELEASE_SCOPE.md). | RESOLVED — existing phase deferrals | 2026-09-14, direct user instruction |
| U02 | Google Play production access is ready; Apple Developer/App Store Connect and full Xcode are not ready | User-reported account status, not a verified console inspection, release approval or completed signing setup. Android can be the first release candidate; iOS remains blocked on setup. | ANSWERED — Google only; Apple/Xcode setup outstanding | 2026-09-15, deadline question answered |
| U03 | State available team/hours through September, setup budget and monthly operations cap | Determines credible parallel capacity/provider plan; does not authorize spending. | AWAITING_RESPONSE | 2026-09-13, initial async question |

Do not repeat these unchanged questions every 30 minutes. Raise again only if the user asks, the question changes, or a new dated release risk materially changes the decision.

**15 September IST, tomorrow deadline:** the user requested completion by 16 September and answered both deadline questions: **Google Play production access only is available**, and **propose a backend/auth setup for approval**. Research and prepare a concrete proposal; do not provision accounts, choose paid plans, deploy or assume approval of a provider/sign-in method. The [tomorrow plan](TOMORROW_SPRINT.md) keeps safe work moving with all four available workers. Do not repeat the answered readiness/preference questions unchanged.

**15 September, approximately02:15 IST:** the [backend proposal](BACKEND_PROPOSAL.md) recommends Supabase Auth/PostgreSQL/private Storage with the existing Ktor backend on Render, and Google plus verified email/password. One question asks for **technical-direction approval only**. It is awaiting response; it explicitly excludes paid accounts, spending and deployment. Apple sign-in remains planned pending setup. Region, SMTP, permanent identity and operating budget remain separate inputs.

Historical question, 13 September at approximately **15:31 IST**: **U04/U05 — desired sign-in methods and whether an auth/backend provider is already preferred**. The user subsequently requested a recommended setup for approval; the proposal and its separate pending technical-direction decision are recorded above. The original question is no longer unanswered. No provider, deployment, budget or permanent identity is approved. Native credential work can proceed meanwhile; legal owner/namespace, region, access and deployment authority remain separate inputs.

On14 September at approximately **02:47 IST**, **U06 — launch recipe/photo rights and a qualified cooking-instruction reviewer** was raised through an in-task question as the deterministic planner reached focused acceptance. The user may say “not arranged”; that is a request to discuss options, not permission to scrape, license, spend or self-certify recipes. Production catalog integration depends on this input. Controller, editorial workflow and fixture-based testing can continue meanwhile. Do not repeat the question unchanged.

On14 September at approximately **08:58 IST**, **U07 — V1 monetization** was raised through an in-task question after rechecking current official Shipaton rules: optional RevenueCat ads while core cooking remains free, or a paid V1 benefit the user will specify. This is AWAITING_RESPONSE. Deferred paid packs/expanded library are not restored; no ad placement, price, paid value, provider account, spend, agreement or deployment is approved by asking. Cooking, cookbook and platform work can continue meanwhile. Do not repeat the question unchanged.

On 14 September at approximately **14:34 IST**, **U13 — temporary test-data retention** was raised through an in-task question. A read-only inventory found 407 owned synthetic PostgreSQL fixture directories using 106.565 GiB; all matched the test configuration and had no `postmaster.pid` or initialization-password file. The question offers local archival followed by removing verified uncompressed copies, or keeping them for now. This is AWAITING_RESPONSE, not cleanup authorization. No database directories have been deleted or moved. Source, reports, screenshots and the user's personal PostgreSQL service are excluded. Revalidate exact targets and live processes before any approved cleanup; further coding can continue with approximately 99 GiB free. Do not repeat this unchanged question.

**14 September,14:13 UTC observation:** approximately53GiB remains free after the accepted eleventh full run and focused follow-ups. Existing source/receipt/screenshot/diagnostic data remains retained; no archive or deletion was performed. U13 still awaits the same retention choice. Bounded source implementation can continue; this is not a new question or cleanup authorization.

## Upcoming decisions — request just in time

| ID | User-owned input/action | Needed before | Work that can proceed without it | Status |
| --- | --- | --- | --- | --- |
| U04 | Legal owner, domain/namespace for permanent app IDs, target audience/markets, auth method | Provider callback configuration, signing and public identity | Temporary dev IDs, interfaces and tests; no domain registration or public name-clearance claim | AWAITING_TECHNICAL_APPROVAL — recommendation delivered; other identity inputs outstanding |
| U05 | Backend/auth/storage provider, region, collaborator access and explicit deployment authority within approved spend cap | Real integration and staged deployment | Local Ktor/database contracts, migration tests and provider-independent auth/storage boundaries | AWAITING_TECHNICAL_APPROVAL — proposal delivered 2026-09-15; no provider/deployment/spend approved |
| U06 | Recipe/photo rights, launch catalog and named qualified content reviewer | Marking content reviewed or distributing cooking guidance as approved | Review workflow, schema, deterministic fixture tests; fixtures stay labeled | AWAITING_RESPONSE — raised2026-09-14 approximately02:47 IST |
| U07 | Approved V1 monetization approach, RevenueCat/store roles, actual paid value/content/price if purchases are chosen, account-holder agreements | Product setup, sandbox integration needing account access and real sales/ads; F44/F45/F46 paid offerings are now deferred, including the earlier proposed pack | Billing adapter and reconciliation fixtures; no deferred feature reinstatement, agreement acceptance or purchases | PARTIALLY_REQUESTED — approach awaiting response; raised2026-09-14 approximately08:58 IST |
| U08 | Legal operator/support details, policy decisions, audience age and accountable moderation/support owner | Public social and store review | Policy drafts with placeholders, report/block/export/deletion implementation | NOT_YET_REQUESTED |
| U09 | GitHub destination confirmed: `anshhu-man/FeedMe`, PUBLIC; publish current source, idea, docs, UI and reference artifacts. CI activation and Devpost representative/team remain separate. | Hosted repository snapshot and entry preparation | Local source/tests and draft release materials | PARTIALLY_RESOLVED — public repository creation/push authorized; no CI spend or Devpost submission authorized |
| U10 | Real Android/iOS test access and real pilot/closed-test participants | Physical-device gates and Google test evidence | Emulator/simulator automation and test instructions | NOT_YET_REQUESTED |
| U11 | Explicit artifact-specific store upload/release and final Devpost submission approvals | Each external upload/publication/submission | Produce reviewable artifact, checksums, listing draft and walkthrough | NOT_YET_REQUESTED |
| U12 | Check desktop notification permission and keep computer/app available if unattended follow-ups are wanted | Receiving desktop alerts and running local scheduled work | In-task questions and persisted action register remain available | INFORMATION_PROVIDED |
| U13 | Choose whether to archive stopped FeedMe-only test databases locally and reclaim their uncompressed space, or retain them | Material diagnostic-data cleanup and sustained repeated full builds | Continue source implementation and bounded checks; preserve all existing diagnostics meanwhile | AWAITING_RESPONSE — raised 2026-09-14 approximately14:34 IST |

Google Play and Apple account checks are status requests, not authorization to create accounts, accept terms, buy memberships or change signing. Recruiting/contacting testers requires the user's applicable direction. If a tool permission is denied, record the exact failed action and continue unaffected safe work; do not work around the restriction.

## Scheduled follow-up / alarm setup

- Created and verified in the app: **FeedMe — milestone progress and needs-you alerts**.
- Automation ID: `feedme-milestone-progress-and-needs-you-alerts`.
- Status at setup: **ACTIVE**. Same-task follow-up every **30 minutes**; no separate task per run.
- It reads the release plan, feature matrix, build status, this register and latest replies; continues a small safe unblocked task, and records evidence.
- Alerts are for a newly needed action, changed blocker, completed milestone, material test failure or changed deadline risk. No repeated unchanged reminders. On completion or user stop, pause the follow-up; do not delete the work.
- Every new action gets an ID, exact request, reason, timing impact and safe work-around; record its last-raised time after presenting it. Update this register when answered, including the answer's scope, rather than inferring permission from silence.

This is an app follow-up, **not a guaranteed audible, phone, SMS or email alarm**. Desktop question/permission alerts and OS notification permission are user-configurable; delivery has not been tested. [Official notification documentation](https://learn.chatgpt.com/docs/notifications).

Local scheduled work requires the computer on, the desktop app running and the project accessible. Runs may also depend on connectivity, available usage and current permissions; a 30-minute cadence is not a guaranteed response SLA. [Official scheduled-task documentation](https://learn.chatgpt.com/docs/automations?surface=app).

## Alert format

“FeedMe needs you — Uxx: [one specific decision/action]. Needed for [milestone/task]. Please [exact next step, without secrets]. If delayed: [honest impact]. Meanwhile I can continue [safe unblocked task].”

## Decision history

- 15 September 2026: user reported Google Play production access is the only ready store/tooling item and requested a proposed backend/auth setup for approval. This resolves the status/preference questions, not iOS setup, exact app signing, provider selection, budget, account creation, deployment or store publication authority.

- 14 September 2026: user requested simultaneous feature work through a complete AI team. Four workers are assigned in AI_TEAM_PLAN.md;44 V1 features retain delivery ownership and the10 later features stay deferred. This authorizes parallel in-scope implementation, not new providers/spend/publication, and does not answer U02/U03 or pending identity/provider questions.

- 14 September 2026, approximately14:34 IST: U13 asks for a retention choice after a read-only inventory of 407 synthetic test clusters (106.565 GiB). No deletion, archival or personal PostgreSQL modification follows from asking; the ninth verification artifacts remain retained.

- 14 September 2026: user said not to include later features in the first version. Applied the existing P2/P3 boundary: F32–F38 and F44–F46 excluded from V1; 44 P1 features retained, full 54-feature/98-screen blueprint preserved. U01 resolved for this boundary; no narrower replacement concept, provider, pricing, purchases/ads, store release or additional publication approved. U07 must resolve V1 monetization without silently restoring paid packs, expanded library or households.

- Confirmed from prior user messages: FeedMe name; original concept must stay intact and not merge TasteEcho; separate Devpost Bite Club is not the user's; Android + iOS with shared Kotlin; main Shipaton competition.
- 13 September 2026: user requested a persistent goal, milestone/task breakdown, needs-user alarm and ship-ready completion. Goal and follow-up created. No new provider, price, permanent identifier, release-scope deferral or publication approval inferred.
- 13 September 2026, ~15:31 IST: U04/U05 sign-in methods/provider preference question raised while building provider-independent native credentials. All other unrequested parts of those action IDs remain unrequested; no account setup or spending authorized.
- 13 September 2026: user supplied `https://github.com/anshhu-man`, requested repository creation and publication of all FeedMe work with a Reference section, then explicitly chose **Public**. Signed-in GitHub identity was verified as `anshhu-man`; `FeedMe` was available. This authorizes the source/design/documentation snapshot, not app-store release, deployment, paid CI or Devpost submission. Public copies redact personal machine paths; originals and historical evidence remain locally preserved. Unfinished native sync-test work must stay labelled unfinished.
