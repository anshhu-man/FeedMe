package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real SQLite, test-only JCA keys and explicit application-level faults; no fsync/power-loss claim. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlannedStateActivationRecoveryTest {
    @Test fun existingOnlyOpenAndPreparedInspectionDoNotInitializeWriteDecryptOrCollectKeys() = runBlocking {
        fixture { f ->
            val plan = f.prepare()
            val unrelated = "a".repeat(32); f.vault.seed(unrelated)
            f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('$unrelated')")
            val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap(); f.observe()
            val handle = f.recover(plan)
            repeat(3) { status(StateActivationStatus.PREPARED, handle) }
            f.assertNoMaterialEffects()
            assertContentEquals(before, Files.readAllBytes(f.file)); assertEquals(keys, f.vault.keys)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_activation_aborts"))
            assertFalse(handle is PrivateStateStore)
        }
    }

    @Test fun partialCandidateInspectionIsReadOnlyAndNeverSelectsItsOwner() = runBlocking {
        fixture { f ->
            val plan = f.prepare(); val record = decode(plan); f.vault.seed(record.keyId)
            val before = Files.readAllBytes(f.file); f.observe()
            val handle = f.recover(plan); status(StateActivationStatus.PARTIAL, handle)
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_owners"))
            assertTrue(record.keyId in f.vault.keys)
        }
    }

    @Test fun selectedEmptyInspectionAllowsMissingKeyButNeverRecreatesIt() = runBlocking {
        for (missing in listOf(false, true)) fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan)
            if (missing) f.vault.keys.remove(record.keyId)
            val before = Files.readAllBytes(f.file); f.observe()
            val handle = f.recover(plan); status(StateActivationStatus.SELECTED_EMPTY, handle)
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(!missing, record.keyId in f.vault.keys)
        }
    }

    @Test fun selectedRowsIncludingTombstonesUnknownSchemasAndCorruptCiphertextAreNeverDecryptedOrAborted() = runBlocking {
        for (payload in listOf("NULL", "X'${"00".repeat(29)}'")) fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan)
            f.row(record.ownerTag, payload = payload, schema = Int.MAX_VALUE)
            val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap(); f.observe()
            val handle = f.recover(plan); status(StateActivationStatus.SELECTED_NONEMPTY, handle)
            rejected(FailureReason.CONFLICT, handle.abort())
            status(StateActivationStatus.SELECTED_NONEMPTY, handle)
            f.assertNoMaterialEffects(); assertEquals(keys, f.vault.keys)
            assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_activation_aborts"))
        }
    }

    @Test fun preparedAbortConsumesBothReservedGenerationsWithoutCreatingAnyOwnerKey() = runBlocking {
        fixture { f ->
            val plan = f.prepare(); val record = decode(plan); f.observe()
            val handle = f.recover(plan)
            assertEquals(Unit, ok(handle.abort())); status(StateActivationStatus.ABORTED, handle)
            consumed(f, record, revision = 1)
            assertTrue(f.vault.keys.isEmpty()); assertEquals(0, f.vault.created)
            assertEquals(0, f.vault.allocated); assertEquals(0, f.vault.sealed); assertEquals(0, f.vault.opened)
        }
    }

    @Test fun partialAndSelectedEmptyAbortsCommitExactConsumptionBeforeDeletingOnlyPlannedKey() = runBlocking {
        for (selected in listOf(false, true)) fixture { f ->
            val plan = f.prepare(selected = selected); val record = decode(plan)
            if (!selected) f.vault.seed(record.keyId)
            val unrelated = "b".repeat(32); f.vault.seed(unrelated)
            f.observe()
            f.vault.beforeDelete = { key ->
                assertEquals(record.keyId, key); consumed(f, record, revision = 1)
                assertTrue(f.events.contains("COMMIT_ACK"), "Key deletion must follow a returned real COMMIT")
            }
            val handle = f.recover(plan); ok(handle.abort())
            status(StateActivationStatus.ABORTED, handle)
            assertEquals(listOf(record.keyId), f.vault.deleted)
            assertEquals(setOf(unrelated), f.vault.keys.keys)
            assertTrue(f.events.indexOf("COMMIT_ACK") < f.events.indexOf("DELETE_KEY:${record.keyId}"))
        }
    }

    @Test fun fullyRetiredPredecessorAbortsToItsExactConsumedGenerationWithoutTouchingOldKey() = runBlocking {
        for (selected in listOf(false, true)) fixture { f ->
            val plan = f.prepare(selected = selected, retired = true); val record = decode(plan)
            assertEquals(2L, record.priorGeneration); assertNotNull(record.priorKeyId)
            if (!selected) f.vault.seed(record.keyId)
            f.observe(); val handle = f.recover(plan); ok(handle.abort())
            consumed(f, record, revision = 1)
            assertEquals(4L, record.consumedGeneration)
            assertEquals(listOf(record.keyId), f.vault.deleted)
            assertFalse(record.priorKeyId in f.vault.deleted)
        }
    }

    @Test fun abortedRetryAlwaysChangesAndCommitsReceiptEvenWhenKeyIsAlreadyAbsent() = runBlocking {
        fixture { f ->
            val plan = f.prepare(); val record = decode(plan); val first = f.recover(plan)
            ok(first.abort()); ok(first.close()); f.observe()
            val reopened = f.recover(StateActivationPlan(plan.copyForStorage()))
            status(StateActivationStatus.ABORTED, reopened); f.assertNoMaterialEffects()
            repeat(3) { index ->
                f.events.clear(); ok(reopened.abort()); consumed(f, record, revision = index + 2L)
                assertTrue(f.events.contains("COMMIT_ACK"))
                assertTrue(f.sql.executed.any(::recoveryDml), "An ABORTED retry must not merely read back")
                assertEquals(0, f.vault.created); status(StateActivationStatus.ABORTED, reopened)
            }
        }
    }

    @Test fun keyDeletionFailureRetainsConsumedReceiptAndReopensAsAbortingWithoutGc() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan)
            val unrelated = "c".repeat(32); f.vault.seed(unrelated)
            f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('$unrelated')")
            val handle = f.recover(plan); f.vault.failDelete = true
            assertIs<PortResult.Failure>(handle.abort()); consumed(f, record, revision = 1)
            assertTrue(record.keyId in f.vault.keys); ok(handle.close())
            f.vault.failDelete = false; f.observe()
            val reopened = f.recover(plan); status(StateActivationStatus.ABORTING, reopened)
            f.assertNoMaterialEffects(); assertTrue(unrelated in f.vault.keys)
            ok(reopened.abort()); consumed(f, record, revision = 2)
            status(StateActivationStatus.ABORTED, reopened)
            assertEquals(listOf(record.keyId), f.vault.deleted)
            assertEquals(setOf(unrelated), f.vault.keys.keys)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
        }
    }

    @Test fun realPrecommitRollbackDoesNotConsumeOrDeleteAndOriginalPlanRemainsRetryable() = runBlocking {
        for (selected in listOf(false, true)) fixture { f ->
            val plan = f.prepare(selected = selected); val record = decode(plan)
            if (!selected) f.vault.seed(record.keyId)
            val handle = f.recover(plan); f.observe(); f.sql.commitFault = RecoveryCommitFault.BEFORE
            rejected(FailureReason.STORAGE_FAILURE, handle.abort())
            assertTrue(f.vault.deleted.isEmpty()); assertTrue(record.keyId in f.vault.keys)
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_activation_aborts"))
            assertEquals(if (selected) 1L else 0L, f.scalar("SELECT count(*) FROM feedme_owners"))
            ok(handle.close()); val reopened = f.recover(plan)
            status(if (selected) StateActivationStatus.SELECTED_EMPTY else StateActivationStatus.PARTIAL, reopened)
            ok(reopened.abort()); consumed(f, record, revision = 1)
        }
    }

    @Test fun lostApplicationReceiptAfterSuccessfulSqliteCommitNeverAuthorizesImmediateKeyDeletion() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan)
            val handle = f.recover(plan); f.observe(); f.sql.commitFault = RecoveryCommitFault.AFTER
            rejected(FailureReason.OUTCOME_UNKNOWN, handle.abort())
            consumed(f, record, revision = 1)
            assertTrue(f.events.contains("COMMIT_ACK")); assertTrue(f.vault.deleted.isEmpty())
            assertTrue(record.keyId in f.vault.keys)
            ok(handle.close()); f.observe()
            val reopened = f.recover(plan); status(StateActivationStatus.ABORTING, reopened)
            f.vault.beforeDelete = { consumed(f, record, revision = 2) }
            ok(reopened.abort()); status(StateActivationStatus.ABORTED, reopened)
            assertEquals(listOf(record.keyId), f.vault.deleted)
        }
    }

    @Test fun abortedReceiptWriteFailureRemainsFailureEvenThoughInspectionAlreadySaysAborted() = runBlocking {
        for (fault in RecoveryCommitFault.entries) fixture { f ->
            val plan = f.prepare(); val record = decode(plan); val handle = f.recover(plan)
            ok(handle.abort()); f.observe(); f.sql.commitFault = fault
            rejected(if (fault == RecoveryCommitFault.BEFORE) FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN, handle.abort())
            assertTrue(f.vault.deleted.isEmpty())
            consumed(f, record, revision = if (fault == RecoveryCommitFault.BEFORE) 1 else 2)
            ok(handle.close()); val reopened = f.recover(plan); status(StateActivationStatus.ABORTED, reopened)
            ok(reopened.abort()); consumed(f, record, revision = if (fault == RecoveryCommitFault.BEFORE) 2 else 3)
        }
    }

    @Test fun failureAfterDeletingExactKeyStillRequiresAnotherChangedReceiptOnReopen() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan)
            val handle = f.recover(plan); f.vault.afterDelete = { error("private-provider-canary") }
            assertIs<PortResult.Failure>(handle.abort()); assertFalse(record.keyId in f.vault.keys)
            consumed(f, record, revision = 1); ok(handle.close()); f.vault.afterDelete = null
            val reopened = f.recover(plan); status(StateActivationStatus.ABORTED, reopened)
            ok(reopened.abort()); consumed(f, record, revision = 2)
            assertEquals(0, f.vault.created)
        }
    }

    @Test fun secondaryTransactionFailureAfterKeyDeletionPreservesPriorAcknowledgedConsumption() = runBlocking {
        for (fault in RecoveryCommitFault.entries) fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan); val handle = f.recover(plan)
            f.observe(); f.sql.emptyWriteCommitFault = fault
            rejected(if (fault == RecoveryCommitFault.BEFORE) FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN, handle.abort())
            assertFalse(record.keyId in f.vault.keys); consumed(f, record, revision = 1)
            assertEquals(listOf(record.keyId), f.vault.deleted)
            ok(handle.close()); val reopened = f.recover(plan); status(StateActivationStatus.ABORTED, reopened)
            ok(reopened.abort()); consumed(f, record, revision = 2)
        }
    }

    @Test fun cancellationBeforeConsumptionRollsBackAndCancellationAfterCommitPreservesKey() = runBlocking {
        for (afterCommit in listOf(false, true)) fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan); val handle = f.recover(plan)
            f.observe()
            if (afterCommit) f.sql.afterCommit = { throw CancellationException("private-cancel-canary") }
            else f.sql.beforeCommit = { throw CancellationException("private-cancel-canary") }
            assertFailsWith<CancellationException> { handle.abort() }
            assertTrue(f.vault.deleted.isEmpty()); assertTrue(record.keyId in f.vault.keys)
            assertEquals(if (afterCommit) 1L else 0L, f.scalar("SELECT count(*) FROM feedme_activation_aborts"))
            f.sql.afterCommit = null; f.sql.beforeCommit = null; ok(handle.close())
            val reopened = f.recover(plan); ok(reopened.abort())
            consumed(f, record, revision = if (afterCommit) 2 else 1)
        }
    }

    @Test fun callerCancellationAtCommitAcknowledgementCannotContinueIntoKeyDeletion() = runTest {
        fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val opening = async { f.recover(plan, dispatcher = dispatcher) }; runCurrent(); val handle = opening.await()
            val running = async(start = CoroutineStart.LAZY) { handle.abort() }
            f.sql.afterCommit = { running.cancel() }
            running.start(); runCurrent(); assertFailsWith<CancellationException> { running.await() }
            assertTrue(f.vault.deleted.isEmpty()); assertTrue(record.keyId in f.vault.keys)
            consumed(f, record, revision = 1)
            f.sql.afterCommit = null
            val closing = async { handle.close() }; runCurrent(); ok(closing.await())
            val reopened = f.recover(plan); ok(reopened.abort()); consumed(f, record, revision = 2)
        }
    }

    @Test fun queuedCancellationAndClosedHandleCannotInspectOrAbort() = runTest {
        fixture { f ->
            val plan = f.prepare(selected = true); val dispatcher = StandardTestDispatcher(testScheduler)
            val opening = async { f.recover(plan, dispatcher = dispatcher) }; runCurrent(); val handle = opening.await()
            f.observe()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { handle.abort() }
            queued.cancel(); runCurrent(); assertFailsWith<CancellationException> { queued.await() }
            f.assertNoMaterialEffects()
            val closing = async { handle.close() }; runCurrent(); ok(closing.await())
            val inspect = async { handle.inspect() }; runCurrent(); rejected(FailureReason.STORAGE_FAILURE, inspect.await())
            val abort = async { handle.abort() }; runCurrent(); rejected(FailureReason.STORAGE_FAILURE, abort.await())
            f.assertNoMaterialEffects()
        }
    }

    @Test fun recordWrittenAfterEmptyInspectionIsRecheckedBeforeConsumptionOrDeletion() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan); val handle = f.recover(plan)
            status(StateActivationStatus.SELECTED_EMPTY, handle)
            f.row(record.ownerTag); val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.CONFLICT, handle.abort())
            status(StateActivationStatus.SELECTED_NONEMPTY, handle)
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_activation_aborts"))
        }
    }

    @Test fun consumptionFencesOldPlanCommitAndRecoveryCannotDeleteANewerIncarnation() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan); val handle = f.recover(plan)
            ok(handle.abort()); ok(handle.close())
            val db = f.normal()
            rejected(FailureReason.STALE_SESSION, db.commitPlannedActivation(OWNER, plan))
            val fresh = ok(db.activate(OWNER)); val newerKey = f.vault.keys.keys.single()
            ok(fresh.commit(OWNER, listOf(StoreMutation.Put(KEY, null, 1, bytes("newer-private-data")))))
            ok(db.close()); val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STALE_SESSION, f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(setOf(newerKey), f.vault.keys.keys)
            assertEquals(record.consumedGeneration + 1, f.scalar("SELECT generation FROM feedme_owners"))
        }
    }

    @Test fun changedOrMissingRetiredPredecessorIsNotReinterpretedAsTheOriginalAbsentOwner() = runBlocking {
        for (change in listOf("missing", "key", "generation", "active")) fixture { f ->
            val plan = f.prepare(retired = true); val record = decode(plan)
            when (change) {
                "missing" -> f.raw("DELETE FROM feedme_owners")
                "key" -> f.raw("UPDATE feedme_owners SET key_id='${"d".repeat(32)}'")
                "generation" -> f.raw("UPDATE feedme_owners SET generation=generation+2")
                else -> f.raw("UPDATE feedme_owners SET active=1")
            }
            f.vault.seed(record.keyId); val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STALE_SESSION, f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertTrue(record.keyId in f.vault.keys)
        }
    }

    @Test fun ordinaryRetirementIsNotMistakenForAnAuthenticatedActivationAbortReceipt() = runBlocking {
        fixture { f ->
            val db = f.normal(); val plan = ok(db.planActivation(OWNER)); ok(db.commitPlannedActivation(OWNER, plan))
            val store = assertNotNull(ok(db.resume(OWNER))); ok(store.eraseScope(OWNER)); ok(db.close())
            assertEquals(decode(plan).consumedGeneration, f.scalar("SELECT generation FROM feedme_owners"))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_activation_aborts"))
            val before = Files.readAllBytes(f.file); f.observe()
            assertIs<PortResult.Failure>(f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun candidateReferencesOrGarbageMarkersAddedBeforeOrAfterInspectionBlockAbort() = runBlocking {
        for (selected in listOf(false, true)) for (reference in listOf("active", "inactive", "gc")) fixture { f ->
            val plan = f.prepare(selected = selected); val record = decode(plan)
            if (!selected) f.vault.seed(record.keyId)
            val handle = f.recover(plan)
            if (reference == "gc") f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('${record.keyId}')")
            else f.raw("INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES('${"f".repeat(64)}',1,${if (reference == "active") 1 else 0},'${record.keyId}')")
            val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.CONFLICT, handle.inspect()); rejected(FailureReason.CONFLICT, handle.abort())
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file)); ok(handle.close())
            rejected(FailureReason.CONFLICT, f.recoveryResult(plan))
            assertTrue(record.keyId in f.vault.keys)
        }
    }

    @Test fun historicalReceiptKeyCollisionsAndMalformedReceiptMetadataFencePlanningCommitAndRecovery() = runBlocking {
        // A real completed abort remains a key reference after that owner advances again. A
        // deterministic test candidate exercises the otherwise improbable random-ID collision.
        for (sameOwner in listOf(false, true)) fixture { f ->
            val receiptScope = if (sameOwner) OWNER else OTHER
            val initial = f.normal(); val oldPlan = ok(initial.planActivation(receiptScope)); ok(initial.close())
            val oldRecord = decode(oldPlan)
            val oldRecovery = ok(f.recoveryResult(oldPlan, scope = receiptScope))
            ok(oldRecovery.abort()); ok(oldRecovery.close())
            val db = f.normal(); ok(ok(db.activate(receiptScope)).eraseScope(receiptScope))
            assertEquals(oldRecord.keyId, f.text("SELECT key_id FROM feedme_activation_aborts"))
            assertNotEquals(oldRecord.keyId, f.text("SELECT key_id FROM feedme_owners"))
            f.vault.nextCandidate = oldRecord.keyId
            val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap(); f.observe()
            rejected(FailureReason.CONFLICT, db.planActivation(OWNER))
            assertEquals(1, f.vault.allocated); assertEquals(0, f.vault.created)
            assertTrue(f.vault.deleted.isEmpty()); assertEquals(keys, f.vault.keys)
            assertContentEquals(before, Files.readAllBytes(f.file))
        }
        // A competing historical receipt appearing after planning must be checked on both
        // selected replay and initial commit, and again on an already opened recovery handle.
        for (sameOwner in listOf(false, true)) for (selected in listOf(false, true)) fixture { f ->
            val plan = f.prepare(selected = selected, retired = sameOwner); val record = decode(plan)
            if (!selected) f.vault.seed(record.keyId)
            val handle = f.recover(plan)
            val receiptTag = if (sameOwner) record.ownerTag else "6".repeat(64)
            if (!sameOwner) f.raw("INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES('$receiptTag',4,0,'${"6".repeat(32)}')")
            f.raw("INSERT INTO feedme_activation_aborts(owner_tag,generation,key_id,revision) VALUES('$receiptTag',${if (sameOwner) record.priorGeneration - 1 else 2},'${record.keyId}',1)")
            val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap(); f.observe()
            rejected(FailureReason.CONFLICT, handle.inspect()); rejected(FailureReason.CONFLICT, handle.abort())
            f.assertNoMaterialEffects(); ok(handle.close())
            rejected(FailureReason.CONFLICT, f.recoveryResult(plan))
            val db = f.normal(); f.observe()
            rejected(FailureReason.CONFLICT, db.commitPlannedActivation(OWNER, plan))
            f.assertNoMaterialEffects(); assertEquals(keys, f.vault.keys)
            assertContentEquals(before, Files.readAllBytes(f.file))
        }
        // Format validation is global even when the malformed receipt does not name this key.
        for (selected in listOf(false, true)) fixture { f ->
            val plan = f.prepare(selected = selected); val otherTag = "7".repeat(64)
            f.raw("INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES('$otherTag',2,0,'${"7".repeat(32)}')",
                "PRAGMA ignore_check_constraints=ON",
                "INSERT INTO feedme_activation_aborts(owner_tag,generation,key_id,revision) VALUES('$otherTag',2,'${"7".repeat(32)}',0)")
            val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            val db = f.normal(); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, db.planActivation(OTHER))
            assertEquals(0, f.vault.created); assertTrue(f.vault.deleted.isEmpty())
            f.observe(); rejected(FailureReason.STORAGE_FAILURE, db.commitPlannedActivation(OWNER, plan))
            f.assertNoMaterialEffects(); ok(db.close())
            rejected(FailureReason.STORAGE_FAILURE, f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertEquals(keys, f.vault.keys)
            assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun malformedOwnerOrGarbageRowsAndStrayPredecessorRecordsFailClosed() = runBlocking {
        for (damage in listOf("owner", "gc", "stray")) fixture { f ->
            val plan = f.prepare(retired = damage == "owner"); val record = decode(plan)
            when (damage) {
                "owner" -> f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_owners SET generation=1.5")
                "gc" -> f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('${"z".repeat(32)}')")
                else -> f.row(record.ownerTag)
            }
            val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun consumedOwnerRequiresMatchingWellFormedReceiptAndZeroRows() = runBlocking {
        for (damage in listOf("missing", "key", "generation", "revision", "row")) fixture { f ->
            val plan = f.prepare(); val record = decode(plan); val handle = f.recover(plan)
            ok(handle.abort()); ok(handle.close())
            when (damage) {
                "missing" -> f.raw("DELETE FROM feedme_activation_aborts")
                "key" -> f.raw("UPDATE feedme_activation_aborts SET key_id='${"e".repeat(32)}'")
                "generation" -> f.raw("UPDATE feedme_activation_aborts SET generation=generation+1")
                "revision" -> f.raw("PRAGMA ignore_check_constraints=ON", "UPDATE feedme_activation_aborts SET revision=0")
                else -> f.row(record.ownerTag)
            }
            val before = Files.readAllBytes(f.file); f.observe()
            assertIs<PortResult.Failure>(f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun receiptRevisionExhaustionFailsWithoutDeletingEvenAnAlreadyConsumedKey() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan); val handle = f.recover(plan)
            f.vault.failDelete = true; assertIs<PortResult.Failure>(handle.abort()); ok(handle.close())
            f.vault.failDelete = false
            f.raw("UPDATE feedme_activation_aborts SET revision=${Long.MAX_VALUE}")
            val reopened = f.recover(plan); status(StateActivationStatus.ABORTING, reopened)
            val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, reopened.abort())
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertTrue(record.keyId in f.vault.keys)
        }
    }

    @Test fun historicalAbortReceiptMayMatchRetiredPredecessorOrBeOlderButNeverNewerOrWrongKey() = runBlocking {
        for (ordinaryRetirement in listOf(false, true)) fixture { f ->
            val oldPlan = f.prepare(); val first = f.recover(oldPlan); ok(first.abort()); ok(first.close())
            val db = f.normal()
            if (ordinaryRetirement) ok(ok(db.activate(OWNER)).eraseScope(OWNER))
            val nextPlan = ok(db.planActivation(OWNER)); val record = decode(nextPlan)
            ok(db.commitPlannedActivation(OWNER, nextPlan)); ok(db.close()); f.observe()
            val recovery = f.recover(nextPlan); status(StateActivationStatus.SELECTED_EMPTY, recovery)
            ok(recovery.abort()); consumed(f, record, revision = 1)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_activation_aborts"))
        }
        for (future in listOf(false, true)) fixture { f ->
            val plan = f.prepare(retired = true); val record = decode(plan)
            f.raw("INSERT INTO feedme_activation_aborts(owner_tag,generation,key_id,revision) VALUES('${record.ownerTag}',${record.priorGeneration + if (future) 1 else 0},'${if (future) record.priorKeyId else "4".repeat(32)}',1)")
            val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun malformedUnrelatedAbortReceiptBlocksInspectionWithoutTouchingEitherOwner() = runBlocking {
        fixture { f ->
            val plan = f.prepare(); val tag = "8".repeat(64); val key = "8".repeat(32)
            f.raw("INSERT INTO feedme_owners(owner_tag,generation,active,key_id) VALUES('$tag',2,0,'$key')",
                "PRAGMA ignore_check_constraints=ON",
                "INSERT INTO feedme_activation_aborts(owner_tag,generation,key_id,revision) VALUES('$tag',2,'$key',0)")
            val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun exactMaximumConsumedGenerationCanAbortAndRepeatWithoutOverflowingOwnerFence() = runBlocking {
        fixture { f ->
            val first = f.normal(); ok(ok(first.activate(OWNER)).eraseScope(OWNER)); ok(first.close())
            f.raw("UPDATE feedme_owners SET generation=${Long.MAX_VALUE - 2}")
            val db = f.normal(); val plan = ok(db.planActivation(OWNER)); val record = decode(plan)
            ok(db.commitPlannedActivation(OWNER, plan)); ok(db.close())
            assertEquals(Long.MAX_VALUE, record.consumedGeneration)
            val recovery = f.recover(plan); ok(recovery.abort()); consumed(f, record, revision = 1)
            ok(recovery.abort()); consumed(f, record, revision = 2); status(StateActivationStatus.ABORTED, recovery)
        }
    }

    @Test fun unusablePlannedAliasCanBeAbortedThroughPresenceWithoutDecryptingOrReplacingIt() = runBlocking {
        for (selected in listOf(false, true)) fixture { f ->
            val plan = f.prepare(selected = selected); val record = decode(plan)
            if (!selected) f.vault.seed(record.keyId)
            f.vault.unusable += record.keyId; f.observe()
            val handle = f.recover(plan)
            status(if (selected) StateActivationStatus.SELECTED_EMPTY else StateActivationStatus.PARTIAL, handle)
            ok(handle.abort()); status(StateActivationStatus.ABORTED, handle)
            assertFalse(record.keyId in f.vault.keys); assertEquals(listOf(record.keyId), f.vault.deleted)
            assertEquals(0, f.vault.created); assertEquals(0, f.vault.opened); assertEquals(0, f.vault.sealed)
        }
    }

    @Test fun wrongScopeForeignVaultMalformedEncodingAndAlteredMacNeverGrantARecoveryHandle() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true)
            val corrupted = plan.copyForStorage().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val malformed = plan.copyForStorage().also { it[0] = 127 }
            val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.INVALID_DATA, f.recoveryResult(plan, scope = OTHER))
            rejected(FailureReason.INVALID_DATA, f.recoveryResult(plan, vault = RecoveryVault(f.events)))
            rejected(FailureReason.INVALID_DATA, f.recoveryResult(StateActivationPlan(corrupted)))
            rejected(FailureReason.INVALID_DATA, f.recoveryResult(StateActivationPlan(malformed)))
            rejected(FailureReason.INVALID_DATA, f.recoveryResult(plan, scope = StorageScope("test", ActorKind.DEMO, "demo")))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertTrue(f.connections.all { it.closed })
        }
    }

    @Test fun malformedUtf16ScopeAndMissingIndexFailureAreSanitizedAndDoNotResetState() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true); val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.INVALID_DATA, f.recoveryResult(plan, scope = StorageScope("broken\uD800", ActorKind.ACCOUNT, "owner")))
            f.vault.indexUnavailable = true
            val result = f.recoveryResult(plan); rejected(FailureReason.STORAGE_FAILURE, result)
            assertFalse(result.toString().contains("private-index-canary"))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
        }
    }

    @Test fun existingOnlyRecoveryRejectsEmptyOldFutureAndForeignSchemasWithoutMigration() = runBlocking {
        for (damage in listOf("empty", "old", "future", "app", "table", "trigger", "index")) fixture { f ->
            val plan = f.prepare()
            when (damage) {
                "empty" -> f.raw("DROP TABLE feedme_activation_aborts", "DROP TABLE feedme_records", "DROP TABLE feedme_owners", "DROP TABLE feedme_key_gc", "PRAGMA user_version=0", "PRAGMA application_id=0")
                "old" -> f.raw("DROP TABLE feedme_activation_aborts", "PRAGMA user_version=1")
                "future" -> f.raw("PRAGMA user_version=3")
                "app" -> f.raw("PRAGMA application_id=99")
                "table" -> f.raw("CREATE TABLE foreign_evidence(marker TEXT)")
                "trigger" -> f.raw("CREATE TRIGGER foreign_trigger AFTER INSERT ON feedme_owners BEGIN SELECT 1; END")
                else -> f.raw("CREATE INDEX foreign_index ON feedme_owners(key_id)")
            }
            val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, f.recoveryResult(plan))
            f.assertNoMaterialEffects(); assertContentEquals(before, Files.readAllBytes(f.file))
            assertEquals(1, f.closedCallbacks); assertTrue(f.sql.closed)
        }
    }

    @Test fun onlyNormalOpenMigratesExactV1AndPreservesItsOwnerKeyAndEncryptedRecords() = runBlocking {
        fixture { f ->
            val first = f.normal(); val store = ok(first.activate(OTHER))
            ok(store.commit(OTHER, listOf(StoreMutation.Put(KEY, null, 3, bytes("v1-private-record")))))
            val plan = ok(first.planActivation(OWNER)); ok(first.close())
            val keys = f.vault.keys.toMap(); val ciphertext = f.blob("SELECT payload FROM feedme_records")
            f.raw("DROP TABLE feedme_activation_aborts", "PRAGMA user_version=1")
            val v1Bytes = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STORAGE_FAILURE, f.recoveryResult(plan))
            assertEquals(1L, f.scalar("PRAGMA user_version")); f.assertNoMaterialEffects()
            assertContentEquals(v1Bytes, Files.readAllBytes(f.file))
            val migrated = f.normal()
            assertEquals(2L, f.scalar("PRAGMA user_version"))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_activation_aborts"))
            assertEquals(keys, f.vault.keys); assertContentEquals(ciphertext, f.blob("SELECT payload FROM feedme_records"))
            val restored = assertNotNull(ok(migrated.resume(OTHER)))
            assertContentEquals("v1-private-record".encodeToByteArray(), assertNotNull(ok(restored.read(OTHER, KEY))).payload.copyForCodec())
            ok(migrated.close()); val handle = f.recover(plan); status(StateActivationStatus.PREPARED, handle)
        }
    }

    @Test fun normalV1MigrationRejectsForeignLegacyShapeWithoutCreatingReceiptTable() = runBlocking {
        fixture { f ->
            f.prepare()
            f.raw("DROP TABLE feedme_activation_aborts", "PRAGMA user_version=1", "CREATE TABLE foreign_evidence(marker TEXT)")
            val before = Files.readAllBytes(f.file); f.observe()
            val connection = BundledSQLiteDriver().open(f.file.toString())
            rejected(FailureReason.STORAGE_FAILURE, EncryptedStateDatabase.open(connection, f.vault))
            assertEquals(1L, f.scalar("PRAGMA user_version"))
            assertEquals(0L, f.scalar("SELECT count(*) FROM sqlite_schema WHERE name='feedme_activation_aborts'"))
            assertContentEquals(before, Files.readAllBytes(f.file)); assertTrue(f.vault.deleted.isEmpty())
        }
    }

    @Test fun unrelatedOwnerRowsAndQueuedGarbageSurviveOpenInspectAbortAndCloseReopen() = runBlocking {
        fixture { f ->
            val db = f.normal(); val otherStore = ok(db.activate(OTHER))
            ok(otherStore.commit(OTHER, listOf(StoreMutation.Put(KEY, null, 7, bytes("other-owner-private-canary")))))
            val otherKey = f.vault.keys.keys.single(); val plan = ok(db.planActivation(OWNER))
            ok(db.commitPlannedActivation(OWNER, plan)); ok(db.close())
            val garbage = "9".repeat(32); f.vault.seed(garbage)
            f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('$garbage')")
            val ciphertext = f.blob("SELECT payload FROM feedme_records"); f.observe()
            val handle = f.recover(plan); status(StateActivationStatus.SELECTED_EMPTY, handle)
            ok(handle.abort()); ok(handle.close())
            val reopened = f.recover(plan); status(StateActivationStatus.ABORTED, reopened); ok(reopened.abort())
            assertEquals(setOf(otherKey, garbage), f.vault.keys.keys)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_records"))
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            assertContentEquals(ciphertext, f.blob("SELECT payload FROM feedme_records"))
            assertEquals(0, f.vault.opened); assertEquals(0, f.vault.sealed); assertEquals(0, f.vault.created)
        }
    }

    @Test fun closeIsIdempotentClosesConnectionBeforeOwnershipAndCanRetryFailedClose() = runBlocking {
        fixture { f ->
            val plan = f.prepare(); val handle = f.recover(plan)
            f.events.clear(); f.sql.failCloseOnce = true
            rejected(FailureReason.STORAGE_FAILURE, handle.close())
            assertEquals(0, f.closedCallbacks, "Ownership must remain held if connection close did not succeed")
            assertFalse(f.sql.closed)
            ok(handle.close()); ok(handle.close())
            assertTrue(f.sql.closed); assertEquals(1, f.closedCallbacks)
            assertEquals(2, f.sql.closeCalls)
            assertTrue(f.events.indexOf("CONNECTION_CLOSED") < f.events.indexOf("OWNERSHIP_CLOSED"))
            rejected(FailureReason.STORAGE_FAILURE, handle.inspect())
        }
    }

    @Test fun failedOwnershipCallbackCanRetryWithoutReopeningOrClosingConnectionTwice() = runBlocking {
        fixture { f ->
            val plan = f.prepare(); var attempts = 0
            val handle = f.recover(plan, onClosed = { if (++attempts == 1) error("private-lock-canary") })
            rejected(FailureReason.STORAGE_FAILURE, handle.close()); assertTrue(f.sql.closed)
            ok(handle.close()); ok(handle.close())
            assertEquals(2, attempts); assertEquals(1, f.sql.closeCalls)
            assertEquals(0, f.vault.created); assertTrue(f.vault.deleted.isEmpty())
        }
    }

    @Test fun cancellationWhileOpeningTransfersNoHandleAndClosesTheOwnedConnection() = runTest {
        fixture { f ->
            val plan = f.prepare(); val dispatcher = StandardTestDispatcher(testScheduler)
            val opening = async(start = CoroutineStart.UNDISPATCHED) { f.recoveryResult(plan, dispatcher = dispatcher) }
            opening.cancel(); runCurrent(); assertFailsWith<CancellationException> { opening.await() }
            assertTrue(f.sql.closed); assertEquals(1, f.closedCallbacks)
            assertTrue(f.vault.deleted.isEmpty()); assertEquals(0, f.vault.created)
        }
    }

    @Test fun inspectionAndHandleRepresentationsDoNotExposePlanOwnerOrKeyMaterial() = runBlocking {
        fixture { f ->
            val plan = f.prepare(selected = true); val record = decode(plan); val handle = f.recover(plan)
            val inspection = ok(handle.inspect())
            for (text in listOf(inspection.toString(), handle.toString(), plan.toString())) {
                assertFalse(text.contains(OWNER.actorId)); assertFalse(text.contains(OWNER.environment))
                assertFalse(text.contains(record.ownerTag)); assertFalse(text.contains(record.keyId))
                assertFalse(text.contains(plan.copyForStorage().joinToString("")))
            }
            assertEquals(StateActivationStatus.SELECTED_EMPTY, inspection.status)
        }
    }

    private suspend fun fixture(block: suspend (RecoveryFixture) -> Unit) {
        val f = RecoveryFixture(); try { block(f) } finally { f.close() }
    }

    companion object {
        private val OWNER = StorageScope("recovery-test-private-environment", ActorKind.ACCOUNT, "private-owner-canary")
        private val OTHER = StorageScope("recovery-test-private-environment", ActorKind.ACCOUNT, "other-private-owner")
        private val KEY = RecordKey("private-recipes", "private-record")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun decode(plan: StateActivationPlan) = StateActivationPlanCodec.decode(plan)
        private fun <T> ok(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail("Expected storage value, got ${result.reason}")
        }
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private suspend fun status(expected: StateActivationStatus, handle: StateActivationRecoveryHandle) = assertEquals(expected, ok(handle.inspect()).status)
        private fun consumed(f: RecoveryFixture, record: StateActivationPlanRecord, revision: Long) {
            val where = " WHERE owner_tag='${record.ownerTag}'"
            assertEquals(record.consumedGeneration, f.scalar("SELECT generation FROM feedme_owners$where"))
            assertEquals(0L, f.scalar("SELECT active FROM feedme_owners$where"))
            assertEquals(record.keyId, f.text("SELECT key_id FROM feedme_owners$where"))
            assertEquals(record.consumedGeneration, f.scalar("SELECT generation FROM feedme_activation_aborts$where"))
            assertEquals(record.keyId, f.text("SELECT key_id FROM feedme_activation_aborts$where"))
            assertEquals(revision, f.scalar("SELECT revision FROM feedme_activation_aborts$where"))
            assertEquals(0L, f.scalar("SELECT count(*) FROM feedme_records$where"))
        }

        private class RecoveryFixture {
            private val directory = Files.createTempDirectory("feedme-activation-recovery-test-")
            val file = directory.resolve("state.sqlite")
            val events = mutableListOf<String>()
            val vault = RecoveryVault(events)
            val connections = mutableListOf<RecoveryConnection>()
            private val managers = mutableListOf<EncryptedStateDatabase>()
            private val handles = mutableListOf<StateActivationRecoveryHandle>()
            lateinit var sql: RecoveryConnection
            var closedCallbacks = 0

            suspend fun normal(): EncryptedStateDatabase {
                val connection = BundledSQLiteDriver().open(file.toString())
                return ok(EncryptedStateDatabase.open(connection, vault)).also(managers::add)
            }

            suspend fun prepare(selected: Boolean = false, retired: Boolean = false): StateActivationPlan {
                val db = normal()
                if (retired) ok(ok(db.activate(OWNER)).eraseScope(OWNER))
                val plan = ok(db.planActivation(OWNER))
                if (selected) ok(db.commitPlannedActivation(OWNER, plan))
                ok(db.close()); observe()
                return plan
            }

            suspend fun recoveryResult(
                plan: StateActivationPlan,
                scope: StorageScope = OWNER,
                dispatcher: CoroutineDispatcher = Dispatchers.IO,
                vault: StateVault = this.vault,
                onClosed: () -> Unit = {},
            ): PortResult<StateActivationRecoveryHandle> {
                val native = BundledSQLiteDriver().open(file.toString(), SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX)
                val connection = RecoveryConnection(native, events).also { sql = it; connections += it }
                return EncryptedStateDatabase.openActivationRecovery(connection, vault, scope, plan, dispatcher) {
                    assertTrue(connection.closed, "Connection must close before native ownership is released")
                    onClosed(); closedCallbacks++; events += "OWNERSHIP_CLOSED"
                }.also { if (it is PortResult.Value) handles += it.value }
            }

            suspend fun recover(
                plan: StateActivationPlan,
                dispatcher: CoroutineDispatcher = Dispatchers.IO,
                onClosed: () -> Unit = {},
            ) = ok(recoveryResult(plan, dispatcher = dispatcher, onClosed = onClosed))

            fun observe() {
                events.clear(); vault.deleted.clear(); vault.created = 0; vault.allocated = 0
                vault.opened = 0; vault.sealed = 0
                if (::sql.isInitialized) sql.executed.clear()
            }

            fun assertNoMaterialEffects() {
                assertEquals(0, vault.created); assertEquals(0, vault.allocated)
                assertEquals(0, vault.opened); assertEquals(0, vault.sealed); assertTrue(vault.deleted.isEmpty())
                if (::sql.isInitialized) assertFalse(sql.executed.any(::recoveryDml))
            }

            fun raw(vararg queries: String) = BundledSQLiteDriver().open(file.toString()).use { c ->
                queries.forEach { query -> c.prepare(query).use { while (it.step()) { } } }
            }
            fun row(tag: String, payload: String = "NULL", schema: Int = 1) = raw(
                "INSERT INTO feedme_records(owner_tag,record_tag,revision,schema_version,payload) VALUES('$tag','${"1".repeat(64)}',1,$schema,$payload)",
            )
            fun scalar(query: String): Long = BundledSQLiteDriver().open(file.toString()).use { c ->
                c.prepare(query).use { assertTrue(it.step()); it.getLong(0) }
            }
            fun text(query: String): String = BundledSQLiteDriver().open(file.toString()).use { c ->
                c.prepare(query).use { assertTrue(it.step()); it.getText(0) }
            }
            fun blob(query: String): ByteArray = BundledSQLiteDriver().open(file.toString()).use { c ->
                c.prepare(query).use { assertTrue(it.step()); it.getBlob(0) }
            }
            suspend fun close() {
                try {
                    handles.forEach { it.close() }; managers.forEach { it.close() }
                    connections.filterNot { it.closed }.forEach { it.failCloseOnce = false; it.close() }
                } finally {
                    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                }
            }
        }
    }
}

private enum class RecoveryCommitFault { BEFORE, AFTER }
private fun recoveryDml(sql: String) = listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ")
    .any(sql.trim().uppercase()::startsWith)

private class RecoveryConnection(private val delegate: SQLiteConnection, private val events: MutableList<String>) : SQLiteConnection by delegate {
    val executed = mutableListOf<String>()
    var commitFault: RecoveryCommitFault? = null
    var emptyWriteCommitFault: RecoveryCommitFault? = null
    var beforeCommit: (() -> Unit)? = null
    var afterCommit: (() -> Unit)? = null
    var failCloseOnce = false
    var closeCalls = 0
    var closed = false
    private var changed = false
    private var writeTransaction = false

    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql
                if (sql == "BEGIN" || sql == "BEGIN IMMEDIATE") {
                    changed = false; writeTransaction = sql == "BEGIN IMMEDIATE"
                }
                val committing = sql == "COMMIT" && changed
                val emptyWriteCommit = sql == "COMMIT" && writeTransaction && !changed
                val fault = when {
                    committing -> commitFault.also { commitFault = null }
                    emptyWriteCommit -> emptyWriteCommitFault.also { emptyWriteCommitFault = null }
                    else -> null
                }
                if (committing) beforeCommit?.invoke()
                if (fault == RecoveryCommitFault.BEFORE) error("Injected failure before SQLite COMMIT")
                val result = statement.step()
                if (recoveryDml(sql)) changed = true
                if (committing) {
                    events += "COMMIT_ACK"; changed = false; afterCommit?.invoke()
                }
                if (emptyWriteCommit) events += "EMPTY_WRITE_COMMIT_ACK"
                // This is a lost application response AFTER SQLite returned success, not failed fsync.
                if (fault == RecoveryCommitFault.AFTER) error("Injected loss after acknowledged SQLite COMMIT")
                if (sql == "ROLLBACK" || sql == "COMMIT") { changed = false; writeTransaction = false }
                return result
            }
        }
    }

    override fun close() {
        closeCalls++
        if (failCloseOnce) { failCloseOnce = false; error("Injected connection close failure") }
        delegate.close(); closed = true; events += "CONNECTION_CLOSED"
    }
}

private class RecoveryVault(private val events: MutableList<String>) : PlannedStateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>()
    val unusable = mutableSetOf<String>()
    val deleted = mutableListOf<String>()
    var created = 0; var allocated = 0; var sealed = 0; var opened = 0
    var failDelete = false; var indexUnavailable = false
    var nextCandidate: String? = null
    var beforeDelete: ((String) -> Unit)? = null
    var afterDelete: ((String) -> Unit)? = null

    override fun index(input: ByteArray): ByteArray {
        if (indexUnavailable) error("private-index-canary")
        return Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    }
    override fun newOwnerKeyId(): String { allocated++; return nextCandidate ?: UUID.randomUUID().toString().replace("-", "") }
    override fun createOwnerKey(keyId: String) { check(keyId !in keys); created++; seed(keyId) }
    override fun createOwnerKey(): String = newOwnerKeyId().also(::createOwnerKey)
    fun seed(keyId: String) { keys[keyId] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey() }
    override fun hasOwnerKey(keyId: String): Boolean {
        if (keyId in unusable && keyId in keys) throw StateVaultException()
        return keyId in keys
    }
    override fun containsOwnerKey(keyId: String) = keyId in keys
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        sealed++; val nonce = ByteArray(12).also(random::nextBytes)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce))
            updateAAD(associatedData); byteArrayOf(1) + nonce + doFinal(plaintext)
        }
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        opened++
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData); doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    }
    override fun deleteOwnerKey(keyId: String) {
        beforeDelete?.invoke(keyId)
        events += "DELETE_KEY:$keyId"; deleted += keyId
        if (failDelete) throw StateVaultException()
        keys.remove(keyId); afterDelete?.invoke(keyId)
    }
}
