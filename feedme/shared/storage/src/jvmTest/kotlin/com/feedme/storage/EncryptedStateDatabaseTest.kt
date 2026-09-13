package com.feedme.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.PrivateRecord
import com.feedme.core.ports.PrivateStateStore
import com.feedme.core.ports.RecordKey
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoreMutation
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** Real bundled SQLite and test-only JCA keys. This is not evidence for Keystore or Keychain. */
class EncryptedStateDatabaseTest {
    private val owner = StorageScope("integration", ActorKind.ACCOUNT, "owner-private-4db021")
    private val key = RecordKey("draft-private-e174fc", "record-private-a132b5")

    @Test fun createUpdateDeleteAndRecreateKeepMonotonicRevision() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            assertNull(value(db.resume(owner)))
            val store = value(db.activate(owner))
            assertFailure(FailureReason.CONFLICT, db.activate(owner))
            assertNull(value(store.read(owner, key)))
            assertEquals(1L, value(store.commit(owner, listOf(put(key, "first"))))[key])
            assertRecord(store, owner, key, 1, "first")
            assertFailure(FailureReason.CONFLICT, store.commit(owner, listOf(put(key, "overwrite"))))
            assertFailure(FailureReason.CONFLICT, store.commit(owner, listOf(put(key, "stale", 2))))
            assertEquals(2L, value(store.commit(owner, listOf(put(key, "second", 1))))[key])
            assertFailure(FailureReason.CONFLICT, store.commit(owner, listOf(StoreMutation.Delete(key, 1))))
            assertNull(value(store.commit(owner, listOf(StoreMutation.Delete(key, 2))))[key])
            assertNull(value(store.read(owner, key)))
            assertFailure(FailureReason.CONFLICT, store.commit(owner, listOf(put(key, "stale resurrection", 2))))
            assertEquals(4L, value(store.commit(owner, listOf(put(key, "new incarnation"))))[key])
            assertRecord(store, owner, key, 4, "new incarnation")
        }
    }

    @Test fun revisionConflictRollsBackEveryMutationInTheBatch() = runBlocking {
        withFixture { fixture ->
            val store = value(fixture.open().activate(owner))
            value(store.commit(owner, listOf(put(key, "original"))))
            val another = RecordKey("outbox", "command-private")
            val outcome = store.commit(owner, listOf(put(another, "pending command"), put(key, "wrong", 27)))
            assertFailure(FailureReason.CONFLICT, outcome)
            assertNull(value(store.read(owner, another)))
            assertRecord(store, owner, key, 1, "original")
            assertEquals(1L, value(store.commit(owner, listOf(put(another, "retry intent"))))[another])
        }
    }

    @Test fun duplicateKeysRejectTheEntireBatch() = runBlocking {
        withFixture { fixture ->
            val store = value(fixture.open().activate(owner))
            assertFailure(FailureReason.INVALID_DATA, store.commit(owner, listOf(put(key, "one"), put(key, "two"))))
            assertNull(value(store.read(owner, key)))
            value(store.commit(owner, listOf(put(key, "original"))))
            assertFailure(
                FailureReason.INVALID_DATA,
                store.commit(owner, listOf(put(key, "replacement", 1), StoreMutation.Delete(key, 1))),
            )
            assertRecord(store, owner, key, 1, "original")
        }
    }

    @Test fun failedEncryptionRollsBackEarlierBatchWrites() = runBlocking {
        withFixture { fixture ->
            val store = value(fixture.open().activate(owner))
            value(store.commit(owner, listOf(put(key, "before"))))
            val another = RecordKey("outbox", "private-command")
            fixture.vault.failSealOnCall = fixture.vault.sealCalls + 2
            assertFailure(
                FailureReason.STORAGE_FAILURE,
                store.commit(owner, listOf(put(key, "after", 1), put(another, "new"))),
            )
            fixture.vault.failSealOnCall = null
            assertRecord(store, owner, key, 1, "before")
            assertNull(value(store.read(owner, another)))
        }
    }

    @Test fun ciphertextPersistsAcrossCloseAndReopenWithoutPrivateIdentifiers() = runBlocking {
        withFixture { fixture ->
            val privateValue = "sensitive dietary note - persistence marker - f9b3402c"
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, privateValue, schema = 7))))
            val keyHandles = fixture.vault.keys.keys.toSet()
            db.close()
            val bytes = Files.readAllBytes(fixture.file)
            for (privateText in listOf(owner.actorId, key.collection, key.id, privateValue)) {
                assertFalse(bytes.containsBytes(privateText.toByteArray(Charsets.UTF_8)), "Private text found in SQLite file")
                assertFalse(bytes.containsBytes(privateText.toByteArray(Charsets.UTF_16LE)), "Private text found in SQLite file")
            }
            val reopened = fixture.open()
            val resumed = assertNotNull(value(reopened.resume(owner)))
            val record = assertNotNull(value(resumed.read(owner, key)))
            assertEquals(1L, record.revision)
            assertEquals(7, record.schemaVersion)
            assertContentEquals(privateValue.encodeToByteArray(), record.payload.copyForCodec())
            assertEquals(keyHandles, fixture.vault.keys.keys)
        }
    }

    @Test fun environmentActorKindAndActorIdAreIndependentOwners() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val scopes = listOf(owner, owner.copy(environment = "production"), owner.copy(actorKind = ActorKind.GUEST),
                owner.copy(actorKind = ActorKind.DEMO), owner.copy(actorId = "different-owner"))
            val stores = scopes.map { value(db.activate(it)) }
            scopes.forEachIndexed { index, scope ->
                assertNull(value(stores[index].read(scope, key)))
                value(stores[index].commit(scope, listOf(put(key, "owner $index"))))
            }
            scopes.forEachIndexed { index, scope -> assertRecord(stores[index], scope, key, 1, "owner $index") }
            value(stores[0].eraseScope(scopes[0]))
            scopes.drop(1).forEachIndexed { index, scope -> assertRecord(stores[index + 1], scope, key, 1, "owner ${index + 1}") }
        }
    }

    @Test fun handleCannotReadWriteOrEraseAnotherScope() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            val other = owner.copy(actorId = "other-owner")
            val otherStore = value(db.activate(other))
            value(otherStore.commit(other, listOf(put(key, "other private state"))))
            assertFailure(FailureReason.STALE_SESSION, store.read(other, key))
            assertFailure(FailureReason.STALE_SESSION, store.commit(other, listOf(put(key, "stolen overwrite", 1))))
            assertFailure(FailureReason.STALE_SESSION, store.eraseScope(other))
            assertRecord(otherStore, other, key, 1, "other private state")
            assertNull(value(store.read(owner, key)))
        }
    }

    @Test fun eraseRetiresAllOldHandlesAndSurvivesReactivationAndReopen() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val first = value(db.activate(owner))
            val retained = assertNotNull(value(db.resume(owner)))
            value(first.commit(owner, listOf(put(key, "old owner state"))))
            val oldKeyIds = fixture.vault.keys.keys.toSet()
            value(first.eraseScope(owner))
            assertNull(value(db.resume(owner)))
            assertTrue(oldKeyIds.none { it in fixture.vault.keys })
            assertStale(first, owner)
            assertStale(retained, owner)
            val replacement = value(db.activate(owner))
            assertNull(value(replacement.read(owner, key)))
            value(replacement.commit(owner, listOf(put(key, "new owner state"))))
            assertStale(first, owner)
            assertStale(retained, owner)
            value(first.eraseScope(owner))
            value(retained.eraseScope(owner))
            assertRecord(replacement, owner, key, 1, "new owner state")
            db.close()
            val reopened = fixture.open()
            assertRecord(assertNotNull(value(reopened.resume(owner))), owner, key, 1, "new owner state")
            assertRejected(first.commit(owner, listOf(put(key, "late old write"))))
        }
    }

    @Test fun failedKeyDeletionRetiresOwnerAndResumeRetriesCleanup() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "must stay erased"))))
            fixture.vault.failDelete = true
            assertFailure(FailureReason.STORAGE_FAILURE, store.eraseScope(owner))
            assertStale(store, owner)
            val attempts = fixture.vault.deleteCalls
            assertFailure(FailureReason.STORAGE_FAILURE, db.resume(owner))
            assertTrue(fixture.vault.deleteCalls > attempts)
            fixture.vault.failDelete = false
            assertNull(value(db.resume(owner)))
            assertTrue(fixture.vault.keys.isEmpty())
            val replacement = value(db.activate(owner))
            assertNull(value(replacement.read(owner, key)))
        }
    }

    @Test fun activateRetriesPendingKeyCleanupBeforeMakingNewOwnerKey() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            fixture.vault.failDelete = true
            assertFailure(FailureReason.STORAGE_FAILURE, store.eraseScope(owner))
            val creates = fixture.vault.createCalls
            assertFailure(FailureReason.STORAGE_FAILURE, db.activate(owner))
            assertEquals(creates, fixture.vault.createCalls)
            fixture.vault.failDelete = false
            val replacement = value(db.activate(owner))
            assertNull(value(replacement.read(owner, key)))
            assertEquals(creates + 1, fixture.vault.createCalls)
            assertEquals(1, fixture.vault.keys.size)
        }
    }

    @Test fun reopenRetriesPersistedPendingKeyCleanup() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            fixture.vault.failDelete = true
            assertFailure(FailureReason.STORAGE_FAILURE, store.eraseScope(owner))
            db.close()
            fixture.vault.failDelete = false
            val attempts = fixture.vault.deleteCalls
            val reopened = fixture.open()
            assertTrue(fixture.vault.deleteCalls > attempts)
            assertNull(value(reopened.resume(owner)))
            assertTrue(fixture.vault.keys.isEmpty())
        }
    }

    @Test fun failedPendingCleanupDuringOpenClosesTransferredConnection() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            fixture.vault.failDelete = true
            assertFailure(FailureReason.STORAGE_FAILURE, store.eraseScope(owner))
            db.close()
            lateinit var controlled: ControlledConnection
            val callbacks = fixture.closedCallbacks
            assertFailure(FailureReason.STORAGE_FAILURE, fixture.tryOpen(wrap = {
                ControlledConnection(it).also { wrapper -> controlled = wrapper }
            }))
            assertEquals(1, controlled.closeCalls)
            assertEquals(callbacks + 1, fixture.closedCallbacks)
            fixture.vault.failDelete = false
            assertNull(value(fixture.open().resume(owner)))
        }
    }

    @Test fun lostOwnerKeyFailsClosedWithoutReplacingIt() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "not recoverable without key"))))
            fixture.vault.keys.clear()
            val creates = fixture.vault.createCalls
            assertFailure(FailureReason.STORAGE_FAILURE, store.read(owner, key))
            assertFailure(FailureReason.STORAGE_FAILURE, store.commit(owner, listOf(put(key, "replacement", 1))))
            assertRejected(db.resume(owner))
            assertRejected(db.activate(owner))
            assertEquals(creates, fixture.vault.createCalls)
        }
    }

    @Test fun malformedCleanupQueueNeverDeletesAKeyOfAnActiveOwner() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "must retain encryption key"))))
            val keyIds = fixture.vault.keys.keys.toSet()
            val deletions = fixture.vault.deleteCalls
            db.close()
            fixture.raw { execute("INSERT INTO feedme_key_gc(key_id) SELECT key_id FROM feedme_owners WHERE active=1") }
            assertFailure(FailureReason.STORAGE_FAILURE, fixture.tryOpen())
            assertEquals(keyIds, fixture.vault.keys.keys)
            assertEquals(deletions, fixture.vault.deleteCalls)
        }
    }

    @Test fun malformedReferencingOwnerNeverLosesItsKeyDuringCleanup() = runBlocking {
        val ownerChanges = listOf(
            "UPDATE feedme_owners SET active=2",
            "UPDATE feedme_owners SET active=1.5",
            "UPDATE feedme_owners SET active=0,generation=1.5",
            "UPDATE feedme_owners SET active=0,generation='broken'",
            "UPDATE feedme_owners SET active=0,generation=0",
            "UPDATE feedme_owners SET key_id=CAST(key_id AS BLOB)",
        )
        for (sql in ownerChanges) withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "key must remain recoverable"))))
            val keyIds = fixture.vault.keys.keys.toSet()
            val deletions = fixture.vault.deleteCalls
            db.close()
            fixture.raw {
                execute("PRAGMA ignore_check_constraints=ON")
                execute("INSERT INTO feedme_key_gc(key_id) SELECT key_id FROM feedme_owners")
                execute(sql)
            }
            assertFailure(FailureReason.STORAGE_FAILURE, fixture.tryOpen())
            assertEquals(keyIds, fixture.vault.keys.keys)
            assertEquals(deletions, fixture.vault.deleteCalls)
        }
    }

    @Test fun oversizedOrNulSuffixedOwnerKeyFailsClosedWithoutDeletingKeys() = runBlocking {
        for (withNul in listOf(false, true)) withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "private record"))))
            val keyId = fixture.vault.keys.keys.single()
            val malformed = keyId + (if (withNul) "\u0000" else "") + "a".repeat(64 * 1024)
            db.close()
            fixture.raw {
                execute("PRAGMA ignore_check_constraints=ON")
                prepare("UPDATE feedme_owners SET key_id=?").use { statement ->
                    statement.bindText(1, malformed)
                    while (statement.step()) Unit
                }
            }
            lateinit var connection: ControlledConnection
            when (val opened = fixture.tryOpen(wrap = { ControlledConnection(it).also { wrapper -> connection = wrapper } })) {
                is PortResult.Failure -> assertFailure(FailureReason.STORAGE_FAILURE, opened)
                is PortResult.Value -> assertFailure(FailureReason.STORAGE_FAILURE, opened.value.resume(owner))
            }
            assertEquals(0, connection.oversizedTextReads, "Oversized owner key was materialized before its bound was checked")
            assertEquals(setOf(keyId), fixture.vault.keys.keys)
            assertEquals(0, fixture.vault.deleteCalls)
        }
    }

    @Test fun oversizedOrNulSuffixedCleanupKeyFailsClosedWithoutDeletingKeys() = runBlocking {
        for (withNul in listOf(false, true)) withFixture { fixture ->
            val db = fixture.open()
            value(db.activate(owner))
            val keyId = fixture.vault.keys.keys.single()
            val malformed = keyId + (if (withNul) "\u0000" else "") + "a".repeat(64 * 1024)
            db.close()
            fixture.raw {
                execute("PRAGMA ignore_check_constraints=ON")
                prepare("INSERT INTO feedme_key_gc(key_id) VALUES(?)").use { statement ->
                    statement.bindText(1, malformed)
                    while (statement.step()) Unit
                }
            }
            lateinit var connection: ControlledConnection
            assertFailure(FailureReason.STORAGE_FAILURE, fixture.tryOpen(wrap = {
                ControlledConnection(it).also { wrapper -> connection = wrapper }
            }))
            assertEquals(0, connection.oversizedTextReads, "Oversized cleanup key was materialized before its bound was checked")
            assertEquals(setOf(keyId), fixture.vault.keys.keys)
            assertEquals(0, fixture.vault.deleteCalls)
        }
    }

    @Test fun boundedBatchesAndKeysRejectBeforeAnyWrite() = runBlocking {
        withFixture { fixture ->
            val store = value(fixture.open().activate(owner))
            assertFailure(FailureReason.INVALID_DATA, store.commit(owner, emptyList()))
            val tooMany = (0..64).map { put(RecordKey("batch", "$it"), "data") }
            assertFailure(FailureReason.INVALID_DATA, store.commit(owner, tooMany))
            assertNull(value(store.read(owner, tooMany.first().key)))
            val invalidKeys = listOf(
                RecordKey("c".repeat(129), "id"), RecordKey("collection", "i".repeat(513)),
                RecordKey("line\nbreak", "id"), RecordKey("collection", "id\u0000suffix"),
                RecordKey("collection", "unpaired\uD800"), RecordKey("unpaired\uDC00", "id"),
            )
            for (invalid in invalidKeys) {
                assertFailure(FailureReason.INVALID_DATA, store.commit(owner, listOf(put(invalid, "data"))))
                assertFailure(FailureReason.INVALID_DATA, store.read(owner, invalid))
            }
            val maximumKey = RecordKey("c".repeat(128), "i".repeat(512))
            value(store.commit(owner, listOf(put(maximumKey, "boundary"))))
            assertRecord(store, owner, maximumKey, 1, "boundary")
            val accepted = (0 until 64).map { put(RecordKey("accepted batch", "$it"), "value") }
            assertEquals(64, value(store.commit(owner, accepted)).size)
        }
    }

    @Test fun recordAndTotalPayloadByteLimitsAreEnforced() = runBlocking {
        withFixture { fixture ->
            val store = value(fixture.open().activate(owner))
            val maximum = ByteArray(1024 * 1024) { 0x5a }
            assertFailure(FailureReason.INVALID_DATA, store.commit(owner, listOf(
                StoreMutation.Put(key, null, 1, PrivateBytes(maximum + byteArrayOf(0))),
            )))
            val oversizedBatch = (0 until 5).map {
                StoreMutation.Put(RecordKey("large", "$it"), null, 1, PrivateBytes(maximum))
            }
            assertFailure(FailureReason.INVALID_DATA, store.commit(owner, oversizedBatch))
            assertNull(value(store.read(owner, oversizedBatch.first().key)))
            assertEquals(4, value(store.commit(owner, oversizedBatch.take(4))).size)
            assertContentEquals(maximum, assertNotNull(value(store.read(owner, oversizedBatch.first().key))).payload.copyForCodec())
        }
    }

    @Test fun emptyPrivatePayloadIsAValidEncryptedRecord() = runBlocking {
        withFixture { fixture ->
            val store = value(fixture.open().activate(owner))
            value(store.commit(owner, listOf(put(key, ""))))
            assertRecord(store, owner, key, 1, "")
        }
    }

    @Test fun configuredConnectionUsesRollbackJournalAndRequiredPragmas() = runBlocking {
        withFixture { fixture ->
            lateinit var connection: ControlledConnection
            fixture.open(wrap = { ControlledConnection(it).also { wrapper -> connection = wrapper } })
            assertEquals("delete", connection.text("PRAGMA journal_mode"))
            assertEquals(3L, connection.number("PRAGMA synchronous"))
            assertEquals(1L, connection.number("PRAGMA foreign_keys"))
            assertEquals(0L, connection.number("PRAGMA trusted_schema"))
            assertEquals(1L, connection.number("PRAGMA secure_delete"))
            assertEquals(2L, connection.number("PRAGMA temp_store"))
            assertEquals(5000L, connection.number("PRAGMA busy_timeout"))
            assertEquals(2L, connection.number("PRAGMA user_version"))
            assertEquals(0x464d5331L, connection.number("PRAGMA application_id"))
        }
    }

    @Test fun realSqliteFullResponseRollsBackWithoutLosingPriorData() = runBlocking {
        withFixture { fixture ->
            lateinit var connection: ControlledConnection
            val db = fixture.open(wrap = { ControlledConnection(it).also { wrapper -> connection = wrapper } })
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "persisted before full"))))
            val currentPages = connection.number("PRAGMA page_count")
            assertEquals(currentPages, connection.number("PRAGMA max_page_count=$currentPages"))
            val another = RecordKey("large data", "does not fit")
            assertFailure(FailureReason.STORAGE_FAILURE, store.commit(owner, listOf(
                put(key, "must roll back", 1),
                StoreMutation.Put(another, null, 1, PrivateBytes(ByteArray(128 * 1024) { 7 })),
            )))
            assertFalse(connection.inTransaction())
            assertRecord(store, owner, key, 1, "persisted before full")
            assertNull(value(store.read(owner, another)))
        }
    }

    @Test fun unknownSchemaVersionApplicationIdOrShapeFailsClosed() = runBlocking {
        val mutations = listOf(
            "PRAGMA user_version=3",
            "PRAGMA application_id=12345",
            "ALTER TABLE feedme_records ADD COLUMN unexpected TEXT",
            "CREATE TABLE injected_table(unexpected TEXT)",
            "CREATE TRIGGER injected_trigger AFTER INSERT ON feedme_records BEGIN SELECT 1; END",
        )
        for (sql in mutations) withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "original private value"))))
            db.close()
            fixture.raw { execute(sql) }
            val before = Files.readAllBytes(fixture.file)
            lateinit var connection: ControlledConnection
            assertFailure(FailureReason.STORAGE_FAILURE, fixture.tryOpen(wrap = {
                ControlledConnection(it).also { wrapper -> connection = wrapper }
            }))
            assertEquals(1, connection.closeCalls)
            assertContentEquals(before, Files.readAllBytes(fixture.file), "Failed migration open changed database content")
        }
    }

    @Test fun existingWalDatabaseIsRejectedWithoutDestructiveConversion() = runBlocking {
        withFixture { fixture ->
            fixture.open().close()
            fixture.raw { assertEquals("wal", text("PRAGMA journal_mode=WAL")) }
            assertFailure(FailureReason.STORAGE_FAILURE, fixture.tryOpen())
            fixture.raw { assertEquals("wal", text("PRAGMA journal_mode")) }
        }
    }

    @Test fun ciphertextModificationOrMetadataSubstitutionCannotAuthenticate() = runBlocking {
        for (sql in listOf(
            "UPDATE feedme_records SET payload=zeroblob(length(payload))",
            "UPDATE feedme_records SET revision=revision+1",
            "UPDATE feedme_records SET schema_version=schema_version+1",
        )) withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "authenticated original"))))
            db.close()
            fixture.raw { execute(sql) }
            val resumed = assertNotNull(value(fixture.open().resume(owner)))
            assertFailure(FailureReason.STORAGE_FAILURE, resumed.read(owner, key))
        }
    }

    @Test fun ciphertextCannotBeMovedBetweenRecordKeys() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            val second = RecordKey("drafts", "another private record")
            value(store.commit(owner, listOf(put(key, "first"), put(second, "second"))))
            db.close()
            fixture.raw {
                execute("UPDATE feedme_records SET payload=(SELECT payload FROM feedme_records ORDER BY record_tag LIMIT 1)")
            }
            val resumed = assertNotNull(value(fixture.open().resume(owner)))
            val results = listOf(resumed.read(owner, key), resumed.read(owner, second))
            assertEquals(1, results.count { it is PortResult.Value })
            assertEquals(1, results.count { it == PortResult.Failure(FailureReason.STORAGE_FAILURE) })
        }
    }

    @Test fun corruptedPriorCiphertextCannotBeOverwrittenOrDeletedByCas() = runBlocking {
        withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "private original"))))
            db.close()
            fixture.raw { execute("UPDATE feedme_records SET payload=zeroblob(length(payload))") }
            val resumed = assertNotNull(value(fixture.open().resume(owner)))
            assertFailure(FailureReason.STORAGE_FAILURE, resumed.commit(owner, listOf(put(key, "replacement", 1))))
            assertFailure(FailureReason.STORAGE_FAILURE, resumed.commit(owner, listOf(StoreMutation.Delete(key, 1))))
            assertFailure(FailureReason.STORAGE_FAILURE, resumed.read(owner, key))
        }
    }

    @Test fun nonIntegerPersistedMetadataIsRejectedWithoutSilentCoercion() = runBlocking {
        for (sql in listOf(
            "UPDATE feedme_records SET revision=1.5",
            "UPDATE feedme_records SET schema_version=1.5",
            "UPDATE feedme_owners SET generation=1.5",
            "UPDATE feedme_owners SET active=1.5",
        )) withFixture { fixture ->
            val db = fixture.open()
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "original"))))
            db.close()
            fixture.raw { execute("PRAGMA ignore_check_constraints=ON"); execute(sql) }
            val resumed = fixture.open().resume(owner)
            when (resumed) {
                is PortResult.Failure -> assertFailure(FailureReason.STORAGE_FAILURE, resumed)
                is PortResult.Value -> assertFailure(FailureReason.STORAGE_FAILURE, assertNotNull(resumed.value).read(owner, key))
            }
        }
    }

    @Test fun inputListIsSnapshottedBeforeDispatchAndReturnedBytesAreDetached() = runTest {
        withFixture { fixture ->
            val store = value(fixture.open(dispatcher = StandardTestDispatcher(testScheduler)).activate(owner))
            val input = "original input".encodeToByteArray()
            val mutations = mutableListOf<StoreMutation>(StoreMutation.Put(key, null, 1, PrivateBytes(input)))
            val pending = async(start = CoroutineStart.UNDISPATCHED) { store.commit(owner, mutations) }
            input.fill(0)
            mutations.clear()
            mutations += put(RecordKey("different", "late insertion"), "changed intent")
            assertEquals(setOf(key), value(pending.await()).keys)
            val output = assertNotNull(value(store.read(owner, key))).payload.copyForCodec()
            output.fill(0)
            assertRecord(store, owner, key, 1, "original input")
            assertNull(value(store.read(owner, mutations.single().key)))
        }
    }

    @Test fun concurrentCompareAndSwapHasExactlyOneWinner() = runBlocking {
        withFixture { fixture ->
            val store = value(fixture.open().activate(owner))
            value(store.commit(owner, listOf(put(key, "initial"))))
            val outcomes = (0 until 12).map { index -> async(Dispatchers.Default) {
                store.commit(owner, listOf(put(key, "candidate $index", 1)))
            } }.awaitAll()
            assertEquals(1, outcomes.count { it is PortResult.Value })
            assertEquals(11, outcomes.count { it == PortResult.Failure(FailureReason.CONFLICT) })
            assertEquals(2L, assertNotNull(value(store.read(owner, key))).revision)
        }
    }

    @Test fun cancellationDuringSynchronousCommitCanHideSuccessWithoutReplaying() = runTest {
        withFixture { fixture ->
            val store = value(fixture.open(dispatcher = StandardTestDispatcher(testScheduler)).activate(owner))
            val pending = async(start = CoroutineStart.UNDISPATCHED) { store.commit(owner, listOf(put(key, "committed despite cancellation"))) }
            val sealCalls = fixture.vault.sealCalls
            fixture.vault.onSeal = { pending.cancel() }
            var wasCancelled = false
            try { pending.await() } catch (_: CancellationException) { wasCancelled = true }
            fixture.vault.onSeal = null
            assertTrue(wasCancelled)
            assertEquals(sealCalls + 1, fixture.vault.sealCalls)
            assertRecord(store, owner, key, 1, "committed despite cancellation")
        }
    }

    @Test fun commitFailureBeforeExecutionRollsBackAndRemainsUsable() = runBlocking {
        withFixture { fixture ->
            lateinit var controlled: ControlledConnection
            val db = fixture.open(wrap = { ControlledConnection(it).also { wrapper -> controlled = wrapper } })
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "before"))))
            controlled.commitFault = CommitFault.BEFORE
            assertFailure(FailureReason.STORAGE_FAILURE, store.commit(owner, listOf(put(key, "not committed", 1))))
            assertFalse(controlled.inTransaction())
            assertRecord(store, owner, key, 1, "before")
            assertEquals(2L, value(store.commit(owner, listOf(put(key, "explicit next write", 1))))[key])
        }
    }

    @Test fun commitFailureAfterExecutionIsUnknownAndNeverReplayed() = runBlocking {
        withFixture { fixture ->
            lateinit var controlled: ControlledConnection
            val db = fixture.open(wrap = { ControlledConnection(it).also { wrapper -> controlled = wrapper } })
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "before"))))
            val commits = controlled.commitSteps
            controlled.commitFault = CommitFault.AFTER
            assertFailure(FailureReason.OUTCOME_UNKNOWN, store.commit(owner, listOf(put(key, "committed once", 1))))
            assertEquals(commits + 1, controlled.commitSteps)
            db.close()
            val reopened = fixture.open()
            val resumed = assertNotNull(value(reopened.resume(owner)))
            assertRecord(resumed, owner, key, 2, "committed once")
            assertFailure(FailureReason.CONFLICT, resumed.commit(owner, listOf(put(key, "blind replay", 1))))
        }
    }

    @Test fun eraseFailureBeforeCommitCanRetrySameHandleWhileItStaysFenced() = runBlocking {
        withFixture { fixture ->
            lateinit var controlled: ControlledConnection
            val db = fixture.open(wrap = { ControlledConnection(it).also { wrapper -> controlled = wrapper } })
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "erase retry original"))))
            controlled.commitFault = CommitFault.BEFORE
            assertFailure(FailureReason.STORAGE_FAILURE, store.eraseScope(owner))
            assertStale(store, owner)
            // Key-first retirement prevents readable resurrection even when SQLite rolls back.
            assertEquals(0, fixture.vault.keys.size)
            assertNull(value(db.resume(owner)))
            value(store.eraseScope(owner))
            assertTrue(fixture.vault.keys.isEmpty())
            assertNull(value(db.resume(owner)))
            val replacement = value(db.activate(owner))
            assertNull(value(replacement.read(owner, key)))
        }
    }

    @Test fun eraseCommitResponseLossKeepsRetirementAndResumesKeyCleanup() = runBlocking {
        withFixture { fixture ->
            lateinit var controlled: ControlledConnection
            val db = fixture.open(wrap = { ControlledConnection(it).also { wrapper -> controlled = wrapper } })
            val store = value(db.activate(owner))
            value(store.commit(owner, listOf(put(key, "erase unknown original"))))
            val commits = controlled.commitSteps
            controlled.commitFault = CommitFault.AFTER
            assertFailure(FailureReason.OUTCOME_UNKNOWN, store.eraseScope(owner))
            assertEquals(commits + 1, controlled.commitSteps)
            assertStale(store, owner)
            // Losing the SQL commit response cannot restore the already deleted owner key.
            assertEquals(0, fixture.vault.keys.size)
            db.close()
            val reopened = fixture.open()
            assertNull(value(reopened.resume(owner)))
            assertTrue(fixture.vault.keys.isEmpty())
            assertNull(value(value(reopened.activate(owner)).read(owner, key)))
        }
    }

    @Test fun closeReleasesConnectionAndRejectsRetainedHandles() = runBlocking {
        withFixture { fixture ->
            lateinit var controlled: ControlledConnection
            val db = fixture.open(wrap = { ControlledConnection(it).also { wrapper -> controlled = wrapper } })
            val store = value(db.activate(owner))
            db.close()
            db.close()
            assertEquals(1, controlled.closeCalls)
            assertEquals(1, fixture.closedCallbacks)
            assertFailure(FailureReason.STORAGE_FAILURE, store.read(owner, key))
            assertFailure(FailureReason.STORAGE_FAILURE, store.commit(owner, listOf(put(key, "after close"))))
            assertFailure(FailureReason.STORAGE_FAILURE, store.eraseScope(owner))
            assertFailure(FailureReason.STORAGE_FAILURE, db.activate(owner))
            assertFailure(FailureReason.STORAGE_FAILURE, db.resume(owner))
        }
    }

    private suspend fun assertStale(store: PrivateStateStore, scope: StorageScope) {
        assertFailure(FailureReason.STALE_SESSION, store.read(scope, key))
        assertFailure(FailureReason.STALE_SESSION, store.commit(scope, listOf(put(key, "stale write"))))
    }

    private suspend fun assertRecord(store: PrivateStateStore, scope: StorageScope, key: RecordKey, revision: Long, text: String) {
        val record: PrivateRecord = assertNotNull(value(store.read(scope, key)))
        assertEquals(revision, record.revision)
        assertContentEquals(text.encodeToByteArray(), record.payload.copyForCodec())
    }

    private fun put(key: RecordKey, text: String, revision: Long? = null, schema: Int = 1) =
        StoreMutation.Put(key, revision, schema, PrivateBytes(text.encodeToByteArray()))

    private fun assertFailure(reason: FailureReason, result: PortResult<*>) =
        assertEquals(PortResult.Failure(reason), result)

    private fun assertRejected(result: PortResult<*>) {
        assertTrue(result is PortResult.Failure, "Expected failure, got ${result::class.simpleName}")
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}

private fun <T> value(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected success, got ${result.reason}")
}

private class Fixture {
    private val directory = Files.createTempDirectory("feedme-sqlite-jvm-test-")
    val file: Path = directory.resolve("state.sqlite")
    val vault = JcaTestVault()
    private val databases = mutableListOf<EncryptedStateDatabase>()
    var closedCallbacks = 0

    suspend fun open(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        wrap: (SQLiteConnection) -> SQLiteConnection = { it },
    ): EncryptedStateDatabase = value(tryOpen(dispatcher, wrap))

    suspend fun tryOpen(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        wrap: (SQLiteConnection) -> SQLiteConnection = { it },
    ): PortResult<EncryptedStateDatabase> {
        val connection = wrap(BundledSQLiteDriver().open(file.toString()))
        return EncryptedStateDatabase.open(connection, vault, dispatcher) { closedCallbacks++ }
            .also { if (it is PortResult.Value) databases += it.value }
    }

    fun raw(action: SQLiteConnection.() -> Unit) {
        BundledSQLiteDriver().open(file.toString()).use(action)
    }

    suspend fun close() {
        try { databases.forEach { it.close() } } finally {
            Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/** Keys live only in this fixture and deliberately survive reopening its SQLite file. */
private class JcaTestVault : StateVault {
    private val random = SecureRandom()
    private val indexKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
    val keys = mutableMapOf<String, SecretKey>()
    var createCalls = 0
    var sealCalls = 0
    var deleteCalls = 0
    var failSealOnCall: Int? = null
    var failDelete = false
    var onSeal: (() -> Unit)? = null

    override fun index(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(indexKey)
        doFinal(input)
    }

    override fun createOwnerKey(): String {
        createCalls++
        val id = UUID.randomUUID().toString().replace("-", "")
        keys[id] = KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
        return id
    }

    override fun hasOwnerKey(keyId: String): Boolean = keyId in keys

    override fun seal(keyId: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        sealCalls++
        if (sealCalls == failSealOnCall) throw StateVaultException()
        onSeal?.invoke()
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys[keyId] ?: throw StateVaultException(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return byteArrayOf(1) + nonce + cipher.doFinal(plaintext)
    }

    override fun open(keyId: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        try {
            if (ciphertext.size < 29 || ciphertext[0] != 1.toByte()) throw StateVaultException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keys[keyId] ?: throw StateVaultException(),
                GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext, 13, ciphertext.size - 13)
        } catch (_: Exception) { throw StateVaultException() }
    }

    override fun deleteOwnerKey(keyId: String) {
        deleteCalls++
        if (failDelete) throw StateVaultException()
        keys.remove(keyId)
    }
}

private enum class CommitFault { BEFORE, AFTER }

private class ControlledConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
    var commitFault: CommitFault? = null
    var commitSteps = 0
    var closeCalls = 0
    var oversizedTextReads = 0

    override fun prepare(sql: String): SQLiteStatement {
        val statement = delegate.prepare(sql)
        val isCommit = sql.trim().startsWith("COMMIT", ignoreCase = true)
        return object : SQLiteStatement by statement {
            override fun getText(index: Int): String = statement.getText(index).also {
                if (it.length > 1024) oversizedTextReads++
            }

            override fun step(): Boolean {
                if (!isCommit) return statement.step()
                commitSteps++
                val fault = commitFault.also { commitFault = null }
                if (fault == CommitFault.BEFORE) throw IllegalStateException("Injected commit failure")
                val result = statement.step()
                if (fault == CommitFault.AFTER) throw IllegalStateException("Injected commit response loss")
                return result
            }
        }
    }

    override fun close() {
        closeCalls++
        delegate.close()
    }
}

private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > size) return false
    for (start in 0..size - needle.size) {
        var match = true
        for (offset in needle.indices) if (this[start + offset] != needle[offset]) { match = false; break }
        if (match) return true
    }
    return false
}

private fun SQLiteConnection.execute(sql: String) = prepare(sql).use { statement -> while (statement.step()) Unit }
private fun SQLiteConnection.number(sql: String): Long = prepare(sql).use { statement ->
    assertTrue(statement.step())
    statement.getLong(0)
}
private fun SQLiteConnection.text(sql: String): String = prepare(sql).use { statement ->
    assertTrue(statement.step())
    statement.getText(0)
}
