# Setup and reproduction

## View the complete UI and blueprint

Clone the entire repository. Open `outputs/biteclub_ui/index.html` or run `python3 -m http.server 8769 --bind 127.0.0.1` at repository root and visit `/outputs/biteclub_ui/index.html?view=gallery`. The blueprint explorer is `/outputs/biteclub_blueprint/index.html`.

These are local, simulated prototypes. Use made-up credentials only. GitHub's file viewer does not execute them, and no live website is deployed.

## Build Android

Use JDK 17, Android SDK platform 36 and the included executable Gradle wrapper. Set `JAVA_HOME` and `ANDROID_HOME`, or create your own untracked `feedme/local.properties`. Run from `feedme/`:

```sh
./gradlew :shared:core:jvmTest :apps:android:assembleDebug
```

Release variants remain intentionally disabled. The development identifier is not a production identity. The isolated storage JNI test helper additionally uses NDK `28.2.13676358`; the current VFS replacement is unfinished and is not part of the demo app.

## Build iOS

Use full Xcode and the appropriate installed SDK/simulator, then open `feedme/apps/ios/FeedMe.xcodeproj`. Command Line Tools alone are insufficient. No iOS compilation or platform-parity claim is recorded for this snapshot. Do not invent signing teams or permanent bundle IDs.

## Run reference and Node checks

Use Node.js with its built-in test runner:

```sh
node tools/verify-reference.mjs
cd feedme
node --test scripts/*.test.mjs
```

The UI/blueprint browser QA additionally needs Playwright and Chrome/Chromium. Set `FEEDME_PLAYWRIGHT_PATH` and `FEEDME_BROWSER_PATH` to your installed runtime/browser. Any redacted machine-specific fallback path is provenance, not a usable installation location.

Full integration verification additionally needs local PostgreSQL 15 binaries (`FEEDME_POSTGRES_BIN`), an Android emulator (`FEEDME_TEST_DEVICE`), SDK/JDK setup and network access for Gradle dependencies. Read each verifier and its documented scope before running. No credentials or production services are needed for the isolated tests. Historical spike scripts can contain macOS-specific temporary-path assumptions.

Do not run unfinished native sync suites expecting green results; [the checkpoint](SNAPSHOT_STATUS.md) lists the remaining hookup work. Re-running code generation or QA may update generated evidence; review those diffs normally.
