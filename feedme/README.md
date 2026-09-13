# FeedMe — native app foundation

**Your kitchen. Your rules.** Android and iOS share Kotlin. FeedMe remains its own Gen Z cooking-and-social concept; this is not TasteEcho and is unrelated to the other Devpost entry with a similar name.

This workspace starts implementation of the [FeedMe blueprint](../outputs/biteclub_blueprint/README.md). The 54-feature / 98-screen design remains the larger product vision. The first milestone is a **local, development-only native journey**, not a public release or functioning social service.

## Structure

Active delivery: [milestone plan](docs/RELEASE_PLAN.md), [all-feature task matrix](docs/FEATURE_DELIVERY_MATRIX.md), [user-action/alert register](docs/USER_ACTIONS.md) and [verified build status](docs/BUILD_STATUS.md). The persistent ship-ready goal is active; creating the plan does not mean the app is production-ready.

The user-requested public repository is [anshhu-man/FeedMe](https://github.com/anshhu-man/FeedMe), with a root **Reference** library for the idea, docs, UI, maps and artifacts. [Publication scope and privacy/evidence handling](docs/GITHUB_REFERENCE.md). This is not a production release.

- `shared/core`: platform-neutral models, reducer, fixture repository and domain tests.
- `shared/app`: shared Compose screens, approved FeedMe theme/assets, UI-to-domain mapping and iOS framework entry point.
- `apps/android`: debug-only Android shell, temporary development identifier.
- `apps/ios`: SwiftUI shell and Xcode project for the same shared Compose application.
- `server`: local-only Ktor scaffold plus independently tested PostgreSQL command/outbox/inbox primitives. Public degraded health works; all other canonical routes are explicitly unavailable. See [backend boundaries](docs/LOCAL_BACKEND_FOUNDATION.md) and [durable-storage tests/integration requirements](docs/DURABLE_STORAGE_FOUNDATION.md).

## First milestone

Explore the local demo → Home/Today → a sample plate → Make Mine → recipe → cooking progress → saved cookbook → explicit local demo sharing / My Plate.

Sample names, photos and recipes are development fixtures. They are not professionally reviewed cooking guidance. Local demo permissions are not backend authorization. No user account is created, no post is uploaded, and no purchase is performed. Release builds are intentionally unavailable until production identity, provider integration, content review and release gates are implemented.

The app starts at the welcome screen. Demo state is currently memory-only; killing the app starts a fresh session. Durable private save/cooking recovery remains a later implementation step and must not be claimed from a successful local state transition.

## Building

Use JDK 17, Android SDK 36, and the checked-in Gradle wrapper. Kotlin, Compose and AGP versions are pinned in `gradle/libs.versions.toml`; they are compatibility-spike choices, not a claim that every platform is verified.

```sh
./gradlew :shared:core:jvmTest :apps:android:assembleDebug
```

Set `JAVA_HOME` to an installed JDK 17 and `ANDROID_HOME` to your SDK, or supply an untracked `local.properties`. No permanent local machine paths are committed. The Android package is `com.feedme.development`, **not an approved production application ID**.

Open `apps/ios/FeedMe.xcodeproj` in full Xcode. See the iOS README for simulator instructions. Apple's Command Line Tools alone are insufficient for iOS compilation. No Xcode installation, account enrollment, signing change, cloud deployment or store submission is performed by this milestone.

## Guardrails

- The normal domain state defaults to production-deny behavior; the native development entry explicitly opts into clearly labeled fixture mode.
- Keep reviewed recipe provenance and source-post identity separate from food photography.
- Basic cooking, Make Mine, Today/My Plate and circles are the product identity. Schedule pressure does not authorize replacing FeedMe with a private recipe utility.
- Never commit API secrets, account credentials or signing keys. Authentication, product server APIs, media sanitization/moderation, persistence, RevenueCat and production monitoring remain outstanding; a local health endpoint does not satisfy those gates.
- The production blueprint is the contract baseline; implement and verify selected routes incrementally without claiming unimplemented controls have shipped.

## Toolchain evidence

The selected Kotlin 2.3.21 / Gradle 8.14 / AGP 8.13.2 combination is within the published [Kotlin compatibility ranges](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html). UI uses [Compose Multiplatform 1.10.3](https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.10.3) and the matching Kotlin Compose compiler. Actual checks and environmental blockers are recorded separately in `docs/BUILD_STATUS.md` after execution.
