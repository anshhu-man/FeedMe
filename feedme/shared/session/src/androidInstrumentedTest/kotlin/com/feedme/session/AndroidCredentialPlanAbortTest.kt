package com.feedme.session

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedme.core.ports.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Borrowed, already-open native credential owner only. No legacy recovery coordinator, provider,
 * consent inference, cross-store ordering or factory-recovery claim is made by these components.
 * Fault callbacks model interruption/acknowledgement loss, not physical power loss.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCredentialPlanAbortTest {
    private val boxes = mutableListOf<AndroidCredentialTestSandbox>()
    private val stores = mutableListOf<AndroidCredentialStore>()

    @After fun cleanup() = runBlocking {
        stores.asReversed().forEach { it.close().credentialValue() }
        stores.clear()
        boxes.asReversed().forEach(AndroidCredentialTestSandbox::close)
        boxes.clear()
    }

    @Test fun preparedAccountAndGuestAbortConsumePlanWithoutClosingParentOrAllocatingKeys() = runBlocking {
        for (credentials in listOf(account(), guest())) {
            val box = sandbox(); val store = open(box)
            val plan = store.planCreate(1, credentials).credentialValue()
            val aliases = box.aliases()
            val capability: CredentialCreatePlanAbort = store
            capability.abortPlannedCreate(credentials.scope, plan).credentialValue()
            assertEmpty(store, 3)
            assertEquals(CredentialCreateRecoveryStatus.ABORTED, status(store, credentials.scope, plan))
            assertEquals(aliases, box.aliases())
            assertEquals(setOf(AndroidCredentialFiles.LOCK, AndroidCredentialFiles.MANIFEST), files(box).keys)
            assertEquals(1, lockDescriptors(box))
            failure(AndroidCredentialStore.openForTests(box.directory, box.keyPrefix), FailureReason.STORAGE_FAILURE)
            assertEquals(1, lockDescriptors(box))
            val consumed = files(box)
            repeat(2) { capability.abortPlannedCreate(credentials.scope, plan).credentialValue() }
            assertEmpty(store, 3); assertFiles(consumed, files(box)); assertEquals(aliases, box.aliases())
            failure(store.commitPlannedCreate(plan, credentials), FailureReason.CONFLICT)
        }
    }

    @Test fun selectedAbortDeletesOnlyExactIncarnationAndPreservesIndependentNativeOwner() = runBlocking {
        val box = sandbox(); val store = open(box)
        val siblingBox = sandbox(); val sibling = open(siblingBox)
        val plan = store.planCreate(1, account()).credentialValue()
        store.commitPlannedCreate(plan, account()).credentialValue()
        val siblingPlan = sibling.planCreate(1, account()).credentialValue()
        sibling.commitPlannedCreate(siblingPlan, account()).credentialValue()
        val siblingFiles = files(siblingBox); val siblingAliases = siblingBox.aliases()
        val aliases = box.aliases()
        store.abortPlannedCreate(ACCOUNT, plan).credentialValue()
        assertEmpty(store, 3); assertEquals(CredentialCreateRecoveryStatus.ABORTED, status(store, ACCOUNT, plan))
        assertEquals(aliases - alias(box, plan), box.aliases())
        assertFalse(File(box.directory, blob(plan)).exists())
        assertFiles(siblingFiles, files(siblingBox)); assertEquals(siblingAliases, siblingBox.aliases())
        assertEquals(CredentialCreateRecoveryStatus.SELECTED, status(sibling, ACCOUNT, siblingPlan))
        assertEquals(1, lockDescriptors(box)); assertEquals(1, lockDescriptors(siblingBox))
    }

    @Test fun wrongScopeDemoAndMalformedUnicodeCannotAuthorizeAnyExactDeletion() = runBlocking {
        val box = sandbox(); val store = open(box)
        val plan = store.planCreate(1, account()).credentialValue()
        store.commitPlannedCreate(plan, account()).credentialValue()
        val before = files(box); val aliases = box.aliases()
        for (scope in listOf(ACCOUNT.copy(environment = "another-environment"), ACCOUNT.copy(actorId = "another-owner"),
            ACCOUNT.copy(actorKind = ActorKind.GUEST), ACCOUNT.copy(actorKind = ActorKind.DEMO),
            ACCOUNT.copy(environment = "bad-${0xD800.toChar()}"), ACCOUNT.copy(actorId = "bad-${0xDFFF.toChar()}"))) {
            failure(store.abortPlannedCreate(scope, plan), FailureReason.INVALID_DATA)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
        assertEquals(CredentialCreateRecoveryStatus.SELECTED, status(store, ACCOUNT, plan))
    }

    @Test fun alteredAndForeignInstallPlansCannotConsumeASelectedNativeSlot() = runBlocking {
        val box = sandbox(); val store = open(box)
        val plan = store.planCreate(1, account()).credentialValue(); val record = decode(plan)
        store.commitPlannedCreate(plan, account()).credentialValue()
        val before = files(box); val aliases = box.aliases()
        for (changed in listOf(record.copy(expectedSlotRevision = 2), record.copy(target = flip(record.target)),
            record.copy(payloadMac = flip(record.payloadMac)), record.copy(authenticationMac = flip(record.authenticationMac)),
            record.copy(incarnation = "00000000-0000-4000-8000-000000000999"))) {
            failure(store.abortPlannedCreate(ACCOUNT, CredentialCreatePlan.create(changed)), FailureReason.INVALID_DATA)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
        val foreignBox = sandbox(); val foreign = open(foreignBox)
        val foreignPlan = foreign.planCreate(1, account()).credentialValue()
        foreign.commitPlannedCreate(foreignPlan, account()).credentialValue()
        val foreignFiles = files(foreignBox); val foreignAliases = foreignBox.aliases()
        failure(foreign.abortPlannedCreate(ACCOUNT, plan), FailureReason.INVALID_DATA)
        assertFiles(foreignFiles, files(foreignBox)); assertEquals(foreignAliases, foreignBox.aliases())
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
    }

    @Test fun refreshedAndNewerSelectionsPreserveTheirSharedOrReplacementKey() = runBlocking {
        val box = sandbox(); val store = open(box)
        val plan = store.planCreate(1, account()).credentialValue()
        val snapshot = store.commitPlannedCreate(plan, account()).credentialValue()
        store.replace(snapshot, account("replacement-access-fixture")).credentialValue()
        var before = files(box); var aliases = box.aliases()
        failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.CONFLICT)
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        assertTrue(alias(box, plan) in box.aliases())
        store.retire(ACCOUNT, snapshot.incarnation).credentialValue()
        val newer = store.planCreate(store.state().credentialValue().revision, account()).credentialValue()
        store.commitPlannedCreate(newer, account()).credentialValue()
        before = files(box); aliases = box.aliases()
        failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.CONFLICT)
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        assertEquals(CredentialCreateRecoveryStatus.SELECTED, status(store, ACCOUNT, newer))
    }

    @Test fun missingKeyOrMissingCorruptAndEmptySelectedBlobRequireNoCredentialReads() = runBlocking {
        for (damage in listOf("key", "blob", "corrupt", "empty")) {
            val box = sandbox(); val store = open(box)
            val plan = store.planCreate(1, account()).credentialValue()
            store.commitPlannedCreate(plan, account()).credentialValue()
            when (damage) {
                "key" -> box.keyStore().deleteEntry(alias(box, plan))
                "blob" -> assertTrue(File(box.directory, blob(plan)).delete())
                "corrupt" -> writePrivate(File(box.directory, blob(plan)), ByteArray(29))
                else -> writePrivate(File(box.directory, blob(plan)), byteArrayOf())
            }
            failure(store.read(ACCOUNT), FailureReason.STORAGE_FAILURE)
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, status(store, ACCOUNT, plan))
            store.abortPlannedCreate(ACCOUNT, plan).credentialValue()
            assertEmpty(store, 3); assertEquals(CredentialCreateRecoveryStatus.ABORTED, status(store, ACCOUNT, plan))
            assertTrue(box.credentialAliases().isEmpty())
            assertEquals(setOf(AndroidCredentialFiles.LOCK, AndroidCredentialFiles.MANIFEST), files(box).keys)
        }
    }

    @Test fun exactPartialKeyAndMalformedOwnedTempsAreConsumedBeforeCleanup() = runBlocking {
        for (partial in listOf("key", "blob", "blob-temp", "create-temp", "abort-temp")) {
            val box = sandbox(); val store = open(box)
            val plan = store.planCreate(1, account()).credentialValue(); val record = decode(plan)
            // Exact test-owned artifacts model interrupted CREATE inventory without poisoning
            // this parent. Arbitrary bytes must not be decrypted or treated as credential proof.
            AndroidCredentialVault.open(box.keyPrefix, initialize = false).createIncarnationKey(record.target)
            when (partial) {
                "blob" -> writePrivate(File(box.directory, blob(plan)), ByteArray(29))
                "blob-temp" -> writePrivate(File(box.directory, blob(plan) + ".pending"), byteArrayOf())
                "create-temp" -> writePrivate(File(box.directory, AndroidCredentialFiles.createManifestPending(record.incarnation)), ByteArray(29))
                "abort-temp" -> writePrivate(File(box.directory, AndroidCredentialFiles.abortManifestPending(record.incarnation)), byteArrayOf())
            }
            assertEquals(if (partial == "abort-temp") CredentialCreateRecoveryStatus.ABORTING
                else CredentialCreateRecoveryStatus.PARTIAL, status(store, ACCOUNT, plan))
            store.abortPlannedCreate(ACCOUNT, plan).credentialValue()
            assertEmpty(store, 3); assertEquals(CredentialCreateRecoveryStatus.ABORTED, status(store, ACCOUNT, plan))
            assertTrue(box.credentialAliases().isEmpty())
            assertEquals(setOf(AndroidCredentialFiles.LOCK, AndroidCredentialFiles.MANIFEST), files(box).keys)
        }
    }

    @Test fun unknownInventoryAndOtherIncarnationKeysAreNeverAdoptedOrCleaned() = runBlocking {
        for (unknown in listOf("file", "key")) {
            val box = sandbox(); val store = open(box)
            val plan = store.planCreate(1, account()).credentialValue()
            store.commitPlannedCreate(plan, account()).credentialValue()
            if (unknown == "file") writePrivate(File(box.directory, "unknown.bin"), ByteArray(29))
            else AndroidCredentialVault.open(box.keyPrefix, initialize = false).createIncarnationKey(flip(decode(plan).target))
            val before = files(box); val aliases = box.aliases()
            failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            assertEquals(1, lockDescriptors(box))
        }
    }

    @Test fun poisonedAndClosedOwnersCannotBorrowAbortAuthorityOrReopenThemselves() = runBlocking {
        for (cancel in listOf(false, true)) {
            val box = sandbox(); val store = open(box) { point ->
                if (point == CredentialFileFaultPoint.AFTER_KEY_CREATE) {
                    if (cancel) throw CancellationException(PRIVATE_FAULT) else throw IOException(PRIVATE_FAULT)
                }
            }
            val plan = store.planCreate(1, account()).credentialValue()
            if (cancel) cancelled { store.commitPlannedCreate(plan, account()) }
            else failure(store.commitPlannedCreate(plan, account()), FailureReason.STORAGE_FAILURE)
            val before = files(box); val aliases = box.aliases()
            failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases()); assertEquals(1, lockDescriptors(box))
            close(store)
            failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            assertEquals(0, lockDescriptors(box)); assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            // Ordinary opening must still reject orphaned unselected material. This capability
            // does not silently substitute an existing-only factory or relax its inventory gate.
            failure(AndroidCredentialStore.openForTests(box.directory, box.keyPrefix), FailureReason.STORAGE_FAILURE)
            assertEquals(0, lockDescriptors(box)); assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
    }

    @Test fun selectedAndAlreadyAbortedRetriesRequireFreshDirectoryAcknowledgement() = runBlocking {
        for (alreadyAborted in listOf(false, true)) {
            val box = sandbox(); var armed = false; var syncs = 0
            var store = open(box) { point ->
                if (point == CredentialFileFaultPoint.BEFORE_DURABILITY_SYNC) {
                    syncs++; if (armed) throw IOException(PRIVATE_FAULT)
                }
            }
            val plan = store.planCreate(1, account()).credentialValue()
            store.commitPlannedCreate(plan, account()).credentialValue()
            if (alreadyAborted) store.abortPlannedCreate(ACCOUNT, plan).credentialValue()
            val before = files(box); val aliases = box.aliases(); val previousSyncs = syncs
            armed = true
            failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            assertEquals(previousSyncs + 1, syncs)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            assertEquals(previousSyncs + 1, syncs)
            close(store)
            store = open(box) { point -> if (point == CredentialFileFaultPoint.BEFORE_DURABILITY_SYNC) syncs++ }
            assertEquals(if (alreadyAborted) CredentialCreateRecoveryStatus.ABORTED
                else CredentialCreateRecoveryStatus.SELECTED, status(store, ACCOUNT, plan))
            store.abortPlannedCreate(ACCOUNT, plan).credentialValue()
            assertEquals(previousSyncs + 2, syncs)
            assertEmpty(store, 3); assertEquals(CredentialCreateRecoveryStatus.ABORTED, status(store, ACCOUNT, plan))
        }
    }

    @Test fun cancellationBeforeDispatchOrAfterExactKeyDeletionNeverReturnsSuccess() = runBlocking {
        val box = sandbox(); var armed = false
        var store = open(box) { point ->
            if (armed && point == CredentialFileFaultPoint.AFTER_KEY_DELETE) throw CancellationException(PRIVATE_FAULT)
        }
        val plan = store.planCreate(1, account()).credentialValue()
        store.commitPlannedCreate(plan, account()).credentialValue()
        val before = files(box); val aliases = box.aliases()
        val queued = async(start = CoroutineStart.LAZY) { store.abortPlannedCreate(ACCOUNT, plan) }
        queued.cancel(); cancelled { queued.await() }
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        armed = true
        cancelled { store.abortPlannedCreate(ACCOUNT, plan) }
        assertFalse(alias(box, plan) in box.aliases())
        assertTrue(File(box.directory, blob(plan)).exists())
        failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
        close(store); store = open(box)
        assertEquals(CredentialCreateRecoveryStatus.SELECTED, status(store, ACCOUNT, plan))
        store.abortPlannedCreate(ACCOUNT, plan).credentialValue()
        assertEmpty(store, 3); assertEquals(CredentialCreateRecoveryStatus.ABORTED, status(store, ACCOUNT, plan))
    }

    @Test fun lostConsumptionReceiptNeedsReopenedAcknowledgementAndCannotEraseSuccessor() = runBlocking {
        for (selected in listOf(false, true)) {
            val box = sandbox(); var armed = false
            var store = open(box) { point ->
                if (armed && point == CredentialFileFaultPoint.AFTER_MANIFEST_RENAME) throw IOException(PRIVATE_FAULT)
            }
            val plan = store.planCreate(1, account()).credentialValue()
            if (selected) store.commitPlannedCreate(plan, account()).credentialValue()
            armed = true
            failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.OUTCOME_UNKNOWN)
            failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            close(store); store = open(box)
            assertEquals(CredentialCreateRecoveryStatus.ABORTED, status(store, ACCOUNT, plan))
            store.abortPlannedCreate(ACCOUNT, plan).credentialValue()
            assertEmpty(store, 3)
            val newer = store.planCreate(3, account()).credentialValue()
            store.commitPlannedCreate(newer, account()).credentialValue()
            val before = files(box); val aliases = box.aliases()
            failure(store.abortPlannedCreate(ACCOUNT, plan), FailureReason.CONFLICT)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, status(store, ACCOUNT, newer))
        }
    }

    private fun sandbox() = AndroidCredentialTestSandbox().also(boxes::add)
    private suspend fun open(box: AndroidCredentialTestSandbox, fault: (CredentialFileFaultPoint) -> Unit = {}) =
        AndroidCredentialStore.openForTests(box.directory, box.keyPrefix, fault).credentialValue().also(stores::add)
    private suspend fun close(store: AndroidCredentialStore) { store.close().credentialValue(); stores.remove(store) }
    private fun decode(plan: CredentialCreatePlan) = CredentialCreatePlanCodec.decode(plan.copyForStorage())
    private fun blob(plan: CredentialCreatePlan) = decode(plan).let { AndroidCredentialFiles.blobName(it.target, it.snapshotRevision) }
    private fun alias(box: AndroidCredentialTestSandbox, plan: CredentialCreatePlan) = "${box.keyPrefix}.credential.${decode(plan).target}"
    private fun flip(value: String) = (if (value.first() == '0') "1" else "0") + value.drop(1)
    private suspend fun status(store: AndroidCredentialStore, scope: StorageScope, plan: CredentialCreatePlan) =
        store.inspectPlannedCreate(scope, plan).credentialValue().status
    private suspend fun assertEmpty(store: AndroidCredentialStore, revision: Long) {
        val slot = store.state().credentialValue()
        assertEquals(revision, slot.revision); assertNull(slot.owner); assertNull(slot.incarnation)
    }
    private fun writePrivate(file: File, bytes: ByteArray) { file.writeBytes(bytes); Os.chmod(file.path, 384) }
    private fun files(box: AndroidCredentialTestSandbox) = checkNotNull(box.directory.listFiles()).associate {
        // Never open another descriptor on this process's POSIX-lock inode.
        it.name to if (it.name == AndroidCredentialFiles.LOCK) { assertEquals(0L, it.length()); byteArrayOf() } else it.readBytes()
    }
    private fun assertFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual.getValue(name)) }
    }
    private fun failure(result: PortResult<*>, reason: FailureReason) {
        assertTrue(result is PortResult.Failure)
        assertEquals(reason, (result as PortResult.Failure).reason)
        assertNull(result.retryAfterSeconds)
        assertFalse(result.toString().contains(PRIVATE_FAULT))
    }
    private suspend fun cancelled(block: suspend () -> Any?) {
        var caught = false
        try { block() } catch (_: CancellationException) { caught = true }
        assertTrue(caught)
    }
    private fun lockDescriptors(box: AndroidCredentialTestSandbox): Int {
        val exact = File(box.directory.canonicalFile, AndroidCredentialFiles.LOCK).path
        return checkNotNull(File("/proc/self/fd").listFiles()).count {
            runCatching { Os.readlink(it.path) == exact }.getOrDefault(false)
        }
    }

    companion object {
        private const val PRIVATE_FAULT = "abort-private-native-fixture"
        private val ACCOUNT = StorageScope("native-plan-abort-test", ActorKind.ACCOUNT, "abort-owner")
        private val GUEST = StorageScope("native-plan-abort-test", ActorKind.GUEST, "abort-guest")
        private fun account(access: String = "abort-fixture-access") = StoredCredentials.Account(ACCOUNT,
            SecretText(access), SecretText("abort-fixture-refresh"), Long.MAX_VALUE,
            SecretText("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        private fun guest() = StoredCredentials.Guest(GUEST, SecretText("abort-fixture-session"),
            SecretText("abort-fixture-guest-token"), Long.MAX_VALUE)
    }
}
