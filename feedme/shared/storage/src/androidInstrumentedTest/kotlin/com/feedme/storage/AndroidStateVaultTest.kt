package com.feedme.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidStateVaultTest {
    private lateinit var sandbox: AndroidStateTestSandbox

    @Before
    fun setUp() {
        sandbox = AndroidStateTestSandbox()
    }

    @After
    fun tearDown() {
        sandbox.close()
    }

    @Test
    fun nativeKeysAreNonExportableAndEncryptionUsesFreshNonces() {
        val vault = AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = false)
        val ownerKey = vault.createOwnerKey()
        val plaintext = "private cooking state".encodeToByteArray()
        val aad = "owner/record/revision/1".encodeToByteArray()

        assertTrue(ownerKey.matches(Regex("[0-9a-f]{32}")))
        assertTrue(vault.hasOwnerKey(ownerKey))
        val nativeIndexKey = sandbox.keyStore().getKey("${sandbox.keyPrefix}.index", null)
        val nativeOwnerKey = sandbox.keyStore().getKey("${sandbox.keyPrefix}.owner.$ownerKey", null)
        assertNotNull(nativeIndexKey)
        assertNotNull(nativeOwnerKey)
        assertNull(nativeIndexKey.encoded)
        assertNull(nativeOwnerKey.encoded)
        assertEquals("HmacSHA256", nativeIndexKey.algorithm)
        assertEquals("AES", nativeOwnerKey.algorithm)

        val first = vault.seal(ownerKey, plaintext, aad)
        val second = vault.seal(ownerKey, plaintext, aad)
        assertEquals(1, first[0].toInt())
        assertEquals(1 + 12 + plaintext.size + 16, first.size)
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
        assertArrayEquals(plaintext, vault.open(ownerKey, first, aad))
        assertArrayEquals(plaintext, vault.open(ownerKey, second, aad))

        val reopened = AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = true)
        assertArrayEquals(plaintext, reopened.open(ownerKey, first, aad))
    }

    @Test
    fun authenticationRejectsChangedPayloadTagNonceAadAndKey() {
        val vault = AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = false)
        val ownerKey = vault.createOwnerKey()
        val otherKey = vault.createOwnerKey()
        val aad = "scope/collection/id/revision/1".encodeToByteArray()
        val ciphertext = vault.seal(ownerKey, "private payload".encodeToByteArray(), aad)

        for (offset in listOf(1, 13, ciphertext.lastIndex)) {
            val changed = ciphertext.copyOf()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            assertVaultFailure { vault.open(ownerKey, changed, aad) }
        }
        val unsupportedVersion = ciphertext.copyOf().also { it[0] = 2 }
        assertVaultFailure { vault.open(ownerKey, unsupportedVersion, aad) }
        assertVaultFailure {
            vault.open(ownerKey, ciphertext, "scope/collection/id/revision/2".encodeToByteArray())
        }
        assertVaultFailure { vault.open(otherKey, ciphertext, aad) }
        assertVaultFailure { vault.open(ownerKey, ciphertext.copyOf(11), aad) }
    }

    @Test
    fun freshDatabaseReusesExistingIndexKey() {
        val input = "opaque ownership index input".encodeToByteArray()
        val first = AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = false)
        val index = first.index(input)
        assertEquals(32, index.size)
        assertArrayEquals(index, first.index(input))
        assertFalse(index.contentEquals(first.index("other owner".encodeToByteArray())))

        val second = AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = false)
        assertArrayEquals(index, second.index(input))
    }

    @Test
    fun existingDatabaseNeverRecreatesMissingIndexKey() {
        AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = false)
        sandbox.keyStore().deleteEntry("${sandbox.keyPrefix}.index")

        assertVaultFailure {
            AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = true)
        }
        assertFalse(sandbox.keyStore().containsAlias("${sandbox.keyPrefix}.index"))
    }

    @Test
    fun erasedOwnerKeyCannotDecryptAndOtherOwnerRemainsReadable() {
        val vault = AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = false)
        val erasedKey = vault.createOwnerKey()
        val retainedKey = vault.createOwnerKey()
        val aad = "authenticated record identity".encodeToByteArray()
        val plaintext = "test state".encodeToByteArray()
        val erasedCiphertext = vault.seal(erasedKey, plaintext, aad)
        val retainedCiphertext = vault.seal(retainedKey, plaintext, aad)
        val index = vault.index(aad)

        vault.deleteOwnerKey(erasedKey)
        vault.deleteOwnerKey(erasedKey)

        assertFalse(vault.hasOwnerKey(erasedKey))
        assertVaultFailure { vault.open(erasedKey, erasedCiphertext, aad) }
        assertTrue(vault.hasOwnerKey(retainedKey))
        assertArrayEquals(plaintext, vault.open(retainedKey, retainedCiphertext, aad))
        assertArrayEquals(index, vault.index(aad))

        val reopened = AndroidStateVault.createOrOpen(sandbox.keyPrefix, databaseExisted = true)
        assertFalse(reopened.hasOwnerKey(erasedKey))
        assertVaultFailure { reopened.open(erasedKey, erasedCiphertext, aad) }
        assertArrayEquals(plaintext, reopened.open(retainedKey, retainedCiphertext, aad))
    }

    private fun assertVaultFailure(operation: () -> Any?) {
        val failure = try {
            operation()
            null
        } catch (error: Exception) {
            error
        }
        assertNotNull("Expected native vault failure", failure)
        assertTrue("Vault errors must be sanitized", failure is StateVaultException)
        assertEquals("Private storage vault unavailable", failure!!.message)
        assertNull("Provider exception must not escape", failure.cause)
    }
}
