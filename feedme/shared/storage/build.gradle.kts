import java.util.Properties

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
            implementation(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":shared:sync"))
            implementation(project(":shared:kitchen"))
            implementation(project(":shared:session"))
            implementation(project(":shared:mealflow"))
        }
        androidInstrumentedTest.dependencies {
            implementation(project(":shared:session"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
        }
    }
}

// Narrow TEST-ONLY protocol bridge. No factory/access constructor is added to main or release.
// Export only the actual runtime fixture and its bridge, not an alternate production authority.
val protocolTestFixturesJar by tasks.registering(Jar::class) {
    dependsOn("jvmTestClasses")
    archiveClassifier.set("jvm-protocol-test-fixtures")
    from(kotlin.targets.getByName("jvm").compilations.getByName("test").output.allOutputs) {
        include("com/feedme/storage/PostDraftHttpSessionFixture*")
        include("com/feedme/storage/PrivateSessionRuntimeTest*")
    }
}
val jvmProtocolTestFixtures by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.getByName("jvmTestImplementation"), configurations.getByName("jvmTestRuntimeOnly"))
}
artifacts { add(jvmProtocolTestFixtures.name, protocolTestFixturesJar) }

android {
    namespace = "com.feedme.storage"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Instrumentation-only VFS injector. It is never an input to main/release JNI packaging.
    sourceSets.getByName("androidTest").jniLibs.srcDir(layout.buildDirectory.dir("generated/syncFailureJni"))
}

// Compile only the small test helper with the already-installed NDK; no fetched native dependency,
// replacement SQLite build, externalNativeBuild main target or production C/C++ source set.
val syncFailureAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android26-clang",
    "armeabi-v7a" to "armv7a-linux-androideabi26-clang",
    "x86" to "i686-linux-android26-clang",
    "x86_64" to "x86_64-linux-android26-clang",
)
val syncFailureNativeTasks = syncFailureAbis.map { (abi, compiler) ->
    tasks.register<Exec>("compile${abi.replace('-', '_').replaceFirstChar(Char::uppercaseChar)}SyncFailureTestJni") {
        val source = layout.projectDirectory.file("src/androidInstrumentedTest/cpp/sqlite_sync_failure.c")
        val output = layout.buildDirectory.file("generated/syncFailureJni/$abi/libfeedmeSqliteSyncFailure.so")
        inputs.file(source)
        outputs.file(output)
        doFirst {
            val local = Properties().apply {
                rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
            }
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
                ?: local.getProperty("sdk.dir") ?: error("Local Android SDK is required for instrumentation JNI")
            val host = when {
                System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
                System.getProperty("os.name").startsWith("Linux") -> "linux-x86_64"
                else -> error("Instrumentation JNI helper requires the supported local NDK host")
            }
            val clang = file("$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/$host/bin/$compiler")
            check(clang.isFile) { "Install the explicitly pinned local test NDK 28.2.13676358" }
            output.get().asFile.parentFile.mkdirs()
            commandLine(clang.absolutePath, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared",
                "-Wl,-z,max-page-size=16384", source.asFile.absolutePath, "-o", output.get().asFile.absolutePath, "-ldl", "-pthread")
        }
    }
}
tasks.matching { it.name == "mergeDebugAndroidTestJniLibFolders" }.configureEach {
    dependsOn(syncFailureNativeTasks)
}
