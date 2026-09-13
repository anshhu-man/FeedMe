package com.feedme.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.*
import com.feedme.sync.CommandExecutionGate
import com.feedme.sync.CommandIntent
import com.feedme.sync.CommandPhase
import com.feedme.sync.DurableCommandQueue
import com.feedme.sync.ExecutionDecision
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Comparator
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Real bundled SQLite with fixture-only JCA keys; no native vault or process-hard-kill claim. */
class DurableCommandStorageTest {
    private val owner = StorageScope("command-integration", ActorKind.ACCOUNT, "private-owner-marker-f17ec8")
    private val draftKey = RecordKey("cooking-private-draft", "private-draft-marker-379a60")
    private val derivedKey = RecordKey("cooking-private-summary", "private-summary-marker-c164a9")
    private val bodyText = " { \"deviceSequence\": 1, \"currentStepId\": \"step1\", " +
        "\"personalNotes\": [ { \"text\": \"$privateMarker\", \"label\": \"myNote\" } ] } \n"
    private val receiptText = """{"id":"$sessionId","version":2,"createdAt":"2026-09-13T00:00:00Z","updatedAt":"2026-09-13T00:01:00Z","planId":"$planId","status":"active","currentStepId":"step1","completedStepIds":[],"deviceSequence":1,"timers":[]}"""

    @Test fun pendingIntentReopensWithExactRequestAndOriginWhileDatabaseHasNoPlaintextMarkers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        withFixture(dispatcher) { fixture ->
            val db = fixture.open()
            val store = queueValue(db.activate(owner))
            val boundary = SessionBoundary()
            val lease = boundary.activate(owner)
            val clock = QueueStorageClock()
            var exchanges = 0
            val queue = queue(store, boundary, dispatcher, clock,
                transport { _, _ -> exchanges++; error("Enqueue must not dispatch") })
            val added = queueValue(queue.enqueue(lease, intent(), listOf(put(draftKey, privateMarker))))
            assertEquals(CommandPhase.READY, added.phase)
            assertEquals(0, exchanges)
            queueValue(db.close())
            fixture.assertNoPlaintext(listOf(privateMarker, owner.actorId, draftKey.collection, draftKey.id,
                commandId, sessionId, originBinding, "updateCookSession"))

            val reopened = fixture.open()
            val restored = assertNotNull(queueValue(reopened.resume(owner)))
            val newBoundary = SessionBoundary()
            val newLease = newBoundary.activate(owner)
            var gated = false
            val resumedQueue = queue(restored, newBoundary, dispatcher, clock,
                transport { _, call -> exchanges++; assertExactCall(call); success() },
                CommandExecutionGate { seenLease, seenIntent ->
                    assertTrue(seenLease === newLease)
                    assertEquals(commandId, seenIntent.commandId)
                    assertEquals(originBinding, seenIntent.originBinding)
                    assertExactCall(seenIntent.call)
                    gated = true
                    ExecutionDecision.Ready
                })
            assertEquals(commandId, queueValue(resumedQueue.pending(newLease)).single().commandId)
            assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(queueValue(resumedQueue.dispatchNext(newLease))).phase)
            assertTrue(gated)
            assertEquals(1, exchanges)
            assertRecord(restored, draftKey, 1, privateMarker)
        }
    }

    @Test fun lostResponseReopensAndRetriesOnlyTheOriginalLogicalCommand() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        withFixture(dispatcher) { fixture ->
            val clock = QueueStorageClock()
            val remoteKeys = mutableSetOf<String>()
            var remoteEffects = 0
            var exchanges = 0
            val endpoint = transport { _, call ->
                assertExactCall(call)
                exchanges++
                if (remoteKeys.add(call.idempotencyKey!!.use { it })) remoteEffects++
                if (exchanges == 1) PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) else success()
            }
            val db = fixture.open()
            val boundary = SessionBoundary()
            val lease = boundary.activate(owner)
            val initial = queue(queueValue(db.activate(owner)), boundary, dispatcher, clock, endpoint)
            queueValue(initial.enqueue(lease, intent()))
            val waiting = assertNotNull(queueValue(initial.dispatchNext(lease)))
            assertEquals(CommandPhase.RETRY_WAIT, waiting.phase)
            assertEquals(1, waiting.attempts)
            queueValue(db.close())

            val reopened = fixture.open()
            val restoredBoundary = SessionBoundary()
            val restoredLease = restoredBoundary.activate(owner)
            val resumed = queue(assertNotNull(queueValue(reopened.resume(owner))), restoredBoundary, dispatcher, clock, endpoint)
            assertEquals(0, queueValue(resumed.recoverInterrupted(restoredLease)))
            val persisted = queueValue(resumed.pending(restoredLease)).single()
            assertEquals(waiting.retryAtMillis, persisted.retryAtMillis)
            assertEquals(waiting.attempts, persisted.attempts)
            clock.millis = persisted.retryAtMillis + 1
            val receipt = assertNotNull(queueValue(resumed.dispatchNext(restoredLease)))
            assertEquals(CommandPhase.RECEIPT_READY, receipt.phase)
            assertEquals(commandId, receipt.commandId)
            assertEquals(2, exchanges)
            assertEquals(1, remoteEffects)
            assertEquals(setOf(commandId), remoteKeys)
        }
    }

    @Test fun cancelledDispatchLeavesRecoverableInFlightIntentAcrossReopen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        withFixture(dispatcher) { fixture ->
            val clock = QueueStorageClock()
            val entered = CompletableDeferred<Unit>()
            var remoteEffects = 0
            val remoteKeys = mutableSetOf<String>()
            val db = fixture.open()
            val boundary = SessionBoundary()
            val lease = boundary.activate(owner)
            val initial = queue(queueValue(db.activate(owner)), boundary, dispatcher, clock,
                transport { _, call ->
                    assertExactCall(call)
                    if (remoteKeys.add(call.idempotencyKey!!.use { it })) remoteEffects++
                    entered.complete(Unit)
                    awaitCancellation()
                })
            queueValue(initial.enqueue(lease, intent()))
            val dispatch = async { initial.dispatchNext(lease) }
            entered.await()
            dispatch.cancelAndJoin()
            assertEquals(CommandPhase.IN_FLIGHT, queueValue(initial.pending(lease)).single().phase)
            queueValue(db.close())

            val reopened = fixture.open()
            val restoredBoundary = SessionBoundary()
            val restoredLease = restoredBoundary.activate(owner)
            val resumed = queue(assertNotNull(queueValue(reopened.resume(owner))), restoredBoundary, dispatcher, clock,
                transport { _, call ->
                    assertExactCall(call)
                    if (remoteKeys.add(call.idempotencyKey!!.use { it })) remoteEffects++
                    success()
                })
            assertEquals(1, queueValue(resumed.recoverInterrupted(restoredLease)))
            val waiting = queueValue(resumed.pending(restoredLease)).single()
            assertEquals(CommandPhase.RETRY_WAIT, waiting.phase)
            assertEquals(1, waiting.attempts)
            clock.millis = waiting.retryAtMillis + 1
            assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(queueValue(resumed.dispatchNext(restoredLease))).phase)
            assertEquals(1, remoteEffects)
            assertEquals(setOf(commandId), remoteKeys)
        }
    }

    @Test fun successfulReceiptSurvivesReopenAndDomainApplyIsAtomicWithAcknowledgement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        withFixture(dispatcher) { fixture ->
            val clock = QueueStorageClock()
            val db = fixture.open()
            val store = queueValue(db.activate(owner))
            queueValue(store.commit(owner, listOf(put(draftKey, "before"))))
            val boundary = SessionBoundary()
            val lease = boundary.activate(owner)
            val initial = queue(store, boundary, dispatcher, clock, transport { _, call -> assertExactCall(call); success() })
            queueValue(initial.enqueue(lease, intent()))
            val received = assertNotNull(queueValue(initial.dispatchNext(lease)))
            assertEquals(CommandPhase.RECEIPT_READY, received.phase)
            queueValue(db.close())

            val reopened = fixture.open()
            val restored = assertNotNull(queueValue(reopened.resume(owner)))
            val restoredBoundary = SessionBoundary()
            val restoredLease = restoredBoundary.activate(owner)
            val resumed = queue(restored, restoredBoundary, dispatcher, clock, transport { _, _ -> error("Applying receipt cannot send") })
            val receipt = assertNotNull(queueValue(resumed.receipt(restoredLease, commandId)))
            assertEquals(received.localRevision, receipt.command.localRevision)
            assertContentEquals(receiptText.encodeToByteArray(), receipt.reply.body!!.copyForCodec())
            assertEquals("\"2\"", receipt.reply.etag)
            assertEquals("receipt-trace", receipt.reply.traceId)
            val conflict = resumed.applyReceipt(restoredLease, commandId, receipt.command.localRevision,
                listOf(put(derivedKey, "must roll back"), put(draftKey, "wrong revision", 999)))
            assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(conflict).reason)
            assertNull(queueValue(restored.read(owner, derivedKey)))
            assertRecord(restored, draftKey, 1, "before")
            assertNotNull(queueValue(resumed.receipt(restoredLease, commandId)))

            queueValue(resumed.applyReceipt(restoredLease, commandId, receipt.command.localRevision,
                listOf(put(derivedKey, "applied once"), put(draftKey, "after", 1))))
            assertTrue(queueValue(resumed.pending(restoredLease)).isEmpty())
            assertNull(queueValue(resumed.receipt(restoredLease, commandId)))
            assertIs<PortResult.Failure>(resumed.applyReceipt(restoredLease, commandId, receipt.command.localRevision,
                listOf(put(draftKey, "duplicate", 2))))
            queueValue(reopened.close())

            val finalStore = assertNotNull(queueValue(fixture.open().resume(owner)))
            assertRecord(finalStore, draftKey, 2, "after")
            assertRecord(finalStore, derivedKey, 1, "applied once")
        }
    }

    @Test fun conflictingDraftBatchLeavesNoDiscoverableOrOrphanedIntent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        withFixture(dispatcher) { fixture ->
            val db = fixture.open()
            val store = queueValue(db.activate(owner))
            queueValue(store.commit(owner, listOf(put(draftKey, "original"))))
            val boundary = SessionBoundary()
            val lease = boundary.activate(owner)
            val initial = queue(store, boundary, dispatcher, QueueStorageClock(), transport { _, _ -> error("No dispatch") })
            val outcome = initial.enqueue(lease, intent(), listOf(put(derivedKey, "rollback"), put(draftKey, "stale", 99)))
            assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(outcome).reason)
            assertTrue(queueValue(initial.pending(lease)).isEmpty())
            assertNull(queueValue(store.read(owner, derivedKey)))
            assertRecord(store, draftKey, 1, "original")
            queueValue(db.close())

            val restored = assertNotNull(queueValue(fixture.open().resume(owner)))
            val restoredBoundary = SessionBoundary()
            val restoredLease = restoredBoundary.activate(owner)
            val resumed = queue(restored, restoredBoundary, dispatcher, QueueStorageClock(), transport { _, _ -> error("No dispatch") })
            assertTrue(queueValue(resumed.pending(restoredLease)).isEmpty())
            // The exact same key succeeds: no hidden metadata/body/index or sequence reservation
            // from the rolled-back transaction can conflict with this corrected atomic enqueue.
            val inserted = queueValue(resumed.enqueue(restoredLease, intent(), listOf(put(draftKey, "corrected", 1))))
            assertEquals(commandId, inserted.commandId)
            assertEquals(1, queueValue(resumed.pending(restoredLease)).size)
            assertRecord(restored, draftKey, 2, "corrected")
        }
    }

    @Test fun ownerRetirementDuringPreflightPreventsSendAndOldQueueCannotUseReplacementOwner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        withFixture(dispatcher) { fixture ->
            val db = fixture.open()
            val store = queueValue(db.activate(owner))
            val boundary = SessionBoundary()
            val lease = boundary.activate(owner)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var exchanges = 0
            val endpoint = transport { _, _ -> exchanges++; error("Retired owner cannot send") }
            val initial = queue(store, boundary, dispatcher, QueueStorageClock(), endpoint,
                CommandExecutionGate { _, _ -> entered.complete(Unit); release.await(); ExecutionDecision.Ready })
            queueValue(initial.enqueue(lease, intent()))
            val oldKeys = fixture.vault.keyIds
            val dispatch = async { initial.dispatchNext(lease) }
            entered.await()
            boundary.clear()
            queueValue(store.eraseScope(owner))
            release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(dispatch.await()).reason)
            assertEquals(0, exchanges)
            assertTrue(oldKeys.none { it in fixture.vault.keyIds })
            assertNull(queueValue(db.resume(owner)))

            val replacement = queueValue(db.activate(owner))
            val replacementLease = boundary.activate(owner)
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(initial.dispatchNext(replacementLease)).reason)
            val replacementQueue = queue(replacement, boundary, dispatcher, QueueStorageClock(), endpoint)
            assertTrue(queueValue(replacementQueue.pending(replacementLease)).isEmpty())
            queueValue(db.close())
            val reopened = assertNotNull(queueValue(fixture.open().resume(owner)))
            val reopenedBoundary = SessionBoundary()
            val reopenedLease = reopenedBoundary.activate(owner)
            assertTrue(queueValue(queue(reopened, reopenedBoundary, dispatcher, QueueStorageClock(), endpoint).pending(reopenedLease)).isEmpty())
            assertEquals(0, exchanges)
        }
    }

    private fun queue(store: PrivateStateStore, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
        clock: EpochClock, transport: AccountTransport,
        gate: CommandExecutionGate = CommandExecutionGate { lease, command ->
            assertEquals(owner, lease.scope)
            assertEquals(originBinding, command.originBinding)
            ExecutionDecision.Ready
        }) = DurableCommandQueue(owner, store, boundary, dispatcher, clock, gate, transport)

    private fun intent() = CommandIntent(commandId, originBinding,
        ApiCall("updateCookSession", mapOf("sessionId" to sessionId), body = PrivateBytes(bodyText.encodeToByteArray()),
            idempotencyKey = SecretText(commandId), ifMatch = "\"0001\""))

    private fun assertExactCall(call: ApiCall) {
        assertEquals("updateCookSession", call.operationId)
        assertEquals(mapOf("sessionId" to sessionId), call.pathParameters)
        assertTrue(call.queryParameters.isEmpty())
        assertEquals(commandId, call.idempotencyKey!!.use { it })
        assertEquals("\"0001\"", call.ifMatch)
        assertContentEquals(bodyText.encodeToByteArray(), call.body!!.copyForCodec())
    }

    private fun success(): PortResult<ApiReply> = PortResult.Value(ApiReply(200, PrivateBytes(receiptText.encodeToByteArray()),
        etag = "\"2\"", traceId = "receipt-trace", contentType = "application/json"))

    private fun transport(block: suspend (SessionLease, ApiCall) -> PortResult<ApiReply>): AccountTransport =
        object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = block(lease, call)
        }

    private fun put(key: RecordKey, text: String, revision: Long? = null) =
        StoreMutation.Put(key, revision, 1, PrivateBytes(text.encodeToByteArray()))

    private suspend fun assertRecord(store: PrivateStateStore, key: RecordKey, revision: Long, text: String) {
        val record = assertNotNull(queueValue(store.read(owner, key)))
        assertEquals(revision, record.revision)
        assertContentEquals(text.encodeToByteArray(), record.payload.copyForCodec())
    }

    private suspend fun withFixture(dispatcher: CoroutineDispatcher, block: suspend (QueueStorageFixture) -> Unit) {
        val fixture = QueueStorageFixture(dispatcher)
        try { block(fixture) } finally { fixture.close() }
    }

    companion object {
        private const val commandId = "58a13108-846d-4f50-bafd-f7996f6a89db"
        private const val originBinding = "9bea4aab-12c2-423e-b1ec-8c4935f03c41"
        private const val sessionId = "095efea0-f83f-47db-bc0a-327c912d7c45"
        private const val planId = "bbf19ba6-b9de-45d4-8019-c55e0e958360"
        private const val privateMarker = "sensitive-command-note-marker-ec5138"
    }
}

private class QueueStorageClock(var millis: Long = 1_000_000) : EpochClock {
    override fun nowMillis(): Long = millis
}

private fun <T> queueValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected success, got ${result.reason}")
}

private class QueueStorageFixture(private val dispatcher: CoroutineDispatcher) {
    private val directory = Files.createTempDirectory("feedme-command-sqlite-test-")
    private val file: Path = directory.resolve("state.sqlite")
    val vault = QueueStorageTestVault()
    private val databases = mutableListOf<EncryptedStateDatabase>()

    suspend fun open(): EncryptedStateDatabase = queueValue(
        EncryptedStateDatabase.open(BundledSQLiteDriver().open(file.toString()), vault, dispatcher),
    ).also { databases += it }

    fun assertNoPlaintext(markers: List<String>) {
        // Inspect SQLite and any remaining journal/WAL files belonging to this fixture.
        Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { path ->
                val bytes = Files.readAllBytes(path)
                for (marker in markers) {
                    assertFalse(bytes.queueContains(marker.toByteArray(Charsets.UTF_8)), "Private UTF-8 marker found in SQLite storage")
                    assertFalse(bytes.queueContains(marker.toByteArray(Charsets.UTF_16LE)), "Private UTF-16 marker found in SQLite storage")
                }
            }
        }
    }

    suspend fun close() {
        try { databases.forEach { it.close() } } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/** Test-only AES/HMAC keys remain in this fixture across controlled connection close/reopen. */
private class QueueStorageTestVault : StateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    private val keys = mutableMapOf<String, SecretKey>()
    val keyIds: Set<String> get() = keys.keys.toSet()

    override fun index(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(indexKey); doFinal(input) }
    override fun createOwnerKey(): String = UUID.randomUUID().toString().replace("-", "").also { id ->
        keys[id] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
    }
    override fun hasOwnerKey(keyId: String): Boolean = keyId in keys
    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return byteArrayOf(1) + nonce + cipher.doFinal(plaintext)
    }
    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray = try {
        if (ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            updateAAD(associatedData)
            doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    } catch (_: Exception) { throw StateVaultException() }
    override fun deleteOwnerKey(keyId: String) { keys.remove(keyId) }
}

private fun ByteArray.queueContains(needle: ByteArray): Boolean {
    if (needle.size > size) return false
    for (start in 0..size - needle.size) if (needle.indices.all { this[start + it] == needle[it] }) return true
    return false
}
