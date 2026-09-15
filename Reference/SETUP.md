# Setup and reproduction

## View the complete UI and blueprint

Clone the entire repository. Open `outputs/biteclub_ui/index.html` or run `python3 -m http.server 8769 --bind 127.0.0.1` at repository root and visit `/outputs/biteclub_ui/index.html?view=gallery`. The blueprint explorer is `/outputs/biteclub_blueprint/index.html`.

These are local, simulated prototypes. Use made-up credentials only. GitHub's file viewer does not execute them, and no live website is deployed.

## Build Android

Use JDK 17, Android SDK platform 36 and the included executable Gradle wrapper. Set `JAVA_HOME` and `ANDROID_HOME`, or create your own untracked `feedme/local.properties`. Run from `feedme/`:

```sh
./gradlew :shared:app:jvmTest :apps:android:testProgressUnitTest :apps:android:assembleProgress
```

This builds the retained local preview. The separate older demo uses `:apps:android:assembleDebug`. Release variants remain intentionally disabled; neither development identifier is a production identity. The isolated storage JNI test helper additionally uses NDK `28.2.13676358`; it is test-only and not part of the preview app.

## Build iOS

Use full Xcode and the appropriate installed SDK/simulator, then open `feedme/apps/ios/FeedMe.xcodeproj`. Command Line Tools alone are insufficient. No iOS compilation or platform-parity claim is recorded for this snapshot. Do not invent signing teams or permanent bundle IDs.

## Run reference and Node checks

Use Node.js with its built-in test runner:

```sh
node tools/verify-reference.mjs
node --test tools/*.test.mjs
node tools/verify-historical-parser-fixture.mjs . --run-tests
```

The UI/blueprint browser QA additionally needs Playwright and Chrome/Chromium. Set `FEEDME_PLAYWRIGHT_PATH` and `FEEDME_BROWSER_PATH` to your installed runtime/browser. Any redacted machine-specific fallback path is provenance, not a usable installation location.

Full integration verification additionally needs local PostgreSQL 15 binaries (`FEEDME_POSTGRES_BIN`), an Android emulator (`FEEDME_TEST_DEVICE`), SDK/JDK setup and network access for Gradle dependencies. Read each verifier and its documented scope before running. No credentials or production services are needed for the isolated tests. Historical spike scripts can contain macOS-specific temporary-path assumptions.

The source workspace's checkpoint runners are historical, fixed-inventory tools, not a single current clone-wide command. Some require excluded original receipts and temporary helpers. In particular, do not advertise `node --test scripts/*.test.mjs` or an old full-checkpoint runner as portable current acceptance. The standalone parser-fixture command above runs exactly ten frozen parser tests without the original ninth receipt; it does not run app/native tests or replace historical acceptance. Read [the evidence boundary](LOCAL_EVIDENCE.md) and [checkpoint](SNAPSHOT_STATUS.md).

The latest source-workspace native run used a dedicated API 35 arm64 emulator. This does not establish API 26/iOS, physical-device, provider or release acceptance. Use a dedicated test emulator for any instrumentation, not a personal device. Builds and QA generate local outputs; run reference-integrity checks on the clean publication tree or a separate clean copy, not a working directory populated with excluded build caches.
