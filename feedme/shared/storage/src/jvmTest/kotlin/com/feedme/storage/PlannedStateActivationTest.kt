package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.*
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Comparator
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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

/** Actual SQLite with exact test-only JCA keys. No native vault, abort or runtime-journal claim. */
class PlannedStateActivationTest {
    @Test fun absentOwnerPlanIsReadOnlyAndAllocatesOnlyAnUnstoredRandomIdentifier() = runBlocking {
        fixture { f ->
            val db = f.open(); f.observe()
            val before = Files.readAllBytes(f.file)
            val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            assertEquals(0L, record.priorGeneration); assertNull(record.priorKeyId)
            assertEquals(1L, record.selectedGeneration); assertEquals(2L, record.consumedGeneration)
            assertEquals(1, f.vault.allocations); assertTrue(f.vault.keys.isEmpty())
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_owners"))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_records"))
            assertEquals(1, f.connection.executed.count { it == "BEGIN" })
            assertEquals(1, f.connection.executed.count { it == "COMMIT" })
            f.assertReadOnly(); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun repeatedPlansDoNotReserveRowsKeysOrMutatePriorEvidence() = runBlocking {
        fixture { f ->
            val db = f.open(); f.observe(); val before = Files.readAllBytes(f.file)
            val plans = List(3) { ok(db.planActivation(OWNER)) }.map(::decode)
            assertEquals(3, plans.map { it.keyId }.toSet().size)
            assertEquals(1, plans.map { it.ownerTag }.toSet().size)
            assertTrue(plans.all { it.priorGeneration == 0L && it.priorKeyId == null })
            f.assertReadOnly(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(3, f.vault.allocations); assertTrue(f.vault.keys.isEmpty())
        }
    }

    @Test fun exactRetiredPredecessorIsBoundWithoutRevivingItsKeyOrOldHandle() = runBlocking {
        fixture { f ->
            val db = f.open(); val old = ok(db.activate(OWNER)); val oldKey = f.vault.keys.keys.single()
            ok(old.eraseScope(OWNER)); f.observe()
            val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            assertEquals(2L, record.priorGeneration); assertEquals(oldKey, record.priorKeyId)
            assertEquals(3L, record.selectedGeneration); assertEquals(4L, record.consumedGeneration)
            f.assertReadOnly(); assertTrue(f.vault.keys.isEmpty())
            assertEquals(Unit, ok(db.commitPlannedActivation(OWNER, plan)))
            assertEquals(setOf(record.keyId), f.vault.keys.keys)
            rejected(FailureReason.STALE_SESSION, old.commit(OWNER, listOf(put())))
            assertNull(ok(assertNotNull(ok(db.resume(OWNER))).read(OWNER, KEY)))
            assertEquals(3L, f.scalar("SELECT generation FROM feedme_owners"))
        }
    }

    @Test fun commitCreatesOnlyThePreselectedKeyAndReturnsAcknowledgementNotAStore() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            f.observe(); assertEquals(Unit, ok(db.commitPlannedActivation(OWNER, plan)))
            assertEquals(listOf(record.keyId), f.vault.exactCreated)
            assertEquals(0, f.vault.legacyCreates); assertTrue(f.vault.deleted.isEmpty())
            assertEquals(record.keyId, f.text("SELECT key_id FROM feedme_owners"))
            assertEquals(1L, f.scalar("SELECT generation FROM feedme_owners"))
            assertEquals(1L, f.scalar("SELECT active FROM feedme_owners"))
            val store = assertNotNull(ok(db.resume(OWNER)))
            ok(store.commit(OWNER, listOf(put("explicitly-resumed-data"))))
            assertRecord(store, OWNER, "explicitly-resumed-data")
        }
    }

    @Test fun exactSelectedReplayAfterReopenPreservesRowsAndCreatesNoKeysOrSqlWrites() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER))
            ok(db.commitPlannedActivation(OWNER, plan))
            val store = assertNotNull(ok(db.resume(OWNER)))
            ok(store.commit(OWNER, listOf(put("retained-secret"))))
            val second = RecordKey("future-collection", "tombstone")
            ok(store.commit(OWNER, listOf(StoreMutation.Put(second, null, 17, bytes("removed")))))
            ok(store.commit(OWNER, listOf(StoreMutation.Delete(second, 1))))
            ok(db.close()); val reopened = f.open(); f.observe()
            val before = Files.readAllBytes(f.file); val aliases = f.vault.keys.toMap()
            repeat(3) { assertEquals(Unit, ok(reopened.commitPlannedActivation(OWNER, StateActivationPlan(plan.copyForStorage())))) }
            f.assertReadOnly(allowWriteTransaction = true); assertEquals(0, f.vault.opens)
            assertContentEquals(before, Files.readAllBytes(f.file)); assertEquals(aliases, f.vault.keys)
            assertEquals(2L, f.scalar("SELECT count(*) FROM feedme_records"))
            assertRecord(assertNotNull(ok(reopened.resume(OWNER))), OWNER, "retained-secret")
        }
    }

    @Test fun activeOwnerCannotBePlannedEvenIfItIsEmptyOrItsKeyIsMissing() = runBlocking {
        for (missingKey in listOf(false, true)) fixture { f ->
            val db = f.open(); val store = ok(db.activate(OWNER))
            if (missingKey) f.vault.keys.clear()
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, db.planActivation(OWNER))
            f.assertReadOnly(); assertEquals(0, f.vault.allocations)
            assertContentEquals(before, Files.readAllBytes(f.file))
            if (!missingKey) ok(store.commit(OWNER, listOf(put())))
        }
    }

    @Test fun unfinishedRetiredPredecessorWithKeyOrGcEntryNeverGetsAReplacementPlan() = runBlocking {
        for (state in listOf("key-and-gc", "key-only", "gc-only")) fixture { f ->
            val db = f.open(); val old = ok(db.activate(OWNER)); val key = f.vault.keys.keys.single()
            f.vault.failDelete = true; rejected(FailureReason.STORAGE_FAILURE, old.eraseScope(OWNER)); f.vault.failDelete = false
            if (state == "key-only") f.raw("DELETE FROM feedme_key_gc")
            if (state == "gc-only") f.vault.keys.remove(key)
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, db.planActivation(OWNER))
            f.assertReadOnly(); assertEquals(0, f.vault.allocations)
            assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun candidateKeyCollisionWithAliasOwnerOrGcIsRejectedBeforeCreationOrCleanup() = runBlocking {
        for (collision in listOf("alias", "active-owner", "retired-owner", "gc")) fixture { f ->
            val db = f.open(); val candidate = "a".repeat(32)
            when (collision) {
                "alias" -> f.vault.seed(candidate)
                "active-owner", "retired-owner" -> {
                    val other = ok(db.activate(OTHER)); val key = f.vault.keys.keys.single()
                    if (collision == "retired-owner") ok(other.eraseScope(OTHER))
                    f.vault.nextId = key
                }
                "gc" -> f.raw("INSERT INTO feedme_key_gc VALUES('$candidate')")
            }
            if (f.vault.nextId == null) f.vault.nextId = candidate
            f.observe(); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            rejected(FailureReason.CONFLICT, db.planActivation(OWNER))
            f.assertReadOnly(); assertEquals(keys, f.vault.keys); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun candidateReferenceAddedAfterPlanningPreventsCommitWithoutDeletingAnyKey() = runBlocking {
        for (selected in listOf(false, true)) for (collision in listOf("owner", "gc")) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            if (selected) ok(db.commitPlannedActivation(OWNER, plan))
            if (collision == "owner") {
                ok(db.activate(OTHER))
                f.raw("UPDATE feedme_owners SET key_id='${record.keyId}' WHERE owner_tag<>'${record.ownerTag}'")
            } else f.raw("INSERT INTO feedme_key_gc VALUES('${record.keyId}')")
            f.observe(); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            rejected(FailureReason.CONFLICT, db.commitPlannedActivation(OWNER, plan))
            f.assertReadOnly(allowWriteTransaction = true); assertEquals(keys, f.vault.keys); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun changedAbsentOrRetiredPredecessorCannotBeOverwrittenByAnOldPlan() = runBlocking {
        for (retired in listOf(false, true)) fixture { f ->
            val db = f.open()
            if (retired) ok(ok(db.activate(OWNER)).eraseScope(OWNER))
            val plan = ok(db.planActivation(OWNER))
            val current = ok(db.activate(OWNER)); ok(current.commit(OWNER, listOf(put("newer-owner-data"))))
            f.observe(); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            rejected(FailureReason.STALE_SESSION, db.commitPlannedActivation(OWNER, plan))
            f.assertReadOnly(allowWriteTransaction = true); assertEquals(keys, f.vault.keys); assertContentEquals(before, Files.readAllBytes(f.file))
            assertRecord(current, OWNER, "newer-owner-data")
        }
    }

    @Test fun changedRetiredKeyAndMissingRetiredPredecessorCannotBeReinterpretedAsAbsent() = runBlocking {
        for (change in listOf("key", "missing")) fixture { f ->
            val db = f.open(); ok(ok(db.activate(OWNER)).eraseScope(OWNER))
            val plan = ok(db.planActivation(OWNER))
            f.raw(if (change == "key") "UPDATE feedme_owners SET key_id='${"f".repeat(32)}'" else "DELETE FROM feedme_owners")
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STALE_SESSION, db.commitPlannedActivation(OWNER, plan))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun retirementAndNewerSameOwnerSelectionPermanentlyFenceOldPlanReplay() = runBlocking {
        fixture { f ->
            val db = f.open(); val oldPlan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, oldPlan))
            val old = assertNotNull(ok(db.resume(OWNER))); ok(old.eraseScope(OWNER)); f.observe()
            rejected(FailureReason.STALE_SESSION, db.commitPlannedActivation(OWNER, oldPlan)); f.assertReadOnly(allowWriteTransaction = true)
            val newPlan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, newPlan)); f.observe()
            val keys = f.vault.keys.toMap(); rejected(FailureReason.STALE_SESSION, db.commitPlannedActivation(OWNER, oldPlan))
            f.assertReadOnly(allowWriteTransaction = true); assertEquals(keys, f.vault.keys)
            assertEquals(decode(newPlan).keyId, f.text("SELECT key_id FROM feedme_owners"))
        }
    }

    @Test fun selectedMissingOrUnusableKeyNeverRegeneratesMaterial() = runBlocking {
        for (unusable in listOf(false, true)) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            val key = decode(plan).keyId
            if (unusable) f.vault.unusable += key else f.vault.keys.remove(key)
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, db.commitPlannedActivation(OWNER, plan))
            f.assertReadOnly(allowWriteTransaction = true); assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(if (unusable) setOf(key) else emptySet(), f.vault.keys.keys)
        }
    }

    @Test fun malformedOwnerAndStrayRowsCannotBeUsedAsAnEmptyPredecessor() = runBlocking {
        for (damage in listOf("generation", "active", "key", "retired-row", "missing-owner-row")) fixture { f ->
            val db = f.open(); val store = ok(db.activate(OWNER)); ok(store.commit(OWNER, listOf(put())))
            when (damage) {
                "generation" -> f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_owners SET generation=0")
                "active" -> f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_owners SET active=2")
                "key" -> f.raw("UPDATE feedme_owners SET key_id='${"g".repeat(32)}'")
                "retired-row" -> { f.raw("UPDATE feedme_owners SET active=0,generation=2"); f.vault.keys.clear() }
                else -> f.raw("PRAGMA foreign_keys=OFF", "DELETE FROM feedme_owners")
            }
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, db.planActivation(OWNER))
            f.assertReadOnly(); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun corruptSelectedRowDoesNotGetDecryptedOrOverwrittenByAcknowledgementReplay() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            ok(assertNotNull(ok(db.resume(OWNER))).commit(OWNER, listOf(put())))
            f.raw("UPDATE feedme_records SET payload=zeroblob(29)"); f.observe()
            val before = Files.readAllBytes(f.file)
            assertEquals(Unit, ok(db.commitPlannedActivation(OWNER, plan)))
            f.assertReadOnly(allowWriteTransaction = true); assertEquals(0, f.vault.opens); assertContentEquals(before, Files.readAllBytes(f.file))
            rejected(FailureReason.STORAGE_FAILURE, assertNotNull(ok(db.resume(OWNER))).read(OWNER, KEY))
        }
    }

    @Test fun wrongScopeTamperedAndForeignInstallPlansFailBeforeAnyMutation() = runBlocking {
        fixture { f -> fixture { foreign ->
            val db = f.open(); val foreignDb = foreign.open(); val plan = ok(db.planActivation(OWNER))
            val otherPlan = ok(foreignDb.planActivation(OWNER)); f.observe()
            val before = Files.readAllBytes(f.file)
            for (scope in listOf(OTHER, OWNER.copy(environment = "elsewhere"), OWNER.copy(actorKind = ActorKind.GUEST)))
                rejected(FailureReason.INVALID_DATA, db.commitPlannedActivation(scope, plan))
            for (offset in listOf(0, 1, 65, 137, StateActivationPlan.ENCODED_SIZE - 1)) {
                val corrupted = plan.copyForStorage(); corrupted[offset] = (corrupted[offset].toInt() xor 1).toByte()
                rejected(FailureReason.INVALID_DATA, db.commitPlannedActivation(OWNER, StateActivationPlan(corrupted)))
            }
            rejected(FailureReason.INVALID_DATA, db.commitPlannedActivation(OWNER, otherPlan))
            f.assertReadOnly(); assertContentEquals(before, Files.readAllBytes(f.file))
        } }
    }

    @Test fun malformedScopeAndIdentifierProviderFailWithoutOwnerOrKeyCreation() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); f.observe()
            for (scope in listOf(OWNER.copy(actorKind = ActorKind.DEMO),
                OWNER.copy(environment = "unpaired-${0xD800.toChar()}"), OWNER.copy(actorId = "unpaired-${0xD800.toChar()}"))) {
                rejected(FailureReason.INVALID_DATA, db.planActivation(scope))
                rejected(FailureReason.INVALID_DATA, db.commitPlannedActivation(scope, plan))
            }
            for (id in listOf("", "a".repeat(31), "a".repeat(33), "G".repeat(32))) {
                f.vault.nextId = id; rejected(FailureReason.STORAGE_FAILURE, db.planActivation(OWNER))
            }
            f.assertReadOnly(); assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun generationOverflowReservesBothSelectionAndFutureConsumptionSlots() = runBlocking {
        for (generation in listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE)) fixture { f ->
            val db = f.open(); ok(ok(db.activate(OWNER)).eraseScope(OWNER))
            f.raw("UPDATE feedme_owners SET generation=$generation"); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.planActivation(OWNER)); f.assertReadOnly()
        }
        fixture { f ->
            val db = f.open(); ok(ok(db.activate(OWNER)).eraseScope(OWNER))
            f.raw("UPDATE feedme_owners SET generation=${Long.MAX_VALUE - 2}")
            val plan = ok(db.planActivation(OWNER))
            assertEquals(Long.MAX_VALUE - 1, decode(plan).selectedGeneration)
            assertEquals(Long.MAX_VALUE, decode(plan).consumedGeneration)
            ok(db.commitPlannedActivation(OWNER, plan))
        }
    }

    @Test fun unrelatedQueuedGcAndOtherOwnerDataSurvivePlanCommitAndSelectedReplay() = runBlocking {
        fixture { f ->
            val db = f.open(); val retired = ok(db.activate(OTHER)); val garbage = f.vault.keys.keys.single()
            f.vault.failDelete = true; rejected(FailureReason.STORAGE_FAILURE, retired.eraseScope(OTHER)); f.vault.failDelete = false
            // Seed GC after opening: ordinary database open intentionally performs GC separately.
            f.observe(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            ok(db.commitPlannedActivation(OWNER, plan))
            assertTrue(garbage in f.vault.keys); assertTrue(f.vault.deleted.isEmpty())
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            assertFalse(f.connection.executed.any { sql -> sql.startsWith("DELETE FROM feedme_key_gc") })
            assertEquals(setOf(garbage, decode(plan).keyId), f.vault.keys.keys)
        }
        fixture { f ->
            val db = f.open(); val other = ok(db.activate(OTHER)); ok(other.commit(OTHER, listOf(put("other-owner-secret"))))
            val otherKey = f.vault.keys.keys.single(); val plan = ok(db.planActivation(OWNER))
            ok(db.commitPlannedActivation(OWNER, plan)); ok(db.commitPlannedActivation(OWNER, plan))
            assertTrue(otherKey in f.vault.keys); assertRecord(other, OTHER, "other-owner-secret")
        }
    }

    @Test fun failedExactKeyCreationPreservesItsArtifactAndSamePlanCanCommitWithoutReplacement() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val key = decode(plan).keyId
            f.vault.afterCreate = { throw StateVaultException() }
            rejected(FailureReason.STORAGE_FAILURE, db.commitPlannedActivation(OWNER, plan))
            assertEquals(setOf(key), f.vault.keys.keys); assertTrue(f.vault.deleted.isEmpty())
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_owners"))
            val originalKey = f.vault.keys.getValue(key); f.vault.afterCreate = null
            ok(db.close()); val reopened = f.open(); ok(reopened.commitPlannedActivation(OWNER, plan))
            assertSame(originalKey, f.vault.keys.getValue(key)); assertEquals(listOf(key), f.vault.exactCreated)
            assertEquals(1L, f.scalar("SELECT generation FROM feedme_owners"))
        }
    }

    @Test fun realPrecommitRollbackRetainsExactKeyAndLostCommitResponseReplaysSelectedOwner() = runBlocking {
        for (fault in ActivationCommitFault.entries) fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val key = decode(plan).keyId
            f.connection.commitFault = fault
            rejected(if (fault == ActivationCommitFault.BEFORE) FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN,
                db.commitPlannedActivation(OWNER, plan))
            assertEquals(if (fault == ActivationCommitFault.BEFORE) 0L else 1L, f.scalar("SELECT count(*) FROM feedme_owners"))
            assertEquals(setOf(key), f.vault.keys.keys); assertTrue(f.vault.deleted.isEmpty())
            val original = f.vault.keys.getValue(key); ok(db.close()); val reopened = f.open()
            ok(reopened.commitPlannedActivation(OWNER, StateActivationPlan(plan.copyForStorage())))
            assertSame(original, f.vault.keys.getValue(key)); assertEquals(listOf(key), f.vault.exactCreated)
            assertEquals(key, f.text("SELECT key_id FROM feedme_owners"))
        }
    }

    @Test fun competingPlanAfterPartialCreationPreservesOldUnselectedKeyAsExplicitRecoveryLimit() = runBlocking {
        fixture { f ->
            val db = f.open(); val first = ok(db.planActivation(OWNER)); val oldKey = decode(first).keyId
            f.vault.afterCreate = { throw StateVaultException() }
            rejected(FailureReason.STORAGE_FAILURE, db.commitPlannedActivation(OWNER, first)); f.vault.afterCreate = null
            val second = ok(db.planActivation(OWNER)); val newKey = decode(second).keyId
            assertNotEquals(oldKey, newKey); ok(db.commitPlannedActivation(OWNER, second))
            rejected(FailureReason.STALE_SESSION, db.commitPlannedActivation(OWNER, first))
            assertEquals(setOf(oldKey, newKey), f.vault.keys.keys); assertTrue(f.vault.deleted.isEmpty())
            // This primitive cannot discover/abort the old plan. Runtime must retain one durable
            // owner-controlled pending plan; full activation recovery is a separate package.
            assertEquals(newKey, f.text("SELECT key_id FROM feedme_owners"))
        }
    }

    @Test fun legacyVaultFailsNotConfiguredWithoutCallingLegacyCreationOrCleanup() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); ok(db.close())
            val legacy = f.open(vault = object : StateVault by f.vault { })
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.NOT_CONFIGURED, legacy.planActivation(OWNER))
            rejected(FailureReason.NOT_CONFIGURED, legacy.commitPlannedActivation(OWNER, plan))
            f.assertReadOnly(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertTrue(f.connection.executed.isEmpty())
        }
    }

    @Test fun cancelledQueuedAndClosedCallsCannotPlanCreateOrMutate() = runTest {
        fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler)); val plan = ok(db.planActivation(OWNER)); f.observe()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { db.commitPlannedActivation(OWNER, plan) }
            queued.cancel(); assertFailsWith<CancellationException> { queued.await() }
            assertTrue(f.connection.executed.isEmpty()); f.assertReadOnly()
            ok(db.close()); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.planActivation(OWNER))
            rejected(FailureReason.STORAGE_FAILURE, db.commitPlannedActivation(OWNER, plan))
            assertTrue(f.connection.executed.isEmpty()); f.assertReadOnly()
        }
    }

    @Test fun cancellationAfterKeyCreationOrCommittedSelectionNeverDeletesOrRegeneratesKey() = runTest {
        for (afterCommit in listOf(false, true)) fixture { f ->
            val db = f.open(StandardTestDispatcher(testScheduler)); val plan = ok(db.planActivation(OWNER)); val key = decode(plan).keyId
            if (!afterCommit) f.vault.afterCreate = { throw CancellationException("synthetic-key-created") }
            val running = async(start = CoroutineStart.UNDISPATCHED) { db.commitPlannedActivation(OWNER, plan) }
            if (afterCommit) f.connection.afterWriteCommit = { running.cancel() }
            assertFailsWith<CancellationException> { running.await() }
            f.vault.afterCreate = null; f.connection.afterWriteCommit = null
            assertEquals(if (afterCommit) 1L else 0L, f.scalar("SELECT count(*) FROM feedme_owners"))
            assertEquals(setOf(key), f.vault.keys.keys); assertTrue(f.vault.deleted.isEmpty())
            ok(db.close()); val reopened = f.open(StandardTestDispatcher(testScheduler))
            ok(reopened.commitPlannedActivation(OWNER, plan)); assertEquals(listOf(key), f.vault.exactCreated)
        }
    }

    @Test fun planCopiesAndResultStringsDoNotLeakPrivateScopeOrMutableEncoding() = runBlocking {
        fixture { f ->
            val db = f.open(); val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            val original = plan.copyForStorage(); val supplied = original.copyOf(); val restored = StateActivationPlan(supplied)
            supplied.fill(0); plan.copyForStorage().fill(0)
            assertContentEquals(original, plan.copyForStorage()); assertContentEquals(original, restored.copyForStorage())
            val text = "$plan ${PortResult.Value(plan)} $record"
            for (privateText in listOf(OWNER.environment, OWNER.actorId, record.ownerTag, record.keyId)) assertFalse(privateText in text)
            for (privateText in listOf(OWNER.environment, OWNER.actorId)) assertFalse(privateText in original.decodeToString())
        }
    }

    private suspend fun fixture(block: suspend (ActivationFixture) -> Unit) {
        val fixture = ActivationFixture(); try { block(fixture) } finally { fixture.close() }
    }
    private suspend fun assertRecord(store: PrivateStateStore, scope: StorageScope, expected: String) =
        assertContentEquals(expected.encodeToByteArray(), assertNotNull(ok(store.read(scope, KEY))).payload.copyForCodec())
    companion object {
        private val OWNER = StorageScope("planned-state-private-environment", ActorKind.ACCOUNT, "planned-owner-private-a")
        private val OTHER = OWNER.copy(actorId = "unselected-owner-b")
        private val KEY = RecordKey("private-planned-record", "private-row")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun put(value: String = "private-content") = StoreMutation.Put(KEY, null, 1, bytes(value))
        private fun decode(plan: StateActivationPlan) = StateActivationPlanCodec.decode(plan)
        private fun <T> ok(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail("Expected planned state value, got ${result.reason}")
        }
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}

private class ActivationFixture {
    private val directory = Files.createTempDirectory("feedme-planned-state-test-")
    val file = directory.resolve("state.sqlite")
    val vault = ActivationVault()
    lateinit var connection: ActivationConnection
    private val managers = mutableListOf<EncryptedStateDatabase>()
    private var creates = 0; private var legacyCreates = 0; private var seals = 0
    suspend fun open(dispatcher: CoroutineDispatcher = Dispatchers.IO, vault: StateVault = this.vault): EncryptedStateDatabase {
        connection = ActivationConnection(BundledSQLiteDriver().open(file.toString()))
        return when (val result = EncryptedStateDatabase.open(connection, vault, dispatcher)) {
            is PortResult.Value -> result.value.also(managers::add)
            is PortResult.Failure -> fail("Expected fixture database, got ${result.reason}")
        }
    }
    fun raw(vararg queries: String) = BundledSQLiteDriver().open(file.toString()).use { c ->
        queries.forEach { sql -> c.prepare(sql).use { while (it.step()) { } } }
    }
    fun scalar(sql: String): Long = BundledSQLiteDriver().open(file.toString()).use { c ->
        c.prepare(sql).use { assertTrue(it.step()); it.getLong(0) }
    }
    fun text(sql: String): String = BundledSQLiteDriver().open(file.toString()).use { c ->
        c.prepare(sql).use { assertTrue(it.step()); it.getText(0) }
    }
    fun observe() {
        connection.executed.clear(); vault.deleted.clear(); vault.opens = 0; vault.allocations = 0
        creates = vault.exactCreated.size; legacyCreates = vault.legacyCreates; seals = vault.seals
    }
    fun assertReadOnly(allowWriteTransaction: Boolean = false) {
        assertEquals(creates, vault.exactCreated.size); assertEquals(legacyCreates, vault.legacyCreates)
        assertEquals(seals, vault.seals); assertTrue(vault.deleted.isEmpty())
        // Exact replay uses an immediate transaction to serialize predecessor/key checks. It may
        // acquire that lock but must not execute DML or alter stored bytes/material.
        assertFalse(connection.executed.any { activationMutation(it) || (!allowWriteTransaction && it == "BEGIN IMMEDIATE") })
    }
    suspend fun close() {
        try { managers.forEach { it.close() } } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

private enum class ActivationCommitFault { BEFORE, AFTER }
private fun activationMutation(sql: String) = listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ").any(sql.trim().uppercase()::startsWith)
private class ActivationConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    val executed = mutableListOf<String>()
    var commitFault: ActivationCommitFault? = null
    var afterWriteCommit: (() -> Unit)? = null
    private var writing = false
    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql
                if (sql == "BEGIN IMMEDIATE") writing = true
                if (sql == "BEGIN") writing = false
                val fault = if (sql == "COMMIT" && writing) commitFault.also { commitFault = null } else null
                if (fault == ActivationCommitFault.BEFORE) error("Synthetic precommit failure")
                val result = statement.step()
                if (sql == "COMMIT" && writing) { writing = false; afterWriteCommit?.invoke() }
                if (fault == ActivationCommitFault.AFTER) error("Synthetic committed reply loss")
                if (sql == "ROLLBACK") writing = false
                return result
            }
        }
    }
}

private class ActivationVault : PlannedStateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>()
    val unusable = mutableSetOf<String>()
    val exactCreated = mutableListOf<String>(); val deleted = mutableListOf<String>()
    var allocations = 0; var legacyCreates = 0; var seals = 0; var opens = 0
    var nextId: String? = null; var failDelete = false; var afterCreate: (() -> Unit)? = null
    override fun index(input: ByteArray) = Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    override fun newOwnerKeyId(): String { allocations++; return nextId ?: UUID.randomUUID().toString().replace("-", "") }
    override fun createOwnerKey(keyId: String) {
        if (keyId in keys) throw StateVaultException()
        seed(keyId); exactCreated += keyId; afterCreate?.invoke()
    }
    override fun createOwnerKey(): String {
        legacyCreates++
        return UUID.randomUUID().toString().replace("-", "").also(::seed)
    }
    fun seed(keyId: String) { keys[keyId] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey() }
    override fun hasOwnerKey(keyId: String): Boolean {
        if (keyId in unusable) throw StateVaultException()
        return keyId in keys
    }
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        seals++; val nonce = ByteArray(12).also(random::nextBytes)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce)); updateAAD(associatedData)
            byteArrayOf(1) + nonce + doFinal(plaintext)
        }
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = try {
        opens++; if (ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData); doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    } catch (_: Exception) { throw StateVaultException() }
    override fun deleteOwnerKey(keyId: String) {
        deleted += keyId; if (failDelete) throw StateVaultException(); keys.remove(keyId)
    }
}
