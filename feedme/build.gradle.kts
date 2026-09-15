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

subprojects {
    tasks.configureEach {
        if (name == "check" || name == "build" || name.startsWith("assemble") || name.startsWith("bundle")) {
            dependsOn(rootProject.tasks.named("verifyReleaseScope"))
        }
    }
}
