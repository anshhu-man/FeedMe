# User-visible Android progress preview — 14 September 2026

## Subsequent read-only observation — 12:57–12:58 UTC

ADB now lists only the owned headless test emulator5556. The user preview5558 is absent; root did not stop, reset or replace it. The launch below remains historical evidence, not a continuing liveness claim. The downloadable accepted tenth APK remains byte-identical (`b31628ce64629d3ec887df61251512b43af8b3009c09ce3f26fa7fda7096dd50`). Resolve actual device/process/boot identity before any later launch or action; do not repurpose a reserved preview serial for tests.

## Latest launch — accepted tenth APK, about 17:52 IST

At the user's renewed request, a new visible read-only/no-snapshot `Metzy_Pixel_API_35` instance was started on **emulator-5558**, process **97217**, launch session **66158**, boot UUID `3cf3ccf4-16d4-4803-96b8-4c1ecda5fee7`. **Reserve this device for the user: no instrumentation, replacement install, reset, uninstall or cleanup.** Re-resolve process and boot identity before any later action. Existing test5556 was left untouched; no5554 was attached at this launch.

The empty preview instance received the [accepted full-tenth progress APK](../recalled-copy-drafts/attempts/2026-09-14T10-07-31.918Z/artifacts/20-android-progress.apk), 27,481,821 bytes, SHA-256 `b31628ce64629d3ec887df61251512b43af8b3009c09ce3f26fa7fda7096dd50`, matched to its immutable verification receipt before install. This is a different artifact from the earlier focused06 preview below. Install returned Success; launching `com.feedme.development.progress/.ProgressActivity` returned Status: ok. The native emulator window was brought forward.

Root entered only Start synthetic preview and visually checked the ready “What's the dinner vibe?” meal-request screen, with Open my cookbook and meal/effort choices. Activity readback confirmed the app was top-resumed; app PID3942 was observed. No meal was submitted, cooking started, recipe saved, account reset or external service contacted for this handoff. Temporary raw captures: `/private/tmp/feedme-preview-start-20260914-1220.png` and `/private/tmp/feedme-preview-ready-20260914-1220.png`.

The preview provides actual retained meal/cooking, cookbook and foreground-timer flows with encrypted native storage, but synthetic account/catalog/service. Live login, social publishing, media upload and Make Again are not wired. New eleventh-package private-draft UI is not opted into this host, and current unverified source edits are not represented by this accepted APK. Closing this disposable emulator does not persist its new changes to the base AVD.

## Earlier launch — historical focused06 preview

Opened at the user's request to see current progress. This is an interactive development preview, not another integrated acceptance run.

- **Reserved user preview: `emulator-5558`, process 45423, launch session 43103. Do not use it for instrumentation, reset, uninstall, cleanup or test-verifier work.** Leave it open for the user. Re-resolve process/serial before any future action; identifiers can be reused.
- Original user preview `emulator-5554` / process 91932 was inspected read-only and left unchanged. It still contains the seventh checkpoint.
- New preview uses a separate `Metzy_Pixel_API_35` read-only, no-snapshot instance, Android API35 ARM64. Closing this disposable instance does not persist its new emulator changes to the base AVD.
- Installed only the progress application into the new instance after confirming no `com.feedme` packages existed. No test APK, in-place upgrade, data clear, uninstall or reset was used.
- Artifact: [focused06 progress APK](../recalled-copy-drafts/focused/06-native/artifacts/android-progress.apk), SHA-256 `4673683d3a85ec81428df7080c2a102a2304019ce0c4e73c6ca98e3937487294`.
- Identity: `com.feedme.development.progress/.ProgressActivity`, label `FeedMe · Retained Progress`, version `0.1.0-dev-progress`. APK metadata and digest verified before launch.
- Install returned `Success`; activity launch returned `Status: ok`, cold start in 816 ms. Native window was brought forward. [Initial screen](start.png) was captured and visually checked; it offered Start synthetic preview without errors.
- Entered Start synthetic preview and visually checked the actual [meal-request screen](meal-request.png): FeedMe, “What’s the dinner vibe?”, Open my cookbook and meal/effort controls are visible. Application process 3781 remained running. No meal was submitted, cooking started, recipe saved or account reset for this handoff.

The APK includes actual encrypted native retention and meal/cooking controllers, basic cookbook Save/browse/confirmed removal and foreground-only timers. Account, catalog and service are synthetic. Login, live backend, post publication/social sharing and Make Again are not connected. Focused06 passed six actual progress-host and five cookbook-host tests; timer/process coverage belongs to preceding accepted checkpoints and was not rerun for this launch. Full tenth-package acceptance remains pending.

Do not downgrade an updated retained ledger to the seventh build: additive cookbook/withdrawal fields are accepted forward but not by the old decoder. A separate preview avoids that migration and rollback risk.
