package com.feedme.storage

import android.system.Os
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feedme.core.ports.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real existing-only SQLite/Keystore ownership, not physical power loss or startup consent. */
@RunWith(AndroidJUnit4::class)
class AndroidStateActivationRecoveryOwnerTest {
    private val sandboxes = mutableListOf<AndroidStateTestSandbox>()
    private val databases = mutableListOf<EncryptedStateDatabase>()
    private val owners = mutableListOf<StateActivationRecoveryOwner>()
    private val faults = mutableListOf<Faults>()
    private val terminalOwners = mutableListOf<Pair<Fixture, StateActivationRecoveryOwner>>()

    @After fun cleanup() = runBlocking {
        faults.forEach { it.failures.clear(); it.cancelAt = null }
        terminalOwners.forEach { (fixture, owner) ->
            // Only this synthetic post-close branch may remove its fixture without a close ack:
            // no lock/SQLite FD remains, and the poisoned process reservation is NOT released.
            assertEquals(0, lockDescriptors(fixture.box)); assertEquals(0, databaseDescriptors(fixture.box))
            failure(owner.close(), FailureReason.STORAGE_FAILURE)
        }
        // Never remove native evidence or aliases while any close is still unacknowledged.
        owners.asReversed().forEach { it.close().valueOrFail() }; owners.clear()
        databases.asReversed().forEach { it.close().valueOrFail() }; databases.clear()
        sandboxes.asReversed().forEach { it.close() }; sandboxes.clear()
    }

    @Test fun constructingRetainedOwnerDoesNoIoAndMissingPathNeverInitializesAnything() = runBlocking {
        val source = prepared(); val empty = sandbox(); val fault = fault()
        val candidate = owner(Fixture(empty, source.plan), fault)
        assertFalse(empty.directory.exists()); assertTrue(aliases(empty).isEmpty()); assertTrue(fault.events.isEmpty())
        assertEquals("StateActivationRecoveryOwner(<redacted>)", candidate.toString())
        failure(candidate.open(), FailureReason.STORAGE_FAILURE)
        assertFalse(empty.directory.exists()); assertTrue(aliases(empty).isEmpty())
        unavailable(candidate); close(candidate)
        assertFalse(empty.directory.exists()); assertTrue(aliases(empty).isEmpty())
    }

    @Test fun successfulOwnerPinsFullReadOnlyEvidenceAndOwnsExactlyOneLockDescriptor() = runBlocking {
        val f = prepared(); val before = snapshot(f.box); val keys = aliases(f.box)
        val candidate = owner(f); assertEquals(0, lockDescriptors(f.box))
        candidate.open().valueOrFail(); assertEquals(1, lockDescriptors(f.box))
        failure(candidate.open(), FailureReason.CONFLICT)
        val first = candidate.inspect().valueOrFail()
        assertEquals(StateActivationStatus.PREPARED, first.status)
        repeat(3) {
            val next = candidate.inspect().valueOrFail()
            assertEquals(first.status, next.status)
            assertArrayEquals(first.fingerprint.copyForCodec(), next.fingerprint.copyForCodec())
        }
        assertEquals("StateActivationPlanObservation(<redacted>)", first.toString())
        val detached = first.fingerprint.copyForCodec(); detached.fill(0)
        assertFalse(detached.contentEquals(candidate.inspect().valueOrFail().fingerprint.copyForCodec()))
        failure(candidate.binding(), FailureReason.STALE_SESSION)
        assertEquals(keys, aliases(f.box)); rejectCompetingOwner(f)
        close(candidate); assertEquals(0, lockDescriptors(f.box)); unavailable(candidate)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
    }

    @Test fun selectedOwnerReturnsOnlyExactDetachedReservedBindingAndRejectsWrongExpectedBytes() = runBlocking {
        val f = prepared("bound"); val candidate = owner(f); candidate.open().valueOrFail()
        assertEquals(StateActivationStatus.SELECTED_NONEMPTY, candidate.inspect().valueOrFail().status)
        val binding = candidate.binding().valueOrFail(); val record = checkNotNull(binding.record)
        assertNotNull(binding.target); assertEquals(2, record.schemaVersion); assertEquals(1L, record.revision)
        assertArrayEquals(BINDING.copyForCodec(), record.payload.copyForCodec())
        val targetBytes = checkNotNull(binding.target).copyForStorage()
        record.payload.copyForCodec().fill(0); targetBytes.fill(0)
        assertArrayEquals(BINDING.copyForCodec(), checkNotNull(candidate.binding().valueOrFail().record).payload.copyForCodec())
        failure(candidate.abort(), FailureReason.CONFLICT)
        failure(candidate.abort(PrivateBytes("wrong-private-binding".encodeToByteArray())), FailureReason.CONFLICT)
        assertTrue(aliases(f.box).contains(ownerAlias(f)))
        candidate.abort(BINDING).valueOrFail()
        assertEquals(StateActivationStatus.ABORTED, candidate.inspect().valueOrFail().status)
        failure(candidate.binding(), FailureReason.STALE_SESSION)
        assertFalse(aliases(f.box).contains(ownerAlias(f)))
        close(candidate)
        assertEquals(0L, scalar(f.box, "SELECT count(*) FROM feedme_records"))
        assertEquals(1L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
    }

    @Test fun partialNativeKeyAndAlreadyAbortedReplayUseExactChangedReceiptWithoutRegeneration() = runBlocking {
        val f = prepared("partial"); val candidate = owner(f); candidate.open().valueOrFail()
        assertEquals(StateActivationStatus.PARTIAL, candidate.inspect().valueOrFail().status)
        candidate.abort().valueOrFail(); val before = candidate.inspect().valueOrFail()
        assertEquals(StateActivationStatus.ABORTED, before.status); assertFalse(aliases(f.box).contains(ownerAlias(f)))
        candidate.abort().valueOrFail(); val after = candidate.inspect().valueOrFail()
        assertEquals(StateActivationStatus.ABORTED, after.status)
        assertFalse(before.fingerprint.copyForCodec().contentEquals(after.fingerprint.copyForCodec()))
        close(candidate); assertEquals(2L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
        val again = owner(f); again.open().valueOrFail(); again.abort().valueOrFail(); close(again)
        assertEquals(3L, scalar(f.box, "SELECT revision FROM feedme_activation_aborts"))
        assertFalse(aliases(f.box).contains(ownerAlias(f)))
    }

    @Test fun eachPartialOpenAfterNativeDescriptorAcquisitionRetainsOwnerUntilExplicitClose() = runBlocking {
        for (stage in listOf("after_lock_open", "before_lock_acquire", "after_lock_acquire",
            "before_sqlite_open", "after_sqlite_open", "before_initialize")) {
            val f = prepared(); val before = snapshot(f.box); val keys = aliases(f.box)
            val fault = fault(stage); val candidate = owner(f, fault)
            failure(candidate.open(), FailureReason.STORAGE_FAILURE)
            assertTrue("Requested acquisition fault was reached", stage in fault.events)
            assertEquals(1, lockDescriptors(f.box))
            assertFalse("Open failure must not auto-close its retained owner", fault.events.any { it in CLOSE_STAGES })
            rejectCompetingOwner(f); assertEquals(1, lockDescriptors(f.box))
            fault.failures.clear(); failure(candidate.open(), FailureReason.CONFLICT); unavailable(candidate)
            close(candidate); assertEquals(0, lockDescriptors(f.box))
            assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
            val again = owner(f); again.open().valueOrFail(); close(again)
        }
    }

    @Test fun failureBeforeOpeningNativeLockCanBeExplicitlyClosedWithoutCreatingAnything() = runBlocking {
        val f = prepared(); val before = snapshot(f.box); val keys = aliases(f.box)
        val fault = fault("before_lock_open"); val candidate = owner(f, fault)
        failure(candidate.open(), FailureReason.STORAGE_FAILURE)
        assertTrue("before_lock_open" in fault.events); assertEquals(0, lockDescriptors(f.box))
        rejectCompetingOwner(f); assertEquals(0, lockDescriptors(f.box))
        fault.failures.clear(); close(candidate)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
        val again = owner(f); again.open().valueOrFail(); close(again)
    }

    @Test fun everyInitializedCloseStageRetainsRetryableOwnershipAndNeverReopensSqlite() = runBlocking {
        for (stage in CLOSE_STAGES) {
            val f = prepared(); val before = snapshot(f.box); val keys = aliases(f.box)
            val fault = fault(); val candidate = owner(f, fault); candidate.open().valueOrFail()
            val opens = fault.events.count { it == "before_sqlite_open" }
            fault.failures += stage
            failure(candidate.close(), FailureReason.STORAGE_FAILURE)
            assertTrue("Requested close fault was reached", stage in fault.events)
            unavailable(candidate); rejectCompetingOwner(f)
            fault.failures.clear(); close(candidate)
            assertEquals(opens, fault.events.count { it == "before_sqlite_open" })
            assertEquals(0, lockDescriptors(f.box)); candidate.close().valueOrFail()
            assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
            val again = owner(f); again.open().valueOrFail(); close(again)
        }
    }

    @Test fun failedSqliteInitializationKeepsConnectionAndLockUntilCloseAcknowledges() = runBlocking {
        val f = prepared(); sql(f.box, "CREATE TABLE private_foreign_schema(value TEXT)")
        val before = snapshot(f.box); val keys = aliases(f.box); val fault = fault()
        val candidate = owner(f, fault)
        failure(candidate.open(), FailureReason.STORAGE_FAILURE)
        assertTrue("before_initialize" in fault.events); assertEquals(1, lockDescriptors(f.box)); unavailable(candidate)
        assertFalse(fault.events.any { it in CLOSE_STAGES })
        fault.failures += "before_sqlite_close"
        failure(candidate.close(), FailureReason.STORAGE_FAILURE); assertEquals(1, lockDescriptors(f.box))
        rejectCompetingOwner(f); fault.failures.clear(); close(candidate)
        assertEquals(0, lockDescriptors(f.box)); assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))
    }

    @Test fun acquisitionFailurePlusCloseFailureDoesNotDiscardTheOnlyNativeOwnershipReference() = runBlocking {
        for (stage in listOf("after_lock_acquire", "after_sqlite_open", "before_initialize")) {
            val f = prepared(); val fault = fault(stage); val candidate = owner(f, fault)
            failure(candidate.open(), FailureReason.STORAGE_FAILURE)
            fault.failures.clear(); fault.failures += "before_descriptor_close"
            failure(candidate.close(), FailureReason.STORAGE_FAILURE)
            assertEquals(1, lockDescriptors(f.box)); rejectCompetingOwner(f)
            fault.failures.clear(); close(candidate); assertEquals(0, lockDescriptors(f.box))
            val again = owner(f); again.open().valueOrFail(); close(again)
        }
    }

    @Test fun cancellationAtPartialOpenLeavesTheSameOwnerAvailableForExplicitClose() = runBlocking {
        for (stage in listOf("after_lock_open", "after_lock_acquire", "after_sqlite_open", "before_initialize")) {
            val f = prepared(); val fault = fault(); fault.cancelAt = stage
            val candidate = owner(f, fault); var cancelled = false
            try { candidate.open() } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled); assertEquals(1, lockDescriptors(f.box))
            assertFalse(fault.events.any { it in CLOSE_STAGES }); rejectCompetingOwner(f)
            fault.cancelAt = null; unavailable(candidate); close(candidate); assertEquals(0, lockDescriptors(f.box))
            val again = owner(f); again.open().valueOrFail(); close(again)
        }
    }

    @Test fun wrongScopePlanOrInstallNeverOpensSqliteAndCannotGainAuthorityByCallingOpenAgain() = runBlocking {
        val source = prepared(); val foreign = prepared()
        val altered = StateActivationPlan(source.plan.copyForStorage().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        for ((scope, plan) in listOf(OTHER to source.plan, OWNER to altered, OWNER to foreign.plan,
            StorageScope(OWNER.environment, ActorKind.DEMO, "private-demo") to source.plan,
            StorageScope(OWNER.environment, ActorKind.ACCOUNT, "\uD800") to source.plan)) {
            val before = snapshot(source.box); val keys = aliases(source.box); val fault = fault()
            val candidate = owner(source, fault, scope, plan)
            failure(candidate.open(), FailureReason.INVALID_DATA)
            assertFalse("Invalid capability must precede SQLite open", "before_sqlite_open" in fault.events)
            failure(candidate.open(), FailureReason.CONFLICT); unavailable(candidate)
            close(candidate); assertEquals(0, lockDescriptors(source.box))
            assertSnapshot(before, snapshot(source.box)); assertEquals(keys, aliases(source.box))
        }
    }

    @Test fun missingDatabaseLockOrIndexAndUnexpectedJournalNeverInitializeOrRepair() = runBlocking {
        for (damage in listOf("state.sqlite", "state.lock", "index", "state.sqlite-journal", "state.sqlite-wal", "unknown.bin")) {
            val f = prepared()
            when (damage) {
                "index" -> f.box.keyStore().deleteEntry("${f.box.keyPrefix}.index")
                "state.sqlite", "state.lock" -> Files.delete(File(f.box.directory, damage).toPath())
                else -> write(File(f.box.directory, damage), byteArrayOf(7, 8, 9))
            }
            val before = snapshot(f.box); val keys = aliases(f.box); val fault = fault(); val candidate = owner(f, fault)
            failure(candidate.open(), FailureReason.STORAGE_FAILURE)
            assertFalse("before_sqlite_open" in fault.events); unavailable(candidate); close(candidate)
            assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box)); assertEquals(0, lockDescriptors(f.box))
        }
    }

    @Test fun missingSelectedKeyStaysObservableButBoundCiphertextCannotBeInferredOrDeleted() = runBlocking {
        for (bound in listOf(false, true)) {
            val f = prepared(if (bound) "bound" else "selected"); f.box.keyStore().deleteEntry(ownerAlias(f))
            val before = snapshot(f.box); val keys = aliases(f.box); val candidate = owner(f); candidate.open().valueOrFail()
            assertEquals(if (bound) StateActivationStatus.SELECTED_NONEMPTY else StateActivationStatus.SELECTED_EMPTY,
                candidate.inspect().valueOrFail().status)
            failure(candidate.binding(), FailureReason.STORAGE_FAILURE)
            if (bound) {
                failure(candidate.abort(BINDING), FailureReason.STORAGE_FAILURE)
                close(candidate); assertSnapshot(before, snapshot(f.box))
            } else {
                candidate.abort().valueOrFail(); assertEquals(StateActivationStatus.ABORTED, candidate.inspect().valueOrFail().status)
                close(candidate)
            }
            assertEquals(keys, aliases(f.box)); assertFalse(aliases(f.box).contains(ownerAlias(f)))
        }
    }

    @Test fun exactAbortedOwnerDoesNotGarbageCollectUnrelatedKeyMarkersDuringOpenInspectOrClose() = runBlocking {
        val f = prepared("partial"); val vault = AndroidStateVault.openExisting(f.box.keyPrefix)
        val unrelated = vault.newOwnerKeyId(); vault.createOwnerKey(unrelated)
        sql(f.box, "INSERT INTO feedme_key_gc(key_id) VALUES('$unrelated')")
        val candidate = owner(f); candidate.open().valueOrFail(); candidate.abort().valueOrFail(); close(candidate)
        assertTrue(vault.containsOwnerKey(unrelated)); assertEquals(1L, scalar(f.box, "SELECT count(*) FROM feedme_key_gc"))
        val before = snapshot(f.box); val keys = aliases(f.box); val again = owner(f); again.open().valueOrFail()
        assertEquals(StateActivationStatus.ABORTED, again.inspect().valueOrFail().status); close(again)
        assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box)); assertTrue(vault.containsOwnerKey(unrelated))
    }

    @Test fun ambiguousPostDescriptorCloseRetainsTerminalReservationWithoutReusingItsFdNumber() = runBlocking {
        val f = prepared(); val before = snapshot(f.box); val keys = aliases(f.box)
        val fault = fault(); val candidate = owner(f, fault); candidate.open().valueOrFail()
        fault.failures += "after_descriptor_close"
        failure(candidate.close(), FailureReason.STORAGE_FAILURE)
        assertTrue("after_descriptor_close" in fault.events)
        assertEquals(0, lockDescriptors(f.box)); assertEquals(0, databaseDescriptors(f.box))
        // Keep the actual owner alive through teardown. Never acknowledge or bypass its poisoned
        // reservation; only the isolated instrumentation process exit can release that gate.
        owners.remove(candidate); terminalOwners += f to candidate
        val closes = fault.events.count { it == "before_descriptor_close" }
        fault.failures.clear()
        repeat(3) {
            failure(candidate.close(), FailureReason.STORAGE_FAILURE)
            assertEquals(closes, fault.events.count { it == "before_descriptor_close" })
            rejectCompetingOwner(f)
            assertEquals(0, lockDescriptors(f.box)); assertEquals(0, databaseDescriptors(f.box))
        }
        unavailable(candidate); assertSnapshot(before, snapshot(f.box)); assertEquals(keys, aliases(f.box))

        // A distinct, already-closed fixture supplies an exclusively owned raw driver connection.
        // It owns no sibling lifetime: this tests the close guard without bypassing a terminal
        // recovery owner to release a real held lock or process reservation.
        val direct = prepared(); val directBefore = snapshot(direct.box); val directKeys = aliases(direct.box)
        val driver = connection(direct.box); val sqliteFault = fault("before_sqlite_close")
        var nativeCloseCalls = 0
        val guarded = AndroidStateDatabase.RetainedRecoveryConnection(object : SQLiteConnection by driver {
            override fun close() { nativeCloseCalls++; driver.close() }
        }, sqliteFault)
        try {
            guarded.prepare("SELECT 1").use { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
            assertEquals(1, databaseDescriptors(direct.box)); assertEquals(0, lockDescriptors(direct.box))
            var beforeFailed = false
            try { guarded.close() } catch (_: IOException) { beforeFailed = true }
            assertTrue(beforeFailed); assertEquals(0, nativeCloseCalls)
            assertEquals(1, databaseDescriptors(direct.box))

            sqliteFault.failures.clear(); sqliteFault.failures += "after_sqlite_close"
            var afterFailed = false
            try { guarded.close() } catch (_: IOException) { afterFailed = true }
            assertTrue(afterFailed); assertEquals(1, nativeCloseCalls)
            assertEquals(0, databaseDescriptors(direct.box))
            val observedHooks = sqliteFault.events.toList(); sqliteFault.failures.clear()
            repeat(3) {
                var terminalFailure = false
                try { guarded.close() } catch (_: IllegalStateException) { terminalFailure = true }
                assertTrue(terminalFailure); assertEquals(1, nativeCloseCalls)
                assertEquals(observedHooks, sqliteFault.events)
                assertEquals(0, databaseDescriptors(direct.box))
            }
        } finally {
            // Cleanup an early assertion failure only if native close was never admitted. Once
            // admitted, never bypass the terminal wrapper or retry the driver's possible no-op.
            if (nativeCloseCalls == 0) driver.close()
            assertEquals(0, databaseDescriptors(direct.box))
        }
        assertSnapshot(directBefore, snapshot(direct.box)); assertEquals(directKeys, aliases(direct.box))
    }

    private data class Fixture(val box: AndroidStateTestSandbox, val plan: StateActivationPlan)
    private class Faults : StateActivationRecoveryFaults {
        val failures = mutableSetOf<String>(); var cancelAt: String? = null; val events = mutableListOf<String>()
        override fun at(stage: String) {
            events += stage
            if (cancelAt == stage) throw CancellationException("Synthetic retained owner cancellation")
            if (stage in failures) throw IOException("Synthetic private recovery owner fault")
        }
    }
    private fun sandbox() = AndroidStateTestSandbox().also { sandboxes += it }
    private fun fault(stage: String? = null) = Faults().also { value -> stage?.let { value.failures += it }; faults += value }
    private fun owner(f: Fixture, injection: Faults = fault(), scope: StorageScope = OWNER, plan: StateActivationPlan = f.plan) =
        AndroidStateDatabase.createActivationRecoveryOwnerForTests(f.box.directory, f.box.keyPrefix, scope, plan, injection)
            .also { owners += it }
    private suspend fun prepared(stage: String = "prepared"): Fixture {
        val box = sandbox(); val db = AndroidStateDatabase.openForTests(box.directory, box.keyPrefix).valueOrFail().also { databases += it }
        val plan = db.planActivation(OWNER).valueOrFail()
        if (stage == "selected" || stage == "bound") db.commitPlannedActivation(OWNER, plan).valueOrFail()
        if (stage == "bound") db.bindPlannedActivation(OWNER, plan, 2, BINDING).valueOrFail()
        db.close().valueOrFail(); databases.remove(db)
        if (stage == "partial") AndroidStateVault.openExisting(box.keyPrefix).createOwnerKey(StateActivationPlanCodec.decode(plan).keyId)
        return Fixture(box, plan)
    }
    private suspend fun close(owner: StateActivationRecoveryOwner) { owner.close().valueOrFail(); owners.remove(owner) }
    private suspend fun unavailable(owner: StateActivationRecoveryOwner) {
        failure(owner.inspect(), FailureReason.STALE_SESSION)
        failure(owner.binding(), FailureReason.STALE_SESSION)
        failure(owner.abort(), FailureReason.STALE_SESSION)
    }
    private suspend fun rejectCompetingOwner(f: Fixture) {
        val competing = owner(f); failure(competing.open(), FailureReason.STORAGE_FAILURE); close(competing)
    }
    private fun ownerAlias(f: Fixture) = "${f.box.keyPrefix}.owner.${StateActivationPlanCodec.decode(f.plan).keyId}"
    private fun aliases(box: AndroidStateTestSandbox) = box.keyStore().aliases().toList().filter { it.startsWith("${box.keyPrefix}.") }.toSet()
    private fun lockDescriptors(box: AndroidStateTestSandbox): Int {
        return descriptors(File(box.directory, "state.lock"))
    }
    private fun databaseDescriptors(box: AndroidStateTestSandbox): Int = descriptors(File(box.directory, "state.sqlite"))
    private fun descriptors(file: File): Int {
        val exact = file.canonicalPath
        return File("/proc/self/fd").listFiles()!!.count { entry -> try { Os.readlink(entry.path) == exact } catch (_: Exception) { false } }
    }
    private fun snapshot(box: AndroidStateTestSandbox): Map<String, ByteArray> = box.directory.listFiles()!!.associate { file ->
        // Opening and closing a sibling descriptor can release this process's POSIX file lock.
        // Never read the lock path, even if a future damaged fixture makes it nonempty.
        file.name to if (file.name == "state.lock") {
            assertEquals(0L, file.length()); byteArrayOf()
        } else file.readBytes()
    }
    private fun assertSnapshot(before: Map<String, ByteArray>, after: Map<String, ByteArray>) {
        assertEquals(before.keys, after.keys); before.forEach { (name, bytes) -> assertArrayEquals(bytes, after.getValue(name)) }
    }
    private fun connection(box: AndroidStateTestSandbox) = BundledSQLiteDriver().open(File(box.directory, "state.sqlite").path,
        SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW)
    private fun sql(box: AndroidStateTestSandbox, sql: String) = connection(box).use { db -> db.prepare(sql).use { while (it.step()) { } } }
    private fun scalar(box: AndroidStateTestSandbox, sql: String): Long = connection(box).use { db -> db.prepare(sql).use { assertTrue(it.step()); it.getLong(0) } }
    private fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes); Os.chmod(file.path, 384) }
    private fun failure(result: PortResult<*>, reason: FailureReason) { assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason) }
    companion object {
        private val OWNER = StorageScope("native-retained-recovery", ActorKind.ACCOUNT, "private-original-owner")
        private val OTHER = StorageScope("native-retained-recovery", ActorKind.ACCOUNT, "private-other-owner")
        private val BINDING = PrivateBytes("{\"synthetic\":\"exact-reserved-binding\"}".encodeToByteArray())
        private val CLOSE_STAGES = listOf("before_sqlite_close", "before_lock_release", "before_channel_close", "before_descriptor_close", "before_reservation_release")
    }
}
