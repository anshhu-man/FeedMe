package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.*
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Comparator
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Real SQLite and test-only metadata/key-presence vault; no native key or durability claim. */
class PlannedStateActivationInspectionTest {
    @Test fun preparedInspectionIsReadOnlyAndDoesNotCollectUnrelatedGarbage() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER))
            val garbage = "e".repeat(32); f.vault.keys += garbage
            f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('$garbage')")
            val before = f.observe()
            repeat(3) { status(StateActivationStatus.PREPARED, db.inspectPlannedActivation(OWNER, plan)) }
            f.assertUnchanged(before)
            assertEquals(3, f.connection.executed.count { it == "BEGIN" })
            assertEquals(3, f.connection.executed.count { it == "COMMIT" })
        }
    }

    @Test fun partialCandidatePresenceIsObservedWithoutAllocatingSelectingOrTestingItsCipher() = runBlocking {
        for (unusable in listOf(false, true)) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val key = decode(plan).keyId
            f.vault.keys += key
            if (unusable) f.vault.unusable += key
            val before = f.observe()
            status(StateActivationStatus.PARTIAL, db.inspectPlannedActivation(OWNER, plan))
            f.assertUnchanged(before)
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun selectedEmptyRemainsMetadataOnlyWhenItsKeyIsMissingOrUnusable() = runBlocking {
        for (damage in listOf("none", "missing", "unusable")) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER))
            ok(db.commitPlannedActivation(OWNER, plan)); val key = decode(plan).keyId
            if (damage == "missing") f.vault.keys.remove(key)
            if (damage == "unusable") f.vault.unusable += key
            val before = f.observe()
            status(StateActivationStatus.SELECTED_EMPTY, db.inspectPlannedActivation(OWNER, plan))
            f.assertUnchanged(before)
        }
    }

    @Test fun liveUnknownAndCorruptRowsCountAsNonemptyWithoutPayloadDecryption() = runBlocking {
        for (schema in listOf(1, Int.MAX_VALUE)) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            f.row(decode(plan).ownerTag, "zeroblob(29)", schema)
            val before = f.observe()
            status(StateActivationStatus.SELECTED_NONEMPTY, db.inspectPlannedActivation(OWNER, plan))
            f.assertUnchanged(before)
        }
    }

    @Test fun tombstonesAlonePreventAnEmptySelectionObservation() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            f.row(decode(plan).ownerTag, "NULL", Int.MAX_VALUE)
            val before = f.observe()
            status(StateActivationStatus.SELECTED_NONEMPTY, db.inspectPlannedActivation(OWNER, plan))
            f.assertUnchanged(before)
        }
    }

    @Test fun exactScopeAndNativeMacAreRequiredBeforeAnyDatabaseObservation() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val changed = plan.copyForStorage()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            val before = f.observe()
            for (scope in listOf(OWNER.copy(environment = "other"), OWNER.copy(actorId = "other"),
                OWNER.copy(actorKind = ActorKind.GUEST), OWNER.copy(actorKind = ActorKind.DEMO),
                OWNER.copy(actorId = "invalid-" + 0xD800.toChar()))) {
                rejected(FailureReason.INVALID_DATA, db.inspectPlannedActivation(scope, plan))
            }
            rejected(FailureReason.INVALID_DATA, db.inspectPlannedActivation(OWNER, StateActivationPlan(changed)))
            rejected(FailureReason.INVALID_DATA, db.inspectPlannedActivation(OWNER, StateActivationPlan(ByteArray(170))))
            f.assertUnchanged(before)
            assertTrue(f.connection.executed.isEmpty())
        }
    }

    @Test fun foreignInstallCannotInterpretAnOtherwiseValidPlan() = runBlocking {
        fixture { first -> fixture { second ->
            val plan = ok(first.open().planActivation(OWNER)); val db = second.open()
            val before = second.observe()
            rejected(FailureReason.INVALID_DATA, db.inspectPlannedActivation(OWNER, plan))
            second.assertUnchanged(before)
            assertTrue(second.connection.executed.isEmpty())
        } }
    }

    @Test fun competingAndNewerOwnerSelectionsFenceTheOldExactPlan() = runBlocking {
        for (previouslySelected in listOf(false, true)) fixture { f ->
            val db = f.open(); val old = ok(db.planActivation(OWNER))
            if (previouslySelected) {
                ok(db.commitPlannedActivation(OWNER, old))
                ok(assertNotNull(ok(db.resume(OWNER))).eraseScope(OWNER))
            }
            val newer = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, newer))
            val before = f.observe()
            rejected(FailureReason.STALE_SESSION, db.inspectPlannedActivation(OWNER, old))
            status(StateActivationStatus.SELECTED_EMPTY, db.inspectPlannedActivation(OWNER, newer))
            f.assertUnchanged(before)
        }
    }

    @Test fun exactAbortingAndAbortedReceiptsAreObservedWithoutRetryingCleanupOrAcknowledgement() = runBlocking {
        for (failedDeletion in listOf(false, true)) fixture { f ->
            val first = f.open(); val plan = ok(first.planActivation(OWNER))
            ok(first.commitPlannedActivation(OWNER, plan)); ok(first.close())
            val recovery = f.recover(plan)
            f.vault.failDelete = failedDeletion
            if (failedDeletion) rejected(FailureReason.STORAGE_FAILURE, recovery.abort()) else ok(recovery.abort())
            f.vault.failDelete = false; ok(recovery.close())
            val db = f.open(); val before = f.observe()
            repeat(2) { status(if (failedDeletion) StateActivationStatus.ABORTING else StateActivationStatus.ABORTED,
                db.inspectPlannedActivation(OWNER, plan)) }
            f.assertUnchanged(before)
            assertEquals(1L, f.scalar("SELECT revision FROM feedme_activation_aborts"))
        }
    }

    @Test fun ordinaryRetirementIsNotReinterpretedAsPlanAbortCompletion() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            ok(assertNotNull(ok(db.resume(OWNER))).eraseScope(OWNER))
            val before = f.observe()
            rejected(FailureReason.STALE_SESSION, db.inspectPlannedActivation(OWNER, plan))
            f.assertUnchanged(before)
        }
    }

    @Test fun malformedOwnerOrStrayPredecessorRowsNeverBecomePrepared() = runBlocking {
        for (damage in listOf("generation", "active", "key", "stray-row")) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            if (damage == "stray-row") f.row(record.ownerTag, "NULL", 1) else {
                ok(db.commitPlannedActivation(OWNER, plan))
                val value = when (damage) { "generation" -> "generation=0"; "active" -> "active=2"; else -> "key_id='${"x".repeat(32)}'" }
                f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_owners SET $value")
            }
            val before = f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.inspectPlannedActivation(OWNER, plan))
            f.assertUnchanged(before)
        }
    }

    @Test fun competingOwnerAndGarbageReferencesAreRejectedWithoutDeletingAnyKey() = runBlocking {
        for (selected in listOf(false, true)) for (reference in listOf("owner", "gc")) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            if (selected) ok(db.commitPlannedActivation(OWNER, plan))
            if (reference == "gc") f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('${record.keyId}')") else {
                ok(db.activate(OWNER.copy(actorId = "other-owner")))
                f.raw("UPDATE feedme_owners SET key_id='${record.keyId}' WHERE owner_tag<>'${record.ownerTag}'")
            }
            val before = f.observe()
            rejected(FailureReason.CONFLICT, db.inspectPlannedActivation(OWNER, plan))
            f.assertUnchanged(before)
        }
    }

    @Test fun closedAndCancelledQueuedInspectionsPerformNoVaultOrSqlCall() = runTest {
        fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler)); val plan = ok(db.planActivation(OWNER))
            val before = f.observe()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { db.inspectPlannedActivation(OWNER, plan) }
            pending.cancel(); assertFailsWith<CancellationException> { pending.await() }
            f.assertUnchanged(before); assertEquals(0, f.vault.indexed); assertTrue(f.connection.executed.isEmpty())
            ok(db.close()); val closed = f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.inspectPlannedActivation(OWNER, plan))
            f.assertUnchanged(closed); assertEquals(0, f.vault.indexed); assertTrue(f.connection.executed.isEmpty())
        }
    }

    @Test fun observationIsRedactedDetachedAndReturnsNeitherStoreNorRecoveryHandle() = runBlocking {
        fixture { f ->
            val db = f.open(); val original = ok(db.planActivation(OWNER)); val bytes = original.copyForStorage()
            val plan = StateActivationPlan(bytes); bytes.fill(0)
            val before = f.observe(); val observed = ok(db.inspectPlannedActivation(OWNER, plan))
            assertEquals(StateActivationStatus.PREPARED, observed.status)
            assertEquals("StateActivationInspection(<redacted>)", observed.toString())
            assertFalse((observed as Any) is PrivateStateStore)
            assertFalse((observed as Any) is StateActivationRecoveryHandle)
            assertContentEquals(original.copyForStorage(), plan.copyForStorage())
            f.assertUnchanged(before)
        }
    }

    @Test fun fingerprintObservationIsBoundedDetachedRedactedAndNotAuthority() {
        for (size in listOf(0, 31, 33, 64)) assertFailsWith<IllegalArgumentException> {
            StateActivationPlanObservation(StateActivationStatus.PREPARED, PrivateBytes(ByteArray(size)))
        }
        val bytes = ByteArray(32) { it.toByte() }
        val result = StateActivationPlanObservation(StateActivationStatus.SELECTED_EMPTY, PrivateBytes(bytes))
        bytes.fill(0); result.fingerprint.copyForCodec().fill(0)
        assertContentEquals(ByteArray(32) { it.toByte() }, result.fingerprint.copyForCodec())
        assertEquals("StateActivationPlanObservation(<redacted>)", result.toString())
        assertFalse((result as Any) is PrivateStateStore)
        assertFalse((result as Any) is StateActivationRecoveryHandle)
    }

    @Test fun metadataFingerprintIsStableReadOnlyAcrossRepeatedInspectionAndReopen() = runBlocking {
        fixture { f ->
            var db = f.open(); val plan = ok(db.planActivation(OWNER))
            val first = ok(db.inspectPlannedActivationState(OWNER, plan))
            val garbage = "e".repeat(32); f.vault.keys += garbage
            f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('$garbage')")
            val before = f.observe()
            repeat(3) { sameObservation(first, ok(db.inspectPlannedActivationState(OWNER, plan))) }
            f.assertUnchanged(before)
            assertEquals(3, f.connection.executed.count { it == "BEGIN" })
            assertEquals(3, f.connection.executed.count { it == "COMMIT" })
            // Existing ordinary open has its separate GC behavior. Remove only this fixture's
            // injected unrelated row/key before reopening; it is not part of observation.
            f.raw("DELETE FROM feedme_key_gc WHERE key_id='$garbage'"); f.vault.keys.remove(garbage)
            ok(db.close()); db = f.open()
            val reopened = f.observe()
            sameObservation(first, ok(db.inspectPlannedActivationState(OWNER, plan)))
            f.assertUnchanged(reopened)
        }
    }

    @Test fun sameEmptyPredecessorDoesNotGiveDistinctPlansTheSameFingerprint() = runBlocking {
        fixture { f ->
            val db = f.open(); val a = ok(db.planActivation(OWNER)); val b = ok(db.planActivation(OWNER))
            val before = f.observe()
            val first = ok(db.inspectPlannedActivationState(OWNER, a))
            val second = ok(db.inspectPlannedActivationState(OWNER, b))
            assertEquals(StateActivationStatus.PREPARED, first.status); assertEquals(first.status, second.status)
            differentObservation(first, second); f.assertUnchanged(before)
        }
    }

    @Test fun partialKeyPresenceChangesFingerprintButCipherUsabilityDoesNot() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val key = decode(plan).keyId
            val prepared = ok(db.inspectPlannedActivationState(OWNER, plan))
            f.vault.keys += key
            val partial = ok(db.inspectPlannedActivationState(OWNER, plan))
            assertEquals(StateActivationStatus.PARTIAL, partial.status); differentObservation(prepared, partial)
            f.vault.unusable += key
            val before = f.observe()
            sameObservation(partial, ok(db.inspectPlannedActivationState(OWNER, plan)))
            f.assertUnchanged(before)
        }
    }

    @Test fun selectedMissingKeyChangesSameStatusFingerprintWithoutRegeneratingIt() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            val key = decode(plan).keyId; val present = ok(db.inspectPlannedActivationState(OWNER, plan))
            f.vault.unusable += key
            val unusable = f.observe()
            sameObservation(present, ok(db.inspectPlannedActivationState(OWNER, plan)))
            f.assertUnchanged(unusable)
            f.vault.unusable.remove(key); f.vault.keys.remove(key)
            val missing = f.observe(); val observed = ok(db.inspectPlannedActivationState(OWNER, plan))
            assertEquals(StateActivationStatus.SELECTED_EMPTY, observed.status)
            differentObservation(present, observed); f.assertUnchanged(missing)
        }
    }

    @Test fun recordPayloadRevisionSchemaAndTombstonesAreNotFingerprintUsabilityEvidence() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            val empty = ok(db.inspectPlannedActivationState(OWNER, plan))
            f.row(decode(plan).ownerTag, "zeroblob(29)", 1)
            val nonempty = ok(db.inspectPlannedActivationState(OWNER, plan))
            assertEquals(StateActivationStatus.SELECTED_NONEMPTY, nonempty.status); differentObservation(empty, nonempty)
            for (changed in listOf("payload=zeroblob(30),revision=2", "payload=NULL,schema_version=2147483647,revision=3")) {
                f.raw("UPDATE feedme_records SET $changed")
                val before = f.observe()
                sameObservation(nonempty, ok(db.inspectPlannedActivationState(OWNER, plan)))
                f.assertUnchanged(before)
            }
        }
    }

    @Test fun alreadyAbortedReceiptReacknowledgementChangesFingerprintDespiteSameStatus() = runBlocking {
        fixture { f ->
            var db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan)); ok(db.close())
            var recovery = f.recover(plan); ok(recovery.abort()); ok(recovery.close())
            db = f.open(); val first = ok(db.inspectPlannedActivationState(OWNER, plan))
            assertEquals(StateActivationStatus.ABORTED, first.status)
            assertEquals(1L, f.scalar("SELECT revision FROM feedme_activation_aborts")); ok(db.close())
            recovery = f.recover(plan); ok(recovery.abort()); ok(recovery.close())
            db = f.open(); val before = f.observe(); val second = ok(db.inspectPlannedActivationState(OWNER, plan))
            assertEquals(first.status, second.status); differentObservation(first, second)
            assertEquals(2L, f.scalar("SELECT revision FROM feedme_activation_aborts"))
            sameObservation(second, ok(db.inspectPlannedActivationState(OWNER, plan))); f.assertUnchanged(before)
        }
    }

    @Test fun fingerprintRequiresExactScopePlanMacAndIssuingInstallBeforeSql() = runBlocking {
        fixture { f -> fixture { foreign ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val other = foreign.open()
            val changed = plan.copyForStorage().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val before = f.observe(); val foreignBefore = foreign.observe()
            for (scope in listOf(OWNER.copy(environment = "other"), OWNER.copy(actorId = "other"),
                OWNER.copy(actorKind = ActorKind.GUEST), OWNER.copy(actorKind = ActorKind.DEMO),
                OWNER.copy(actorId = "invalid-" + 0xD800.toChar())))
                rejected(FailureReason.INVALID_DATA, db.inspectPlannedActivationState(scope, plan))
            rejected(FailureReason.INVALID_DATA, db.inspectPlannedActivationState(OWNER, StateActivationPlan(changed)))
            rejected(FailureReason.INVALID_DATA, other.inspectPlannedActivationState(OWNER, plan))
            f.assertUnchanged(before); foreign.assertUnchanged(foreignBefore)
            assertTrue(f.connection.executed.isEmpty()); assertTrue(foreign.connection.executed.isEmpty())
        } }
    }

    @Test fun newerMalformedAndConflictingMetadataCannotProduceAFingerprint() = runBlocking {
        for (damage in listOf("newer", "malformed", "gc")) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            ok(db.commitPlannedActivation(OWNER, plan))
            val reason = when (damage) {
                "newer" -> {
                    ok(assertNotNull(ok(db.resume(OWNER))).eraseScope(OWNER))
                    val next = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, next))
                    FailureReason.STALE_SESSION
                }
                "malformed" -> {
                    f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_owners SET generation=0")
                    FailureReason.STORAGE_FAILURE
                }
                else -> {
                    f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('${record.keyId}')")
                    FailureReason.CONFLICT
                }
            }
            val before = f.observe()
            rejected(reason, db.inspectPlannedActivationState(OWNER, plan)); f.assertUnchanged(before)
        }
    }

    @Test fun fingerprintInspectionHonorsClosedAndQueuedCancellationGuards() = runTest {
        fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler)); val plan = ok(db.planActivation(OWNER))
            val before = f.observe()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { db.inspectPlannedActivationState(OWNER, plan) }
            pending.cancel(); assertFailsWith<CancellationException> { pending.await() }
            f.assertUnchanged(before); assertEquals(0, f.vault.indexed); assertTrue(f.connection.executed.isEmpty())
            ok(db.close()); val closed = f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.inspectPlannedActivationState(OWNER, plan))
            f.assertUnchanged(closed); assertEquals(0, f.vault.indexed); assertTrue(f.connection.executed.isEmpty())
        }
    }

    private suspend fun fixture(action: suspend (InspectionFixture) -> Unit) {
        val f = InspectionFixture(); try { action(f) } finally { f.close() }
    }

    companion object {
        private val OWNER = StorageScope("planned-inspection-environment", ActorKind.ACCOUNT, "private-inspection-owner")
        private fun decode(plan: StateActivationPlan) = StateActivationPlanCodec.decode(plan)
        private fun <T> ok(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun rejected(reason: FailureReason, result: PortResult<*>) =
            assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun status(expected: StateActivationStatus, result: PortResult<StateActivationInspection>) =
            assertEquals(expected, ok(result).status)
        private fun sameObservation(expected: StateActivationPlanObservation, actual: StateActivationPlanObservation) {
            assertEquals(expected.status, actual.status)
            assertContentEquals(expected.fingerprint.copyForCodec(), actual.fingerprint.copyForCodec())
        }
        private fun differentObservation(first: StateActivationPlanObservation, second: StateActivationPlanObservation) =
            assertFalse(first.fingerprint.copyForCodec().contentEquals(second.fingerprint.copyForCodec()))
    }

    private class InspectionFixture {
        private val directory = Files.createTempDirectory("feedme-activation-inspection-")
        private val file = directory.resolve("state.sqlite")
        val vault = InspectionVault()
        lateinit var connection: InspectionConnection
        private val databases = mutableListOf<EncryptedStateDatabase>()
        private val recoveries = mutableListOf<StateActivationRecoveryHandle>()
        suspend fun open(dispatcher: CoroutineDispatcher = Dispatchers.IO): EncryptedStateDatabase {
            connection = InspectionConnection(BundledSQLiteDriver().open(file.toString()))
            return ok(EncryptedStateDatabase.open(connection, vault, dispatcher)).also(databases::add)
        }
        suspend fun recover(plan: StateActivationPlan): StateActivationRecoveryHandle =
            ok(EncryptedStateDatabase.openActivationRecovery(BundledSQLiteDriver().open(file.toString()), vault, OWNER, plan))
                .also(recoveries::add)
        fun raw(vararg queries: String) = BundledSQLiteDriver().open(file.toString()).use { db ->
            queries.forEach { sql -> db.prepare(sql).use { while (it.step()) { } } }
        }
        fun row(tag: String, payload: String, schema: Int) = raw(
            "INSERT INTO feedme_records(owner_tag,record_tag,revision,schema_version,payload) VALUES('$tag','${"b".repeat(64)}',1,$schema,$payload)",
        )
        fun scalar(sql: String): Long = BundledSQLiteDriver().open(file.toString()).use { db ->
            db.prepare(sql).use { assertTrue(it.step()); it.getLong(0) }
        }
        fun observe(): Observation {
            connection.executed.clear(); vault.resetCounters()
            return Observation(Files.readAllBytes(file), vault.keys.toSet(), vault.unusable.toSet())
        }
        fun assertUnchanged(before: Observation) {
            assertContentEquals(before.bytes, Files.readAllBytes(file))
            assertEquals(before.keys, vault.keys); assertEquals(before.unusable, vault.unusable)
            assertEquals(0, vault.allocated); assertEquals(0, vault.created); assertEquals(0, vault.deleted)
            assertEquals(0, vault.sealed); assertEquals(0, vault.opened)
            assertFalse(connection.executed.any { sql ->
                sql == "BEGIN IMMEDIATE" || listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ", "PRAGMA ")
                    .any(sql.trim().uppercase()::startsWith)
            })
        }
        suspend fun close() {
            try { recoveries.forEach { it.close() }; databases.forEach { it.close() } }
            finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }

    private class Observation(val bytes: ByteArray, val keys: Set<String>, val unusable: Set<String>)
    private class InspectionConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
        val executed = mutableListOf<String>()
        override fun prepare(sql: String): SQLiteStatement {
            val statement = delegate.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean { executed += sql; return statement.step() }
            }
        }
    }
    private class InspectionVault : PlannedStateVault {
        private val indexKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "HmacSHA256")
        val keys = mutableSetOf<String>(); val unusable = mutableSetOf<String>()
        var indexed = 0; var allocated = 0; var created = 0; var deleted = 0; var sealed = 0; var opened = 0
        var failDelete = false
        fun resetCounters() { indexed = 0; allocated = 0; created = 0; deleted = 0; sealed = 0; opened = 0 }
        override fun index(input: ByteArray): ByteArray {
            indexed++
            return Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
        }
        override fun newOwnerKeyId(): String { allocated++; return UUID.randomUUID().toString().replace("-", "") }
        override fun createOwnerKey(): String = newOwnerKeyId().also(::createOwnerKey)
        override fun createOwnerKey(keyId: String) {
            created++; if (!keys.add(keyId)) throw StateVaultException()
        }
        override fun hasOwnerKey(keyId: String): Boolean {
            if (keyId in unusable) throw StateVaultException()
            return keyId in keys
        }
        override fun containsOwnerKey(keyId: String): Boolean = keyId in keys
        override fun deleteOwnerKey(keyId: String) {
            deleted++; if (failDelete) throw StateVaultException(); keys.remove(keyId)
        }
        override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            sealed++; throw AssertionError("Metadata inspection must not encrypt a record")
        }
        override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            opened++; throw AssertionError("Metadata inspection must not decrypt a record")
        }
    }
}
