package com.feedme.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.PortResult
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Every test owns its directory and alias namespace, including when tests run in parallel. */
internal class AndroidStateTestSandbox {
    private val testId = UUID.randomUUID().toString()
    val keyPrefix = "com.feedme.storage.instrumented.$testId"
    val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "private-state-instrumented-$testId",
    ).also { assertFalse("Test directory must start absent", it.exists()) }

    fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun close() {
        val store = keyStore()
        val aliases = store.aliases().toList().filter { it.startsWith("$keyPrefix.") }
        aliases.forEach(store::deleteEntry)
        if (Files.isSymbolicLink(directory.toPath())) {
            Files.delete(directory.toPath())
        } else {
            assertTrue("Could not remove isolated test directory", !directory.exists() || directory.deleteRecursively())
        }
    }
}

internal fun <T> PortResult<T>.valueOrFail(): T = when (this) {
    is PortResult.Value -> value
    is PortResult.Failure -> throw AssertionError("Expected storage value, got $reason")
}
