package com.feedme.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import com.feedme.sync.CommandPhase
import com.feedme.sync.DurableCommandQueue
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual encrypted SQLite integration with fixture-only JCA keys, not a native/process-kill test. */
class KitchenStorageTest {
    @Test fun ownedSavedRecipeReopensWithExactOriginalWireAndNoPlaintextInSqlite() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            val raw = " \n" + savedBody() + "\n "
            var exchanges = 0
            val repository = SavedRecipeRepository(OWNER, store, boundary, fixture.dispatcher, CLOCK, endpoint { _, call ->
                exchanges++
                assertEquals("getSavedRecipe", call.operationId)
                assertEquals(mapOf("savedRecipeId" to SAVED), call.pathParameters)
                assertNull(call.body)
                assertNull(call.idempotencyKey)
                reply(raw, "\"0001\"")
            })
            val downloaded = kitchenStorageValue(repository.download(lease, SAVED.uppercase()))
            assertEquals(SavedRecipeAvailability.AVAILABLE, downloaded.availability)
            assertContentEquals(raw.encodeToByteArray(), assertNotNull(downloaded.savedRecipe).document.encodeUtf8())
            assertEquals("\"0001\"", downloaded.etag)
            val revision = assertNotNull(downloaded.localRevision)
            kitchenStorageValue(db.close())
            fixture.assertNoPlaintext(listOf(PRIVATE_TITLE, PRIVATE_INSTRUCTION, OWNER.actorId, SAVED, VERSION,
                "feedme.kitchen.saved.body", "privateCopyOnly", "Private creator marker"))

            val reopened = fixture.open()
            val restored = assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(OWNER)
            val offline = SavedRecipeRepository(OWNER, restored, nextBoundary, fixture.dispatcher, CLOCK,
                endpoint { _, _ -> exchanges++; error("Owned offline reads and title search cannot send") })
            val read = assertNotNull(kitchenStorageValue(offline.read(nextLease, SAVED)))
            assertEquals(SavedRecipeAvailability.AVAILABLE, read.availability)
            assertEquals(revision, read.localRevision)
            assertEquals("\"0001\"", read.etag)
            assertContentEquals(raw.encodeToByteArray(), assertNotNull(read.savedRecipe).document.encodeUtf8())
            assertEquals("1.2500", read.savedRecipe!!.snapshot.ingredients.single().quantity.jsonToken)
            assertEquals(listOf(SAVED), kitchenStorageValue(offline.search(nextLease, "private kitchen")).map { it.id })
            assertTrue(kitchenStorageValue(offline.search(nextLease, "not in this title")).isEmpty())
            assertEquals(1, exchanges)
        }
    }

    @Test fun authoritativeRecallPersistsAcrossDatabaseAndIdentityBoundaryRecreation() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            var response = reply(savedBody(), "\"1\"")
            val repository = SavedRecipeRepository(OWNER, store, boundary, fixture.dispatcher, CLOCK, endpoint { _, _ -> response })
            kitchenStorageValue(repository.download(lease, SAVED))
            response = PortResult.Value(ApiReply(410, bytes("""{"type":"https://example.test/problems/recall","title":"Content recalled","status":410,"code":"RECIPE_RECALLED","traceId":"recall-trace"}"""),
                contentType = "application/problem+json"))
            storageFailure(FailureReason.NOT_FOUND, repository.download(lease, SAVED))
            val held = assertNotNull(kitchenStorageValue(repository.read(lease, SAVED)))
            assertEquals(SavedRecipeAvailability.RECALLED, held.availability)
            assertNull(held.savedRecipe)
            assertNotNull(kitchenStorageValue(store.read(OWNER, RecordKey("feedme.kitchen.recall", "saved:$SAVED"))))
            assertNotNull(kitchenStorageValue(store.read(OWNER, RecordKey("feedme.kitchen.recall", "version:$VERSION"))))
            kitchenStorageValue(db.close())

            val reopened = fixture.open()
            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(OWNER)
            val restored = assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            val offline = SavedRecipeRepository(OWNER, restored, nextBoundary, fixture.dispatcher, CLOCK,
                endpoint { _, _ -> error("Recall survives without a server request or old in-memory boundary") })
            val recalled = assertNotNull(kitchenStorageValue(offline.read(nextLease, SAVED)))
            assertEquals(SavedRecipeAvailability.RECALLED, recalled.availability)
            assertNull(recalled.savedRecipe)
            assertTrue(kitchenStorageValue(offline.search(nextLease)).all { it.availability == SavedRecipeAvailability.RECALLED && it.savedRecipe == null })
        }
    }

    @Test fun cookingMutationRecallObserverDurablyHidesSameVersionSaveBeforeAnyCookingRead() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            val rawPlan = " \n" + planBody() + "\n "
            var mutationCalls = 0
            val transport = endpoint { seenLease, call ->
                assertSame(lease, seenLease)
                when (call.operationId) {
                    "getSavedRecipe" -> reply(savedBody(), "\"1\"")
                    "getCookSession" -> reply(sessionBody(), "\"7\"")
                    "getPlan" -> reply(rawPlan, "\"3\"")
                    "updateCookSession" -> {
                        mutationCalls++
                        assertEquals(ACTION, call.idempotencyKey!!.use { it })
                        assertEquals("\"7\"", call.ifMatch)
                        assertEquals(mapOf("sessionId" to SESSION), call.pathParameters)
                        PortResult.Value(ApiReply(410,
                            bytes("""{"type":"https://example.test/problems/recall","title":"Recipe recalled","status":410,"code":"RECIPE_RECALLED","traceId":"mutation-recall-trace"}"""),
                            traceId = "mutation-recall-trace", contentType = "application/problem+json"))
                    }
                    else -> error("Unexpected factory operation")
                }
            }
            val session = PrivateKitchenSession(OWNER, store, boundary, fixture.dispatcher, CLOCK, transport, ORIGIN)
            val saved = kitchenStorageValue(session.savedRecipes.download(lease, SAVED))
            assertEquals(SavedRecipeAvailability.AVAILABLE, saved.availability)
            assertEquals(VERSION, saved.savedRecipe!!.snapshot.id.value)
            val pin = kitchenStorageValue(session.cooking.download(lease, SESSION))
            assertContentEquals(rawPlan.encodeToByteArray(), pin.plan.document.encodeUtf8())
            kitchenStorageValue(session.cooking.edit(lease, SESSION, pin.localRevision, ACTION, CookingEdit.MoveTo("step-two")))
            assertEquals(ACTION, assertNotNull(kitchenStorageValue(session.cooking.materializeNext(lease, SESSION))).commandId)
            val outcome = assertNotNull(kitchenStorageValue(session.commands.dispatchAutomatic(lease, ACTION)))
            assertEquals(CommandPhase.NEEDS_RESOLUTION, outcome.phase)
            assertEquals(1, mutationCalls)
            val marker = assertNotNull(kitchenStorageValue(store.read(OWNER, RecordKey("feedme.kitchen.recall", "version:$VERSION"))))
            assertContentEquals(byteArrayOf(1), marker.payload.copyForCodec())
            assertContentEquals(rawPlan.encodeToByteArray(), storedBytes(store, "plan", SESSION))
            // The queue's reply observer installed the shared version fence; no cooking read or
            // subsequent detail refresh is needed to protect this independently owned saved copy.
            val hidden = assertNotNull(kitchenStorageValue(session.savedRecipes.read(lease, SAVED)))
            assertEquals(SavedRecipeAvailability.RECALLED, hidden.availability)
            assertNull(hidden.savedRecipe)
            kitchenStorageValue(db.close())

            val reopened = fixture.open()
            val restored = assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(OWNER)
            val offline = PrivateKitchenSession(OWNER, restored, nextBoundary, fixture.dispatcher, CLOCK,
                endpoint { _, _ -> error("A durable recall must not require another request") }, ORIGIN)
            // This saved read is deliberately the first repository operation with the new boundary.
            val firstRead = assertNotNull(kitchenStorageValue(offline.savedRecipes.read(nextLease, SAVED)))
            assertEquals(SavedRecipeAvailability.RECALLED, firstRead.availability)
            assertNull(firstRead.savedRecipe)
            assertTrue(kitchenStorageValue(offline.savedRecipes.search(nextLease)).all {
                it.availability == SavedRecipeAvailability.RECALLED && it.savedRecipe == null
            })
            assertContentEquals(rawPlan.encodeToByteArray(), storedBytes(restored, "plan", SESSION))
            assertEquals(CookingAvailability.RECALLED, assertNotNull(kitchenStorageValue(offline.cooking.read(nextLease, SESSION))).availability)
            assertNull(kitchenStorageValue(offline.commands.dispatchAutomatic(nextLease, ACTION)))
            assertEquals(1, mutationCalls)
        }
    }

    @Test fun authenticatedButMismatchedSavedBodyFailsClosedAfterReopenWithoutDestructiveRepair() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            val repository = SavedRecipeRepository(OWNER, store, boundary, fixture.dispatcher, CLOCK, endpoint { _, _ -> reply(savedBody(), "\"1\"") })
            kitchenStorageValue(repository.download(lease, SAVED))
            val key = RecordKey("feedme.kitchen.saved.body", SAVED)
            val old = assertNotNull(kitchenStorageValue(store.read(OWNER, key)))
            val tampered = savedBody().replace(PRIVATE_INSTRUCTION, "modified private instruction")
            // This is an authenticated store write with a stale metadata digest, not AEAD corruption.
            kitchenStorageValue(store.commit(OWNER, listOf(StoreMutation.Put(key, old.revision, 1, bytes(tampered)))))
            kitchenStorageValue(db.close())

            val reopened = fixture.open()
            val restored = assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(OWNER)
            var exchanges = 0
            val offline = SavedRecipeRepository(OWNER, restored, nextBoundary, fixture.dispatcher, CLOCK,
                endpoint { _, _ -> exchanges++; error("Corruption must not trigger automatic download") })
            val broken = assertNotNull(kitchenStorageValue(offline.read(nextLease, SAVED)))
            assertEquals(SavedRecipeAvailability.INTEGRITY_FAILURE, broken.availability)
            assertNull(broken.savedRecipe)
            assertNull(broken.etag)
            assertEquals(SavedRecipeAvailability.INTEGRITY_FAILURE, kitchenStorageValue(offline.search(nextLease)).single().availability)
            storageFailure(FailureReason.INVALID_DATA, offline.download(nextLease, SAVED))
            assertEquals(0, exchanges)
            assertContentEquals(tampered.encodeToByteArray(), assertNotNull(kitchenStorageValue(restored.read(OWNER, key))).payload.copyForCodec())
        }
    }

    @Test fun savedCopiesRemainOwnerIsolatedAcrossActualDatabaseHandles() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            kitchenStorageValue(SavedRecipeRepository(OWNER, store, boundary, fixture.dispatcher, CLOCK,
                endpoint { _, _ -> reply(savedBody(), "\"1\"") }).download(lease, SAVED))
            val otherScope = OWNER.copy(actorId = "different-private-owner")
            val otherStore = kitchenStorageValue(db.activate(otherScope))
            val otherBoundary = SessionBoundary()
            val otherLease = otherBoundary.activate(otherScope)
            val other = SavedRecipeRepository(otherScope, otherStore, otherBoundary, fixture.dispatcher, CLOCK,
                endpoint { _, _ -> error("Other owner cannot discover saved records remotely") })
            assertNull(kitchenStorageValue(other.read(otherLease, SAVED)))
            assertTrue(kitchenStorageValue(other.search(otherLease)).isEmpty())
            storageFailure(FailureReason.STALE_SESSION, other.read(lease, SAVED))
            kitchenStorageValue(db.close())
            val reopened = fixture.open()
            assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            assertNotNull(kitchenStorageValue(reopened.resume(otherScope)))
        }
    }

    @Test fun cookingPinAndTwoOfflineEditsReopenThenApplyOrderedReceiptsWithActualEtags() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            val readCalls = mutableListOf<String>()
            val onlineRead = endpoint { _, call ->
                readCalls += call.operationId
                when (call.operationId) {
                    "getCookSession" -> reply(sessionBody(), "\"0007\"")
                    "getPlan" -> reply(planBody(), "\"3\"")
                    else -> error("Offline edits must not dispatch mutations")
                }
            }
            val initial = cooking(store, boundary, fixture.dispatcher, onlineRead)
            val pin = kitchenStorageValue(initial.repository.download(lease, SESSION))
            assertEquals(CookingAvailability.AVAILABLE, pin.availability)
            val first = kitchenStorageValue(initial.repository.edit(lease, SESSION, pin.localRevision, ACTION,
                CookingEdit.MarkStepComplete("step-one")))
            val second = kitchenStorageValue(initial.repository.edit(lease, SESSION, first.localRevision, ACTION2,
                CookingEdit.MoveTo("step-two")))
            assertEquals(listOf(ACTION, ACTION2), second.pendingCommandIds)
            assertEquals("5", second.progress.deviceSequence)
            assertEquals("step-two", second.progress.currentStepId)
            assertEquals(listOf("step-one"), second.progress.completedStepIds)
            assertEquals("3", second.remote.deviceSequence.jsonToken)
            assertEquals(listOf("getCookSession", "getPlan"), readCalls)
            assertTrue(kitchenStorageValue(initial.queue.pending(lease)).isEmpty())
            val actionBytes = mapOf(ACTION to storedBytes(store, "action-body", ACTION), ACTION2 to storedBytes(store, "action-body", ACTION2))
            val remoteBytes = second.remote.document.encodeUtf8()
            val planBytes = second.plan.document.encodeUtf8()
            kitchenStorageValue(db.close())
            fixture.assertNoPlaintext(listOf(PRIVATE_INSTRUCTION, SESSION, PLAN, ORIGIN, ACTION, ACTION2,
                "feedme.kitchen.cook.progress", "step-two", "MarkStepComplete"))

            val reopened = fixture.open()
            val restored = assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(OWNER)
            val sent = mutableListOf<ApiCall>()
            val mutations = endpoint { seenLease, call ->
                assertSame(nextLease, seenLease)
                assertEquals("updateCookSession", call.operationId)
                assertEquals(mapOf("sessionId" to SESSION), call.pathParameters)
                val command = call.idempotencyKey!!.use { it }
                assertContentEquals(actionBytes.getValue(command), call.body!!.copyForCodec())
                sent += call
                when (command) {
                    ACTION -> { assertEquals("\"0007\"", call.ifMatch); reply(sessionBody(version = 8, sequence = 4, completed = "[\"step-one\"]"), "\"8\"") }
                    ACTION2 -> { assertEquals("\"8\"", call.ifMatch); reply(sessionBody(version = 9, sequence = 5, currentStep = "step-two", completed = "[\"step-one\"]"), "\"9\"") }
                    else -> error("No invented logical command key")
                }
            }
            val resumed = cooking(restored, nextBoundary, fixture.dispatcher, mutations)
            val recovered = assertNotNull(kitchenStorageValue(resumed.repository.read(nextLease, SESSION)))
            assertEquals(CookingAvailability.AVAILABLE, recovered.availability)
            assertEquals(listOf(ACTION, ACTION2), recovered.pendingCommandIds)
            assertEquals("step-two", recovered.progress.currentStepId)
            assertEquals("5", recovered.progress.deviceSequence)
            assertContentEquals(remoteBytes, recovered.remote.document.encodeUtf8())
            assertContentEquals(planBytes, recovered.plan.document.encodeUtf8())
            assertTrue(sent.isEmpty())
            assertEquals(ACTION, assertNotNull(kitchenStorageValue(resumed.repository.materializeNext(nextLease, SESSION))).commandId)
            assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(kitchenStorageValue(resumed.queue.dispatchNext(nextLease))).phase)
            val afterFirst = kitchenStorageValue(resumed.repository.applyReceipt(nextLease, SESSION))
            assertEquals(listOf(ACTION2), afterFirst.pendingCommandIds)
            assertEquals("8", afterFirst.remote.version.jsonToken)
            assertEquals("5", afterFirst.progress.deviceSequence)
            assertEquals("step-two", afterFirst.progress.currentStepId)
            assertEquals(ACTION2, assertNotNull(kitchenStorageValue(resumed.repository.materializeNext(nextLease, SESSION))).commandId)
            assertEquals(CommandPhase.RECEIPT_READY, assertNotNull(kitchenStorageValue(resumed.queue.dispatchNext(nextLease))).phase)
            val completed = kitchenStorageValue(resumed.repository.applyReceipt(nextLease, SESSION))
            assertTrue(completed.pendingCommandIds.isEmpty())
            assertEquals("9", completed.remote.version.jsonToken)
            assertTrue(kitchenStorageValue(resumed.queue.pending(nextLease)).isEmpty())
            assertEquals(listOf(ACTION, ACTION2), sent.map { it.idempotencyKey!!.use { key -> key } })
            kitchenStorageValue(reopened.close())

            val finalDatabase = fixture.open()
            val finalStore = assertNotNull(kitchenStorageValue(finalDatabase.resume(OWNER)))
            val finalBoundary = SessionBoundary()
            val finalLease = finalBoundary.activate(OWNER)
            val final = cooking(finalStore, finalBoundary, fixture.dispatcher, endpoint { _, _ -> error("Final offline read must not send") })
            val read = assertNotNull(kitchenStorageValue(final.repository.read(finalLease, SESSION)))
            assertTrue(read.pendingCommandIds.isEmpty())
            assertEquals("9", read.remote.version.jsonToken)
            assertEquals("5", read.progress.deviceSequence)
            assertEquals("step-two", read.progress.currentStepId)
            assertEquals("\"9\"", read.etag)
            assertNull(kitchenStorageValue(finalStore.read(OWNER, cookKey("action-body", ACTION))))
            assertNull(kitchenStorageValue(finalStore.read(OWNER, cookKey("action-body", ACTION2))))
        }
    }

    @Test fun staleCookingEditRevisionCannotPartiallyWriteProgressOrCommandIntent() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            val initial = cooking(store, boundary, fixture.dispatcher, pinEndpoint())
            val pin = kitchenStorageValue(initial.repository.download(lease, SESSION))
            val edited = kitchenStorageValue(initial.repository.edit(lease, SESSION, pin.localRevision, ACTION, CookingEdit.MoveTo("step-two")))
            storageFailure(FailureReason.CONFLICT, initial.repository.edit(lease, SESSION, pin.localRevision, ACTION2,
                CookingEdit.MarkStepComplete("step-one")))
            assertNull(kitchenStorageValue(store.read(OWNER, cookKey("action", ACTION2))))
            assertNull(kitchenStorageValue(store.read(OWNER, cookKey("action-body", ACTION2))))
            kitchenStorageValue(db.close())
            val reopened = fixture.open()
            val restored = assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(OWNER)
            val offline = cooking(restored, nextBoundary, fixture.dispatcher, endpoint { _, _ -> error("Read cannot retry old edit") })
            val read = assertNotNull(kitchenStorageValue(offline.repository.read(nextLease, SESSION)))
            assertEquals(edited.localRevision, read.localRevision)
            assertEquals(listOf(ACTION), read.pendingCommandIds)
            assertEquals("step-two", read.progress.currentStepId)
            assertTrue(read.progress.completedStepIds.isEmpty())
            assertEquals("4", read.progress.deviceSequence)
        }
    }

    @Test fun exponentSpelledHugeRemoteCounterReopensExactlyButCannotCreateLocalEdit() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            val raw = sessionBody().replace("\"deviceSequence\":3", "\"deviceSequence\":1e1000")
            val normalized = "1" + "0".repeat(1000)
            var exchanges = 0
            val initial = cooking(store, boundary, fixture.dispatcher, endpoint { _, call ->
                exchanges++
                when (call.operationId) {
                    "getCookSession" -> reply(raw, "\"7\"")
                    "getPlan" -> reply(planBody(), "\"3\"")
                    else -> error("An unrepresentable local sequence cannot create an HTTP mutation")
                }
            })
            val downloaded = kitchenStorageValue(initial.repository.download(lease, SESSION))
            assertEquals(normalized, downloaded.progress.deviceSequence)
            assertEquals("1e1000", downloaded.remote.deviceSequence.jsonToken)
            assertContentEquals(raw.encodeToByteArray(), downloaded.remote.document.encodeUtf8())
            storageFailure(FailureReason.INVALID_DATA, initial.repository.edit(lease, SESSION, downloaded.localRevision,
                ACTION, CookingEdit.MoveTo("step-two")))
            assertTrue(kitchenStorageValue(initial.queue.pending(lease)).isEmpty())
            assertEquals(2, exchanges)
            kitchenStorageValue(db.close())

            val reopened = fixture.open()
            val restored = assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(OWNER)
            val offline = cooking(restored, nextBoundary, fixture.dispatcher, endpoint { _, _ ->
                exchanges++
                error("Read-only huge counter must not dispatch or refresh automatically")
            })
            val read = assertNotNull(kitchenStorageValue(offline.repository.read(nextLease, SESSION)))
            assertEquals(downloaded.localRevision, read.localRevision)
            assertEquals(normalized, read.progress.deviceSequence)
            assertEquals("1e1000", read.remote.deviceSequence.jsonToken)
            assertContentEquals(raw.encodeToByteArray(), read.remote.document.encodeUtf8())
            assertTrue(read.pendingCommandIds.isEmpty())
            storageFailure(FailureReason.INVALID_DATA, offline.repository.edit(nextLease, SESSION, read.localRevision,
                ACTION2, CookingEdit.MarkStepComplete("step-one")))
            assertNull(kitchenStorageValue(restored.read(OWNER, cookKey("action", ACTION))))
            assertNull(kitchenStorageValue(restored.read(OWNER, cookKey("action-body", ACTION))))
            assertNull(kitchenStorageValue(restored.read(OWNER, cookKey("action", ACTION2))))
            assertNull(kitchenStorageValue(restored.read(OWNER, cookKey("action-body", ACTION2))))
            assertTrue(kitchenStorageValue(offline.queue.pending(nextLease)).isEmpty())
            assertEquals(2, exchanges)
        }
    }

    @Test fun alteredEncryptedProgressFailsClosedAfterReopenBeforeAnyDispatch() = runTest {
        withDatabase(StandardTestDispatcher(testScheduler)) { fixture ->
            val db = fixture.open()
            val store = kitchenStorageValue(db.activate(OWNER))
            val boundary = SessionBoundary()
            val lease = boundary.activate(OWNER)
            val initial = cooking(store, boundary, fixture.dispatcher, pinEndpoint())
            val pin = kitchenStorageValue(initial.repository.download(lease, SESSION))
            kitchenStorageValue(initial.repository.edit(lease, SESSION, pin.localRevision, ACTION, CookingEdit.MoveTo("step-two")))
            val key = cookKey("progress", SESSION)
            val before = assertNotNull(kitchenStorageValue(store.read(OWNER, key)))
            val altered = before.payload.copyForCodec().decodeToString().replace("step-two", "step-one")
            kitchenStorageValue(store.commit(OWNER, listOf(StoreMutation.Put(key, before.revision, 1, bytes(altered)))))
            kitchenStorageValue(db.close())

            val reopened = fixture.open()
            val restored = assertNotNull(kitchenStorageValue(reopened.resume(OWNER)))
            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(OWNER)
            var sent = 0
            val offline = cooking(restored, nextBoundary, fixture.dispatcher, endpoint { _, _ -> sent++; error("Corrupt private progress must not send") })
            storageFailure(FailureReason.INVALID_DATA, offline.repository.read(nextLease, SESSION))
            storageFailure(FailureReason.INVALID_DATA, offline.repository.list(nextLease))
            storageFailure(FailureReason.INVALID_DATA, offline.repository.materializeNext(nextLease, SESSION))
            assertTrue(kitchenStorageValue(offline.queue.pending(nextLease)).isEmpty())
            assertEquals(0, sent)
            assertNotNull(kitchenStorageValue(restored.read(OWNER, cookKey("action-body", ACTION))))
        }
    }

    private class CookingBundle(val repository: CookingRepository, val queue: DurableCommandQueue)
    private fun cooking(store: PrivateStateStore, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
        endpoint: AccountTransport): CookingBundle {
        val session = PrivateKitchenSession(OWNER, store, boundary, dispatcher, CLOCK, endpoint, ORIGIN)
        return CookingBundle(session.cooking, session.commands)
    }

    private suspend fun storedBytes(store: PrivateStateStore, part: String, id: String): ByteArray =
        assertNotNull(kitchenStorageValue(store.read(OWNER, cookKey(part, id)))).payload.copyForCodec()

    private suspend fun withDatabase(dispatcher: CoroutineDispatcher, block: suspend (KitchenDatabaseFixture) -> Unit) {
        val fixture = KitchenDatabaseFixture(dispatcher)
        try { block(fixture) } finally { fixture.close() }
    }

    companion object {
        private val OWNER = StorageScope("kitchen-integration", ActorKind.ACCOUNT, "private-kitchen-owner-379fda")
        private val CLOCK = EpochClock { 1_800_000_000_000 }
        private const val SAVED = "123e4567-e89b-12d3-a456-426614174abc"
        private const val SESSION = "123e4567-e89b-12d3-a456-426614174abd"
        private const val PLAN = "123e4567-e89b-12d3-a456-426614174abe"
        private const val ORIGIN = "123e4567-e89b-12d3-a456-426614174abf"
        private const val ACTION = "123e4567-e89b-12d3-a456-426614174ac0"
        private const val ACTION2 = "123e4567-e89b-12d3-a456-426614174ac1"
        private const val VERSION = "123e4567-e89b-12d3-a456-426614174080"
        private const val RECIPE = "123e4567-e89b-12d3-a456-426614174081"
        private const val INGREDIENT = "123e4567-e89b-12d3-a456-426614174082"
        private const val SOURCE = "123e4567-e89b-12d3-a456-426614174083"
        private const val GRANT = "123e4567-e89b-12d3-a456-426614174084"
        private const val PRIVATE_TITLE = "Private kitchen title marker 092eaa"
        private const val PRIVATE_INSTRUCTION = "Private kitchen instruction marker c3bf71"
        private fun bytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
        private fun reply(raw: String, etag: String): PortResult<ApiReply> = PortResult.Value(ApiReply(200, bytes(raw), etag = etag, contentType = "application/json"))
        private fun endpoint(block: suspend (SessionLease, ApiCall) -> PortResult<ApiReply>) = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = block(lease, call)
        }
        private fun pinEndpoint() = endpoint { _, call -> when (call.operationId) {
            "getCookSession" -> reply(sessionBody(), "\"7\"")
            "getPlan" -> reply(planBody(), "\"3\"")
            else -> error("Pin-only fixture cannot dispatch edits")
        } }
        private fun cookKey(part: String, id: String) = RecordKey("feedme.kitchen.cook.$part", id)
        private fun recipeBody() = """{"id":"$VERSION","recipeId":"$RECIPE","version":2,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","title":"Pinned private recipe","reviewStatus":"published","contentLicense":"privateCopyOnly","servings":1,"activeMinutes":5,"totalMinutes":5,"utensilCount":1,"equipmentIds":["bowl"],"modes":["cook"],"tasteTags":[],"ingredients":[{"ingredientId":"$INGREDIENT","quantity":1.2500,"unit":"g","optional":false}],"steps":[{"stepId":"step-one","position":1,"instruction":"$PRIVATE_INSTRUCTION","ingredientIds":["$INGREDIENT"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":false},{"stepId":"step-two","position":2,"instruction":"Finish the private dish","ingredientIds":["$INGREDIENT"],"requiredEquipmentIds":["bowl"],"mandatorySafetyStep":false}]}"""
        private fun savedBody() = """{"id":"$SAVED","version":1,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","title":"$PRIVATE_TITLE","snapshot":${recipeBody()},"sourceType":"postGrant","sourcePostId":"$SOURCE","grantId":"$GRANT","creatorLabel":"Private creator marker","recalled":false,"contentLicense":"privateCopyOnly"}"""
        private fun planBody() = """{"id":"$PLAN","version":3,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","recipeVersionId":"$VERSION","mode":"cook","status":"ready","constraints":{"ingredientIds":["$INGREDIENT"],"energy":"little","equipmentIds":["bowl"],"servings":1,"hardExcludedIngredientIds":[]},"recipeSnapshot":${recipeBody()},"missingIngredients":[],"changes":[],"reasons":[],"catalogRevision":"integration-catalog-1"}"""
        private fun sessionBody(version: Int = 7, sequence: Int = 3, currentStep: String = "step-one", completed: String = "[]") =
            """{"id":"$SESSION","version":$version,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","planId":"$PLAN","status":"active","currentStepId":"$currentStep","completedStepIds":$completed,"deviceSequence":$sequence,"timers":[]}"""
        private fun storageFailure(reason: FailureReason, result: PortResult<*>) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}

private fun <T> kitchenStorageValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected success, got ${result.reason}")
}

private class KitchenDatabaseFixture(val dispatcher: CoroutineDispatcher) {
    private val directory = Files.createTempDirectory("feedme-kitchen-sqlite-test-")
    private val file: Path = directory.resolve("state.sqlite")
    private val vault = KitchenFixtureVault()
    private val opened = mutableListOf<EncryptedStateDatabase>()
    suspend fun open(): EncryptedStateDatabase = kitchenStorageValue(
        EncryptedStateDatabase.open(BundledSQLiteDriver().open(file.toString()), vault, dispatcher),
    ).also { opened += it }
    fun assertNoPlaintext(markers: List<String>) {
        Files.list(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { path ->
            val bytes = Files.readAllBytes(path)
            for (marker in markers) {
                assertFalse(bytes.kitchenContains(marker.toByteArray(Charsets.UTF_8)), "Private UTF-8 marker in SQLite")
                assertFalse(bytes.kitchenContains(marker.toByteArray(Charsets.UTF_16LE)), "Private UTF-16 marker in SQLite")
            }
        } }
    }
    suspend fun close() {
        try { opened.forEach { it.close() } } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/** Fixture-only keys persist in memory across controlled connection close/reopen, never production. */
private class KitchenFixtureVault : StateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    private val keys = mutableMapOf<String, SecretKey>()
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

private fun ByteArray.kitchenContains(needle: ByteArray): Boolean {
    if (needle.size > size) return false
    for (start in 0..size - needle.size) if (needle.indices.all { this[start + it] == needle[it] }) return true
    return false
}
