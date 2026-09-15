plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}
kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            api(project(":shared:contracts"))
            api(project(":shared:session"))
            api(project(":shared:kitchen"))
            implementation(project(":shared:transport"))
            implementation(project(":shared:sync"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Actual SQLite/runtime fixtures remain test-only; no release access factory.
            implementation(project(path = ":shared:storage", configuration = "jvmProtocolTestFixtures"))
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
            implementation(libs.androidx.work.runtime)
        }
    }
}
android {
    namespace = "com.feedme.mealflow"
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
