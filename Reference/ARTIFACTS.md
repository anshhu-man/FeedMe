# Built artifacts

## Historical Android demo

[Download FeedMe Android demo debug APK](artifacts/FeedMe-android-demo-debug.apk)

This is the earlier local-only development build: sample food, simulated sharing, no real sign-in or purchases, and memory-only demo state. It is not a store release or a fresh build of all sources in this repository. Install only if you intentionally want to inspect this development demo; use a test device/emulator.

- Package: `com.feedme.development`
- Size: 18,981,844 bytes
- SHA-256: `bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805`
- Historical smoke evidence: [report](../feedme/docs/verification/android-smoke/report.json), [screenshots](../feedme/docs/verification/android-smoke/)

No signing private key is included. Other Gradle-generated libraries, intermediate/test APKs, SDK caches and temporary database clusters are reproducible local outputs and are excluded. Their source, test code and retained verification records are included.

## UI and blueprint

The complete current [UI collection](../outputs/biteclub_ui/) and [blueprint](../outputs/biteclub_blueprint/) are checked in with all assets. Earlier BiteClub-name downloadable exports are preserved as [sanitized historical archives](History/README.md); use the current folders for active FeedMe work.
