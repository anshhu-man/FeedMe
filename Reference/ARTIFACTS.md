# Built artifacts

## Current retained Android preview — 15 September 2026

[Download FeedMe Android preview APK](artifacts/FeedMe-android-preview-2026-09-15.apk)

This development build has retained meal requests, cooking, foreground timers, cookbook storage, private text drafts and explicit reviewed self-only publication. It uses synthetic identity, recipes and a local service. It does not sign into real accounts, upload photos or share with other people. Use a test device; use made-up draft text and do not treat synthetic recipes as cooking or food-safety guidance.

- Package: `com.feedme.development.progress`
- Size: 28,317,464 bytes
- SHA-256: `21755e11413984e877211776f40eaf81c46f048dd095f2c0c21a7322b70eb86b`
- Source-workspace build receipt: `567440805bac6c49b86c0519c6c5c537dfe0f045a4a0ee98ed4c8eba5b1f70e4`
- Source-workspace native receipt: `84b65e17cad9b111ccbadbe0874517e80db37ef2e5dfd8e8e5f3cf623f68a78d`
- Verification: 360 JVM methods, 63 emulator methods, two clean lint reports and 76 individually reviewed captures. [Exact scope and local-only evidence](SNAPSHOT_STATUS.md).

Choose **Start preview** to enter the meal flow, then **My private drafts** to try text drafts. Private Save and Publish have separate reviews and confirmations. Publication is only to yourself in this local preview. Timer alerts are foreground-only; leaving the screen does not pause or cancel timers. Exiting and resetting are different actions; reset explicitly retires preview data.

This is not a signed store release, live backend or completed 44-feature V1. The historical demo below is not overwritten.

## Historical Android demo

[Download FeedMe Android demo debug APK](artifacts/FeedMe-android-demo-debug.apk)

This is the earlier local-only development build: sample food, simulated sharing, no real sign-in or purchases, and memory-only demo state. It is not a store release or a fresh build of all sources in this repository. Install only if you intentionally want to inspect this development demo; use a test device/emulator.

- Package: `com.feedme.development`
- Size: 18,981,844 bytes
- SHA-256: `bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805`
- Historical smoke evidence: [report](../feedme/docs/verification/android-smoke/report.json), [screenshots](../feedme/docs/verification/android-smoke/)

No signing private key is included. Other Gradle-generated libraries, intermediate/test APKs, SDK caches and temporary database clusters are excluded. Source and test code are included; repeated local verification attempts are deliberately excluded under the [evidence policy](LOCAL_EVIDENCE.md). Historical source-receipt hashes are not a native acceptance claim for a fresh clone.

## UI and blueprint

The complete current [UI collection](../outputs/biteclub_ui/) and [blueprint](../outputs/biteclub_blueprint/) are checked in with all assets. Earlier BiteClub-name downloadable exports are preserved as [sanitized historical archives](History/README.md); use the current folders for active FeedMe work.
