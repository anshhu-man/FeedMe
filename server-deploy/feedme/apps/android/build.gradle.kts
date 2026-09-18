plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.feedme.development"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.feedme.development"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("progress") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".progress"
            versionNameSuffix = "-progress"
            matchingFallbacks += "debug"
        }
    }
    // This app previously had no instrumentation suite. The new suite owns only the
    // isolated progress UID; the installed historical development preview is not a target.
    testBuildType = "progress"
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// This identity and fixture build must never become a store release by accident.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = false
    }
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:app"))
    implementation(libs.androidx.activity.compose)
    add("progressImplementation", project(":shared:session"))
    add("progressImplementation", project(":shared:storage"))
    add("progressImplementation", project(":shared:planning"))
    add("progressImplementation", project(":shared:contracts"))
    add("progressImplementation", project(":shared:transport"))
    add("progressImplementation", project(":shared:mealflow"))
    add("progressImplementation", libs.kotlinx.coroutines.core)
    add("progressImplementation", libs.kotlinx.serialization.json)
    // The progress manifest merges this provider to remove only WorkManagerInitializer.
    // Make the already-selected runtime 1.2.0 visible to manifest class validation too.
    add("progressImplementation", "androidx.startup:startup-runtime:1.2.0")
    add("progressImplementation", "org.jetbrains.compose.foundation:foundation:${libs.versions.compose.get()}")
    // Match the Material3 version selected by the existing shared Compose visual system.
    add("progressImplementation", "org.jetbrains.compose.material3:material3:1.9.0")
    add("testProgressImplementation", kotlin("test-junit"))
    add("testProgressImplementation", libs.kotlinx.coroutines.test)
    add("androidTestImplementation", libs.androidx.test.runner)
    add("androidTestImplementation", libs.androidx.test.junit)
}
