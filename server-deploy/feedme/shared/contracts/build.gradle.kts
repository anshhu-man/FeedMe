plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Contract metadata, bounded wire documents and cooking projections. No transport or credentials.
kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.serialization.json) }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("jvmTest").resources.apply {
            srcDir(rootProject.file("docs/verification/canonical-validation"))
            include("format-corpus.json")
        }
    }
}

val verifyContractArtifacts by tasks.registering(Exec::class) {
    group = "verification"
    description = "Reject canonical contract, registry or generated-source drift. Requires Node.js."
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/generate-contract-artifacts.mjs", "--check")
}
tasks.matching { it.name == "check" || it.name.startsWith("compileKotlin") || it.name == "compileCommonMainKotlinMetadata" }
    .configureEach { dependsOn(verifyContractArtifacts) }
