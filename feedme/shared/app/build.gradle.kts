plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "FeedMeShared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            api(project(":shared:mealflow"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Actual encrypted SQLite/session fixture only; no production access-minting API.
            implementation(project(path = ":shared:storage", configuration = "jvmProtocolTestFixtures"))
            implementation(project(":shared:planning"))
            implementation(project(":shared:transport"))
        }
        androidInstrumentedTest.dependencies {
            implementation(project(":shared:session"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
            implementation(libs.androidx.activity.compose)
        }
    }
}

// Compile this finite, platform-neutral progress service/integration closure ONLY into
// the JVM test task. Android's storage classes use different internal method names and
// native SQLite loading: never mix them with the precompiled JVM runtime fixture.
// No copied implementation, Android owner/activity, production dependency or main source
// set is introduced. The actual shared app main and existing JVM test sources stay intact.
val progressIntegrationJvmTestSources = listOf(
    "ProgressReviewedPostIntegration.kt",
    "ProgressCanonicalService.kt",
    "ProgressIdentity.kt",
    "ProgressPostPreviewContract.kt",
    "ProgressPostServiceCodec.kt",
    "ProgressPostServiceLedger.kt",
    "ProgressServiceLedger.kt",
    "ProgressCookbookLedger.kt",
    "ProgressCatalog.kt",
).map { rootProject.file("apps/android/src/progress/kotlin/com/feedme/development/progress/$it") }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlinJvm") {
    source(progressIntegrationJvmTestSources)
    doFirst {
        check(progressIntegrationJvmTestSources.all { it.isFile }) { "Exact progress JVM test source closure is missing" }
    }
}

android {
    namespace = "com.feedme.app"
    compileSdk = 36
    sourceSets["androidTest"].manifest.srcFile("src/androidInstrumentedTest/AndroidManifest.xml")
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions { targetSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.feedme.app.generated.resources"
    generateResClass = always
}
