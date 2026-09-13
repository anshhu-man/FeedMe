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

/** Native metadata only: no recovery factory, abort, provider, token-usability or durability claim. */
@RunWith(AndroidJUnit4::class)
class AndroidCredentialCreateInspectionTest {
    private val boxes = mutableListOf<AndroidCredentialTestSandbox>()
    private val stores = mutableListOf<AndroidCredentialStore>()

    @After fun cleanup() = runBlocking {
        stores.asReversed().forEach { it.close().credentialValue() }
        stores.clear()
        boxes.asReversed().forEach(AndroidCredentialTestSandbox::close)
        boxes.clear()
    }

    @Test fun observationFingerprintIsExactly32BytesDetachedAndRedacted() {
        for (size in listOf(0, 1, 31, 33, 64)) {
            var rejected = false
            try { CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.PREPARED, PrivateBytes(ByteArray(size))) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        val bytes = ByteArray(32) { it.toByte() }
        val value = CredentialCreatePlanObservation(CredentialCreateRecoveryStatus.SELECTED, PrivateBytes(bytes))
        bytes.fill(0)
        val copied = value.fingerprint.copyForCodec(); copied.fill(0)
        assertArrayEquals(ByteArray(32) { it.toByte() }, value.fingerprint.copyForCodec())
        assertEquals("CredentialCreatePlanObservation(<redacted>)", value.toString())
    }

    @Test fun preparedInspectionUsesOnlyExistingMetadataAndIsStableAcrossReopen() = runBlocking {
        for (credentials in listOf(account(), guest())) {
            val box = sandbox(); var store = open(box)
            val plan = store.planCreate(1, credentials).credentialValue()
            val before = files(box); val aliases = box.aliases()
            val observation = store.inspectPlannedCreate(credentials.scope, plan).credentialValue()
            assertEquals(CredentialCreateRecoveryStatus.PREPARED, observation.status)
            repeat(3) {
                assertObservation(observation, store.inspectPlannedCreate(credentials.scope, plan).credentialValue())
                assertEquals(1, lockDescriptors(box))
            }
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            close(store); assertEquals(0, lockDescriptors(box)); store = open(box)
            assertObservation(observation, store.inspectPlannedCreate(credentials.scope, plan).credentialValue())
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
    }

    @Test fun distinctPreparedPlansHaveDistinctFingerprintsInTheSameEmptySlot() = runBlocking {
        val box = sandbox(); val store = open(box)
        val first = store.planCreate(1, account()).credentialValue()
        val second = store.planCreate(1, account()).credentialValue()
        val before = files(box); val aliases = box.aliases()
        val a = store.inspectPlannedCreate(ACCOUNT, first).credentialValue()
        val b = store.inspectPlannedCreate(ACCOUNT, second).credentialValue()
        assertEquals(a.status, b.status); assertDifferent(a, b)
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
    }

    @Test fun selectionAndExactKeyOrBlobPresenceChangeFingerprintWithoutUsabilityClaim() = runBlocking {
        for (damage in listOf("key", "blob")) {
            val box = sandbox(); val store = open(box)
            val plan = store.planCreate(1, account()).credentialValue()
            val prepared = store.inspectPlannedCreate(ACCOUNT, plan).credentialValue()
            store.commitPlannedCreate(plan, account()).credentialValue()
            val selected = store.inspectPlannedCreate(ACCOUNT, plan).credentialValue()
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, selected.status); assertDifferent(prepared, selected)
            if (damage == "key") box.keyStore().deleteEntry(alias(box, plan))
            else assertTrue(File(box.directory, blob(plan)).delete())
            val before = files(box); val aliases = box.aliases()
            val damaged = store.inspectPlannedCreate(ACCOUNT, plan).credentialValue()
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, damaged.status); assertDifferent(selected, damaged)
            assertObservation(damaged, store.inspectPlannedCreate(ACCOUNT, plan).credentialValue())
            failure(store.read(ACCOUNT), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
    }

    @Test fun ciphertextContentsAreIntentionallyExcludedAndNeverReadByInspection() = runBlocking {
        for (damagedBytes in listOf(ByteArray(29), byteArrayOf())) {
            val box = sandbox(); val store = open(box)
            val plan = store.planCreate(1, account()).credentialValue()
            store.commitPlannedCreate(plan, account()).credentialValue()
            val selected = store.inspectPlannedCreate(ACCOUNT, plan).credentialValue()
            writePrivate(File(box.directory, blob(plan)), damagedBytes)
            val before = files(box); val aliases = box.aliases()
            assertObservation(selected, store.inspectPlannedCreate(ACCOUNT, plan).credentialValue())
            failure(store.read(ACCOUNT), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
    }

    @Test fun exactPlanArtifactsChangeMetadataFingerprintWithoutContentReads() = runBlocking {
        val box = sandbox(); val store = open(box)
        val plan = store.planCreate(1, account()).credentialValue()
        val record = decode(plan)
        val prepared = store.inspectPlannedCreate(ACCOUNT, plan).credentialValue()
        // Exact test-owned native names emulate interrupted CREATE artifacts; their bytes are
        // deliberately invalid. Observation must neither parse them nor normalize/repair them.
        writePrivate(File(box.directory, blob(plan) + ".pending"), byteArrayOf())
        val partial = store.inspectPlannedCreate(ACCOUNT, plan).credentialValue()
        assertEquals(CredentialCreateRecoveryStatus.PARTIAL, partial.status); assertDifferent(prepared, partial)
        writePrivate(File(box.directory, AndroidCredentialFiles.createManifestPending(record.incarnation)), ByteArray(29))
        val before = files(box); val aliases = box.aliases()
        val another = store.inspectPlannedCreate(ACCOUNT, plan).credentialValue()
        assertEquals(CredentialCreateRecoveryStatus.PARTIAL, another.status); assertDifferent(partial, another)
        assertObservation(another, store.inspectPlannedCreate(ACCOUNT, plan).credentialValue())
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
    }

    @Test fun wrongScopeDemoAndMalformedUnicodeAreRejectedWithoutEffects() = runBlocking {
        val box = sandbox(); val store = open(box)
        val plan = store.planCreate(1, account()).credentialValue()
        val before = files(box); val aliases = box.aliases()
        for (scope in listOf(ACCOUNT.copy(environment = "another-environment"), ACCOUNT.copy(actorId = "another-owner"),
            ACCOUNT.copy(actorKind = ActorKind.GUEST), ACCOUNT.copy(actorKind = ActorKind.DEMO),
            ACCOUNT.copy(environment = "bad-${0xD800.toChar()}"), ACCOUNT.copy(actorId = "bad-${0xDFFF.toChar()}"))) {
            failure(store.inspectPlannedCreate(scope, plan), FailureReason.INVALID_DATA)
        }
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        assertEquals(CredentialCreateRecoveryStatus.PREPARED, store.inspectPlannedCreate(ACCOUNT, plan).credentialValue().status)
    }

    @Test fun forgedAndForeignInstallPlansCannotBeObserved() = runBlocking {
        val box = sandbox(); val store = open(box)
        val plan = store.planCreate(1, account()).credentialValue(); val record = decode(plan)
        val before = files(box); val aliases = box.aliases()
        for (altered in listOf(record.copy(expectedSlotRevision = 2), record.copy(target = flip(record.target)),
            record.copy(payloadMac = flip(record.payloadMac)), record.copy(authenticationMac = flip(record.authenticationMac)))) {
            failure(store.inspectPlannedCreate(ACCOUNT, CredentialCreatePlan.create(altered)), FailureReason.INVALID_DATA)
        }
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        val foreignBox = sandbox(); val foreign = open(foreignBox)
        val foreignFiles = files(foreignBox); val foreignAliases = foreignBox.aliases()
        failure(foreign.inspectPlannedCreate(ACCOUNT, plan), FailureReason.INVALID_DATA)
        assertFiles(foreignFiles, files(foreignBox)); assertEquals(foreignAliases, foreignBox.aliases())
    }

    @Test fun refreshedOrNewerSelectionNeverMatchesTheOriginalPlan() = runBlocking {
        val box = sandbox(); val store = open(box)
        val plan = store.planCreate(1, account()).credentialValue()
        val snapshot = store.commitPlannedCreate(plan, account()).credentialValue()
        store.replace(snapshot, account(access = "replacement-fixture-access")).credentialValue()
        var before = files(box); var aliases = box.aliases()
        failure(store.inspectPlannedCreate(ACCOUNT, plan), FailureReason.CONFLICT)
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        store.retire(ACCOUNT, snapshot.incarnation).credentialValue()
        val next = store.planCreate(store.state().credentialValue().revision, account()).credentialValue()
        store.commitPlannedCreate(next, account()).credentialValue()
        before = files(box); aliases = box.aliases()
        failure(store.inspectPlannedCreate(ACCOUNT, plan), FailureReason.CONFLICT)
        assertEquals(CredentialCreateRecoveryStatus.SELECTED, store.inspectPlannedCreate(ACCOUNT, next).credentialValue().status)
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
    }

    @Test fun unknownFilesAndOtherIncarnationKeysFailClosedWithoutCleanup() = runBlocking {
        for (unknown in listOf("file", "key")) {
            val box = sandbox(); val store = open(box)
            val plan = store.planCreate(1, account()).credentialValue()
            if (unknown == "file") writePrivate(File(box.directory, "unrecognized.bin"), ByteArray(29))
            else AndroidCredentialVault.open(box.keyPrefix, initialize = false).createIncarnationKey(flip(decode(plan).target))
            val before = files(box); val aliases = box.aliases()
            failure(store.inspectPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
    }

    @Test fun poisonedAndClosedManagersCannotUseInspectionToRecoverAuthority() = runBlocking {
        for (cancel in listOf(false, true)) {
            val box = sandbox(); val store = open(box) { point ->
                if (point == CredentialFileFaultPoint.AFTER_KEY_CREATE) {
                    if (cancel) throw CancellationException("inspection-private-fault")
                    else throw IOException("inspection-private-fault")
                }
            }
            val plan = store.planCreate(1, account()).credentialValue()
            if (cancel) cancelled { store.commitPlannedCreate(plan, account()) }
            else failure(store.commitPlannedCreate(plan, account()), FailureReason.STORAGE_FAILURE)
            val before = files(box); val aliases = box.aliases()
            failure(store.inspectPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            failure(store.state(), FailureReason.STORAGE_FAILURE)
            assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
            close(store)
            failure(store.inspectPlannedCreate(ACCOUNT, plan), FailureReason.STORAGE_FAILURE)
            assertEquals(0, lockDescriptors(box)); assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        }
    }

    @Test fun queuedCancellationDoesNotRunInspectionOrChangeMetadata() = runBlocking {
        val box = sandbox(); val store = open(box)
        val plan = store.planCreate(1, account()).credentialValue()
        val before = files(box); val aliases = box.aliases()
        val pending = async(start = CoroutineStart.LAZY) { store.inspectPlannedCreate(ACCOUNT, plan) }
        pending.cancel()
        cancelled { pending.await() }
        assertFiles(before, files(box)); assertEquals(aliases, box.aliases())
        assertEquals(CredentialCreateRecoveryStatus.PREPARED, store.inspectPlannedCreate(ACCOUNT, plan).credentialValue().status)
    }

    private fun sandbox() = AndroidCredentialTestSandbox().also(boxes::add)
    private suspend fun open(box: AndroidCredentialTestSandbox, fault: (CredentialFileFaultPoint) -> Unit = {}) =
        AndroidCredentialStore.openForTests(box.directory, box.keyPrefix, fault).credentialValue().also(stores::add)
    private suspend fun close(store: AndroidCredentialStore) { store.close().credentialValue(); stores.remove(store) }
    private fun decode(plan: CredentialCreatePlan) = CredentialCreatePlanCodec.decode(plan.copyForStorage())
    private fun blob(plan: CredentialCreatePlan) = decode(plan).let { AndroidCredentialFiles.blobName(it.target, it.snapshotRevision) }
    private fun alias(box: AndroidCredentialTestSandbox, plan: CredentialCreatePlan) = "${box.keyPrefix}.credential.${decode(plan).target}"
    private fun flip(value: String) = (if (value.first() == '0') "1" else "0") + value.drop(1)
    private fun writePrivate(file: File, bytes: ByteArray) { file.writeBytes(bytes); Os.chmod(file.path, 384) }
    private fun files(box: AndroidCredentialTestSandbox) = checkNotNull(box.directory.listFiles()).associate {
        // Never open another descriptor for the inode carrying this process's POSIX lock.
        it.name to if (it.name == AndroidCredentialFiles.LOCK) { assertEquals(0L, it.length()); byteArrayOf() } else it.readBytes()
    }
    private fun assertFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual.getValue(name)) }
    }
    private fun assertObservation(expected: CredentialCreatePlanObservation, actual: CredentialCreatePlanObservation) {
        assertEquals(expected.status, actual.status)
        assertEquals(32, actual.fingerprint.copyForCodec().size)
        assertArrayEquals(expected.fingerprint.copyForCodec(), actual.fingerprint.copyForCodec())
    }
    private fun assertDifferent(a: CredentialCreatePlanObservation, b: CredentialCreatePlanObservation) =
        assertFalse(a.fingerprint.copyForCodec().contentEquals(b.fingerprint.copyForCodec()))
    private fun failure(result: PortResult<*>, reason: FailureReason) {
        assertTrue(result is PortResult.Failure)
        assertEquals(reason, (result as PortResult.Failure).reason)
        assertFalse(result.toString().contains("inspection-private-fault"))
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
        private val ACCOUNT = StorageScope("native-inspection-test", ActorKind.ACCOUNT, "inspection-owner")
        private val GUEST = StorageScope("native-inspection-test", ActorKind.GUEST, "inspection-guest")
        private fun account(access: String = "inspection-fixture-access") = StoredCredentials.Account(ACCOUNT,
            SecretText(access), SecretText("inspection-fixture-refresh"), Long.MAX_VALUE,
            SecretText("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        private fun guest() = StoredCredentials.Guest(GUEST, SecretText("inspection-fixture-session"),
            SecretText("inspection-fixture-guest-token"), Long.MAX_VALUE)
    }
}
