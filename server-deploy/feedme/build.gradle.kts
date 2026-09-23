plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

tasks.wrapper {
    gradleVersion = "8.14"
    networkTimeout = 60_000
    distributionType = Wrapper.DistributionType.ALL
    distributionSha256Sum = "efe9a3d147d948d7528a9887fa35abcf24ca1a43ad06439996490f77569b02d1"
}

// Scope selection is checked before packaging; this is not runtime feature-gating acceptance.
val verifyReleaseScope by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects later-phase features in FeedMe's first-release manifest."
    workingDir(rootDir)
    commandLine("node", "scripts/verify-release-scope.mjs")
    inputs.files("docs/V1_RELEASE_SCOPE.json", "scripts/verify-release-scope.mjs",
        "../outputs/biteclub_blueprint/registry/screen_registry.json")
}

// Production registration/source gate for the approved free V1. Final APK/AAB inspection
// separately pins the merged component surface; this check does not claim artifact acceptance.
val verifyV1RuntimeSurface by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects deferred background registrations and paid-offer activation in free V1."
    workingDir(rootDir)
    commandLine("node", "scripts/verify-v1-runtime-surface.mjs")
    inputs.files("scripts/verify-v1-runtime-surface.mjs",
        "server/src/main/kotlin/com/feedme/server/runtime/AccountCoreRuntime.kt",
        "server/src/main/kotlin/com/feedme/server/runtime/V1ReleaseScope.kt",
        "apps/androidApp/src/main/AndroidManifest.xml", "gradle/libs.versions.toml")
    inputs.files(fileTree(rootDir) {
        include("**/*.gradle.kts", "apps/androidApp/src/main/**/*.kt",
            "shared/app/src/commonMain/**/*.kt", "shared/app/src/androidMain/**/*.kt",
            "shared/app/src/iosMain/**/*.kt", "server/src/main/**/*.kt")
        exclude("**/build/**", "docs/**")
    })
}

// Coverage of declared catalog licenses only; not a resolved SBOM or legal clearance.
// No outputs are declared: the local, network-free check runs whenever its task is included.
val verifyDependencyLicenses by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks every version-catalog library/plugin against its reviewed license inventory."
    workingDir(rootDir)
    commandLine("node", "scripts/verify-dependency-licenses.mjs")
    inputs.files("gradle/libs.versions.toml", "docs/DEPENDENCY_LICENSES.json",
        "scripts/verify-dependency-licenses.mjs")
}

subprojects {
    tasks.configureEach {
        if (name == "check" || name == "build" || name == "distZip" || name == "distTar" ||
            name == "installDist" || name.startsWith("assemble") || name.startsWith("bundle")) {
            dependsOn(rootProject.tasks.named("verifyReleaseScope"))
            dependsOn(rootProject.tasks.named("verifyV1RuntimeSurface"))
        }
        // Direct test/compiler/package entry points must not rely on an incidental
        // assemble-resource task to bring this check into their dependency graph.
        if (name == "check" || name == "build" || name == "jvmTest" || name == "integrationTest" ||
            name == "distZip" || name == "distTar" || name == "installDist" ||
            listOf("assemble", "bundle", "compile", "test", "lint", "package", "install")
                .any { name.startsWith(it) }) {
            dependsOn(rootProject.tasks.named("verifyDependencyLicenses"))
        }
    }
}

// Strict version locks for the four selected Android-debug/server production graphs.
apply(from = "scripts/dependency-locking.gradle")

// Explicit offline evidence task only; ordinary builds do not run this inventory.
apply(from = "scripts/graphics-native-alignment.gradle")
apply(from = "scripts/resolved-dependency-inventory.gradle")

// Explicit diagnostic gate for the current Android APK. This does not enable release,
// replace native code, or stand in for a 16 KiB device/AAB acceptance run.
tasks.register<Exec>("verifyAndroidDebugNativeAlignment") {
    group = "verification"
    description = "Checks all packaged Android debug native libraries for ZIP, LOAD and RELRO alignment."
    dependsOn(":apps:androidApp:assembleDebug")
    workingDir(rootDir)
    commandLine("node", "scripts/verify-android-native-alignment.mjs")
    inputs.files("scripts/verify-android-native-alignment.mjs", "scripts/verify-elf-page-alignment.mjs")
    // No declared outputs: always inspect the actual packaged APK, not a prior success.
}
