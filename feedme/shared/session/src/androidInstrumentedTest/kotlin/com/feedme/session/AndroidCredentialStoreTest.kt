package com.feedme.session

import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoredCredentials
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Keystore/private-file component tests; no provider sign-in or app-session integration. */
@RunWith(AndroidJUnit4::class)
class AndroidCredentialStoreTest {
    private lateinit var sandbox: AndroidCredentialTestSandbox
    private val sandboxes = mutableListOf<AndroidCredentialTestSandbox>()
    private val opened = mutableListOf<AndroidCredentialStore>()

    @Before fun setUp() { sandbox = newSandbox() }

    @After fun tearDown() = runBlocking {
        try { opened.asReversed().forEach { it.close().credentialValue() } }
        finally {
            opened.clear()
            sandboxes.asReversed().forEach(AndroidCredentialTestSandbox::close)
            sandboxes.clear()
        }
    }

    @Test fun createRefreshBootstrapAndReopenPreserveExactIncarnationAndSecrets() = runBlocking {
        val store = openStore()
        val empty = store.state().credentialValue()
        assertEquals(1L, empty.revision)
        assertNull(empty.owner)
        assertNull(empty.incarnation)
        assertNull(store.read(ACCOUNT).credentialValue())
        val created = store.create(empty.revision, account()).credentialValue()
        assertEquals(2L, created.revision)
        assertTrue(created.incarnation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        val refreshed = store.replace(created, account(access = "private-rotated-access", refresh = "private-rotated-refresh")).credentialValue()
        assertEquals(3L, refreshed.revision)
        assertEquals(created.incarnation, refreshed.incarnation)
        val bootstrapped = store.attachDeviceSession(refreshed, SecretText(DEVICE.uppercase())).credentialValue()
        assertEquals(4L, bootstrapped.revision)
        assertEquals(created.incarnation, bootstrapped.incarnation)
        assertAccount(bootstrapped, "private-rotated-access", "private-rotated-refresh", DEVICE.uppercase())
        assertEquals(3, sandbox.aliases().size)
        for (alias in sandbox.aliases()) assertNull(sandbox.keyStore().getKey(alias, null).encoded)
        assertFalse(bootstrapped.toString().contains("private-rotated"))
        assertPrivateModes()
        closeStore(store)
        assertNoPlaintext(listOf("private-rotated-access", "private-rotated-refresh", ACCOUNT.actorId, created.incarnation, DEVICE.uppercase()))

        val reopened = openStore()
        val actual = reopened.read(ACCOUNT).credentialValue()
        assertNotNull(actual)
        assertEquals(4L, actual!!.revision)
        assertEquals(created.incarnation, actual.incarnation)
        assertAccount(actual, "private-rotated-access", "private-rotated-refresh", DEVICE.uppercase())
        assertEquals(ACCOUNT, reopened.state().credentialValue().owner)
    }

    @Test fun exactRetirementErasesKeyAndOldRetirementCannotTouchNewSameAccountLogin() = runBlocking {
        val firstStore = openStore()
        val old = firstStore.create(1, account()).credentialValue()
        val oldAlias = sandbox.credentialAliases().single()
        val aliasesBefore = sandbox.aliases()
        // Different tuple components cannot authorize the selected account's destructive key path.
        for (other in listOf(ACCOUNT.copy(environment = "other-environment"), ACCOUNT.copy(actorId = "other-owner"),
            ACCOUNT.copy(actorKind = ActorKind.GUEST))) {
            firstStore.retire(other, old.incarnation).credentialValue()
            assertEquals(aliasesBefore, sandbox.aliases())
            assertEquals(old.incarnation, firstStore.read(ACCOUNT).credentialValue()!!.incarnation)
        }
        val malformedScope = ACCOUNT.copy(actorId = "private-${0xD800.toChar()}")
        assertRejected(firstStore.retire(malformedScope, old.incarnation))
        assertEquals(aliasesBefore, sandbox.aliases())
        assertEquals(old.incarnation, firstStore.read(ACCOUNT).credentialValue()!!.incarnation)
        firstStore.retire(ACCOUNT, old.incarnation).credentialValue()
        assertFalse("Exact credential key must be physically absent from Keystore", sandbox.keyStore().containsAlias(oldAlias))
        val empty = firstStore.state().credentialValue()
        assertEquals(3L, empty.revision)
        assertNull(empty.owner)
        assertNull(firstStore.read(ACCOUNT).credentialValue())
        closeStore(firstStore)

        val reopened = openStore()
        assertEquals(3L, reopened.state().credentialValue().revision)
        val fresh = reopened.create(3, account(access = "new-login-access", refresh = "new-login-refresh")).credentialValue()
        assertEquals(4L, fresh.revision)
        assertNotEquals(old.incarnation, fresh.incarnation)
        val newAliases = sandbox.aliases()
        assertFalse(oldAlias in newAliases)
        reopened.retire(ACCOUNT, old.incarnation).credentialValue()
        assertEquals(newAliases, sandbox.aliases())
        assertEquals(fresh.incarnation, reopened.read(ACCOUNT).credentialValue()!!.incarnation)
        assertAccount(reopened.read(ACCOUNT).credentialValue()!!, "new-login-access", "new-login-refresh", null)
        assertFailure(FailureReason.CONFLICT, reopened.create(1, account()))
        assertFailure(FailureReason.CONFLICT, reopened.replace(old, account(access = "stale-access")))
    }

    @Test fun oneActiveSlotAndExactRevisionCasRejectOtherLoginAndCompetingRefresh() = runBlocking {
        val store = openStore()
        val created = store.create(1, account()).credentialValue()
        assertFailure(FailureReason.CONFLICT, store.create(2, guest()))
        assertFailure(FailureReason.CONFLICT, store.create(2, account(scope = ACCOUNT.copy(actorId = "other-private-owner"))))
        val results = listOf("first-refresh", "second-refresh").map { access ->
            async { store.replace(created, account(access = access)) }
        }.awaitAll()
        assertEquals(1, results.count { it is PortResult.Value })
        assertEquals(1, results.count { it is PortResult.Failure && it.reason == FailureReason.CONFLICT })
        assertEquals(3L, store.state().credentialValue().revision)
        assertEquals(created.incarnation, store.read(ACCOUNT).credentialValue()!!.incarnation)
    }

    @Test fun guestCredentialsRemainGuestAcrossReopenAndCannotAcceptDeviceBootstrap() = runBlocking {
        val first = openStore()
        val created = first.create(1, guest()).credentialValue()
        assertRejected(first.attachDeviceSession(created, SecretText(DEVICE)))
        assertRejected(first.replace(created, account()))
        assertFailure(FailureReason.INVALID_DATA, first.replace(created, guest().copy(guestSessionId = SecretText(OTHER_DEVICE))))
        assertNull(first.read(ACCOUNT).credentialValue())
        closeStore(first)
        val reopened = openStore()
        val actual = reopened.read(GUEST).credentialValue()!!
        val credentials = actual.credentials as StoredCredentials.Guest
        assertEquals(created.incarnation, actual.incarnation)
        assertEquals(2L, actual.revision)
        assertEquals(GUEST_SESSION, credentials.guestSessionId.use { it })
        assertEquals("private-guest-token", credentials.guestToken.use { it })
        assertEquals(EXPIRY, credentials.expiresAtMillis)
    }

    @Test fun refreshCannotChangeOwnerKindOrBootstrappedDeviceAndAttachmentIsExactCas() = runBlocking {
        val store = openStore()
        val created = store.create(1, account()).credentialValue()
        assertRejected(store.replace(created, account(scope = ACCOUNT.copy(environment = "other-environment"))))
        assertRejected(store.replace(created, account(scope = ACCOUNT.copy(actorId = "other-owner"))))
        assertRejected(store.replace(created, guest()))
        assertRejected(store.replace(created, account(device = DEVICE)))
        val forgedExpected = CredentialSnapshot(created.incarnation, created.revision, account(device = DEVICE))
        assertRejected(store.replace(forgedExpected, account(device = DEVICE)))
        assertRejected(store.attachDeviceSession(created, SecretText("not-a-device-uuid")))
        assertEquals(2L, store.state().credentialValue().revision)
        val attached = store.attachDeviceSession(created, SecretText(DEVICE)).credentialValue()
        assertFailure(FailureReason.CONFLICT, store.attachDeviceSession(created, SecretText(DEVICE)))
        assertRejected(store.attachDeviceSession(attached, SecretText(OTHER_DEVICE)))
        assertRejected(store.replace(attached, account(device = null)))
        assertRejected(store.replace(attached, account(device = OTHER_DEVICE)))
        assertAccount(store.read(ACCOUNT).credentialValue()!!, ACCESS, REFRESH, DEVICE)
        assertEquals(3L, store.state().credentialValue().revision)
    }

    @Test fun duplicateOpenRespectsLifetimeLockAndCloseAllowsExactReopen() = runBlocking {
        val first = openStore()
        val snapshot = first.create(1, account()).credentialValue()
        assertFailure(FailureReason.STORAGE_FAILURE, openResult())
        assertEquals(snapshot.incarnation, first.read(ACCOUNT).credentialValue()!!.incarnation)
        closeStore(first)
        assertEquals(snapshot.incarnation, openStore().read(ACCOUNT).credentialValue()!!.incarnation)
    }

    @Test fun ownedLockDescriptorIsClosedAfterEveryOpenAndDuplicateFailureCycle() = runBlocking {
        assertEquals(0, ownLockDescriptorCount())
        repeat(4) {
            val store = openStore()
            assertEquals(1, ownLockDescriptorCount())
            assertFailure(FailureReason.STORAGE_FAILURE, openResult())
            assertEquals(1, ownLockDescriptorCount())
            closeStore(store)
            assertEquals("The borrowed stream must not leave its Os.open descriptor alive", 0, ownLockDescriptorCount())
        }
    }

    @Test fun missingInstallKeysNeverRecreateOrRebindExistingCredentialFiles() = runBlocking {
        for (suffix in listOf("index", "manifest")) {
            sandbox = newSandbox()
            val first = openStore()
            first.create(1, account()).credentialValue()
            closeStore(first)
            val filesBefore = fileContents()
            val missing = "${sandbox.keyPrefix}.$suffix"
            sandbox.keyStore().deleteEntry(missing)
            val aliasesBefore = sandbox.aliases()
            repeat(2) {
                assertFailure(FailureReason.STORAGE_FAILURE, openResult())
                assertFalse(sandbox.keyStore().containsAlias(missing))
                assertEquals(aliasesBefore, sandbox.aliases())
                assertFilesEqual(filesBefore)
            }
        }
    }

    @Test fun missingActiveKeyOrBlobBlocksReadsButExactRetirementCanFinish() = runBlocking {
        for (damage in listOf("key", "blob")) {
            sandbox = newSandbox()
            val first = openStore()
            val snapshot = first.create(1, account()).credentialValue()
            val alias = sandbox.credentialAliases().single()
            val blob = credentialFile()
            closeStore(first)
            if (damage == "key") sandbox.keyStore().deleteEntry(alias) else Files.delete(blob.toPath())
            val reopened = openStore()
            assertEquals(snapshot.incarnation, reopened.state().credentialValue().incarnation)
            assertFailure(FailureReason.STORAGE_FAILURE, reopened.read(ACCOUNT))
            assertFailure(FailureReason.STORAGE_FAILURE, reopened.replace(snapshot, account(access = "must-not-repair")))
            reopened.retire(ACCOUNT, snapshot.incarnation).credentialValue()
            assertFalse(sandbox.keyStore().containsAlias(alias))
            assertEquals(3L, reopened.state().credentialValue().revision)
            assertNull(reopened.state().credentialValue().owner)
            assertNull(reopened.read(ACCOUNT).credentialValue())
            closeStore(reopened)
        }
    }

    @Test fun missingOrCorruptManifestFailsClosedWithoutResettingKeysOrFiles() = runBlocking {
        for (damage in listOf("missing", "corrupt")) {
            sandbox = newSandbox()
            val first = openStore()
            first.create(1, account()).credentialValue()
            closeStore(first)
            val file = manifestFile()
            if (damage == "missing") Files.delete(file.toPath())
            else {
                val changed = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                writePrivateFile(file, changed)
            }
            val filesBefore = fileContents()
            val aliasesBefore = sandbox.aliases()
            repeat(2) {
                assertFailure(FailureReason.STORAGE_FAILURE, openResult())
                assertEquals(aliasesBefore, sandbox.aliases())
                assertFilesEqual(filesBefore)
            }
        }
    }

    @Test fun preexistingEmptyDirectoryOrLostRetiredSlotCannotResetToInitialRevision() = runBlocking {
        for (previouslyRetired in listOf(false, true)) {
            sandbox = newSandbox()
            if (previouslyRetired) {
                val first = openStore()
                val snapshot = first.create(1, account()).credentialValue()
                first.retire(ACCOUNT, snapshot.incarnation).credentialValue()
                assertEquals(3L, first.state().credentialValue().revision)
                closeStore(first)
                Files.delete(manifestFile().toPath())
                val keyStore = sandbox.keyStore()
                sandbox.aliases().forEach(keyStore::deleteEntry)
                assertEquals(setOf("credentials.lock"), sandbox.directory.list()!!.toSet())
            } else {
                // The opener did not create this directory. Absence of keys is not fresh-install proof.
                Os.mkdir(sandbox.directory.path, 448)
                assertTrue(sandbox.directory.list()!!.isEmpty())
            }
            assertTrue(sandbox.aliases().isEmpty())
            repeat(2) {
                // No usable store is returned, so a previously captured create(1, ...) cannot run.
                assertFailure(FailureReason.STORAGE_FAILURE, openResult())
                assertFalse(manifestFile().exists())
                assertTrue("Existing directory must not authorize replacement install keys", sandbox.aliases().isEmpty())
                assertTrue(sandbox.directory.list()!!.all { it == "credentials.lock" })
            }
        }
    }

    @Test fun corruptedCredentialCiphertextCannotBeOverwrittenByRefresh() = runBlocking {
        val first = openStore()
        val snapshot = first.create(1, account()).credentialValue()
        val blob = credentialFile()
        closeStore(first)
        val changed = blob.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        writePrivateFile(blob, changed)
        val reopened = openStore()
        assertFailure(FailureReason.STORAGE_FAILURE, reopened.read(ACCOUNT))
        assertFailure(FailureReason.STORAGE_FAILURE, reopened.replace(snapshot, account(access = "must-not-overwrite-corruption")))
        assertArrayEquals(changed, blob.readBytes())
        assertEquals(2L, reopened.state().credentialValue().revision)
    }

    @Test fun symlinkDirectoryCannotRedirectCredentialFilesOrModifyItsTarget() = runBlocking {
        val target = newSandbox()
        Os.mkdir(target.directory.path, 448)
        val marker = File(target.directory, "test-owned-marker")
        val expected = "untouched test directory target".encodeToByteArray()
        writePrivateFile(marker, expected)
        Os.symlink(target.directory.path, sandbox.directory.path)
        assertFailure(FailureReason.STORAGE_FAILURE, openResult())
        assertTrue(Files.isSymbolicLink(sandbox.directory.toPath()))
        assertArrayEquals(expected, marker.readBytes())
        assertEquals(listOf(marker.name), target.directory.listFiles()!!.map { it.name })
        assertTrue(sandbox.aliases().isEmpty())
    }

    @Test fun symlinkManifestAndCredentialBlobAreRejectedWithoutChangingTargets() = runBlocking {
        for (kind in listOf("manifest", "blob")) {
            sandbox = newSandbox()
            val first = openStore()
            first.create(1, account()).credentialValue()
            val original = if (kind == "manifest") manifestFile() else credentialFile()
            closeStore(first)
            val target = newSandbox()
            Os.mkdir(target.directory.path, 448)
            val targetFile = File(target.directory, "test-owned-ciphertext-target")
            val expected = original.readBytes()
            writePrivateFile(targetFile, expected)
            Files.delete(original.toPath())
            Os.symlink(targetFile.path, original.path)
            val aliasesBefore = sandbox.aliases()
            assertFailure(FailureReason.STORAGE_FAILURE, openResult())
            assertTrue(Files.isSymbolicLink(original.toPath()))
            assertArrayEquals(expected, targetFile.readBytes())
            assertEquals(aliasesBefore, sandbox.aliases())
        }
    }

    @Test fun hardlinksAreDeniedByPlatformOrRejectedByStoreWithoutChangingCredentials() = runBlocking {
        for (kind in listOf("manifest", "blob")) {
            sandbox = newSandbox()
            val first = openStore()
            val snapshot = first.create(1, account()).credentialValue()
            val original = if (kind == "manifest") manifestFile() else credentialFile()
            closeStore(first)
            val target = newSandbox()
            Os.mkdir(target.directory.path, 448)
            val targetFile = File(target.directory, "test-owned-hardlink-target")
            val expected = original.readBytes()
            val aliasesBefore = sandbox.aliases()
            val filesBefore = fileContents()
            val deniedErrno = try {
                Os.link(original.path, targetFile.path)
                null
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EACCES && error.errno != OsConstants.EPERM) throw error
                error.errno
            }
            val branch = if (deniedErrno != null) {
                // This platform prevents construction of the adversarial inode. That is evidence
                // of platform denial, not proof that the store exercised its existing-link guard.
                assertFalse(targetFile.exists())
                assertEquals(1L, Os.lstat(original.path).st_nlink)
                assertArrayEquals(expected, original.readBytes())
                assertEquals(aliasesBefore, sandbox.aliases())
                val reopened = openStore()
                val restored = reopened.read(ACCOUNT).credentialValue()!!
                assertEquals(snapshot.incarnation, restored.incarnation)
                assertEquals(snapshot.revision, restored.revision)
                assertAccount(restored, ACCESS, REFRESH, null)
                closeStore(reopened)
                assertFilesEqual(filesBefore)
                assertEquals(aliasesBefore, sandbox.aliases())
                "platform-denied:$deniedErrno; existing-hardlink-branch-unexercised"
            } else {
                assertEquals(2L, Os.lstat(original.path).st_nlink)
                assertFailure(FailureReason.STORAGE_FAILURE, openResult())
                assertArrayEquals(expected, targetFile.readBytes())
                assertArrayEquals(expected, original.readBytes())
                assertEquals(aliasesBefore, sandbox.aliases())
                "existing-hardlink-rejected-by-store"
            }
            // Emit only this non-secret branch label into the final instrumentation result bundle;
            // do not add status events that could masquerade as additional passing tests.
            InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
                putString("credential_hardlink_$kind", branch)
            })
        }
    }

    @Test fun permissiveDirectoryAndManifestModesFailInsteadOfSilentlyRepairingThem() = runBlocking {
        for (kind in listOf("directory", "manifest")) {
            sandbox = newSandbox()
            val first = openStore()
            first.create(1, account()).credentialValue()
            closeStore(first)
            val changed = if (kind == "directory") sandbox.directory else manifestFile()
            val mode = if (kind == "directory") 493 else 420 // 0755 / 0644
            Os.chmod(changed.path, mode)
            val aliasesBefore = sandbox.aliases()
            assertFailure(FailureReason.STORAGE_FAILURE, openResult())
            assertEquals(mode, Os.lstat(changed.path).st_mode and 511)
            assertEquals(aliasesBefore, sandbox.aliases())
        }
    }

    @Test fun createAcknowledgementLossAfterManifestRenameReopensExactCommittedIncarnation() = runBlocking {
        var armed = false
        val store = openStore { point ->
            if (armed && point == CredentialFileFaultPoint.AFTER_MANIFEST_RENAME) {
                armed = false
                throw IOException("test-owned post-rename failure")
            }
        }
        armed = true
        assertFailure(FailureReason.OUTCOME_UNKNOWN, store.create(1, account()))
        assertFailure(FailureReason.STORAGE_FAILURE, store.state())
        val aliasesAfterCommit = sandbox.aliases()
        closeStore(store)
        val reopened = openStore()
        val committed = reopened.read(ACCOUNT).credentialValue()!!
        assertEquals(2L, committed.revision)
        assertAccount(committed, ACCESS, REFRESH, null)
        assertEquals(committed.incarnation, reopened.state().credentialValue().incarnation)
        assertFailure(FailureReason.CONFLICT, reopened.create(1, account(access = "must-not-duplicate")))
        assertEquals(aliasesAfterCommit, sandbox.aliases())
        assertEquals(committed.incarnation, reopened.read(ACCOUNT).credentialValue()!!.incarnation)
    }

    @Test fun retirementAcknowledgementLossOccursOnlyAfterKeyErasureAndReopensEmpty() = runBlocking {
        var armed = false
        val store = openStore { point ->
            if (armed && point == CredentialFileFaultPoint.AFTER_MANIFEST_RENAME) {
                armed = false
                throw IOException("test-owned retirement acknowledgement loss")
            }
        }
        val created = store.create(1, account()).credentialValue()
        val alias = sandbox.credentialAliases().single()
        armed = true
        assertFailure(FailureReason.OUTCOME_UNKNOWN, store.retire(ACCOUNT, created.incarnation))
        assertFalse(sandbox.keyStore().containsAlias(alias))
        assertFailure(FailureReason.STORAGE_FAILURE, store.read(ACCOUNT))
        closeStore(store)
        val reopened = openStore()
        assertEquals(3L, reopened.state().credentialValue().revision)
        assertNull(reopened.state().credentialValue().owner)
        reopened.retire(ACCOUNT, created.incarnation).credentialValue()
        assertNull(reopened.read(ACCOUNT).credentialValue())
        assertFalse(sandbox.keyStore().containsAlias(alias))
    }

    @Test fun refreshInterruptionLeavesOrphanEvidenceAsExplicitRepairGate() = runBlocking {
        for (failurePoint in listOf(CredentialFileFaultPoint.AFTER_BLOB_RENAME, CredentialFileFaultPoint.AFTER_MANIFEST_RENAME)) {
            sandbox = newSandbox()
            var armed = false
            val store = openStore { point ->
                if (armed && point == failurePoint) {
                    armed = false
                    throw IOException("test-owned interrupted refresh")
                }
            }
            val original = store.create(1, account()).credentialValue()
            armed = true
            assertFailure(FailureReason.OUTCOME_UNKNOWN, store.replace(original, account(access = "interrupted-refresh-access")))
            assertFailure(FailureReason.STORAGE_FAILURE, store.state())
            closeStore(store)
            val filesBefore = fileContents()
            val aliasesBefore = sandbox.aliases()
            repeat(2) {
                assertFailure(FailureReason.STORAGE_FAILURE, openResult())
                assertEquals(aliasesBefore, sandbox.aliases())
                assertFilesEqual(filesBefore)
            }
        }
    }

    private fun newSandbox() = AndroidCredentialTestSandbox().also { sandboxes += it }
    private suspend fun openStore(faultInjector: (CredentialFileFaultPoint) -> Unit = {}) = openResult(faultInjector).credentialValue()
    private suspend fun openResult(faultInjector: (CredentialFileFaultPoint) -> Unit = {}): PortResult<AndroidCredentialStore> =
        AndroidCredentialStore.openForTests(sandbox.directory, sandbox.keyPrefix, faultInjector).also {
            if (it is PortResult.Value) opened += it.value
        }
    private suspend fun closeStore(store: AndroidCredentialStore) { store.close().credentialValue(); opened.remove(store) }

    private fun manifestFile() = File(sandbox.directory, "manifest.bin")
    private fun credentialFile(): File = sandbox.directory.listFiles()!!.single { it.name.matches(Regex("[0-9a-f]{64}\\.[1-9][0-9]*\\.bin")) }
    private fun fileContents() = sandbox.directory.listFiles()!!.filter(File::isFile).associate { it.name to it.readBytes() }
    private fun assertFilesEqual(expected: Map<String, ByteArray>) {
        val actual = fileContents()
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, actual[name]) }
    }
    private fun writePrivateFile(file: File, bytes: ByteArray) { file.writeBytes(bytes); Os.chmod(file.path, 384) }

    private fun ownLockDescriptorCount(): Int {
        val exactPath = File(sandbox.directory.canonicalFile, "credentials.lock").path
        val descriptors = File("/proc/self/fd").listFiles()
        assertNotNull("Expected this test process's descriptor directory", descriptors)
        // Inspect only for equality to this exact test-owned path. Never retain or print unrelated
        // descriptor targets, and tolerate an unrelated descriptor disappearing during enumeration.
        return descriptors!!.count { descriptor ->
            try { Os.readlink(descriptor.path) == exactPath } catch (_: Exception) { false }
        }
    }

    private fun assertPrivateModes() {
        assertEquals(448, Os.lstat(sandbox.directory.path).st_mode and 511)
        sandbox.directory.listFiles()!!.forEach { file -> assertEquals(384, Os.lstat(file.path).st_mode and 511) }
    }

    private fun assertNoPlaintext(markers: List<String>) {
        sandbox.directory.listFiles()!!.filter(File::isFile).forEach { file ->
            val bytes = file.readBytes()
            for (marker in markers) for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                val needle = marker.toByteArray(encoding)
                assertFalse("Private credential material must not appear in native files", bytes.indices.any { start ->
                    start + needle.size <= bytes.size && needle.indices.all { bytes[start + it] == needle[it] }
                })
            }
        }
    }

    private fun assertAccount(snapshot: CredentialSnapshot, access: String, refresh: String?, device: String?) {
        val account = snapshot.credentials as StoredCredentials.Account
        assertEquals(ACCOUNT, account.scope)
        assertEquals(access, account.accessToken.use { it })
        assertEquals(refresh, account.refreshToken?.use { it })
        assertEquals(device, account.deviceSessionId?.use { it })
        assertEquals(EXPIRY, account.expiresAtMillis)
    }

    private fun assertFailure(reason: FailureReason, result: PortResult<*>) = assertEquals(PortResult.Failure(reason), result)
    private fun assertRejected(result: PortResult<*>) = assertTrue("Invalid or conflicting credential operation must fail", result is PortResult.Failure)

    companion object {
        private val ACCOUNT = StorageScope("credential-instrumented", ActorKind.ACCOUNT, "private-account-marker-7e24c8")
        private val GUEST = StorageScope("credential-instrumented", ActorKind.GUEST, "private-guest-marker-7e24c8")
        private const val DEVICE = "123e4567-e89b-12d3-a456-426614174abc"
        private const val OTHER_DEVICE = "123e4567-e89b-12d3-a456-426614174abd"
        private const val GUEST_SESSION = "123e4567-e89b-12d3-a456-426614174abe"
        private const val ACCESS = "private-access-marker-7e24c8"
        private const val REFRESH = "private-refresh-marker-7e24c8"
        private const val EXPIRY = 1_800_000_000_000L
        private fun account(scope: StorageScope = ACCOUNT, access: String = ACCESS, refresh: String? = REFRESH, device: String? = null) =
            StoredCredentials.Account(scope, SecretText(access), refresh?.let(::SecretText), EXPIRY, device?.let(::SecretText))
        private fun guest() = StoredCredentials.Guest(GUEST, SecretText(GUEST_SESSION), SecretText("private-guest-token"), EXPIRY)
    }
}
