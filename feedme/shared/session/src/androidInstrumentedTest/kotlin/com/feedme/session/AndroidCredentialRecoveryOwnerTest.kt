package com.feedme.session

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Retained existing-only native ownership foundation, not startup orchestration. Close faults
 * are controlled pre-call interruption or explicitly simulated post-close errors, not OS EIO.
 * The terminal post-close case holds only a process reservation after its actual FD is closed.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCredentialRecoveryOwnerTest {
    private val boxes = mutableListOf<AndroidCredentialTestSandbox>()
    private val stores = mutableListOf<AndroidCredentialStore>()
    private val owners = mutableListOf<Pair<CredentialCreateRecoveryOwner, AndroidCredentialTestSandbox>>()
    private val terminalOwners = mutableSetOf<CredentialCreateRecoveryOwner>()

    @After fun cleanup() = runBlocking {
        owners.asReversed().forEach { (owner, box) ->
            if (owner in terminalOwners) {
                failure(owner.close(), FailureReason.STORAGE_FAILURE)
                assertEquals("Terminal simulation must leave no live native descriptor", 0, descriptors(box))
            } else owner.close().credentialValue()
        }
        owners.clear(); terminalOwners.clear()
        stores.asReversed().forEach { it.close().credentialValue() }; stores.clear()
        // Every path/alias belongs to a successfully created isolated fixture. The simulated
        // terminal owner's descriptor is demonstrably closed; its logical hold ends at process
        // exit, not through a production reset or a fabricated successful close result.
        boxes.asReversed().forEach(AndroidCredentialTestSandbox::close); boxes.clear()
    }

    @Test fun constructionDefersContextAndNativeIoAndCloseBeforeOpenNeverAcquiresAnything() = runBlocking {
        val f = fixture(Stage.PREPARED)
        var locations = 0
        val trap = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context { locations++; throw IOException(PRIVATE_FAULT) }
        }
        val before = files(f.box); val aliases = f.box.aliases()
        val owner = AndroidCredentialStore.createRecoveryOwner(trap, SCOPE, f.plan)
        assertEquals(0, locations); assertEquals("CredentialCreateRecoveryOwner(<redacted>)", owner.toString())
        failure(owner.inspect(), FailureReason.STALE_SESSION)
        failure(owner.abort(), FailureReason.STALE_SESSION)
        owner.close().credentialValue(); owner.close().credentialValue()
        failure(owner.open(), FailureReason.CONFLICT)
        assertEquals(0, locations); assertEquals(0, descriptors(f.box))
        assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
    }

    @Test fun preparedPartialSelectedAndAbortedMetadataRemainExactAcrossRetainedOpenAndAbort() = runBlocking {
        for (stage in Stage.entries) {
            val f = fixture(stage); val owner = owner(f)
            val before = files(f.box); val aliases = f.box.aliases()
            owner.open().credentialValue()
            val observation = owner.inspect().credentialValue()
            assertEquals(stage.status, observation.status); assertEquals(32, observation.fingerprint.copyForCodec().size)
            repeat(2) { sameObservation(observation, owner.inspect().credentialValue()) }
            assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases()); assertEquals(1, descriptors(f.box))
            failure(owner.open(), FailureReason.CONFLICT)
            owner.abort().credentialValue()
            assertEquals(CredentialCreateRecoveryStatus.ABORTED, owner.inspect().credentialValue().status)
            assertTrue(f.box.credentialAliases().isEmpty())
            val consumed = files(f.box)
            owner.abort().credentialValue(); assertFiles(consumed, files(f.box))
            owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
            failure(owner.inspect(), FailureReason.STALE_SESSION); failure(owner.abort(), FailureReason.STALE_SESSION)
        }
    }

    @Test fun scopeMacTamperingAndForeignInstallFailuresRetainCloseOnlyOwnerWithoutDeletion() = runBlocking {
        val f = fixture(Stage.SELECTED); val record = decode(f.plan)
        val requests = listOf(
            SCOPE.copy(actorId = "another-owner") to f.plan,
            SCOPE.copy(actorKind = ActorKind.DEMO) to f.plan,
            SCOPE.copy(environment = "bad-${0xD800.toChar()}") to f.plan,
            SCOPE to CredentialCreatePlan.create(record.copy(authenticationMac = flip(record.authenticationMac))),
        )
        for ((scope, plan) in requests) {
            val before = files(f.box); val aliases = f.box.aliases()
            val owner = owner(f, scope, plan)
            failure(owner.open(), FailureReason.INVALID_DATA)
            assertEquals(1, descriptors(f.box))
            failure(owner.inspect(), FailureReason.STALE_SESSION); failure(owner.abort(), FailureReason.STALE_SESSION)
            failure(owner.open(), FailureReason.CONFLICT)
            assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
            owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
        }
        val other = fixture(Stage.SELECTED)
        val before = files(other.box); val aliases = other.box.aliases()
        val foreign = owner(other, plan = f.plan)
        failure(foreign.open(), FailureReason.INVALID_DATA)
        assertFiles(before, files(other.box)); assertEquals(aliases, other.box.aliases())
        foreign.close().credentialValue(); assertEquals(0, descriptors(other.box))
    }

    @Test fun missingDirectoryLockManifestOrInstallKeyNeverInitializesReplacementState() = runBlocking {
        for (missing in listOf("directory", "lock", "manifest", "index", "manifest-key")) {
            val f = fixture(Stage.PREPARED)
            when (missing) {
                "directory" -> assertTrue(f.box.directory.deleteRecursively())
                "lock" -> assertTrue(File(f.box.directory, AndroidCredentialFiles.LOCK).delete())
                "manifest" -> assertTrue(File(f.box.directory, AndroidCredentialFiles.MANIFEST).delete())
                "index" -> f.box.keyStore().deleteEntry("${f.box.keyPrefix}.index")
                else -> f.box.keyStore().deleteEntry("${f.box.keyPrefix}.manifest")
            }
            val before = files(f.box); val aliases = f.box.aliases()
            val owner = owner(f)
            failure(owner.open(), FailureReason.STORAGE_FAILURE)
            failure(owner.open(), FailureReason.CONFLICT)
            failure(owner.abort(), FailureReason.STALE_SESSION)
            assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
            owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
            assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
        }
    }

    @Test fun failuresAtEveryOpeningStageKeepPartialDescriptorsReachableUntilAcknowledgedClose() = runBlocking {
        for (point in OPEN_POINTS) {
            val f = fixture(Stage.SELECTED); var armed = true
            val owner = owner(f) { at -> if (armed && at == point) { armed = false; throw IOException(PRIVATE_FAULT) } }
            val before = files(f.box); val aliases = f.box.aliases()
            failure(owner.open(), FailureReason.STORAGE_FAILURE)
            assertEquals(1, descriptors(f.box))
            failure(owner.open(), FailureReason.CONFLICT)
            failure(owner.inspect(), FailureReason.STALE_SESSION); failure(owner.abort(), FailureReason.STALE_SESSION)
            failure(AndroidCredentialStore.openForTests(f.box.directory, f.box.keyPrefix), FailureReason.STORAGE_FAILURE)
            assertEquals(1, descriptors(f.box)); assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
            owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
            val next = owner(f); next.open().credentialValue()
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, next.inspect().credentialValue().status)
            next.close().credentialValue(); assertEquals(0, descriptors(f.box))
        }
    }

    @Test fun cancelledOpeningAndLateAuthenticatedCancellationKeepAnExplicitCloseOnlyOwner() = runBlocking {
        for (point in OPEN_POINTS) {
            val f = fixture(Stage.SELECTED)
            val owner = owner(f) { at -> if (at == point) throw CancellationException(PRIVATE_FAULT) }
            val before = files(f.box); val aliases = f.box.aliases()
            cancelled { owner.open() }
            assertEquals(1, descriptors(f.box))
            failure(owner.inspect(), FailureReason.STALE_SESSION); failure(owner.abort(), FailureReason.STALE_SESSION)
            owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
            assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
        }
        val f = fixture(Stage.PREPARED)
        lateinit var pending: Deferred<PortResult<Unit>>
        val owner = owner(f) { at -> if (at == CredentialFileFaultPoint.AFTER_RECOVERY_AUTHENTICATED) pending.cancel() }
        pending = async(start = CoroutineStart.LAZY) { owner.open() }
        pending.start(); cancelled { pending.await() }
        assertEquals(1, descriptors(f.box)); failure(owner.open(), FailureReason.CONFLICT)
        failure(owner.inspect(), FailureReason.STALE_SESSION)
        owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
        // Cancellation of a second queued caller must not revoke the successfully admitted
        // opener. The gate is entirely test-owned; neither opening nor cleanup is fabricated.
        val queuedFixture = fixture(Stage.SELECTED)
        val reached = CountDownLatch(1); val proceed = CountDownLatch(1)
        val queuedOwner = owner(queuedFixture) { at -> if (at == CredentialFileFaultPoint.AFTER_RECOVERY_AUTHENTICATED) {
            reached.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
        } }
        val first = async(Dispatchers.Default) { queuedOwner.open() }
        try {
            withContext(Dispatchers.IO) { assertTrue(reached.await(5, TimeUnit.SECONDS)) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { queuedOwner.open() }
            second.cancel(); proceed.countDown(); cancelled { second.await() }
            first.await().credentialValue()
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, queuedOwner.inspect().credentialValue().status)
            queuedOwner.abort().credentialValue()
            queuedOwner.close().credentialValue(); assertEquals(0, descriptors(queuedFixture.box))
        } finally { proceed.countDown(); first.cancelAndJoin() }
        for (closeBeforeReturn in listOf(false, true)) {
            val gatedFixture = fixture(Stage.SELECTED)
            val gatedOwner = owner(gatedFixture)
            val before = files(gatedFixture.box); val aliases = gatedFixture.box.aliases()
            val caller = CallerHandoffDispatcher()
            val gated = async(caller, start = CoroutineStart.UNDISPATCHED) { gatedOwner.open() }
            // The queued return proves IO initialization finished, but the caller has not
            // received its result. Neither observation nor abort has authority in this gap.
            val returnToCaller = withContext(Dispatchers.IO) { caller.next() }
            assertFalse(gated.isCompleted)
            failure(gatedOwner.inspect(), FailureReason.STALE_SESSION)
            failure(gatedOwner.abort(), FailureReason.STALE_SESSION)
            assertFiles(before, files(gatedFixture.box)); assertEquals(aliases, gatedFixture.box.aliases())
            if (closeBeforeReturn) gatedOwner.close().credentialValue() else gated.cancel()
            returnToCaller.run()
            while (!gated.isCompleted) withContext(Dispatchers.IO) { caller.next() }.run()
            if (closeBeforeReturn) failure(gated.await(), FailureReason.STALE_SESSION)
            else cancelled { gated.await() }
            failure(gatedOwner.inspect(), FailureReason.STALE_SESSION)
            gatedOwner.close().credentialValue(); assertEquals(0, descriptors(gatedFixture.box))
            assertFiles(before, files(gatedFixture.box)); assertEquals(aliases, gatedFixture.box.aliases())
        }
    }

    @Test fun everyPreCloseStageFailureRetainsReservationAndRetriesOnlyUnfinishedStages() = runBlocking {
        for (point in CLOSE_POINTS) {
            val f = fixture(Stage.SELECTED); var armed = true; val visits = mutableListOf<CredentialFileFaultPoint>()
            val owner = owner(f) { at ->
                if (at in CLOSE_POINTS || at == CredentialFileFaultPoint.AFTER_LOCK_DESCRIPTOR_CLOSE) visits += at
                if (armed && at == point) { armed = false; throw IOException(PRIVATE_FAULT) }
            }
            owner.open().credentialValue()
            val before = files(f.box); val aliases = f.box.aliases()
            failure(owner.close(), FailureReason.STORAGE_FAILURE)
            assertEquals(1, descriptors(f.box))
            failure(owner.open(), FailureReason.CONFLICT); failure(owner.inspect(), FailureReason.STALE_SESSION)
            failure(owner.abort(), FailureReason.STALE_SESSION)
            failure(AndroidCredentialStore.openForTests(f.box.directory, f.box.keyPrefix), FailureReason.STORAGE_FAILURE)
            assertEquals(1, descriptors(f.box)); assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
            owner.close().credentialValue(); owner.close().credentialValue()
            assertEquals(0, descriptors(f.box))
            assertEquals(2, visits.count { it == point })
            CLOSE_POINTS.filter { it != point }.forEach { assertEquals(1, visits.count { at -> at == it }) }
            assertEquals(1, visits.count { it == CredentialFileFaultPoint.AFTER_LOCK_DESCRIPTOR_CLOSE })
        }
    }

    @Test fun simulatedPlatformErrorAfterActualDescriptorCloseIsTerminalAndNeverRetriesNumericFd() = runBlocking {
        val f = fixture(Stage.SELECTED); var nativeCloseReturns = 0
        val owner = owner(f) { at -> if (at == CredentialFileFaultPoint.AFTER_LOCK_DESCRIPTOR_CLOSE) {
            nativeCloseReturns++; throw IOException(PRIVATE_FAULT)
        } }
        owner.open().credentialValue()
        val before = files(f.box); val aliases = f.box.aliases()
        terminalOwners += owner
        failure(owner.close(), FailureReason.STORAGE_FAILURE)
        assertEquals(0, descriptors(f.box))
        repeat(3) { failure(owner.close(), FailureReason.STORAGE_FAILURE) }
        assertEquals(1, nativeCloseReturns)
        failure(owner.open(), FailureReason.CONFLICT); failure(owner.abort(), FailureReason.STALE_SESSION)
        failure(AndroidCredentialStore.openForTests(f.box.directory, f.box.keyPrefix), FailureReason.STORAGE_FAILURE)
        assertEquals(0, descriptors(f.box)); assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
    }

    @Test fun missingAndCorruptSelectedMaterialCanBeInspectedAndAbortedWithoutCredentialReads() = runBlocking {
        for (damage in listOf("key", "blob", "corrupt", "empty")) {
            val f = fixture(Stage.SELECTED)
            when (damage) {
                "key" -> f.box.keyStore().deleteEntry(alias(f))
                "blob" -> assertTrue(File(f.box.directory, blob(f)).delete())
                "corrupt" -> writePrivate(File(f.box.directory, blob(f)), ByteArray(29))
                else -> writePrivate(File(f.box.directory, blob(f)), byteArrayOf())
            }
            val before = files(f.box); val aliases = f.box.aliases()
            val owner = owner(f); owner.open().credentialValue()
            assertEquals(CredentialCreateRecoveryStatus.SELECTED, owner.inspect().credentialValue().status)
            assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
            owner.abort().credentialValue()
            assertEquals(CredentialCreateRecoveryStatus.ABORTED, owner.inspect().credentialValue().status)
            assertTrue(f.box.credentialAliases().isEmpty())
            owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
        }
    }

    @Test fun unknownInventoryAndForeignIncarnationKeysCannotBeAdoptedByRetainedRecovery() = runBlocking {
        for (unknown in listOf("file", "key")) {
            val f = fixture(Stage.PARTIAL)
            if (unknown == "file") writePrivate(File(f.box.directory, "unknown.bin"), ByteArray(29))
            else AndroidCredentialVault.open(f.box.keyPrefix, initialize = false).createIncarnationKey(flip(decode(f.plan).target))
            val before = files(f.box); val aliases = f.box.aliases()
            val owner = owner(f)
            failure(owner.open(), FailureReason.STORAGE_FAILURE)
            failure(owner.abort(), FailureReason.STALE_SESSION)
            assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
            owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
        }
    }

    @Test fun interruptedAbortPoisonsOwnerUntilExactCloseAndFreshExistingOnlyRetry() = runBlocking {
        val f = fixture(Stage.SELECTED); var failAbort = true; var failClose = true
        val owner = owner(f) { at ->
            if (failAbort && at == CredentialFileFaultPoint.AFTER_KEY_DELETE) { failAbort = false; throw IOException(PRIVATE_FAULT) }
            if (failClose && at == CredentialFileFaultPoint.BEFORE_LOCK_DESCRIPTOR_CLOSE) { failClose = false; throw IOException(PRIVATE_FAULT) }
        }
        owner.open().credentialValue()
        failure(owner.abort(), FailureReason.STORAGE_FAILURE)
        assertFalse(alias(f) in f.box.aliases()); assertTrue(File(f.box.directory, blob(f)).exists())
        val before = files(f.box); val aliases = f.box.aliases()
        failure(owner.inspect(), FailureReason.STORAGE_FAILURE); failure(owner.abort(), FailureReason.STORAGE_FAILURE)
        failure(owner.close(), FailureReason.STORAGE_FAILURE); assertEquals(1, descriptors(f.box))
        assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
        owner.close().credentialValue(); assertEquals(0, descriptors(f.box))
        val retry = owner(f); retry.open().credentialValue()
        assertEquals(CredentialCreateRecoveryStatus.SELECTED, retry.inspect().credentialValue().status)
        retry.abort().credentialValue()
        assertEquals(CredentialCreateRecoveryStatus.ABORTED, retry.inspect().credentialValue().status)
        assertTrue(f.box.credentialAliases().isEmpty())
        retry.close().credentialValue(); assertEquals(0, descriptors(f.box))
    }

    @Test fun refreshedAndNewerSelectionsCannotBeOpenedThroughOldPlanOrClosedOwner() = runBlocking {
        for (newIncarnation in listOf(false, true)) {
            val f = fixture(Stage.SELECTED)
            val old = owner(f); old.open().credentialValue(); old.close().credentialValue()
            val store = openStore(f.box)
            val selected = store.read(SCOPE).credentialValue()!!
            if (newIncarnation) {
                store.retire(SCOPE, selected.incarnation).credentialValue()
                store.create(store.state().credentialValue().revision, account()).credentialValue()
            } else store.replace(selected, account("new-access-fixture")).credentialValue()
            closeStore(store)
            val before = files(f.box); val aliases = f.box.aliases()
            failure(old.open(), FailureReason.CONFLICT); failure(old.abort(), FailureReason.STALE_SESSION)
            val stale = owner(f)
            failure(stale.open(), FailureReason.CONFLICT)
            failure(stale.abort(), FailureReason.STALE_SESSION)
            assertFiles(before, files(f.box)); assertEquals(aliases, f.box.aliases())
            stale.close().credentialValue(); assertEquals(0, descriptors(f.box))
        }
    }

    private class Fixture(val box: AndroidCredentialTestSandbox, val plan: CredentialCreatePlan)
    private enum class Stage(val status: CredentialCreateRecoveryStatus) {
        PREPARED(CredentialCreateRecoveryStatus.PREPARED), PARTIAL(CredentialCreateRecoveryStatus.PARTIAL),
        SELECTED(CredentialCreateRecoveryStatus.SELECTED), ABORTED(CredentialCreateRecoveryStatus.ABORTED),
    }
    private suspend fun fixture(stage: Stage): Fixture {
        val box = AndroidCredentialTestSandbox().also(boxes::add)
        val store = openStore(box)
        val plan = store.planCreate(1, account()).credentialValue()
        when (stage) {
            Stage.PARTIAL -> {
                AndroidCredentialVault.open(box.keyPrefix, initialize = false).createIncarnationKey(decode(plan).target)
                writePrivate(File(box.directory, AndroidCredentialFiles.createManifestPending(decode(plan).incarnation)), byteArrayOf())
            }
            Stage.SELECTED, Stage.ABORTED -> {
                store.commitPlannedCreate(plan, account()).credentialValue()
                if (stage == Stage.ABORTED) store.abortPlannedCreate(SCOPE, plan).credentialValue()
            }
            Stage.PREPARED -> Unit
        }
        closeStore(store)
        return Fixture(box, plan)
    }
    private fun owner(f: Fixture, scope: StorageScope = SCOPE, plan: CredentialCreatePlan = f.plan,
        fault: (CredentialFileFaultPoint) -> Unit = {}): CredentialCreateRecoveryOwner =
        AndroidCredentialStore.createRecoveryOwnerForTests(f.box.directory, f.box.keyPrefix, scope, plan, fault)
            .also { owners += it to f.box }
    private suspend fun openStore(box: AndroidCredentialTestSandbox) =
        AndroidCredentialStore.openForTests(box.directory, box.keyPrefix).credentialValue().also(stores::add)
    private suspend fun closeStore(store: AndroidCredentialStore) { store.close().credentialValue(); stores.remove(store) }
    private fun decode(plan: CredentialCreatePlan) = CredentialCreatePlanCodec.decode(plan.copyForStorage())
    private fun blob(f: Fixture) = decode(f.plan).let { AndroidCredentialFiles.blobName(it.target, it.snapshotRevision) }
    private fun alias(f: Fixture) = "${f.box.keyPrefix}.credential.${decode(f.plan).target}"
    private fun flip(value: String) = (if (value.first() == '0') "1" else "0") + value.drop(1)
    private fun writePrivate(file: File, bytes: ByteArray) { file.writeBytes(bytes); Os.chmod(file.path, 384) }
    private fun files(box: AndroidCredentialTestSandbox): Map<String, ByteArray> =
        box.directory.listFiles()?.associate { it.name to if (it.name == AndroidCredentialFiles.LOCK) {
            assertEquals(0L, it.length()); byteArrayOf()
        } else it.readBytes() } ?: emptyMap()
    private fun assertFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual.getValue(name)) }
    }
    private fun sameObservation(a: CredentialCreatePlanObservation, b: CredentialCreatePlanObservation) {
        assertEquals(a.status, b.status); assertArrayEquals(a.fingerprint.copyForCodec(), b.fingerprint.copyForCodec())
    }
    private fun failure(result: PortResult<*>, reason: FailureReason) {
        assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason)
        assertNull(result.retryAfterSeconds); assertFalse(result.toString().contains(PRIVATE_FAULT))
    }
    private suspend fun cancelled(action: suspend () -> Any?) {
        var caught = false
        try { action() } catch (_: CancellationException) { caught = true }
        assertTrue(caught)
    }
    private fun descriptors(box: AndroidCredentialTestSandbox): Int {
        // Context's /data/user/0 spelling may alias /data/data. Match the same canonical
        // pathname used by the native opener, without opening another FD on its lock inode.
        val exact = File(box.directory, AndroidCredentialFiles.LOCK).canonicalPath
        return checkNotNull(File("/proc/self/fd").listFiles()).count { runCatching { Os.readlink(it.path) == exact }.getOrDefault(false) }
    }
    private class CallerHandoffDispatcher : CoroutineDispatcher() {
        private val pending = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun next(): Runnable = checkNotNull(pending.poll(5, TimeUnit.SECONDS))
    }
    companion object {
        private const val PRIVATE_FAULT = "retained-credential-owner-private-fault"
        private val SCOPE = StorageScope("native-retained-recovery", ActorKind.ACCOUNT, "recovery-owner")
        private val OPEN_POINTS = listOf(CredentialFileFaultPoint.AFTER_LOCK_DESCRIPTOR_OPEN,
            CredentialFileFaultPoint.AFTER_LOCK_ACQUIRED, CredentialFileFaultPoint.AFTER_RECOVERY_FILES_OPEN,
            CredentialFileFaultPoint.AFTER_RECOVERY_AUTHENTICATED)
        private val CLOSE_POINTS = listOf(CredentialFileFaultPoint.BEFORE_LOCK_RELEASE,
            CredentialFileFaultPoint.BEFORE_LOCK_CHANNEL_CLOSE, CredentialFileFaultPoint.BEFORE_LOCK_DESCRIPTOR_CLOSE)
        private fun account(access: String = "recovery-access-fixture") = StoredCredentials.Account(SCOPE,
            SecretText(access), SecretText("recovery-refresh-fixture"), Long.MAX_VALUE,
            SecretText("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
    }
}
