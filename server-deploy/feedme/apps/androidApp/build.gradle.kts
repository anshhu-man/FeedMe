import com.sun.security.auth.module.UnixSystem
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import javax.inject.Inject
import org.gradle.api.configuration.BuildFeatures

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

abstract class UploadSigningBuildFeatures {
    @get:Inject abstract val buildFeatures: BuildFeatures
}

// Explicit local opt-in only; ordinary debug configuration never reads signing material.
val uploadSigningProperty = providers.gradleProperty("feedmeUploadSigning").orNull
if (uploadSigningProperty != null && (uploadSigningProperty != "true" ||
        gradle.startParameter.projectProperties["feedmeUploadSigning"] != "true")) {
    throw GradleException("Omit feedmeUploadSigning or explicitly pass -PfeedmeUploadSigning=true.")
}
val uploadSigningEnabled = uploadSigningProperty == "true"

// Publishing can deliberately expose the release variant without changing normal debug
// builds. Both switches must be supplied on this invocation, never inherited from a file.
val releaseBuildProperty = providers.gradleProperty("feedmeReleaseBuild").orNull
if (releaseBuildProperty != null && (releaseBuildProperty != "true" ||
        gradle.startParameter.projectProperties["feedmeReleaseBuild"] != "true")) {
    throw GradleException("Omit feedmeReleaseBuild or explicitly pass -PfeedmeReleaseBuild=true.")
}
val releaseBuildEnabled = releaseBuildProperty == "true"
if (releaseBuildEnabled && !uploadSigningEnabled) {
    throw GradleException("Release builds require explicit -PfeedmeUploadSigning=true with -PfeedmeReleaseBuild=true.")
}
val localUploadSigning = if (uploadSigningEnabled) {
    val configurationCache = objects.newInstance(UploadSigningBuildFeatures::class.java)
        .buildFeatures.configurationCache
    if (configurationCache.requested.orNull == true || configurationCache.active.get()) {
        throw GradleException("Local upload signing requires --no-configuration-cache.")
    }
    try {
        val repository = rootProject.projectDir.toPath().toRealPath()
        val currentUid = UnixSystem().uid
        fun privatePath(relative: String, directory: Boolean): Path {
            val path = repository.resolve(relative)
            val attributes = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            check(!attributes.isSymbolicLink && if (directory) attributes.isDirectory else attributes.isRegularFile)
            check(path.toRealPath() == path)
            check((Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong() == currentUid)
            check(attributes.permissions() == PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            if (!directory) check((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L)
            return path
        }
        privatePath(".local", true)
        privatePath(".local/signing", true)
        val store = privatePath(".local/signing/feedme-upload.p12", false)
        val password = privatePath(".local/signing/keystore-password", false)
        val bytes = Files.newInputStream(password, READ, NOFOLLOW_LINKS).use { it.readNBytes(66) }
        try {
            check(bytes.size == 65 && bytes[64] == 10.toByte() && (0 until 64).all { index ->
                val byte = bytes[index]
                byte in 65.toByte()..90.toByte() || byte in 97.toByte()..122.toByte() ||
                    byte in 48.toByte()..57.toByte() || byte == 43.toByte() || byte == 47.toByte()
            })
            store.toFile() to String(bytes, 0, 64, Charsets.US_ASCII)
        } finally {
            bytes.fill(0)
        }
    } catch (_: Exception) {
        // Never attach a potentially secret-bearing exception or print credential contents.
        throw GradleException("Local upload signing material is unavailable or fails the fixed-path ownership, permission or format checks.")
    }
} else null

// Validate the exact bounded public-only byte image before writing the generated asset.
// Do not replace this with Sync: validating then copying by path would reread unvalidated bytes.
// No tracked secret-bearing input or cached output: each invocation validates afresh, and
// absent optional debug configuration removes only its stale generated asset.
val prepareClientConfiguration by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/prepare-android-client-config.mjs")
    standardOutput = ByteArrayOutputStream()
    errorOutput = ByteArrayOutputStream()
    isIgnoreExitValue = true
    doLast {
        if (executionResult.get().exitValue != 0) {
            throw GradleException("Public Android client configuration is unavailable or invalid; private diagnostics were not printed.")
        }
    }
}

// Independently invokable without exposing release variants. Required preparation also
// gates preReleaseBuild whenever this invocation explicitly enables the release variant.
val prepareReleaseClientConfiguration by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates required public release configuration without packaging or enabling release."
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/prepare-android-client-config.mjs", "--release")
    standardOutput = ByteArrayOutputStream()
    errorOutput = ByteArrayOutputStream()
    isIgnoreExitValue = true
    doLast {
        if (executionResult.get().exitValue != 0) {
            throw GradleException("Public Android client configuration is unavailable or invalid; private diagnostics were not printed.")
        }
    }
}

android {
    namespace = "com.feedme.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anshhuman.feedme"
        manifestPlaceholders["feedmeAppLabel"] = "FeedMe"
        // The authenticated native credential adapter requires API 27.
        minSdk = 27
        targetSdk = 36
        // Play already serves the separate offline edition at code 1. Connected builds
        // must advance that shared application ID without rewriting the offline artifact.
        versionCode = 2
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (localUploadSigning != null) {
        signingConfigs.create("feedmeUpload") {
            storeFile = localUploadSigning.first
            storeType = "PKCS12"
            keyAlias = "feedme-upload"
            storePassword = localUploadSigning.second
            keyPassword = localUploadSigning.second
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-connected-dev"
            manifestPlaceholders["feedmeAppLabel"] = "FeedMe · Connected dev"
        }
        if (localUploadSigning != null) {
            getByName("release") { signingConfig = signingConfigs.getByName("feedmeUpload") }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    // Distinct variant assets: optional debug preparation cannot supply a release fallback.
    // Both contain public client configuration only, with no default tenant or credentials.
    sourceSets["debug"].assets.srcDir(layout.buildDirectory.dir("generated/clientConfiguration"))
    sourceSets["release"].assets.srcDir(layout.buildDirectory.dir("generated/releaseClientConfiguration"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(prepareClientConfiguration) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(prepareReleaseClientConfiguration) }

// Explicit publication preparation only. This switch is not connected-service acceptance,
// policy compliance, a Play upload or approval. Release still requires real configuration
// and the protected upload-signing path; no unsigned or unconfigured fallback is supplied.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = releaseBuildEnabled }
}

// Deliberately not a debug dependency and never signs, packages, uploads or enables release.
tasks.register<Exec>("verifyLocalUploadSigning") {
    group = "verification"
    description = "Verifies the opted-in local upload certificate and dormant release signing reference."
    val publicOutput = ByteArrayOutputStream()
    workingDir(rootProject.projectDir)
    // The verifier intentionally invokes this JDK's keytool. Gradle's
    // org.gradle.java.home selection does not export JAVA_HOME to child processes.
    environment("JAVA_HOME", System.getProperty("java.home"))
    commandLine("node", "scripts/prepare-android-upload-key.mjs", "--verify")
    standardOutput = publicOutput
    errorOutput = ByteArrayOutputStream()
    isIgnoreExitValue = true
    doFirst {
        if (!uploadSigningEnabled) throw GradleException("Pass -PfeedmeUploadSigning=true to verify local upload signing.")
        val upload = android.signingConfigs.getByName("feedmeUpload")
        if (android.buildTypes.getByName("release").signingConfig !== upload ||
            android.buildTypes.getByName("debug").signingConfig === upload ||
            upload.storeType != "PKCS12" || upload.keyAlias != "feedme-upload" ||
            upload.storeFile != localUploadSigning?.first) {
            throw GradleException("The dormant release upload-signing reference does not match the fixed local configuration.")
        }
    }
    doLast {
        if (executionResult.get().exitValue != 0) {
            throw GradleException("Local upload certificate verification failed; private diagnostics were not printed.")
        }
        logger.lifecycle(publicOutput.toString(Charsets.UTF_8.name()).trim())
    }
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:contracts"))
    implementation(project(":shared:session"))
    implementation(project(":shared:mealflow"))
    implementation(project(":shared:app"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.credentials.core)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.compose.get()}")
    implementation(libs.compose.material3)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
