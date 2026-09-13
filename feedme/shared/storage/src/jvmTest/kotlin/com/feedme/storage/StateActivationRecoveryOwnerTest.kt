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
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

/** Real bundled SQLite and test-only JCA keys. Injected exceptions are not native fsync faults. */
@OptIn(ExperimentalCoroutinesApi::class)
class StateActivationRecoveryOwnerTest {
    @Test fun constructionTransfersOwnershipWithoutSqlVaultOrCallbackIoAndPinsDetachedPlan() = runBlocking {
        fixture { f ->
            val original = f.prepare(); val bytes = original.copyForStorage(); val passed = StateActivationPlan(bytes)
            val owner = f.owner(passed); bytes.fill(0); passed.copyForStorage().fill(0)
            assertTrue(f.sql.prepared.isEmpty()); assertTrue(f.sql.executed.isEmpty())
            assertEquals(0, f.vault.indexed); assertEquals(0, f.vault.presenceChecks); assertEquals(0, f.sql.closeCalls)
            assertEquals(0, f.callbackCalls); f.noMaterialEffects()
            ok(owner.open()); assertEquals(StateActivationStatus.PREPARED, ok(owner.inspect()).status)
            assertEquals(0, f.vault.created); assertFalse(owner is PrivateStateStore)
        }
    }

    @Test fun beforeOpenMethodsCannotReadDecryptAbortOrLazilyInitializeTheTransferredConnection() = runBlocking {
        fixture { f ->
            val owner = f.owner(f.prepare("bound")); val before = Files.readAllBytes(f.file)
            rejected(FailureReason.STALE_SESSION, owner.inspect()); rejected(FailureReason.STALE_SESSION, owner.binding())
            rejected(FailureReason.STALE_SESSION, owner.abort()); rejected(FailureReason.STALE_SESSION, owner.abort(BODY))
            assertTrue(f.sql.prepared.isEmpty()); assertEquals(0, f.vault.indexed); f.noMaterialEffects()
            assertContentEquals(before, Files.readAllBytes(f.file)); assertEquals(0, f.sql.closeCalls)
            ok(owner.open()); same(BODY, assertNotNull(ok(owner.binding()).record).payload)
        }
    }

    @Test fun readyMetadataIsStableReadOnlyAndNeverRunsQueuedGarbageCollectionOrCipherProbes() = runBlocking {
        fixture { f ->
            val plan = f.prepare("selected"); val garbage = "e".repeat(32); f.vault.seed(garbage)
            f.raw("INSERT INTO feedme_key_gc(key_id) VALUES('$garbage')")
            f.vault.unusable += decode(plan).keyId
            val owner = f.owner(plan); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            ok(owner.open()); val first = ok(owner.inspect())
            repeat(3) { val next = ok(owner.inspect()); assertEquals(first.status, next.status); same(first.fingerprint, next.fingerprint) }
            assertEquals(StateActivationStatus.SELECTED_EMPTY, first.status)
            assertEquals(32, first.fingerprint.copyForCodec().size); f.noMaterialEffects()
            assertEquals(0, f.vault.usabilityChecks); assertEquals(keys, f.vault.keys)
            assertEquals(1L, f.scalar("SELECT count(*) FROM feedme_key_gc"))
            assertContentEquals(before, Files.readAllBytes(f.file)); assertFalse(f.sql.executed.any(::dml))
        }
    }

    @Test fun allAuthenticatedStagesCanOpenWithoutSelectingConsumingOrDeletingAnything() = runBlocking {
        for ((stage, status) in listOf("prepared" to StateActivationStatus.PREPARED, "partial" to StateActivationStatus.PARTIAL,
            "selected" to StateActivationStatus.SELECTED_EMPTY, "bound" to StateActivationStatus.SELECTED_NONEMPTY,
            "aborting" to StateActivationStatus.ABORTING, "aborted" to StateActivationStatus.ABORTED)) fixture { f ->
            val plan = f.prepare(stage); val owner = f.owner(plan); val before = Files.readAllBytes(f.file); val keys = f.vault.keys.toMap()
            ok(owner.open()); assertEquals(status, ok(owner.inspect()).status, stage)
            f.noMaterialEffects(); assertEquals(keys, f.vault.keys); assertContentEquals(before, Files.readAllBytes(f.file))
            assertFalse(f.sql.executed.any(::dml)); assertEquals(0, f.callbackCalls)
        }
    }

    @Test fun readyBindingReturnsOnlyTheExactReservedRecordAndCurrentRetirementTarget() = runBlocking {
        fixture { f ->
            val plan = f.prepare("bound"); val expected = assertNotNull(f.expectedBinding)
            val owner = f.owner(plan); ok(owner.open()); f.observe()
            assertEquals(StateActivationStatus.SELECTED_NONEMPTY, ok(owner.inspect()).status); assertEquals(0, f.vault.opened)
            val inspection = ok(owner.binding()); val record = assertNotNull(inspection.record)
            assertEquals(1L, record.revision); assertEquals(2, record.schemaVersion); same(BODY, record.payload)
            assertContentEquals(assertNotNull(expected.target).copyForStorage(), assertNotNull(inspection.target).copyForStorage())
            assertEquals(1, f.vault.opened); assertEquals(0, f.vault.sealed); assertEquals(0, f.vault.created)
            assertTrue(f.vault.deleted.isEmpty()); assertFalse(f.sql.executed.any(::dml))
        }
    }

    @Test fun selectedEmptyBindingHasExactTargetButNoInventedRecordAndPreparedBindingDoesNotActivate() = runBlocking {
        for (selected in listOf(false, true)) fixture { f ->
            val owner = f.owner(f.prepare(if (selected) "selected" else "prepared")); ok(owner.open()); f.observe()
            if (selected) { val result = ok(owner.binding()); assertNotNull(result.target); assertNull(result.record) }
            else assertIs<PortResult.Failure>(owner.binding())
            f.noMaterialEffects(); assertFalse(f.sql.executed.any(::dml))
            assertEquals(if (selected) 1L else 0L, f.scalar("SELECT count(*) FROM feedme_owners"))
        }
    }

    @Test fun otherDomainRowsTombstonesAndCorruptBindingNeverBecomeGeneralPrivateDataAccess() = runBlocking {
        for (damage in listOf("domain", "tombstone", "ciphertext", "schema")) fixture { f ->
            val plan = f.prepare("bound")
            when (damage) {
                "domain" -> f.raw("INSERT INTO feedme_records VALUES('${decode(plan).ownerTag}','${"c".repeat(64)}',1,1,NULL)")
                "tombstone" -> f.raw("UPDATE feedme_records SET payload=NULL")
                "ciphertext" -> f.raw("UPDATE feedme_records SET payload=zeroblob(29)")
                else -> f.raw("UPDATE feedme_records SET schema_version=3")
            }
            val owner = f.owner(plan); val before = Files.readAllBytes(f.file); ok(owner.open())
            assertEquals(StateActivationStatus.SELECTED_NONEMPTY, ok(owner.inspect()).status); assertEquals(0, f.vault.opened)
            assertIs<PortResult.Failure>(owner.binding()); assertIs<PortResult.Failure>(owner.abort(BODY))
            assertTrue(f.vault.deleted.isEmpty()); assertEquals(0, f.vault.created)
            assertContentEquals(before, Files.readAllBytes(f.file)); assertFalse(f.sql.executed.any(::dml))
        }
    }

    @Test fun wrongPlanMacScopeAndInstallationFailWithoutMutationAndRemainExplicitlyClosable() = runBlocking {
        for (damage in listOf("mac", "scope", "install")) fixture { f ->
            val plan = f.prepare("selected"); val changed = plan.copyForStorage().also { it[169] = (it[169].toInt() xor 1).toByte() }
            val actualVault = if (damage == "install") OwnerVault(f.events) else f.vault
            val owner = f.owner(if (damage == "mac") StateActivationPlan(changed) else plan,
                scope = if (damage == "scope") OWNER.copy(actorId = "other-owner") else OWNER, vault = actualVault)
            val before = Files.readAllBytes(f.file)
            rejected(FailureReason.INVALID_DATA, owner.open()); assertEquals(0, f.sql.closeCalls); assertEquals(0, f.callbackCalls)
            val reads = f.sql.executed.toList(); assertIs<PortResult.Failure>(owner.open())
            assertIs<PortResult.Failure>(owner.inspect()); assertIs<PortResult.Failure>(owner.binding()); assertIs<PortResult.Failure>(owner.abort())
            assertEquals(reads, f.sql.executed); f.noMaterialEffects(); assertTrue(actualVault.deleted.isEmpty())
            assertContentEquals(before, Files.readAllBytes(f.file)); ok(owner.close()); assertEquals(1, f.callbackCalls)
        }
    }

    @Test fun missingLegacyFutureAndForeignSchemaAreNotCreatedMigratedOrRepairedDuringOpen() = runBlocking {
        for (damage in listOf("empty", "legacy", "future", "application", "foreign")) fixture { f ->
            val plan = f.prepare()
            when (damage) {
                "empty" -> f.raw("DROP TABLE feedme_activation_aborts", "DROP TABLE feedme_records", "DROP TABLE feedme_owners", "DROP TABLE feedme_key_gc", "PRAGMA user_version=0", "PRAGMA application_id=0")
                "legacy" -> f.raw("DROP TABLE feedme_activation_aborts", "PRAGMA user_version=1")
                "future" -> f.raw("PRAGMA user_version=3")
                "application" -> f.raw("PRAGMA application_id=17")
                else -> f.raw("CREATE TABLE foreign_evidence(marker TEXT)")
            }
            val before = Files.readAllBytes(f.file); val owner = f.owner(plan)
            rejected(FailureReason.STORAGE_FAILURE, owner.open()); assertEquals(0, f.sql.closeCalls)
            assertEquals(0, f.callbackCalls); assertContentEquals(before, Files.readAllBytes(f.file))
            f.noMaterialEffects(); assertFalse(f.sql.executed.any(::dml)); ok(owner.close())
        }
    }

    @Test fun failedInitializationPermanentlyBecomesCloseOnlyEvenWhenTheSqlFaultIsRemoved() = runBlocking {
        fixture { f ->
            val owner = f.owner(f.prepare("selected")); f.sql.beforeStep = { error("private-initialization-canary") }
            val result = owner.open(); rejected(FailureReason.STORAGE_FAILURE, result)
            assertFalse(result.toString().contains("private-initialization-canary")); assertEquals(0, f.sql.closeCalls)
            f.sql.beforeStep = null; val before = f.sql.executed.toList()
            assertIs<PortResult.Failure>(owner.open()); assertIs<PortResult.Failure>(owner.inspect())
            assertIs<PortResult.Failure>(owner.binding()); assertIs<PortResult.Failure>(owner.abort(BODY))
            assertEquals(before, f.sql.executed); f.noMaterialEffects(); ok(owner.close())
        }
    }

    @Test fun failedInitializationAndFailedConnectionCloseRetainTheSameOwnerUntilExplicitRetry() = runBlocking {
        fixture { f ->
            val owner = f.owner(f.prepare()); f.sql.beforeStep = { error("private-init-failure") }
            rejected(FailureReason.STORAGE_FAILURE, owner.open()); f.sql.beforeStep = null
            f.sql.closeFailures = 1; rejected(FailureReason.STORAGE_FAILURE, owner.close())
            assertFalse(f.sql.closed); assertEquals(0, f.callbackCalls); assertEquals(1, f.sql.closeCalls)
            val reads = f.sql.executed.toList(); assertIs<PortResult.Failure>(owner.open()); assertEquals(reads, f.sql.executed)
            ok(owner.close()); ok(owner.close()); assertTrue(f.sql.closed); assertEquals(2, f.sql.closeCalls)
            assertEquals(1, f.callbackCalls); assertEquals(listOf("CONNECTION_CLOSED", "CALLBACK"), f.events.filter { it in setOf("CONNECTION_CLOSED", "CALLBACK") })
        }
    }

    @Test fun failedOwnershipReleaseRetriesOnlyTheCallbackAfterConnectionCloseAcknowledgement() = runBlocking {
        fixture { f ->
            val owner = f.owner(f.prepare()); ok(owner.open()); f.callbackFailures = 1
            rejected(FailureReason.STORAGE_FAILURE, owner.close()); assertTrue(f.sql.closed)
            assertEquals(1, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
            assertIs<PortResult.Failure>(owner.open()); assertIs<PortResult.Failure>(owner.inspect())
            ok(owner.close()); ok(owner.close()); assertEquals(1, f.sql.closeCalls); assertEquals(2, f.callbackCalls)
            assertTrue(f.events.indexOf("CONNECTION_CLOSED") < f.events.indexOf("CALLBACK"))
        }
    }

    @Test fun closeBeforeOpenReleasesTransferredConnectionWithoutInitializingItAndIsIdempotent() = runBlocking {
        fixture { f ->
            val owner = f.owner(f.prepare()); ok(owner.close()); ok(owner.close())
            assertTrue(f.sql.prepared.isEmpty()); assertEquals(0, f.vault.indexed)
            assertEquals(1, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
            assertIs<PortResult.Failure>(owner.open()); assertIs<PortResult.Failure>(owner.inspect())
            assertIs<PortResult.Failure>(owner.binding()); assertIs<PortResult.Failure>(owner.abort()); f.noMaterialEffects()
        }
    }

    @Test fun cancellationBeforeAdmissionOrDuringReadyHandoffRetainsCloseOnlyOwnershipWithoutAutoClose() = runTest {
        for (scenario in listOf("cancel-before", "cancel-handoff", "close-handoff", "failed-close-handoff")) fixture { f ->
            val dispatcher = OwnerHandoffDispatcher()
            val owner = f.owner(f.prepare(), dispatcher = dispatcher)
            val opening = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }
            try {
                if (scenario != "cancel-before") {
                    dispatcher.runPending()
                    assertEquals(1, f.sql.executed.count { it == "PRAGMA user_version" })
                    assertFalse(opening.isCompleted, "The caller dispatcher has not received READY yet")
                    val reads = f.sql.executed.toList()
                    // Enter on the I/O context while the original caller's return is still gated.
                    // Merely finishing initialization must not expose any method to another caller.
                    val inspect = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { owner.inspect() }
                    val binding = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { owner.binding() }
                    val abort = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { owner.abort() }
                    assertTrue(inspect.isCompleted); assertTrue(binding.isCompleted); assertTrue(abort.isCompleted)
                    rejected(FailureReason.STALE_SESSION, inspect.await()); rejected(FailureReason.STALE_SESSION, binding.await())
                    rejected(FailureReason.STALE_SESSION, abort.await()); assertEquals(reads, f.sql.executed)
                    assertFalse(opening.isCompleted); f.noMaterialEffects()
                }
                if (scenario.startsWith("cancel")) {
                    opening.cancel()
                    repeat(3) { dispatcher.runPending(); runCurrent() }
                    assertFailsWith<CancellationException> { opening.await() }
                    if (scenario == "cancel-before") assertTrue(f.sql.prepared.isEmpty())
                    assertEquals(0, f.sql.closeCalls); assertEquals(0, f.callbackCalls)
                } else {
                    val failedClose = scenario == "failed-close-handoff"
                    if (failedClose) f.sql.closeFailures = 1
                    val closing = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { owner.close() }
                    assertTrue(closing.isCompleted)
                    if (failedClose) rejected(FailureReason.STORAGE_FAILURE, closing.await()) else ok(closing.await())
                    assertFalse(opening.isCompleted); runCurrent()
                    rejected(FailureReason.STALE_SESSION, opening.await())
                    assertEquals(1, f.sql.closeCalls); assertEquals(if (failedClose) 0 else 1, f.callbackCalls)
                }
                dispatcher.direct = true
                f.noMaterialEffects()
                rejected(FailureReason.CONFLICT, owner.open()); rejected(FailureReason.STALE_SESSION, owner.inspect())
                ok(owner.close()); assertEquals(if (scenario == "failed-close-handoff") 2 else 1, f.sql.closeCalls)
                assertEquals(1, f.callbackCalls)
            } finally {
                // A failed assertion must not leave fixture cleanup waiting on this manual queue.
                dispatcher.direct = true
            }
        }
    }

    @Test fun cancellationInsideInitializationCannotPublishReadyAfterNoncooperativeSqlReturns() = runTest {
        fixture { f ->
            val owner = f.owner(f.prepare("bound"), dispatcher = StandardTestDispatcher(testScheduler))
            val opening = async(start = CoroutineStart.LAZY) { owner.open() }
            f.sql.afterStep = { opening.cancel() }
            opening.start(); assertFailsWith<CancellationException> { opening.await() }
            f.sql.afterStep = null; assertEquals(0, f.sql.closeCalls); assertEquals(0, f.callbackCalls)
            val reads = f.sql.executed.toList(); assertIs<PortResult.Failure>(owner.inspect())
            assertIs<PortResult.Failure>(owner.binding()); assertIs<PortResult.Failure>(owner.open()); assertEquals(reads, f.sql.executed)
            f.noMaterialEffects(); ok(owner.close()); assertEquals(1, f.callbackCalls)
        }
    }

    @Test fun repeatedAndConcurrentOpenNeverRerunInitializationOrInvalidateTheFirstReadyOwner() = runTest {
        fixture { f ->
            val owner = f.owner(f.prepare("selected"), dispatcher = StandardTestDispatcher(testScheduler))
            val first = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }
            val results = listOf(first.await(), second.await())
            assertEquals(1, results.count { it is PortResult.Value }); assertEquals(1, results.count { it is PortResult.Failure })
            val reads = f.sql.executed.toList(); rejected(FailureReason.CONFLICT, owner.open()); assertEquals(reads, f.sql.executed)
            assertEquals(1, reads.count { it == "PRAGMA user_version" })
            val rejectedOpen = async(start = CoroutineStart.UNDISPATCHED) { owner.open() }
            rejectedOpen.cancel(); runCurrent(); assertFailsWith<CancellationException> { rejectedOpen.await() }
            assertEquals(StateActivationStatus.SELECTED_EMPTY, ok(owner.inspect()).status); f.noMaterialEffects()
        }
    }

    @Test fun cancelledCloseStillReleasesConnectionAndCallbackWithoutReactivatingTheOwner() = runTest {
        fixture { f ->
            val owner = f.owner(f.prepare(), dispatcher = StandardTestDispatcher(testScheduler)); ok(owner.open())
            val closing = async(start = CoroutineStart.UNDISPATCHED) { owner.close() }
            closing.cancel(); runCurrent(); assertFailsWith<CancellationException> { closing.await() }
            assertTrue(f.sql.closed); assertEquals(1, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
            assertIs<PortResult.Failure>(owner.open()); assertIs<PortResult.Failure>(owner.inspect()); ok(owner.close())
            assertEquals(1, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
        }
    }

    @Test fun readyAbortDelegatesStrictEmptyOrExactBindingOnlyAndReacknowledgesEveryReplay() = runBlocking {
        for (bound in listOf(false, true)) fixture { f ->
            val plan = f.prepare(if (bound) "bound" else "selected"); val owner = f.owner(plan); ok(owner.open())
            if (bound) { rejected(FailureReason.CONFLICT, owner.abort()); rejected(FailureReason.CONFLICT, owner.abort(bytes("different"))) }
            val before = ok(owner.inspect()); ok(owner.abort(if (bound) BODY else null)); val after = ok(owner.inspect())
            assertEquals(StateActivationStatus.ABORTED, after.status); assertFalse(equal(before.fingerprint, after.fingerprint))
            assertEquals(listOf(decode(plan).keyId), f.vault.deleted); assertEquals(1L, f.receipt())
            ok(owner.abort(if (bound) BODY else null)); val replay = ok(owner.inspect())
            assertFalse(equal(after.fingerprint, replay.fingerprint)); assertEquals(2L, f.receipt())
            assertEquals(0, f.vault.created); assertEquals(0, f.vault.sealed)
        }
    }

    @Test fun failedAbortBeforeOrAfterRealCommitDoesNotDeleteAndSameReadyOwnerCanRetryOriginalPlan() = runBlocking {
        for (fault in OwnerCommitFault.entries) fixture { f ->
            val plan = f.prepare("bound"); val owner = f.owner(plan); ok(owner.open()); f.sql.commitFault = fault
            rejected(if (fault == OwnerCommitFault.BEFORE) FailureReason.STORAGE_FAILURE else FailureReason.OUTCOME_UNKNOWN, owner.abort(BODY))
            assertTrue(decode(plan).keyId in f.vault.keys); assertTrue(f.vault.deleted.isEmpty())
            assertEquals(if (fault == OwnerCommitFault.BEFORE) StateActivationStatus.SELECTED_NONEMPTY else StateActivationStatus.ABORTING,
                ok(owner.inspect()).status)
            val previous = f.receipt(); ok(owner.abort(BODY)); assertEquals(previous + 1, f.receipt())
            assertEquals(StateActivationStatus.ABORTED, ok(owner.inspect()).status)
        }
    }

    @Test fun changedOwnerAfterReadyNeverLetsThePinnedOldPlanReadOrDeleteTheNewIncarnation() = runBlocking {
        fixture { f ->
            val plan = f.prepare("selected"); val owner = f.owner(plan); ok(owner.open())
            f.raw("UPDATE feedme_owners SET generation=generation+2")
            val before = Files.readAllBytes(f.file); f.observe()
            rejected(FailureReason.STALE_SESSION, owner.inspect()); assertIs<PortResult.Failure>(owner.binding())
            rejected(FailureReason.STALE_SESSION, owner.abort()); f.noMaterialEffects()
            assertContentEquals(before, Files.readAllBytes(f.file)); assertTrue(decode(plan).keyId in f.vault.keys)
        }
    }

    @Test fun closingReadyOwnerPermanentlyFencesEveryCapabilityWithoutFurtherSqlOrVaultIo() = runBlocking {
        fixture { f ->
            val owner = f.owner(f.prepare("bound")); ok(owner.open()); ok(owner.close()); f.observe()
            assertIs<PortResult.Failure>(owner.open()); assertIs<PortResult.Failure>(owner.inspect())
            assertIs<PortResult.Failure>(owner.binding()); assertIs<PortResult.Failure>(owner.abort()); assertIs<PortResult.Failure>(owner.abort(BODY))
            assertTrue(f.sql.prepared.isEmpty()); assertEquals(0, f.vault.indexed); f.noMaterialEffects()
            assertEquals(1, f.sql.closeCalls); assertEquals(1, f.callbackCalls)
        }
    }

    @Test fun ownerObservationsErrorsAndClosureDoNotExposePlanScopeKeyOrBindingMaterial() = runBlocking {
        fixture { f ->
            val plan = f.prepare("bound"); val decoded = decode(plan); val owner = f.owner(plan); ok(owner.open())
            val values = listOf(owner.toString(), ok(owner.inspect()).toString(), ok(owner.binding()).toString(),
                owner.abort(bytes("incorrect-private-body")).toString())
            for (value in values) for (secret in listOf(OWNER.environment, OWNER.actorId, decoded.ownerTag, decoded.keyId,
                BODY.copyForCodec().decodeToString(), "incorrect-private-body")) assertFalse(value.contains(secret))
            ok(owner.close()); assertEquals(1, f.callbackCalls); assertTrue(f.vault.deleted.isEmpty())
        }
    }

    private suspend fun fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture(); try { action(f) } finally { f.close() }
    }

    private class Fixture {
        private val directory = Files.createTempDirectory("feedme-retained-recovery-owner-")
        val file = directory.resolve("state.sqlite")
        val events = mutableListOf<String>(); val vault = OwnerVault(events)
        lateinit var sql: OwnerConnection
        var expectedBinding: StateRecordInspection? = null
        var callbackCalls = 0; var callbackFailures = 0
        private val owners = mutableListOf<StateActivationRecoveryOwner>()
        private val connections = mutableListOf<OwnerConnection>()
        suspend fun prepare(stage: String = "prepared"): StateActivationPlan {
            val db = ok(EncryptedStateDatabase.open(BundledSQLiteDriver().open(file.toString()), vault))
            try {
                val plan = ok(db.planActivation(OWNER)); val key = decode(plan).keyId
                if (stage == "partial") vault.seed(key)
                if (stage in setOf("selected", "bound", "aborting", "aborted")) ok(db.commitPlannedActivation(OWNER, plan))
                if (stage == "bound") expectedBinding = ok(db.bindPlannedActivation(OWNER, plan, 2, BODY))
                if (stage in setOf("aborting", "aborted")) {
                    vault.failDelete = stage == "aborting"
                    val result = db.abortPlannedActivation(OWNER, plan)
                    if (stage == "aborting") rejected(FailureReason.STORAGE_FAILURE, result) else ok(result)
                    vault.failDelete = false
                }
                return plan
            } finally { ok(db.close()); observe() }
        }
        fun owner(plan: StateActivationPlan, scope: StorageScope = OWNER, vault: StateVault = this.vault,
            dispatcher: CoroutineDispatcher = Dispatchers.IO): StateActivationRecoveryOwner {
            val native = BundledSQLiteDriver().open(file.toString(), SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX)
            val connection = OwnerConnection(native, events).also { sql = it; connections += it }
            return EncryptedStateDatabase.createActivationRecoveryOwner(connection, vault, scope, plan, dispatcher) {
                assertTrue(connection.closed, "Native ownership must not be released before connection close")
                callbackCalls++; events += "CALLBACK"
                if (callbackFailures > 0) { callbackFailures--; error("private-ownership-close-canary") }
            }.also(owners::add)
        }
        fun observe() {
            events.clear(); vault.resetObservations()
            if (::sql.isInitialized) { sql.executed.clear(); sql.prepared.clear() }
        }
        fun noMaterialEffects() {
            assertEquals(0, vault.created); assertEquals(0, vault.allocated); assertEquals(0, vault.sealed)
            assertEquals(0, vault.opened); assertTrue(vault.deleted.isEmpty())
        }
        fun raw(vararg queries: String) = BundledSQLiteDriver().open(file.toString()).use { c ->
            queries.forEach { query -> c.prepare(query).use { while (it.step()) { } } }
        }
        fun scalar(query: String): Long = BundledSQLiteDriver().open(file.toString()).use { c ->
            c.prepare(query).use { assertTrue(it.step()); it.getLong(0) }
        }
        fun receipt() = scalar("SELECT coalesce(max(revision),0) FROM feedme_activation_aborts")
        suspend fun close() {
            try {
                callbackFailures = 0
                connections.forEach { it.closeFailures = 0; it.beforeStep = null; it.afterStep = null }
                owners.forEach { ok(it.close()) }
                connections.filterNot { it.closed }.forEach { it.close() }
            } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }

    companion object {
        private val OWNER = StorageScope("retained-recovery-owner-test", ActorKind.ACCOUNT, "private-owner-canary")
        private val BODY = bytes("exact-private-original-binding-v2")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun decode(plan: StateActivationPlan) = StateActivationPlanCodec.decode(plan)
        private fun <T> ok(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun rejected(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
        private fun same(a: PrivateBytes, b: PrivateBytes) = assertContentEquals(a.copyForCodec(), b.copyForCodec())
        private fun equal(a: PrivateBytes, b: PrivateBytes) = a.copyForCodec().contentEquals(b.copyForCodec())
        private fun dml(sql: String) = listOf("INSERT ", "UPDATE ", "DELETE ", "CREATE ", "ALTER ", "DROP ")
            .any(sql.trim().uppercase()::startsWith)
    }
}

private enum class OwnerCommitFault { BEFORE, AFTER }
/** Explicit dispatch boundary; never sleeps or races a real thread to induce prompt cancellation. */
private class OwnerHandoffDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()
    var direct = false
    override fun isDispatchNeeded(context: CoroutineContext) = !direct
    override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
    fun runPending() { while (queued.isNotEmpty()) queued.removeFirst().run() }
}
private class OwnerConnection(private val native: SQLiteConnection, private val events: MutableList<String>) : SQLiteConnection by native {
    val prepared = mutableListOf<String>(); val executed = mutableListOf<String>()
    var beforeStep: ((String) -> Unit)? = null; var afterStep: ((String) -> Unit)? = null
    var commitFault: OwnerCommitFault? = null; var closeFailures = 0; var closeCalls = 0; var closed = false
    private var changed = false
    override fun prepare(sql: String): SQLiteStatement {
        prepared += sql; val statement = native.prepare(sql)
        return object : SQLiteStatement by statement {
            override fun step(): Boolean {
                executed += sql; beforeStep?.invoke(sql)
                if (sql.startsWith("BEGIN")) changed = false
                val committing = sql == "COMMIT" && changed
                val fault = if (committing) commitFault.also { commitFault = null } else null
                if (fault == OwnerCommitFault.BEFORE) error("Injected failure before SQLite COMMIT")
                val result = statement.step()
                if (listOf("INSERT ", "UPDATE ", "DELETE ").any(sql::startsWith)) changed = true
                if (committing) events += "COMMIT_ACK"
                afterStep?.invoke(sql)
                if (fault == OwnerCommitFault.AFTER) error("Injected application receipt loss after successful SQLite COMMIT")
                if (sql == "COMMIT" || sql == "ROLLBACK") changed = false
                return result
            }
        }
    }
    override fun close() {
        closeCalls++
        if (closeFailures > 0) { closeFailures--; error("private-connection-close-canary") }
        native.close(); closed = true; events += "CONNECTION_CLOSED"
    }
}

private class OwnerVault(private val events: MutableList<String>) : PlannedStateVault {
    private val random = SecureRandom(); private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>(); val unusable = mutableSetOf<String>(); val deleted = mutableListOf<String>()
    var indexed = 0; var presenceChecks = 0; var usabilityChecks = 0
    var created = 0; var allocated = 0; var opened = 0; var sealed = 0; var failDelete = false
    fun resetObservations() { indexed = 0; presenceChecks = 0; usabilityChecks = 0; created = 0; allocated = 0; opened = 0; sealed = 0; deleted.clear() }
    override fun index(input: ByteArray): ByteArray { indexed++; return Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) } }
    override fun newOwnerKeyId(): String { allocated++; return UUID.randomUUID().toString().replace("-", "") }
    override fun createOwnerKey(): String = newOwnerKeyId().also(::createOwnerKey)
    override fun createOwnerKey(keyId: String) { check(keyId !in keys); created++; seed(keyId) }
    fun seed(keyId: String) { keys[keyId] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey() }
    override fun containsOwnerKey(keyId: String): Boolean { presenceChecks++; return keyId in keys }
    override fun hasOwnerKey(keyId: String): Boolean { usabilityChecks++; if (keyId in unusable) throw StateVaultException(); return keyId in keys }
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        sealed++; val nonce = ByteArray(12).also(random::nextBytes)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce)); updateAAD(associatedData)
            byteArrayOf(1) + nonce + doFinal(plaintext)
        }
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = try {
        opened++; if (keyId in unusable || ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData); doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    } catch (_: Exception) { throw StateVaultException() }
    override fun deleteOwnerKey(keyId: String) { events += "DELETE_KEY"; deleted += keyId; if (failDelete) throw StateVaultException(); keys.remove(keyId) }
}
