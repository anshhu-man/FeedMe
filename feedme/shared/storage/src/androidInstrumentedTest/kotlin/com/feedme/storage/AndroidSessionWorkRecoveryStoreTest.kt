package com.feedme.storage

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.session.SessionWorkOriginPlan
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real existing-only native work storage SPI; no scheduler, sign-in or startup consent. */
@OptIn(WorkRecoveryCompositionApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidSessionWorkRecoveryStoreTest {
    private val fixtures = mutableListOf<AndroidWorkOriginPlanFixture>()
    private val emptyBoxes = mutableListOf<AndroidStateTestSandbox>()
    private val stores = mutableListOf<ExistingSessionWorkRecoveryStore>()
    private val faults = mutableListOf<Faults>()
    private val terminals = mutableListOf<Pair<ExistingSessionWorkRecoveryStore, AndroidStateTestSandbox>>()

    @After fun cleanup() = runBlocking {
        faults.forEach { it.failures.clear(); it.cancelAt = null; it.callback = null }
        terminals.forEach { (owner, box) ->
            // Explicit simulated post-descriptor-close error: native FDs are closed, but the
            // logical reservation remains until this isolated instrumentation process exits.
            assertEquals(0, descriptors(box, "state.lock")); assertEquals(0, descriptors(box, "state.sqlite"))
            failure(owner.close(), FailureReason.STORAGE_FAILURE)
        }
        stores.asReversed().forEach { it.close().valueOrFail() }; stores.clear()
        fixtures.asReversed().forEach { it.close() }
        fixtures.asReversed().forEach { it.destroy() }; fixtures.clear()
        emptyBoxes.asReversed().forEach { it.close() }; emptyBoxes.clear()
    }

    @Test fun constructionDefersContextIoAndCloseBeforeOpenNeverInitializesMissingStorage() = runBlocking {
        var locations = 0
        val trap = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context { locations++; throw IOException(PRIVATE_FAULT) }
        }
        val owner = AndroidSessionWorkStore.createRecoveryStore(trap).also(stores::add)
        assertEquals(0, locations); assertEquals("ExistingSessionWorkRecoveryStore(<redacted>)", owner.toString())
        unavailable(owner); owner.close().valueOrFail(); owner.close().valueOrFail()
        failure(owner.open(), FailureReason.CONFLICT); assertEquals(0, locations)
        val box = AndroidStateTestSandbox().also(emptyBoxes::add)
        val missing = owner(box)
        failure(missing.open(), FailureReason.STORAGE_FAILURE); unavailable(missing)
        missing.close().valueOrFail()
        assertFalse(box.directory.exists()); assertTrue(aliases(box).isEmpty())
    }

    @Test fun existingPreparedSelectedSealedAndAbortedRecordsAreReadWithoutWritingOrSigning() = runBlocking {
        for (stage in listOf("prepared", "selected", "sealed", "aborted")) {
            val f = fixture(stage); val before = snapshot(f.native.box); val keys = f.native.aliases()
            val owner = owner(f.native.box); owner.open().valueOrFail()
            assertEquals(1, descriptors(f.native.box, "state.lock")); assertEquals(1, descriptors(f.native.box, "state.sqlite"))
            assertFalse(owner is WorkOriginPlanAuthentication)
            failure(owner.open(), FailureReason.CONFLICT)
            repeat(3) {
                sameRecord(f.current, checkNotNull(owner.read().valueOrFail()))
                owner.verifyOriginPlan(1, f.proposal, f.proof).valueOrFail()
            }
            if (stage == "prepared") owner.verifyOriginPredecessor(f.predecessor, f.proposal, f.proof).valueOrFail()
            else failure(owner.verifyOriginPredecessor(f.predecessor, f.proposal, f.proof), FailureReason.CONFLICT)
            checkNotNull(owner.read().valueOrFail()).payload.copyForCodec().fill(0)
            sameRecord(f.current, checkNotNull(owner.read().valueOrFail()))
            close(owner); assertEquals(0, descriptors(f.native.box, "state.lock"))
            assertEquals(before, snapshot(f.native.box)); assertEquals(keys, f.native.aliases()); f.native.assertNoEffects()
        }
    }

    @Test fun authenticProofsRejectAnotherInstallMutationsAndInvalidBoundsWithoutSelection() = runBlocking {
        val f = fixture(); val other = fixture(); val before = snapshot(f.native.box); val keys = f.native.aliases()
        val owner = owner(f.native.box); owner.open().valueOrFail()
        owner.verifyOriginPlan(1, f.proposal, f.proof).valueOrFail()
        failure(owner.verifyOriginPlan(1, other.proposal, other.proof), FailureReason.INVALID_DATA)
        failure(owner.verifyOriginPlan(1, bytes("changed-proposal"), f.proof), FailureReason.INVALID_DATA)
        val changed = f.proof.copyForCodec().also { it[0] = (it[0].toInt() xor 1).toByte() }
        failure(owner.verifyOriginPlan(1, f.proposal, PrivateBytes(changed)), FailureReason.INVALID_DATA)
        for (revision in listOf(0L, Long.MAX_VALUE - 1, Long.MAX_VALUE))
            failure(owner.verifyOriginPlan(revision, f.proposal, f.proof), FailureReason.INVALID_DATA)
        for (size in listOf(0, 4097)) failure(owner.verifyOriginPlan(1, PrivateBytes(ByteArray(size)), f.proof), FailureReason.INVALID_DATA)
        failure(owner.verifyOriginPlan(1, f.proposal, PrivateBytes(ByteArray(63))), FailureReason.INVALID_DATA)
        failure(owner.verifyOriginPredecessor(SessionControlRecord(1, bytes("changed-predecessor")), f.proposal, f.proof), FailureReason.CONFLICT)
        sameRecord(f.current, checkNotNull(owner.read().valueOrFail())); close(owner)
        assertEquals(before, snapshot(f.native.box)); assertEquals(keys, f.native.aliases()); f.native.assertNoEffects()
    }

    @Test fun exactSamePayloadCasChangesRevisionAndSurvivesExistingOnlyReopen() = runBlocking {
        val f = fixture("selected"); val keys = f.native.aliases()
        val owner = owner(f.native.box); owner.open().valueOrFail()
        failure(owner.compareAndSet(null, f.current.payload), FailureReason.CONFLICT)
        failure(owner.compareAndSet(0, f.current.payload), FailureReason.INVALID_DATA)
        failure(owner.compareAndSet(f.current.revision, PrivateBytes(byteArrayOf())), FailureReason.INVALID_DATA)
        failure(owner.compareAndSet(f.current.revision, PrivateBytes(ByteArray(32_769))), FailureReason.INVALID_DATA)
        val first = owner.compareAndSet(f.current.revision, f.current.payload).valueOrFail()
        assertEquals(f.current.revision + 1, first.revision); sameBytes(f.current.payload, first.payload)
        sameRecord(first, checkNotNull(owner.read().valueOrFail()))
        failure(owner.compareAndSet(f.current.revision, f.current.payload), FailureReason.CONFLICT)
        owner.verifyOriginPlan(1, f.proposal, f.proof).valueOrFail(); close(owner)
        val reopened = owner(f.native.box); reopened.open().valueOrFail()
        sameRecord(first, checkNotNull(reopened.read().valueOrFail()))
        val second = reopened.compareAndSet(first.revision, first.payload).valueOrFail()
        assertEquals(first.revision + 1, second.revision); sameRecord(second, checkNotNull(reopened.read().valueOrFail()))
        close(reopened); assertEquals(keys, f.native.aliases()); f.native.assertNoEffects()
    }

    @Test fun absentFilesInstallOrOwnerKeysAndUnsupportedSchemaCannotCreateReplacementState() = runBlocking {
        for (damage in listOf("directory", "lock", "database", "index", "owner-key", "legacy", "future", "foreign-schema")) {
            val f = fixture(); val box = f.native.box
            when (damage) {
                "directory" -> assertTrue(box.directory.deleteRecursively())
                "lock" -> assertTrue(File(box.directory, "state.lock").delete())
                "database" -> assertTrue(File(box.directory, "state.sqlite").delete())
                "index" -> box.keyStore().deleteEntry("${box.keyPrefix}.index")
                "owner-key" -> box.keyStore().deleteEntry(f.native.aliases().single { it.startsWith("${box.keyPrefix}.owner.") })
                "legacy" -> sql(box, "DROP TABLE feedme_activation_aborts", "PRAGMA user_version=1")
                "future" -> sql(box, "PRAGMA user_version=3")
                else -> sql(box, "CREATE TABLE foreign_evidence(marker TEXT)")
            }
            val before = snapshot(box); val keys = f.native.aliases(); val owner = owner(box)
            failure(owner.open(), FailureReason.STORAGE_FAILURE); unavailable(owner); failure(owner.open(), FailureReason.CONFLICT)
            close(owner); assertEquals(before, snapshot(box)); assertEquals(keys, f.native.aliases())
        }
    }

    @Test fun foreignRowsTombstonesGcAndAbortReceiptsArePreservedAndNeverCollected() = runBlocking {
        val damageSql = listOf(
            "DELETE FROM feedme_records",
            "UPDATE feedme_records SET payload=NULL",
            "UPDATE feedme_records SET schema_version=2",
            "UPDATE feedme_records SET payload=zeroblob(29)",
            "UPDATE feedme_owners SET active=0",
            "INSERT INTO feedme_records SELECT owner_tag,'${"a".repeat(64)}',1,1,zeroblob(29) FROM feedme_owners",
            "INSERT INTO feedme_owners VALUES('${"a".repeat(64)}',1,1,'${"b".repeat(32)}')",
            "INSERT INTO feedme_key_gc VALUES('${"b".repeat(32)}')",
            "INSERT INTO feedme_activation_aborts SELECT owner_tag,1,key_id,1 FROM feedme_owners",
        )
        for (damage in damageSql) {
            val f = fixture(); val box = f.native.box
            if (damage.startsWith("INSERT INTO feedme_key_gc"))
                AndroidStateVault.openExisting(box.keyPrefix).createOwnerKey("b".repeat(32))
            sql(box, damage)
            val before = snapshot(box); val keys = f.native.aliases(); val owner = owner(box)
            failure(owner.open(), FailureReason.STORAGE_FAILURE); unavailable(owner); close(owner)
            assertEquals(before, snapshot(box)); assertEquals(keys, f.native.aliases()); f.native.assertNoEffects()
        }
    }

    @Test fun sidecarsUnknownChildrenUnsafeModesAndSymlinksBlockBeforeSqliteOpening() = runBlocking {
        for (damage in listOf("journal", "wal", "shm", "unknown", "mode", "symlink", "lock-content")) {
            val f = fixture(); val box = f.native.box
            when (damage) {
                "mode" -> Os.chmod(File(box.directory, "state.sqlite").path, 420)
                "symlink" -> {
                    val original = File(box.directory, "state.sqlite"); val target = File(box.directory, "foreign-owned.bin")
                    assertTrue(original.renameTo(target)); Files.createSymbolicLink(original.toPath(), target.toPath())
                }
                "lock-content" -> write(File(box.directory, "state.lock"), byteArrayOf(1))
                else -> write(File(box.directory, if (damage == "unknown") "unknown.bin" else "state.sqlite-$damage"), byteArrayOf())
            }
            val before = snapshot(box); val keys = f.native.aliases(); val fault = fault(); val owner = owner(box, fault)
            failure(owner.open(), FailureReason.STORAGE_FAILURE); unavailable(owner)
            assertFalse("SQLite must not be opened behind an invalid native inventory", "before_sqlite_open" in fault.events)
            close(owner); assertEquals(before, snapshot(box)); assertEquals(keys, f.native.aliases())
        }
    }

    @Test fun partialAcquisitionFailuresRetainExactOwnerAndPreventSecondDescriptorsUntilClose() = runBlocking {
        for (stage in OPEN_STAGES) {
            val f = fixture(); val box = f.native.box; val before = snapshot(box); val keys = f.native.aliases()
            val fault = fault(stage); val owner = owner(box, fault)
            failure(owner.open(), FailureReason.STORAGE_FAILURE); unavailable(owner)
            assertEquals(1, descriptors(box, "state.lock"))
            assertEquals(if (stage in setOf("after_sqlite_open", "before_initialize")) 1 else 0, descriptors(box, "state.sqlite"))
            val competing = owner(box); failure(competing.open(), FailureReason.STORAGE_FAILURE); close(competing)
            assertEquals(1, descriptors(box, "state.lock"))
            fault.failures.clear(); close(owner)
            assertEquals(0, descriptors(box, "state.lock")); assertEquals(0, descriptors(box, "state.sqlite"))
            assertEquals(before, snapshot(box)); assertEquals(keys, f.native.aliases())
            val retry = owner(box); retry.open().valueOrFail(); sameRecord(f.current, checkNotNull(retry.read().valueOrFail())); close(retry)
        }
    }

    @Test fun failedInitializationAndEachPreCloseFaultRetryOnlyUnfinishedAcknowledgedStages() = runBlocking {
        for (stage in CLOSE_STAGES) {
            val f = fixture(); val box = f.native.box; sql(box, "PRAGMA user_version=3")
            val before = snapshot(box); val keys = f.native.aliases(); val fault = fault(stage); val owner = owner(box, fault)
            failure(owner.open(), FailureReason.STORAGE_FAILURE); failure(owner.close(), FailureReason.STORAGE_FAILURE)
            unavailable(owner); assertEquals(if (stage == "before_sqlite_close") 1 else 0, descriptors(box, "state.sqlite"))
            assertEquals(if (stage == "before_reservation_release") 0 else 1, descriptors(box, "state.lock"))
            val competing = owner(box); failure(competing.open(), FailureReason.STORAGE_FAILURE); close(competing)
            val sqliteCloseCalls = fault.events.count { it == "before_sqlite_close" }
            fault.failures.clear(); close(owner); owner.close().valueOrFail()
            assertEquals(if (stage == "before_sqlite_close") 2 else sqliteCloseCalls, fault.events.count { it == "before_sqlite_close" })
            assertEquals(0, descriptors(box, "state.lock")); assertEquals(0, descriptors(box, "state.sqlite"))
            assertEquals(before, snapshot(box)); assertEquals(keys, f.native.aliases())
        }
    }

    @Test fun cancelledAcquisitionAndCallerReturnGapNeverExposeLedgerOrCasAuthority() = runBlocking {
        for (stage in OPEN_STAGES) {
            val f = fixture(); val box = f.native.box; val before = snapshot(box); val fault = fault().also { it.cancelAt = stage }
            val owner = owner(box, fault); cancelled { owner.open() }; unavailable(owner)
            assertEquals(1, descriptors(box, "state.lock")); fault.cancelAt = null; close(owner)
            assertEquals(before, snapshot(box)); f.native.assertNoEffects()
        }
        for (closeBeforeReturn in listOf(false, true)) {
            val f = fixture(); val box = f.native.box; val before = snapshot(box); val owner = owner(box)
            val caller = CallerHandoffDispatcher()
            val opening = async(caller, start = CoroutineStart.UNDISPATCHED) { owner.open() }
            val returned = withContext(Dispatchers.IO) { caller.next() }
            assertFalse(opening.isCompleted); unavailable(owner)
            if (closeBeforeReturn) owner.close().valueOrFail() else opening.cancel()
            returned.run()
            while (!opening.isCompleted) withContext(Dispatchers.IO) { caller.next() }.run()
            if (closeBeforeReturn) failure(opening.await(), FailureReason.STALE_SESSION) else cancelled { opening.await() }
            unavailable(owner); close(owner); assertEquals(before, snapshot(box)); f.native.assertNoEffects()
        }
    }

    @Test fun competingLegacyAndCancelledQueuedOpensCannotRevokeTheFirstReadyOwner() = runBlocking {
        val f = fixture(); val box = f.native.box; val before = snapshot(box); val fault = fault()
        val reached = CountDownLatch(1); val proceed = CountDownLatch(1)
        fault.callback = { stage -> if (stage == "before_initialize") { reached.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) } }
        val owner = owner(box, fault); val first = async(Dispatchers.Default) { owner.open() }
        try {
            withContext(Dispatchers.IO) { assertTrue(reached.await(5, TimeUnit.SECONDS)) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }
            second.cancel(); proceed.countDown(); cancelled { second.await() }; first.await().valueOrFail()
            sameRecord(f.current, checkNotNull(owner.read().valueOrFail()))
            failure(AndroidStateDatabase.openForTests(box.directory, box.keyPrefix), FailureReason.STORAGE_FAILURE)
            val other = owner(box); failure(other.open(), FailureReason.STORAGE_FAILURE); close(other)
            assertEquals(1, descriptors(box, "state.lock")); assertEquals(1, descriptors(box, "state.sqlite"))
            owner.verifyOriginPlan(1, f.proposal, f.proof).valueOrFail(); close(owner)
            assertEquals(before, snapshot(box)); f.native.assertNoEffects()
        } finally { proceed.countDown(); first.cancelAndJoin() }
    }

    @Test fun simulatedPostDescriptorCloseFailureKeepsTerminalReservationWithoutNumericRetry() = runBlocking {
        val f = fixture(); val box = f.native.box; val before = snapshot(box); val keys = f.native.aliases()
        val fault = fault("after_descriptor_close"); val owner = owner(box, fault); owner.open().valueOrFail()
        terminals += owner to box; stores.remove(owner)
        failure(owner.close(), FailureReason.STORAGE_FAILURE)
        assertEquals(0, descriptors(box, "state.lock")); assertEquals(0, descriptors(box, "state.sqlite"))
        val events = fault.events.toList(); fault.failures.clear()
        repeat(3) { failure(owner.close(), FailureReason.STORAGE_FAILURE) }
        assertEquals(events, fault.events); unavailable(owner)
        val other = owner(box); failure(other.open(), FailureReason.STORAGE_FAILURE); close(other)
        assertEquals(0, descriptors(box, "state.lock")); assertEquals(0, descriptors(box, "state.sqlite"))
        assertEquals(before, snapshot(box)); assertEquals(keys, f.native.aliases())
    }

    private class Fixture(val native: AndroidWorkOriginPlanFixture, val predecessor: SessionControlRecord,
        val current: SessionControlRecord, val proposal: PrivateBytes, val proof: PrivateBytes)
    private suspend fun fixture(stage: String = "prepared"): Fixture {
        val f = AndroidWorkOriginPlanFixture(Dispatchers.IO).also(fixtures::add)
        f.initialize()
        val predecessor = f.record(); val plan = f.registry.planOrigin(AndroidWorkOriginPlanFixture.SCOPE, predecessor.revision).valueOrFail()
        val (proposal, proof) = proof(plan)
        if (stage == "selected" || stage == "sealed") f.registry.selectOrigin(plan).valueOrFail()
        if (stage == "sealed") f.registry.sealOrigin(plan).valueOrFail()
        if (stage == "aborted") f.registry.abortOrigin(plan).valueOrFail()
        val current = f.record(); f.close(); f.allowIds = false
        return Fixture(f, predecessor, current, proposal, proof)
    }
    private fun proof(plan: SessionWorkOriginPlan): Pair<PrivateBytes, PrivateBytes> {
        // Parse only this real test-generated canonical plan's final proof field. The production
        // session codec/facade owns structural validation; this SPI test manufactures no MAC.
        val raw = plan.copyForStorage().copyForCodec().decodeToString()
        val match = checkNotNull(Regex(",\"proof\":\"([0-9a-f]{128})\"\\}$").find(raw))
        val proof = ByteArray(64) { match.groupValues[1].substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return bytes(raw.substring(0, match.range.first) + "}") to PrivateBytes(proof)
    }
    private class Faults : StateActivationRecoveryFaults {
        val failures = mutableSetOf<String>(); var cancelAt: String? = null; var callback: ((String) -> Unit)? = null
        val events = mutableListOf<String>()
        override fun at(stage: String) {
            events += stage; callback?.invoke(stage)
            if (stage == cancelAt) throw CancellationException(PRIVATE_FAULT)
            if (stage in failures) throw IOException(PRIVATE_FAULT)
        }
    }
    private fun fault(stage: String? = null) = Faults().also { stage?.let(it.failures::add); faults += it }
    private fun owner(box: AndroidStateTestSandbox, fault: Faults = fault()) =
        AndroidSessionWorkStore.createRecoveryStoreForTests(box.directory, box.keyPrefix, fault).also(stores::add)
    private suspend fun close(owner: ExistingSessionWorkRecoveryStore) { owner.close().valueOrFail(); stores.remove(owner) }
    private suspend fun unavailable(owner: ExistingSessionWorkRecoveryStore) {
        failure(owner.read(), FailureReason.STALE_SESSION)
        failure(owner.compareAndSet(1, bytes("{}")), FailureReason.STALE_SESSION)
        failure(owner.verifyOriginPlan(1, bytes("{}"), PrivateBytes(ByteArray(64))), FailureReason.STALE_SESSION)
        failure(owner.verifyOriginPredecessor(SessionControlRecord(1, bytes("{}")), bytes("{}"), PrivateBytes(ByteArray(64))), FailureReason.STALE_SESSION)
    }
    private fun sameRecord(expected: SessionControlRecord, actual: SessionControlRecord) {
        assertEquals(expected.revision, actual.revision); sameBytes(expected.payload, actual.payload)
    }
    private fun sameBytes(a: PrivateBytes, b: PrivateBytes) = assertArrayEquals(a.copyForCodec(), b.copyForCodec())
    private fun aliases(box: AndroidStateTestSandbox) = box.keyStore().aliases().toList().filter { it.startsWith("${box.keyPrefix}.") }.toSet()
    private fun snapshot(box: AndroidStateTestSandbox): Map<String, String> = box.directory.listFiles()?.associate { file ->
        val stat = Os.lstat(file.path)
        val content = if (Files.isSymbolicLink(file.toPath())) "symlink:${Os.readlink(file.path)}"
            else if (file.name == "state.lock") "lock:${stat.st_size}"
            else MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        file.name to "${stat.st_ino}:${stat.st_mode}:${stat.st_size}:$content"
    } ?: emptyMap()
    private fun descriptors(box: AndroidStateTestSandbox, name: String): Int {
        val exact = File(box.directory, name).canonicalPath
        return File("/proc/self/fd").listFiles()!!.count { runCatching { Os.readlink(it.path) == exact }.getOrDefault(false) }
    }
    private fun sql(box: AndroidStateTestSandbox, vararg commands: String) {
        BundledSQLiteDriver().open(File(box.directory, "state.sqlite").canonicalPath,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW).use { db ->
            commands.forEach { command -> db.prepare(command).use { while (it.step()) { } } }
        }
    }
    private fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes); Os.chmod(file.path, 384) }
    private fun failure(result: PortResult<*>, expected: FailureReason) {
        assertTrue(result is PortResult.Failure); assertEquals(expected, (result as PortResult.Failure).reason)
        assertNull(result.retryAfterSeconds); assertFalse(result.toString().contains(PRIVATE_FAULT))
    }
    private suspend fun cancelled(action: suspend () -> Any?) {
        var caught = false; try { action() } catch (_: CancellationException) { caught = true }; assertTrue(caught)
    }
    private class CallerHandoffDispatcher : CoroutineDispatcher() {
        private val pending = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun next(): Runnable = checkNotNull(pending.poll(5, TimeUnit.SECONDS))
    }
    companion object {
        private const val PRIVATE_FAULT = "private-native-work-recovery-fixture"
        private val OPEN_STAGES = listOf("after_lock_open", "before_lock_acquire", "after_lock_acquire", "before_sqlite_open", "after_sqlite_open", "before_initialize")
        private val CLOSE_STAGES = listOf("before_sqlite_close", "before_lock_release", "before_channel_close", "before_descriptor_close", "before_reservation_release")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
    }
}
