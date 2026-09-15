# Shipaton 2026 — official-rules recheck

Checked public primary sources on **14 September 2026**. A time check during the audit was **10:06 UTC / 15:36 IST**. This is a bounded release audit, not entrant certification, store approval or submission. The English Devpost rules are the governing reference; organizer guidance helps explain the submission process but does not waive those rules.

The existing decision remains **main competition, FeedMe, Android + iOS with shared Kotlin**. Preserve all 54 product features and the approved 44-feature V1 / ten existing later-phase deferrals. Neither the deadline nor the student exception authorizes a smaller replacement product, revived paid pack, new provider, purchase, publication or submission. See [user decisions](USER_ACTIONS.md), [V1 scope](V1_RELEASE_SCOPE.md) and [current release plan](RELEASE_PLAN.md). The older [blueprint competition proposal](../../outputs/biteclub_blueprint/13_Shipaton_2026_Release_Plan.md) is explicitly superseded; its estimates and extra cuts are not official requirements or approved delivery commitments.

## Deadline and source discrepancies

The submission/registration deadline remains **30 September 2026, 23:45 PDT**. Converted using PDT = UTC−07:00: **1 October 2026, 06:45 UTC = 12:15 IST (Asia/Kolkata)**. These are the same instant, not an additional local-day extension. No extension was found. The schedule also gives 21 October for winners. [Official Devpost schedule](https://revenuecat-shipaton-2026.devpost.com/details/dates).

There are discrepancies worth preserving rather than silently reconciling:

- The rules/schedule start submissions on **31 July at 08:00 PDT**; the overview, FAQ and submission guide describe **1 August**. A genuinely new September launch avoids that boundary; an earlier-release eligibility question needs organizer clarification. [Schedule](https://revenuecat-shipaton-2026.devpost.com/details/dates), [overview](https://revenuecat-shipaton-2026.devpost.com/), [FAQ](https://www.shipaton.com/faq).
- The FAQ still says winners on **22 October**. Its looser wording about submitting to a store must not be read as permitting pending review: that same FAQ explicitly rejects it and recommends submitting for review at least one week early. That buffer is advice, not guaranteed approval. [Organizer FAQ](https://www.shipaton.com/faq).
- The overview's purchase-only summary omits the Ads alternative found in the rules and newer detailed submission guidance. Do not use the abbreviated summary to force a paid offering. [Overview](https://revenuecat-shipaton-2026.devpost.com/), [submission guide](https://www.revenuecat.com/blog/engineering/how-to-submit-your-app-for-shipaton).

## Main-competition gates

**Entrant:** Adults, adult teams and established organizations may enter; teams need an authorized representative. Check residence/sanctions, sponsor/judge conflicts, ownership and licenses under §§3–4. Minors aged 13+ are Next Gen-only with guardian consent. This audit does not verify the user's circumstances. [Official rules, updated 31 August 2026](https://revenuecat-shipaton-2026.devpost.com/rules).

**Monetization:** No entry purchase/payment is required. The app must use the RevenueCat SDK for at least one in-app/web purchase **or serve RevenueCat Ads**; merely installing an SDK is insufficient. A paid subscription or deferred recipe pack is not mandatory. [Official rules §4](https://revenuecat-shipaton-2026.devpost.com/rules).

**Release:** A working iOS, iPadOS, macOS or Android app must be fully published on an eligible Apple, Google Play or Samsung Galaxy store and downloadable in the US. First public store release must be within the event window; moving a previously released app to another store is not a new launch. TestFlight, testing tracks and pending review do not qualify. [Organizer submission guide, published 27 August 2026](https://www.revenuecat.com/blog/engineering/how-to-submit-your-app-for-shipaton).

**Judge access:** Keep unrestricted, free testing access through **13 October, 12:00 PDT**; provide the required trial/promo access to premium features. [Official rules §§1,4](https://revenuecat-shipaton-2026.devpost.com/rules).

**Repository:** Main categories do not require source publication. Next Gen requires a functional public, open-source repository with license; it is not our fallback. [Official rules §4](https://revenuecat-shipaton-2026.devpost.com/rules).

**Optional Kotlin category:** Both live Apple App Store and Google Play URLs, KMP/Compose explanation and public build-insight links are required. Any claimed ecosystem contribution needs linked evidence. [Official rules §4](https://revenuecat-shipaton-2026.devpost.com/rules). This category check does not change the user's dual-platform product commitment.

The [FAQ](https://www.shipaton.com/faq) confirms no team-size cap. Its student exception requires verifiable academic enrollment/email and, for minors, guardian consent. We have not selected that route or verified such eligibility. Nor does this audit select any influencer or sponsor category.

## Submission checklist — prepare, do not publish automatically

The main entry needs app-description text, a device-running demo on YouTube/Vimeo with essential footage under two minutes, a live store URL, a **1024×1024** icon, at least one **1179×2556** screenshot without a device frame, and trial/promo access. Use only permitted music, trademarks and other protected material. [Devpost submission requirements](https://revenuecat-shipaton-2026.devpost.com/).

The organizer additionally specifies the **RevenueCat project ID**, project name/tagline and category-specific answers. English materials or translations are needed. Unlisted YouTube is acceptable; private video is not. Complete all five Devpost sections and verify **Submitted / 5/5**, then inspect the public entry. A draft is not an entry; portfolio changes after the deadline do not update the entered version. [Submission guide](https://www.revenuecat.com/blog/engineering/how-to-submit-your-app-for-shipaton).

Local acceptance must compare those materials with the actual released binary, not a synthetic fixture, emulator-only success or promised feature. The current preview and native test captures remain development evidence, not automatic contest assets. The RevenueCat project ID is a concrete checklist detail to carry into M9.05 when account configuration is approved; no project ID was obtained or invented here.

**Form-handling uncertainty:** the inspected public guidance does not explain the premium-access field for an Ads-only app with no paid features. We did not inspect the authenticated form. Do not fabricate a promo code or invent an IAP to satisfy it; if such a design is approved and the field cannot truthfully be completed, seek written organizer clarification before submission.

## Immediate account-dependent risk

For personal Google Play accounts created after **13 November 2023**, the app requires at least **12 testers continuously opted in for 14 days**, followed by an application for production access. Google says that review usually takes seven days or less but sometimes longer; insufficient testing can require more testing. [Current Google Play policy](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).

**Conditional arithmetic, not verified Console status:** if the entire qualifying cohort starts on 14 September, the duration gate is around 28 September at the corresponding time. A subsequent seven-day review reaches approximately 5 October, after the event deadline, before allowing for publication issues. Starting on 13 September instead gives approximately 27 September / 4 October. No guaranteed last-safe start date follows from variable review duration. The existing U02 account/access/test-start question therefore remains time-critical. This audit did not inspect an authenticated Play Console or App Store Connect account, enroll testers, check current Xcode, or choose an alternative store.

## FeedMe action mapping and honest status

| Existing gate | Evidence still needed | Status / authorized next step |
| --- | --- | --- |
| U02 / U10 — store route | Actual production access, or app-specific test dates/counts; Apple account/toolchain and real-device readiness | Awaiting account facts / unrequested test arrangements; do not infer readiness from a local APK |
| U07 — V1 monetization | User-approved approach, authorized account configuration and working released integration | Already awaiting response; neither Ads nor a paid benefit is selected by this audit |
| U04–U06 / U08 — identity, service, content, operations | Actual configured providers, licensed/reviewed launch content and accountable release operations | Existing blockers unchanged; synthetic identity/content/service remain explicitly synthetic |
| U09 / M9.05 — entry preparation | Representative/team, RevenueCat project ID, truthful media, category evidence and judge-access instructions | Prepare locally; public GitHub authorization does not authorize Devpost submission |
| U11 / M9.02–M9.06 — external actions | Artifact-specific store upload/release approval, actual live listing and final submission approval | Not granted by the ongoing ship goal; no upload, purchase, terms acceptance or submission performed |

Statuses above come from [USER_ACTIONS](USER_ACTIONS.md) and [BUILD_STATUS](BUILD_STATUS.md), not the competition website. All 44 V1 features remain assigned; ten existing deferrals remain deferred. Component test totals, the historical public repository and the visible previews do not certify feature completion or Shipaton eligibility.

**Notice assessment:** no newly extended deadline or changed requirement was found relative to today's release plan. U02 and U07 remain concrete critical-path risks, but their unchanged questions should not be repeatedly reissued. Escalate a newly confirmed account delay or changed rule promptly, without promising a September release. For a material rules ambiguity, prepare a written question for the organizer's listed `shipaton@revenuecat.com`; no contact was made. [Organizer contact](https://www.shipaton.com/faq).

This audit changed only this document. It did not alter sources, tests, canonical scope, verification receipts, user decisions, stores, repositories or the reserved user previews **5554 / 5558**.
