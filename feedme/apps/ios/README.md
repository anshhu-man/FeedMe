# FeedMe iOS development shell

This native SwiftUI host loads the shared Compose application from `:shared:app` via the `FeedMeShared` framework. It is a development scaffold, **not an App Store-ready release**. No Apple team, production identifier, provisioning profile, account, or secret is supplied.

## Prerequisites

- A Mac with full Xcode and an iOS simulator runtime, compatible with the repository's pinned Kotlin/Compose versions. Command Line Tools alone cannot build this target.
- The repository's required JDK and working Gradle wrapper; first builds need access to the declared dependency repositories.
- The shared Gradle module must export `FeedMeShared` through `binaries.framework` for the desired Apple target. Its `iosMain/MainViewController.kt` exposes `MainViewController(): UIViewController`.

The foundation currently targets iPhone and iOS 16 or later. The bundle ID `com.feedme.development.ios` and display name `FeedMe · Dev` are development placeholders, not an approved production identity. The Xcode project, Swift app, shared framework and native package paths now use FeedMe. No push, camera, purchases, or photo-library entitlements/permissions have been added; introduce them only with implemented native features and accurate disclosures.

## Debug simulator build

Open `apps/ios/FeedMe.xcodeproj`, select the shared **FeedMe** scheme, select an installed iPhone simulator, and run. Its Debug simulator configuration disables code signing. Device runs require your own authorized development signing configuration; do not commit credentials or personal team settings.

From the repository root, once full Xcode is installed:

```sh
xcodebuild -project apps/ios/FeedMe.xcodeproj -scheme FeedMe -showdestinations
xcodebuild -project apps/ios/FeedMe.xcodeproj -scheme FeedMe -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

If the active developer directory is Command Line Tools, set `DEVELOPER_DIR` for your own command to the installed Xcode application's `Contents/Developer` directory. This scaffold does not change global `xcode-select` settings.

The first build phase runs `./gradlew :shared:app:embedAndSignAppleFrameworkForXcode` from the repository root before compiling Swift, and honors the Kotlin IDE's duplicate-build override. `ENABLE_USER_SCRIPT_SANDBOXING=NO` permits that local Gradle integration. Framework search/link settings point to the module's generated Xcode framework directory. No CocoaPods dependency or checked-in generated framework is required. This follows Kotlin's [official direct integration guide](https://kotlinlang.org/docs/multiplatform-direct-integration.html).

## Release gate

**Every non-Debug build deliberately fails in the first build phase.** The guard also runs when an IDE has already built Kotlin. Do not remove it merely to obtain an archive. First implement and approve the production identity, team/signing, release environment, icon/store assets, privacy declarations, real integrations, and release verification. Replacing that gate is a reviewed release-engineering change.

## Verification status

- Project and Info.plist syntax passed `plutil -lint`; the shared scheme passed `xmllint --noout` on September 13, 2026.
- The active developer directory is `/Library/Developer/CommandLineTools`; `xcodebuild -version` failed because full Xcode is unavailable from that directory. No global developer-directory changes were made.
- Simulator/device compilation, Swift-to-Kotlin linking, startup, layout, keyboard/safe-area behavior, accessibility, and signing remain **unverified** until full Xcode and the complete shared module are available and tested.
- The scheme contains no native iOS test target yet. An empty Test action is not test coverage.
- Shared UI owns safe-area handling because the host uses `ignoresSafeArea()`. Verify insets and keyboard handling on real destinations before release.
