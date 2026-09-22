import com.sun.security.auth.module.UnixSystem
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

abstract class OfflineSigningBuildFeatures {
    @get:Inject abstract val buildFeatures: BuildFeatures
}

fun explicitOptIn(name: String): Boolean {
    val value = providers.gradleProperty(name).orNull
    if (value != null && (value != "true" || gradle.startParameter.projectProperties[name] != "true")) {
        throw GradleException("Omit $name or explicitly pass -P$name=true.")
    }
    return value == "true"
}
val offlineRelease = explicitOptIn("feedmeOfflineRelease")
val uploadSigning = explicitOptIn("feedmeUploadSigning")
if (offlineRelease && !uploadSigning) throw GradleException("Offline release requires explicit upload signing.")
val signingMaterial = if (offlineRelease && uploadSigning) {
    val cache = objects.newInstance(OfflineSigningBuildFeatures::class.java).buildFeatures.configurationCache
    if (cache.requested.orNull == true || cache.active.get()) throw GradleException("Signing requires --no-configuration-cache.")
    try {
        val repository = rootProject.projectDir.toPath().toRealPath()
        val uid = UnixSystem().uid
        fun privatePath(relative: String, directory: Boolean): Path {
            val p = repository.resolve(relative)
            val a = Files.readAttributes(p, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            check(!a.isSymbolicLink && if (directory) a.isDirectory else a.isRegularFile)
            check(p.toRealPath() == p && (Files.getAttribute(p, "unix:uid", NOFOLLOW_LINKS) as Number).toLong() == uid)
            check(a.permissions() == PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            if (!directory) check((Files.getAttribute(p, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L)
            return p
        }
        privatePath(".local", true)
        privatePath(".local/signing", true)
        val key = privatePath(".local/signing/feedme-upload.p12", false)
        val password = privatePath(".local/signing/keystore-password", false)
        val bytes = Files.newInputStream(password, READ, NOFOLLOW_LINKS).use { it.readNBytes(66) }
        try {
            check(bytes.size == 65 && bytes[64] == 10.toByte() && (0 until 64).all {
                bytes[it] in 65.toByte()..90.toByte() || bytes[it] in 97.toByte()..122.toByte() ||
                    bytes[it] in 48.toByte()..57.toByte() || bytes[it] == 43.toByte() || bytes[it] == 47.toByte()
            })
            key.toFile() to String(bytes, 0, 64, Charsets.US_ASCII)
        } finally { bytes.fill(0) }
    } catch (_: Exception) {
        throw GradleException("Protected upload signing material unavailable; no private diagnostics printed.")
    }
} else null

android {
    namespace = "com.feedme.offline.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anshhuman.feedme"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (signingMaterial != null) signingConfigs.create("feedmeUpload") {
        storeFile = signingMaterial.first
        storeType = "PKCS12"
        keyAlias = "feedme-upload"
        storePassword = signingMaterial.second
        keyPassword = signingMaterial.second
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".offline.debug"
            versionNameSuffix = "-offline-preview"
        }
        getByName("release") {
            isDebuggable = false
            if (signingMaterial != null) signingConfig = signingConfigs.getByName("feedmeUpload")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = offlineRelease } }

dependencies {
    implementation(project(":shared:offline"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.get()}")
    implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.compose.get()}")
    implementation("org.jetbrains.compose.ui:ui:${libs.versions.compose.get()}")
    implementation("androidx.savedstate:savedstate:1.3.3")
    implementation("androidx.savedstate:savedstate-compose:1.3.3")
    implementation("org.jetbrains.compose.material3:material3:${libs.versions.material3.get()}")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
