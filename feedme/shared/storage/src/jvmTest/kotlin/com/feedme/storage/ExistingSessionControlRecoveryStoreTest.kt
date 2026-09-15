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
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Real bundled SQLite and test-only JCA keys; no native factory or physical fsync claim. */
@OptIn(SessionControlRecoveryCompositionApi::class, ExperimentalCoroutinesApi::class)
class ExistingSessionControlRecoveryStoreTest {
    @Test fun constructionTransfersConnectionWithoutSqlKeyAccessOrOwnershipCallback() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner()
            assertTrue(f.sql.prepared.isEmpty()); assertTrue(f.sql.executed.isEmpty())
            assertEquals(0, f.vault.indexes); assertEquals(0, f.vault.opens); assertEquals(0, f.vault.checks)
            assertEquals(0, f.callbackCalls); assertEquals(0, f.sql.closeCalls); f.noKeyEffects()
            assertFalse(owner is PrivateStateStore); assertFalse(owner is WorkOriginPlanAuthentication)
            assertFalse(owner is WorkOriginPlanVerification)
            ok(owner.open()); same(f.original, record(owner))
        }
    }

    @Test fun beforeOpenCapabilitiesAreFencedWithoutLazyInitialization() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STALE_SESSION, owner.read())
            rejected(FailureReason.STALE_SESSION, owner.compareAndSet(1, IDLE))
            assertTrue(f.sql.prepared.isEmpty()); assertEquals(0, f.vault.indexes); assertEquals(0, f.vault.opens)
            f.assertReadOnly(before); assertEquals(0, f.sql.closeCalls)
        }
    }

    @Test fun openAndRepeatedReadsPreserveExactBytesKeysAndRevision() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner(); val before = Files.readAllBytes(f.file)
            val keys = f.vault.keys.toMap(); ok(owner.open())
            repeat(3) {
                same(f.original, record(owner))
            }
            f.assertReadOnly(before); assertEquals(keys, f.vault.keys)
            assertTrue(f.vault.opens > 0); assertTrue(f.vault.indexes > 0)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_owners"))
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_records"))
        }
    }

    @Test fun purposeFixedStorageKeepsBoundedLedgerBytesOpaqueWithoutParsingSessionStates() = runBlocking {
        for (body in listOf(bytes("metadata-is-validated-by-the-session-facade"), PrivateBytes(ByteArray(32_768) { 73 }))) fixture { f ->
            f.prepare(body); val owner = f.owner(); val before = Files.readAllBytes(f.file)
            ok(owner.open()); same(body, record(owner).payload)
            f.assertReadOnly(before)
        }
    }

    @Test fun samePayloadCasMakesOneChangedAcknowledgedRevisionAndNeverAllocatesOrDeletesKeys() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner(); ok(owner.open()); f.observe()
            val input = f.original.payload.copyForCodec(); val payload = PrivateBytes(input); input.fill(0)
            val written = ok(owner.compareAndSet(f.original.revision, payload))
            assertEquals(f.original.revision + 1, written.revision); same(f.original.payload, written.payload)
            same(written, record(owner)); assertEquals(1, f.sql.writeCommits)
            assertEquals(1, f.vault.seals); assertEquals(0, f.vault.creates); assertEquals(0, f.vault.deletes)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_records"))
        }
    }

    @Test fun nullStaleInvalidAndExhaustedCasCannotInitializeReplaceOrResetTheLedger() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner(); ok(owner.open()); f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.CONFLICT, owner.compareAndSet(null, IDLE))
            rejected(FailureReason.CONFLICT, owner.compareAndSet(2, IDLE))
            for (revision in listOf(-1L, 0L)) rejected(FailureReason.INVALID_DATA, owner.compareAndSet(revision, IDLE))
            for (size in listOf(0, 32_769)) rejected(FailureReason.INVALID_DATA, owner.compareAndSet(1, PrivateBytes(ByteArray(size))))
            f.assertReadOnly(before)
            f.rewriteRecord(IDLE, Long.MAX_VALUE); f.observe(); val maximum = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, owner.compareAndSet(Long.MAX_VALUE, IDLE))
            assertEquals(Long.MAX_VALUE, record(owner).revision); f.assertReadOnly(maximum)
        }
    }

    @Test fun beforeCommitFailureAndLostApplicationReceiptRemainFailureUntilFreshChangedCas() = runBlocking {
        for (fault in ControlCommitFault.entries) fixture { f ->
            f.prepare(); val owner = f.owner(); ok(owner.open()); f.sql.commitFault = fault
            rejected(if (fault == ControlCommitFault.BEFORE) FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN,
                owner.compareAndSet(1, bytes("exact-retained-control-complete-marker")))
            val observed = record(owner)
            assertEquals(if (fault == ControlCommitFault.BEFORE) 1L else 2L, observed.revision)
            val written = ok(owner.compareAndSet(observed.revision, observed.payload))
            assertEquals(observed.revision + 1, written.revision); same(observed.payload, written.payload)
            assertEquals(0, f.vault.creates); assertEquals(0, f.vault.deletes)
            // The AFTER hook throws only after real SQLite COMMIT acknowledged; it is not OS EIO.
            assertEquals(if (fault == ControlCommitFault.BEFORE) 1 else 2, f.sql.writeCommits)
        }
    }

    @Test fun cancelledCallerAfterAcknowledgedCommitGetsNoSuccessAndCanObserveExactWrittenRecord() = runTest {
        fixture { f ->
            f.prepare(); val owner = f.owner(dispatcher = StandardTestDispatcher(testScheduler)); ok(owner.open())
            val writing = async(start = CoroutineStart.LAZY) { owner.compareAndSet(1, bytes("cancelled-return-control-marker")) }
            f.sql.afterStep = { sql -> if (sql == "COMMIT" && f.sql.writeCommits == 1) writing.cancel() }
            writing.start(); assertFailsWith<CancellationException> { writing.await() }; f.sql.afterStep = null
            assertEquals(2L, record(owner).revision); assertEquals(0, f.vault.deletes); assertEquals(0, f.vault.creates)
            val observed = record(owner); assertEquals(3L, ok(owner.compareAndSet(observed.revision, observed.payload)).revision)
        }
    }

    @Test fun synchronousReadCancellationBeforeReturnCannotDeliverValueOnTheSameDispatcher() = runTest {
        fixture { f ->
            f.prepare(); val dispatcher = StandardTestDispatcher(testScheduler)
            val owner = f.owner(dispatcher = dispatcher); ok(owner.open()); f.observe()
            val before = Files.readAllBytes(f.file); var returned = false
            val reading = async(dispatcher, start = CoroutineStart.LAZY) {
                owner.read().also { returned = true }
            }
            // No dispatcher change can supply an implicit prompt-cancellation check here.
            // Synchronous SQLite finishes its read transaction after cancelling this caller.
            f.sql.afterStep = { sql -> if (sql == "COMMIT") reading.cancel() }
            reading.start(); assertFailsWith<CancellationException> { reading.await() }
            f.sql.afterStep = null
            assertFalse(returned, "A cancelled raw read must not return Value before caller checks")
            f.assertReadOnly(before); same(f.original, record(owner))
            // The new check does not poison READY or replace normal typed storage failures.
            f.vault.indexFailure = IllegalStateException("private-read-provider-canary")
            val failed = owner.read(); rejected(FailureReason.STORAGE_FAILURE, failed)
            assertFalse(failed.toString().contains("private-read-provider-canary"))
            same(f.original, record(owner)); f.assertReadOnly(before)
        }
    }

    @Test fun extraActiveInactiveOrForeignOwnersAreRejectedWithoutEnumerationAuthorityOrCleanup() = runBlocking {
        for (damage in listOf("active", "inactive", "foreign-only")) fixture { f ->
            f.prepare(); val foreign = "d".repeat(64); val key = f.vault.seed()
            if (damage == "foreign-only") f.raw("DELETE FROM feedme_records", "DELETE FROM feedme_owners")
            f.raw("INSERT INTO feedme_owners VALUES('$foreign',1,${if (damage == "inactive") 0 else 1},'$key')")
            val owner = f.owner(); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            rejected(FailureReason.STORAGE_FAILURE, owner.open()); f.assertReadOnly(before)
            assertEquals(keys, f.vault.keys); assertEquals(0, f.sql.closeCalls); ok(owner.close())
        }
    }

    @Test fun extraRowsTombstonesWrongRecordIdentityAndMissingLedgerCannotBeResetOrIgnored() = runBlocking {
        for (damage in listOf("extra-null", "extra-body", "tombstone", "wrong-record", "missing")) fixture { f ->
            f.prepare()
            when (damage) {
                "extra-null", "extra-body" -> f.raw("INSERT INTO feedme_records VALUES('${f.ownerTag()}','${"b".repeat(64)}',1,1,${if (damage == "extra-null") "NULL" else "zeroblob(29)"})")
                "tombstone" -> f.raw("UPDATE feedme_records SET payload=NULL")
                "wrong-record" -> f.raw("UPDATE feedme_records SET record_tag='${"b".repeat(64)}'")
                else -> f.raw("DELETE FROM feedme_records")
            }
            val owner = f.owner(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, owner.open()); f.assertReadOnly(before)
            assertEquals(0, f.sql.closeCalls); ok(owner.close())
        }
    }

    @Test fun anyQueuedKeyGarbageOrHistoricalAbortReceiptBlocksOpenAndRemainsUntouched() = runBlocking {
        for (damage in listOf("garbage", "own-receipt", "foreign-receipt")) fixture { f ->
            f.prepare(); val key = f.vault.seed()
            if (damage == "garbage") f.raw("INSERT INTO feedme_key_gc VALUES('$key')")
            else f.raw("INSERT INTO feedme_activation_aborts VALUES('${if (damage == "own-receipt") f.ownerTag() else "e".repeat(64)}',1,'$key',1)")
            val owner = f.owner(); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            rejected(FailureReason.STORAGE_FAILURE, owner.open()); f.assertReadOnly(before); assertEquals(keys, f.vault.keys)
            assertEquals(1L, f.scalar("SELECT count(*) FROM ${if (damage == "garbage") "feedme_key_gc" else "feedme_activation_aborts"}"))
            ok(owner.close())
        }
    }

    @Test fun existingOnlyOpenRejectsEmptyLegacyFutureForeignAndAlteredSchemaWithoutInitialization() = runBlocking {
        for (damage in listOf("empty", "legacy", "future", "application", "extra-table", "extra-index")) fixture { f ->
            f.prepare()
            when (damage) {
                "empty" -> f.raw("DROP TABLE feedme_activation_aborts", "DROP TABLE feedme_records", "DROP TABLE feedme_owners", "DROP TABLE feedme_key_gc", "PRAGMA user_version=0", "PRAGMA application_id=0")
                "legacy" -> f.raw("DROP TABLE feedme_activation_aborts", "PRAGMA user_version=1")
                "future" -> f.raw("PRAGMA user_version=3")
                "application" -> f.raw("PRAGMA application_id=17")
                "extra-table" -> f.raw("CREATE TABLE foreign_evidence(marker TEXT)")
                else -> f.raw("CREATE INDEX extra_record_index ON feedme_records(revision)")
            }
            val owner = f.owner(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, owner.open()); f.assertReadOnly(before); ok(owner.close())
        }
    }

    @Test fun missingUnusableReplacedKeysAndMalformedEncryptedLedgerFailClosedWithoutRepair() = runBlocking {
        for (damage in listOf("missing-key", "unusable-key", "replaced-key", "ciphertext", "schema", "oversize", "zero-bytes", "retired")) fixture { f ->
            f.prepare()
            when (damage) {
                "missing-key" -> f.vault.keys.clear()
                "unusable-key" -> f.vault.unusable += f.ownerKey()
                "replaced-key" -> f.vault.seed(f.ownerKey())
                "ciphertext" -> f.raw("UPDATE feedme_records SET payload=zeroblob(29)")
                "schema" -> f.rewriteRecord(IDLE, 1, schema = 2)
                "oversize" -> f.rewriteRecord(PrivateBytes(ByteArray(32_769)), 1)
                "zero-bytes" -> f.rewriteRecord(PrivateBytes(byteArrayOf()), 1)
                else -> f.raw("UPDATE feedme_owners SET active=0")
            }
            val owner = f.owner(); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            rejected(FailureReason.STORAGE_FAILURE, owner.open()); f.assertReadOnly(before); assertEquals(keys, f.vault.keys)
            assertEquals(0, f.sql.closeCalls); ok(owner.close())
        }
    }

    @Test fun everyReadyReadAndCasRevalidatesStrictShapeBeforeAnyWrite() = runBlocking {
        for (damage in listOf("extra-row", "garbage", "receipt", "ciphertext", "missing")) fixture { f ->
            f.prepare(); val owner = f.owner(); ok(owner.open())
            when (damage) {
                "extra-row" -> f.raw("INSERT INTO feedme_records VALUES('${f.ownerTag()}','${"c".repeat(64)}',1,1,NULL)")
                "garbage" -> f.raw("INSERT INTO feedme_key_gc VALUES('${f.vault.seed()}')")
                "receipt" -> f.raw("INSERT INTO feedme_activation_aborts VALUES('${f.ownerTag()}',1,'${f.ownerKey()}',1)")
                "ciphertext" -> f.raw("UPDATE feedme_records SET payload=zeroblob(29)")
                else -> f.raw("DELETE FROM feedme_records")
            }
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STORAGE_FAILURE, owner.read()); rejected(FailureReason.STORAGE_FAILURE, owner.compareAndSet(1, IDLE))
            f.assertReadOnly(before)
        }
    }

    @Test fun pinnedGenerationAndKeyNeverRebindToChangedOwnerAfterReady() = runBlocking {
        for (damage in listOf("generation", "key", "inactive")) fixture { f ->
            f.prepare(); val owner = f.owner(); ok(owner.open())
            when (damage) {
                "generation" -> f.raw("UPDATE feedme_owners SET generation=generation+1")
                "key" -> f.raw("UPDATE feedme_owners SET key_id='${f.vault.seed()}'")
                else -> f.raw("UPDATE feedme_owners SET active=0")
            }
            f.observe(); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STALE_SESSION, owner.read()); rejected(FailureReason.STALE_SESSION, owner.compareAndSet(1, IDLE))
            assertEquals(0, f.vault.opens); f.assertReadOnly(before)
        }
    }

    @Test fun acknowledgedCloseAndExistingReopenRetainOnlyTheExactCurrentLedger() = runBlocking {
        fixture { f ->
            f.prepare(); val first = f.owner(); ok(first.open())
            val written = ok(first.compareAndSet(1, bytes("opaque-selected-or-aborted-marker"))); ok(first.close())
            val next = f.owner(); val before = Files.readAllBytes(f.file); ok(next.open()); same(written, record(next))
            f.assertReadOnly(before); assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun failedInitializationBecomesCloseOnlyAndRetainsConnectionAcrossFailedClose() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner(); f.sql.beforeStep = { error("control-private-initialization-canary") }
            val result = owner.open(); rejected(FailureReason.STORAGE_FAILURE, result)
            assertFalse(result.toString().contains("control-private-initialization-canary")); assertEquals(0, f.sql.closeCalls)
            f.sql.beforeStep = null; val reads = f.sql.executed.toList()
            rejected(FailureReason.CONFLICT, owner.open()); rejected(FailureReason.STALE_SESSION, owner.read())
            rejected(FailureReason.STALE_SESSION, owner.compareAndSet(1, IDLE)); assertEquals(reads, f.sql.executed)
            f.sql.closeFailures = 1; rejected(FailureReason.STORAGE_FAILURE, owner.close())
            assertFalse(f.sql.closed); assertEquals(0, f.callbackCalls)
            ok(owner.close()); ok(owner.close()); assertEquals(2, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
            f.noKeyEffects()
        }
    }

    @Test fun failedOwnershipCallbackRetriesOnlyAfterConnectionClosureAndNeverReopensSql() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner(); ok(owner.open()); f.callbackFailures = 1
            rejected(FailureReason.STORAGE_FAILURE, owner.close()); assertTrue(f.sql.closed)
            assertEquals(1, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
            val reads = f.sql.executed.toList(); rejected(FailureReason.STALE_SESSION, owner.read())
            rejected(FailureReason.CONFLICT, owner.open()); assertEquals(reads, f.sql.executed)
            ok(owner.close()); ok(owner.close()); assertEquals(1, f.sql.closeCalls); assertEquals(2, f.callbackCalls)
            assertEquals(listOf("CONNECTION_CLOSED", "CALLBACK", "CALLBACK"), f.events)
        }
    }

    @Test fun closeBeforeOpenIsIdempotentAndDoesNotInitializeReadOrTouchKeys() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner(); val before = Files.readAllBytes(f.file)
            ok(owner.close()); ok(owner.close()); assertEquals(1, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
            rejected(FailureReason.CONFLICT, owner.open()); rejected(FailureReason.STALE_SESSION, owner.read())
            rejected(FailureReason.STALE_SESSION, owner.compareAndSet(1, IDLE))
            assertTrue(f.sql.prepared.isEmpty()); assertEquals(0, f.vault.indexes); f.assertReadOnly(before)
        }
    }

    @Test fun readinessIsNotExposedUntilSuccessfulCallerHandoffAndCloseOrCancellationWinsTheGap() = runTest {
        for (scenario in listOf("cancel-before", "cancel-handoff", "close-handoff", "failed-close-handoff")) fixture { f ->
            f.prepare(); val dispatcher = ControlHandoffDispatcher(); val owner = f.owner(dispatcher = dispatcher)
            val opening = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }
            try {
                if (scenario != "cancel-before") {
                    dispatcher.runPending(); assertFalse(opening.isCompleted)
                    assertEquals(1, f.sql.executed.count { it == "PRAGMA user_version" })
                    val reads = f.sql.executed.toList()
                    val reading = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { owner.read() }
                    val writing = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { owner.compareAndSet(1, IDLE) }
                    assertTrue(reading.isCompleted); assertTrue(writing.isCompleted)
                    rejected(FailureReason.STALE_SESSION, reading.await()); rejected(FailureReason.STALE_SESSION, writing.await())
                    assertEquals(reads, f.sql.executed); f.noKeyEffects()
                }
                if (scenario.startsWith("cancel")) {
                    opening.cancel(); repeat(3) { dispatcher.runPending(); runCurrent() }
                    assertFailsWith<CancellationException> { opening.await() }
                    if (scenario == "cancel-before") assertTrue(f.sql.prepared.isEmpty())
                    assertEquals(0, f.sql.closeCalls); assertEquals(0, f.callbackCalls)
                } else {
                    val failed = scenario == "failed-close-handoff"; if (failed) f.sql.closeFailures = 1
                    val closing = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { owner.close() }
                    assertTrue(closing.isCompleted)
                    if (failed) rejected(FailureReason.STORAGE_FAILURE, closing.await()) else ok(closing.await())
                    runCurrent(); rejected(FailureReason.STALE_SESSION, opening.await())
                }
                dispatcher.direct = true
                rejected(FailureReason.CONFLICT, owner.open()); rejected(FailureReason.STALE_SESSION, owner.read())
                ok(owner.close()); assertEquals(if (scenario == "failed-close-handoff") 2 else 1, f.sql.closeCalls)
                assertEquals(1, f.callbackCalls); f.noKeyEffects()
            } finally { dispatcher.direct = true }
        }
    }

    @Test fun cancelledInitializationCannotPublishReadyAfterNoncooperativeSqlReturns() = runTest {
        fixture { f ->
            f.prepare(); val owner = f.owner(dispatcher = StandardTestDispatcher(testScheduler))
            val opening = async(start = CoroutineStart.LAZY) { owner.open() }
            f.sql.afterStep = { opening.cancel() }; opening.start()
            assertFailsWith<CancellationException> { opening.await() }; f.sql.afterStep = null
            assertEquals(0, f.sql.closeCalls); assertEquals(0, f.callbackCalls); val reads = f.sql.executed.toList()
            rejected(FailureReason.CONFLICT, owner.open()); rejected(FailureReason.STALE_SESSION, owner.read())
            assertEquals(reads, f.sql.executed); f.noKeyEffects(); ok(owner.close())
        }
    }

    @Test fun concurrentOrCancelledSecondOpenCannotInvalidateSuccessfullyReadyOwner() = runTest {
        fixture { f ->
            f.prepare(); val owner = f.owner(dispatcher = StandardTestDispatcher(testScheduler))
            val first = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }
            val results = listOf(first.await(), second.await())
            assertEquals(1, results.count { it is PortResult.Value }); assertEquals(1, results.count { it is PortResult.Failure })
            val reads = f.sql.executed.toList(); rejected(FailureReason.CONFLICT, owner.open()); assertEquals(reads, f.sql.executed)
            assertEquals(1, reads.count { it == "PRAGMA user_version" })
            val rejected = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }; rejected.cancel(); runCurrent()
            assertFailsWith<CancellationException> { rejected.await() }; same(f.original, record(owner)); f.noKeyEffects()
        }
    }

    @Test fun cancelledCloseStillReleasesOwnershipAndClosedCapabilitiesNeverTouchSqlOrVault() = runTest {
        fixture { f ->
            f.prepare(); val owner = f.owner(dispatcher = StandardTestDispatcher(testScheduler)); ok(owner.open())
            val closing = async(start = CoroutineStart.UNDISPATCHED) { owner.close() }; closing.cancel(); runCurrent()
            assertFailsWith<CancellationException> { closing.await() }
            assertTrue(f.sql.closed); assertEquals(1, f.callbackCalls); f.observe()
            rejected(FailureReason.CONFLICT, owner.open()); rejected(FailureReason.STALE_SESSION, owner.read())
            rejected(FailureReason.STALE_SESSION, owner.compareAndSet(1, IDLE))
            ok(owner.close()); assertEquals(1, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
            assertTrue(f.sql.prepared.isEmpty()); assertEquals(0, f.vault.indexes); assertEquals(0, f.vault.opens); f.noKeyEffects()
        }
    }

    @Test fun unexpectedVaultFailuresAreSanitizedAndCancellationDoesNotBecomeSuccessOrDestroyReadyOwner() = runBlocking {
        fixture { f ->
            f.prepare(); val owner = f.owner(); ok(owner.open()); f.observe(); val before = Files.readAllBytes(f.file)
            f.vault.indexFailure = IllegalStateException("private-provider-and-owner-canary")
            val result = owner.read(); rejected(FailureReason.STORAGE_FAILURE, result)
            assertFalse(result.toString().contains("private-provider-and-owner-canary"))
            f.vault.indexFailure = CancellationException("cancel-private-proof")
            assertFailsWith<CancellationException> { owner.read() }
            same(f.original, record(owner)); f.assertReadOnly(before)
            for (value in listOf(owner.toString(), record(owner).toString(), result.toString()))
                for (secret in listOf(f.ownerTag(), f.ownerKey(), IDLE.copyForCodec().decodeToString()))
                    assertFalse(value.contains(secret))
        }
    }

    @Test fun fixedControlIdentityRejectsWorkLedgerEvenWithTheSameVaultAndRevision() = runBlocking {
        fixture { f ->
            f.prepareWork()
            val owner = f.owner(); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            rejected(FailureReason.STORAGE_FAILURE, owner.open()); f.assertReadOnly(before)
            assertEquals(keys, f.vault.keys); rejected(FailureReason.STALE_SESSION, owner.read())
        }
    }

    private suspend fun fixture(action: suspend (Fixture) -> Unit) {
        val fixture = Fixture(); try { action(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        private val directory = Files.createTempDirectory("feedme-existing-control-recovery-")
        val file = directory.resolve("state.sqlite")
        val vault = ControlRecoveryVault(); val events = mutableListOf<String>()
        lateinit var sql: ControlRecoveryConnection
        lateinit var original: SessionControlRecord
        var callbackCalls = 0; var callbackFailures = 0
        private val owners = mutableListOf<ExistingSessionControlRecoveryStore>()
        private val connections = mutableListOf<ControlRecoveryConnection>()
        suspend fun prepare(payload: PrivateBytes = IDLE) {
            val db = ok(EncryptedStateDatabase.open(BundledSQLiteDriver().open(file.toString()), vault))
            val work = ok(EncryptedSessionControlStore.open(db, true))
            try {
                if (!equal(payload, IDLE)) rewriteRecord(payload, 1)
                original = record(work)
            } finally { ok(work.close()); observe() }
        }
        suspend fun prepareWork() {
            val db = ok(EncryptedStateDatabase.open(BundledSQLiteDriver().open(file.toString()), vault))
            val work = ok(EncryptedSessionWorkStore.open(db, true))
            original = record(work); ok(work.close()); observe()
        }
        fun owner(dispatcher: CoroutineDispatcher = Dispatchers.IO): ExistingSessionControlRecoveryStore {
            val connection = ControlRecoveryConnection(BundledSQLiteDriver().open(file.toString(), SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX), events)
                .also { sql = it; connections += it }
            observe()
            return EncryptedStateDatabase.createControlRecoveryStore(connection, vault, dispatcher) {
                assertTrue(connection.closed, "Connection close must acknowledge before lifetime ownership release")
                callbackCalls++; events += "CALLBACK"
                if (callbackFailures > 0) { callbackFailures--; error("private-control-close-canary") }
            }.also(owners::add)
        }
        fun observe() {
            vault.reset(); events.clear()
            if (::sql.isInitialized) { sql.prepared.clear(); sql.executed.clear(); sql.writeCommits = 0 }
        }
        fun noKeyEffects() { assertEquals(0, vault.creates); assertEquals(0, vault.deletes); assertEquals(0, vault.seals) }
        fun assertReadOnly(before: ByteArray) {
            noKeyEffects(); assertContentEquals(before, Files.readAllBytes(file))
            if (::sql.isInitialized) assertFalse(sql.executed.any { query -> listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "DROP ", "ALTER ")
                .any(query.trim().uppercase()::startsWith) })
        }
        fun raw(vararg queries: String) = BundledSQLiteDriver().open(file.toString()).use { c ->
            queries.forEach { query -> c.prepare(query).use { while (it.step()) {} } }
        }
        fun scalar(query: String): Long = BundledSQLiteDriver().open(file.toString()).use { c ->
            c.prepare(query).use { assertTrue(it.step()); it.getLong(0) }
        }
        private fun text(query: String): String = BundledSQLiteDriver().open(file.toString()).use { c ->
            c.prepare(query).use { assertTrue(it.step()); it.getText(0) }
        }
        fun ownerTag() = text("SELECT owner_tag FROM feedme_owners LIMIT 1")
        fun ownerKey() = text("SELECT key_id FROM feedme_owners LIMIT 1")
        fun rewriteRecord(payload: PrivateBytes, revision: Long, schema: Int = 1) {
            BundledSQLiteDriver().open(file.toString()).use { c ->
                c.prepare("SELECT o.owner_tag,o.generation,o.key_id,r.record_tag FROM feedme_owners o JOIN feedme_records r USING(owner_tag)").use { row ->
                    assertTrue(row.step()); val tag = row.getText(0); val generation = row.getLong(1); val key = row.getText(2); val recordTag = row.getText(3)
                    val aad = frame("feedme.record-aead.v1", tag, generation.toString(), recordTag, revision.toString(), schema.toString())
                    val plain = payload.copyForCodec(); val ciphertext = try { vault.seal(key, plain, aad) } finally { plain.fill(0); aad.fill(0) }
                    c.prepare("UPDATE feedme_records SET revision=?,schema_version=?,payload=? WHERE owner_tag=? AND record_tag=?").use {
                        it.bindLong(1, revision); it.bindLong(2, schema.toLong()); it.bindBlob(3, ciphertext); it.bindText(4, tag); it.bindText(5, recordTag); it.step()
                    }
                }
            }
        }
        suspend fun replaceOwner() {
            val db = ok(EncryptedStateDatabase.open(BundledSQLiteDriver().open(file.toString()), vault))
            try {
                val old = assertNotNull(ok(db.resume(CONTROL_SCOPE))); ok(old.eraseScope(CONTROL_SCOPE))
                val fresh = ok(db.activate(CONTROL_SCOPE)); ok(fresh.commit(CONTROL_SCOPE, listOf(StoreMutation.Put(CONTROL_KEY, null, 1, IDLE))))
            } finally { ok(db.close()); observe() }
        }
        suspend fun close() {
            try {
                callbackFailures = 0
                connections.forEach { it.closeFailures = 0; it.beforeStep = null; it.afterStep = null }
                owners.forEach { ok(it.close()) }; connections.filterNot { it.closed }.forEach { it.close() }
            } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }

    companion object {
        private val CONTROL_SCOPE = StorageScope("feedme-session-control-v1", ActorKind.DEMO, "install-control")
        private val CONTROL_KEY = RecordKey("session-control", "retirement-ledger")
        private val IDLE = bytes("{\"version\":1,\"state\":\"idle\"}")
        private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun <T> ok(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private suspend fun record(store: SessionControlStore) = assertNotNull(ok(store.read()))
        private fun same(a: SessionControlRecord, b: SessionControlRecord) { assertEquals(a.revision, b.revision); same(a.payload, b.payload) }
        private fun same(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
        private fun equal(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
        private fun frame(vararg fields: String): ByteArray {
            val parts = fields.map { it.encodeToByteArray(throwOnInvalidSequence = true) }; val result = ByteArray(4 + parts.sumOf { 4 + it.size }); var offset = 0
            fun length(value: Int) { for (shift in 24 downTo 0 step 8) result[offset++] = (value ushr shift).toByte() }
            length(parts.size); parts.forEach { length(it.size); it.copyInto(result, offset); offset += it.size }; return result
        }
    }
}

private enum class ControlCommitFault { BEFORE, AFTER }
private class ControlHandoffDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>(); var direct = false
    override fun isDispatchNeeded(context: CoroutineContext) = !direct
    override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
    fun runPending() { while (queued.isNotEmpty()) queued.removeFirst().run() }
}
private class ControlRecoveryConnection(private val native: SQLiteConnection, private val events: MutableList<String>) : SQLiteConnection by native {
    val prepared = mutableListOf<String>(); val executed = mutableListOf<String>()
    var beforeStep: ((String) -> Unit)? = null; var afterStep: ((String) -> Unit)? = null
    var closeFailures = 0; var closeCalls = 0; var closed = false; var writeCommits = 0
    var commitFault: ControlCommitFault? = null; private var changed = false
    override fun prepare(sql: String): SQLiteStatement {
        prepared += sql; val statement = native.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql; beforeStep?.invoke(sql)
                if (sql.startsWith("BEGIN")) changed = false
                val committing = sql == "COMMIT" && changed
                val fault = if (committing) commitFault.also { commitFault = null } else null
                if (fault == ControlCommitFault.BEFORE) error("Injected before SQLite COMMIT")
                val result = statement.step()
                if (listOf("INSERT ", "UPDATE ", "DELETE ").any(sql::startsWith)) changed = true
                if (committing) writeCommits++
                afterStep?.invoke(sql)
                if (fault == ControlCommitFault.AFTER) error("Injected application receipt loss after SQLite COMMIT")
                if (sql == "COMMIT" || sql == "ROLLBACK") changed = false
                return result
            }
        }
    }
    override fun close() {
        closeCalls++; if (closeFailures > 0) { closeFailures--; error("private-control-connection-close-canary") }
        native.close(); closed = true; events += "CONNECTION_CLOSED"
    }
}
private class ControlRecoveryVault : StateVault {
    private val random = SecureRandom(); private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>(); val unusable = mutableSetOf<String>()
    var creates = 0; var seals = 0; var opens = 0; var deletes = 0; var indexes = 0; var checks = 0
    var indexFailure: Exception? = null
    fun reset() { creates = 0; seals = 0; opens = 0; deletes = 0; indexes = 0; checks = 0 }
    fun seed(key: String = UUID.randomUUID().toString().replace("-", "")): String {
        keys[key] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey(); return key
    }
    override fun index(input: ByteArray): ByteArray {
        indexes++; indexFailure?.let { indexFailure = null; throw it }
        return Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    }
    override fun createOwnerKey(): String { creates++; return seed() }
    override fun hasOwnerKey(keyId: String): Boolean { checks++; if (keyId in unusable) throw StateVaultException(); return keyId in keys }
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        seals++; val nonce = ByteArray(12).also(random::nextBytes)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce)); updateAAD(associatedData)
            byteArrayOf(1) + nonce + doFinal(plaintext)
        }
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        opens++; if (keyId in unusable || ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData); doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    }
    override fun deleteOwnerKey(keyId: String) { deletes++; keys.remove(keyId) }
}
