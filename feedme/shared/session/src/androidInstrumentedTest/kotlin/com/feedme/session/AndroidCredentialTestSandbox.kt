package com.feedme.session

import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.PortResult
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Every test owns exact private paths and aliases, never the FeedMe app's actual credential store. */
internal class AndroidCredentialTestSandbox {
    private val testId = UUID.randomUUID().toString()
    val keyPrefix = "com.feedme.session.instrumented.$testId"
    val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "credential-state-instrumented-$testId",
    ).also { assertFalse("Test directory must start absent", it.exists()) }

    fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun aliases(): Set<String> = keyStore().aliases().toList().filter { it.startsWith("$keyPrefix.") }.toSet()
    fun credentialAliases(): Set<String> = aliases().filter { it.startsWith("$keyPrefix.credential.") }.toSet()

    fun close() {
        val store = keyStore()
        aliases().forEach(store::deleteEntry)
        assertTrue("Owned credential test aliases remain after cleanup", aliases().isEmpty())
        if (Files.isSymbolicLink(directory.toPath())) Files.delete(directory.toPath())
        else assertTrue("Could not remove isolated credential test directory", !directory.exists() || directory.deleteRecursively())
    }
}

internal fun <T> PortResult<T>.credentialValue(): T = when (this) {
    is PortResult.Value -> value
    is PortResult.Failure -> throw AssertionError("Expected credential value, got $reason")
}
