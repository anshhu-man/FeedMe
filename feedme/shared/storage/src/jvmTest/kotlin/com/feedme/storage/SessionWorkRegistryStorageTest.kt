package com.feedme.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.RecordKey
import com.feedme.core.ports.SessionBoundary
import com.feedme.core.ports.SessionControlRecord
import com.feedme.core.ports.SessionControlStore
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoreMutation
import com.feedme.session.NativeWorkAdmissionPolicy
import com.feedme.session.NativeWorkCancellationPort
import com.feedme.session.NativeWorkExecutionPolicy
import com.feedme.session.NativeWorkIdSource
import com.feedme.session.NativeWorkKind
import com.feedme.session.NativeWorkPhase
import com.feedme.session.NativeWorkTicket
import com.feedme.session.SessionWorkRegistry
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Real encrypted bundled SQLite plus recording cancellation ports, not native OS/Keystore proof. */
class SessionWorkRegistryStorageTest {
    private val scope = StorageScope("private-work-integration", ActorKind.ACCOUNT, "private-registry-owner")

    @Test fun independentWorkRegistryCanRetireAfterDomainErasureAndDatabaseClosure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        withFixture(dispatcher) { workFixture ->
            withFixture(dispatcher) { domainFixture ->
                val boundary = SessionBoundary()
                val lease = boundary.activate(scope)
                val work = workFixture.openWork(initialize = true)
                val cancelled = mutableListOf<Pair<String, NativeWorkKind>>()
                val registry = workFixture.registry(work, boundary, NativeWorkCancellationPort {
                    cancelled += it.identity(); PortResult.Value(Unit)
                })
                val binding = registryValue(registry.createOrigin(lease, registryValue(registry.snapshot()).revision))
                val timer = registryValue(registry.install(binding, NativeWorkKind.TIMER, "private-timer-logical-id") { PortResult.Value(Unit) })
                val worker = registryValue(registry.install(binding, NativeWorkKind.WORKER, "private-worker-logical-id") { PortResult.Value(Unit) })
                val workKeys = workFixture.vault.keyIds

                val domain = domainFixture.openDatabase()
                val owner = registryValue(domain.activate(scope))
                registryValue(owner.commit(scope, listOf(StoreMutation.Put(
                    RecordKey("private-domain", "draft"), null, 1, PrivateBytes("private-domain-value".encodeToByteArray()),
                ))))
                boundary.clear()
                registryValue(owner.eraseScope(scope))
                assertTrue(domainFixture.vault.keyIds.isEmpty())
                registryValue(domain.close())
                assertEquals(workKeys, workFixture.vault.keyIds)
                assertEquals(setOf(timer.id, worker.id), registryValue(registry.snapshot()).entries.map { it.ticket.id }.toSet())

                // Neither a current lease nor the erased domain database is needed for cleanup.
                registryValue(registry.retire(scope, binding.originBinding))
                assertEquals(listOf(timer.identity(), worker.identity()), cancelled)
                val idle = registryValue(registry.snapshot())
                assertNull(idle.scope)
                assertNull(idle.originBinding)
                assertTrue(idle.entries.isEmpty())
                registryValue(registry.close())
                registryValue(work.close())
                workFixture.assertNoPlaintext(scope.actorId)
                workFixture.assertNoPlaintext("private-timer-logical-id")

                val reopenedWork = workFixture.openWork(initialize = false)
                val reopened = workFixture.registry(reopenedWork, SessionBoundary(), NativeWorkCancellationPort {
                    fail("An idle durable registry must not invent cancellation work")
                })
                assertEquals(idle.revision, registryValue(reopened.snapshot()).revision)
                assertTrue(registryValue(reopened.snapshot()).entries.isEmpty())
                assertEquals(workKeys, workFixture.vault.keyIds)
                assertTrue(domainFixture.vault.keyIds.isEmpty())
            }
        }
    }

    @Test fun cancellingExactTicketSurvivesReopenAndReconcilesWithoutReplacementIdentity() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val firstBoundary = SessionBoundary()
            val lease = firstBoundary.activate(scope)
            val work = fixture.openWork(initialize = true)
            val attempted = mutableListOf<Pair<String, NativeWorkKind>>()
            val first = fixture.registry(work, firstBoundary, NativeWorkCancellationPort {
                attempted += it.identity(); PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            })
            val binding = registryValue(first.createOrigin(lease, registryValue(first.snapshot()).revision))
            val ticket = registryValue(first.install(binding, NativeWorkKind.TIMER, "pending-private-timer") { PortResult.Value(Unit) })
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), first.cancel(binding, ticket))
            val pending = registryValue(first.snapshot())
            assertFalse(pending.retiring)
            assertEquals(ticket.identity(), pending.entries.single().ticket.identity())
            assertEquals(NativeWorkPhase.CANCELLING, pending.entries.single().phase)
            registryValue(first.close())
            registryValue(work.close())
            fixture.assertNoPlaintext("pending-private-timer")
            fixture.assertNoPlaintext(ticket.id)

            val nextBoundary = SessionBoundary()
            val nextLease = nextBoundary.activate(scope)
            val reopenedWork = fixture.openWork(initialize = false)
            val replayed = mutableListOf<Pair<String, NativeWorkKind>>()
            val reopened = fixture.registry(reopenedWork, nextBoundary, NativeWorkCancellationPort {
                replayed += it.identity(); PortResult.Value(Unit)
            }, ids = NativeWorkIdSource { fail("Recovery must not allocate a replacement origin or ticket") })
            assertEquals(pending.revision, registryValue(reopened.snapshot()).revision)
            val resumed = registryValue(reopened.resume(nextLease))
            assertEquals(binding.originBinding, resumed.originBinding)
            var effects = 0
            assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), reopened.runLocalEffect(ticket) {
                effects++; PortResult.Value(Unit)
            })
            assertEquals(0, effects)
            registryValue(reopened.reconcilePending(resumed))
            assertEquals(listOf(ticket.identity()), attempted)
            assertEquals(listOf(ticket.identity()), replayed)
            assertTrue(registryValue(reopened.snapshot()).entries.isEmpty())
            registryValue(reopened.retire(scope, resumed.originBinding))
            val idle = registryValue(reopened.snapshot())
            assertNull(idle.scope)
            registryValue(reopened.close())
            registryValue(reopenedWork.close())

            val finalWork = fixture.openWork(initialize = false)
            val finalRegistry = fixture.registry(finalWork, SessionBoundary(), NativeWorkCancellationPort {
                fail("Acknowledged cleanup must not reappear after a second reopen")
            })
            assertEquals(idle.revision, registryValue(finalRegistry.snapshot()).revision)
            assertNull(registryValue(finalRegistry.snapshot()).originBinding)
        }
    }

    @Test fun partiallyRetiredOriginReplaysOnlyRemainingTicketWithoutLeaseOrAdmission() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val boundary = SessionBoundary()
            val lease = boundary.activate(scope)
            val work = fixture.openWork(initialize = true)
            var failingId: String? = null
            val attempted = mutableListOf<Pair<String, NativeWorkKind>>()
            val first = fixture.registry(work, boundary, NativeWorkCancellationPort {
                attempted += it.identity()
                if (it.id == failingId) PortResult.Failure(FailureReason.UNAVAILABLE) else PortResult.Value(Unit)
            })
            val binding = registryValue(first.createOrigin(lease, registryValue(first.snapshot()).revision))
            val timer = registryValue(first.install(binding, NativeWorkKind.TIMER, "one") { PortResult.Value(Unit) })
            val worker = registryValue(first.install(binding, NativeWorkKind.WORKER, "two") { PortResult.Value(Unit) })
            failingId = timer.id
            boundary.clear()
            assertEquals(PortResult.Failure(FailureReason.UNAVAILABLE), first.retire(scope, binding.originBinding))
            assertEquals(listOf(timer.identity(), worker.identity()), attempted)
            val pending = registryValue(first.snapshot())
            assertTrue(pending.retiring)
            assertEquals(listOf(timer.identity()), pending.entries.map { it.ticket.identity() })
            registryValue(first.close())
            registryValue(work.close())

            val reopenedWork = fixture.openWork(initialize = false)
            val replayed = mutableListOf<Pair<String, NativeWorkKind>>()
            val noSession = SessionBoundary()
            val reopened = fixture.registry(reopenedWork, noSession, NativeWorkCancellationPort {
                replayed += it.identity(); PortResult.Value(Unit)
            }, ids = NativeWorkIdSource { fail("Retirement must not allocate native identity") },
                admission = NativeWorkAdmissionPolicy { fail("Retirement must not restore/admit identity") },
                execution = NativeWorkExecutionPolicy { _, _, _, _ -> fail("Retirement must not execute private work") })
            assertNull(noSession.current())
            assertEquals(pending.revision, registryValue(reopened.snapshot()).revision)
            registryValue(reopened.retire(scope, binding.originBinding))
            assertEquals(listOf(timer.identity()), replayed)
            assertNull(noSession.current())
            assertNull(registryValue(reopened.snapshot()).scope)
            assertTrue(registryValue(reopened.snapshot()).entries.isEmpty())
            registryValue(reopened.retire(scope, binding.originBinding))
            assertEquals(listOf(timer.identity()), replayed)
        }
    }

    @Test fun latePriorOriginRetirementCannotAlterNewSameOwnerOriginAfterReopen() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val boundary = SessionBoundary()
            val oldLease = boundary.activate(scope)
            val work = fixture.openWork(initialize = true)
            val first = fixture.registry(work, boundary, NativeWorkCancellationPort { PortResult.Value(Unit) })
            val oldBinding = registryValue(first.createOrigin(oldLease, registryValue(first.snapshot()).revision))
            val oldTicket = registryValue(first.install(oldBinding, NativeWorkKind.TIMER, "shared-logical-name") { PortResult.Value(Unit) })
            boundary.clear()
            registryValue(first.retire(scope, oldBinding.originBinding))
            val newLease = boundary.activate(scope)
            val newBinding = registryValue(first.createOrigin(newLease, registryValue(first.snapshot()).revision))
            val newTicket = registryValue(first.install(newBinding, NativeWorkKind.TIMER, "shared-logical-name") { PortResult.Value(Unit) })
            assertNotEquals(oldBinding.originBinding, newBinding.originBinding)
            assertNotEquals(oldTicket.id, newTicket.id)
            registryValue(first.close())
            registryValue(work.close())

            val reopenedWork = fixture.openWork(initialize = false)
            val nextBoundary = SessionBoundary()
            val verifiedLease = nextBoundary.activate(scope)
            val canceled = mutableListOf<Pair<String, NativeWorkKind>>()
            val reopened = fixture.registry(reopenedWork, nextBoundary, NativeWorkCancellationPort {
                canceled += it.identity(); PortResult.Value(Unit)
            })
            assertEquals(newBinding.originBinding, registryValue(reopened.resume(verifiedLease)).originBinding)
            val before = assertNotNull(registryValue(reopenedWork.read()))
            registryValue(reopened.retire(scope, oldBinding.originBinding))
            val after = assertNotNull(registryValue(reopenedWork.read()))
            assertEquals(before.revision, after.revision)
            assertContentEquals(before.payload.copyForCodec(), after.payload.copyForCodec())
            assertTrue(canceled.isEmpty())
            assertEquals(newTicket.identity(), registryValue(reopened.snapshot()).entries.single().ticket.identity())
            var effects = 0
            assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), reopened.runLocalEffect(oldTicket) {
                effects++; PortResult.Value(Unit)
            })
            registryValue(reopened.runLocalEffect(newTicket) { effects++; PortResult.Value(Unit) })
            assertEquals(1, effects)
        }
    }

    @Test fun committedUnknownOriginAndReservationRequireFreshAcknowledgementsAcrossSqliteReopen() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val boundary = SessionBoundary()
            val lease = boundary.activate(scope)
            val work = fixture.openWork(initialize = true)
            val faults = WorkAcknowledgementFaultStore(work)
            val cancellations = mutableListOf<Pair<String, NativeWorkKind>>()
            val first = fixture.registry(faults, boundary, NativeWorkCancellationPort { cancellations += it.identity(); PortResult.Value(Unit) })
            faults.nextFailure = FailureReason.OUTCOME_UNKNOWN
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), first.createOrigin(lease, registryValue(first.snapshot()).revision))
            val selectedOrigin = registryValue(first.snapshot()).originBinding!!
            registryValue(first.close()); registryValue(work.close())

            val reopenedWork = fixture.openWork(initialize = false)
            val replayFaults = WorkAcknowledgementFaultStore(reopenedWork)
            val reopened = fixture.registry(replayFaults, boundary, NativeWorkCancellationPort { cancellations += it.identity(); PortResult.Value(Unit) })
            replayFaults.nextFailure = FailureReason.OUTCOME_UNKNOWN
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), reopened.resume(lease))
            val failedResume = registryValue(reopenedWork.read())!!
            val binding = registryValue(reopened.resume(lease))
            assertEquals(selectedOrigin, binding.originBinding)
            val resumed = registryValue(reopenedWork.read())!!
            assertEquals(failedResume.revision + 1, resumed.revision)
            assertContentEquals(failedResume.payload.copyForCodec(), resumed.payload.copyForCodec())
            replayFaults.nextFailure = FailureReason.OUTCOME_UNKNOWN
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), reopened.install(binding, NativeWorkKind.TIMER, "uncertain-reservation") {
                fail("An unknown committed reservation cannot run the installer")
            })
            val pending = registryValue(reopened.snapshot()).entries.single()
            assertEquals(NativeWorkPhase.RESERVED, pending.phase)
            assertTrue(cancellations.isEmpty())
            registryValue(reopened.close()); registryValue(reopenedWork.close())

            val finalWork = fixture.openWork(initialize = false)
            val finalFaults = WorkAcknowledgementFaultStore(finalWork)
            var failedRevision = 0L
            val finalRegistry = fixture.registry(finalFaults, boundary, NativeWorkCancellationPort {
                assertEquals(pending.ticket.identity(), it.identity())
                assertTrue(registryValue(finalWork.read())!!.revision > failedRevision)
                cancellations += it.identity(); PortResult.Value(Unit)
            }, ids = NativeWorkIdSource { fail("Retry must keep the original origin and ticket") })
            val finalBinding = registryValue(finalRegistry.resume(lease))
            finalFaults.nextFailure = FailureReason.OUTCOME_UNKNOWN
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), finalRegistry.reconcilePending(finalBinding))
            failedRevision = registryValue(finalWork.read())!!.revision
            assertTrue(cancellations.isEmpty())
            registryValue(finalRegistry.reconcilePending(finalBinding))
            assertEquals(listOf(pending.ticket.identity()), cancellations)
            assertTrue(registryValue(finalRegistry.snapshot()).entries.isEmpty())
        }
    }

    @Test fun retiringAndIdleReadbackCannotCompleteWithoutFreshChangedSqliteAcknowledgement() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val boundary = SessionBoundary()
            val lease = boundary.activate(scope)
            val work = fixture.openWork(initialize = true)
            val idleBytes = registryValue(work.read())!!.payload.copyForCodec()
            val faults = WorkAcknowledgementFaultStore(work)
            val canceled = mutableListOf<Pair<String, NativeWorkKind>>()
            val first = fixture.registry(faults, boundary, NativeWorkCancellationPort { canceled += it.identity(); PortResult.Value(Unit) })
            val binding = registryValue(first.createOrigin(lease, registryValue(first.snapshot()).revision))
            val ticket = registryValue(first.install(binding, NativeWorkKind.WORKER, "retiring-worker") { PortResult.Value(Unit) })
            boundary.clear()
            faults.nextFailure = FailureReason.OUTCOME_UNKNOWN
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), first.retire(scope, binding.originBinding))
            assertTrue(registryValue(first.snapshot()).retiring)
            assertTrue(canceled.isEmpty())
            registryValue(first.close()); registryValue(work.close())

            val reopenedWork = fixture.openWork(initialize = false)
            val replayFaults = WorkAcknowledgementFaultStore(reopenedWork)
            var failedBarrierRevision = 0L
            val reopened = fixture.registry(replayFaults, boundary, NativeWorkCancellationPort {
                assertEquals(ticket.identity(), it.identity())
                assertTrue(registryValue(reopenedWork.read())!!.revision > failedBarrierRevision)
                canceled += it.identity(); PortResult.Value(Unit)
            }, admission = NativeWorkAdmissionPolicy { fail("Retirement must not acquire a lease") })
            replayFaults.nextFailure = FailureReason.STORAGE_FAILURE
            assertEquals(PortResult.Failure(FailureReason.STORAGE_FAILURE), reopened.retire(scope, binding.originBinding))
            failedBarrierRevision = registryValue(reopenedWork.read())!!.revision
            assertTrue(canceled.isEmpty())
            replayFaults.failMatching = { payload ->
                if (payload.copyForCodec().contentEquals(idleBytes)) FailureReason.OUTCOME_UNKNOWN else null
            }
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), reopened.retire(scope, binding.originBinding))
            assertEquals(listOf(ticket.identity()), canceled)
            assertNull(registryValue(reopened.snapshot()).originBinding)
            val failedIdle = registryValue(reopenedWork.read())!!
            replayFaults.nextFailure = FailureReason.OUTCOME_UNKNOWN
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), reopened.retire(scope, binding.originBinding))
            assertEquals(failedIdle.revision + 1, registryValue(reopenedWork.read())!!.revision)
            registryValue(reopened.retire(scope, binding.originBinding))
            assertEquals(failedIdle.revision + 2, registryValue(reopenedWork.read())!!.revision)
            assertEquals(listOf(ticket.identity()), canceled)
            assertNull(boundary.current())
        }
    }

    @Test fun readableInstalledWorkNeverExecutesUntilItsExactPayloadReceivesFreshSqliteAcknowledgement() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val boundary = SessionBoundary()
            val lease = boundary.activate(scope)
            val work = fixture.openWork(initialize = true)
            val first = fixture.registry(work, boundary, NativeWorkCancellationPort { fail("Callback must not invent cancellation") })
            val binding = registryValue(first.createOrigin(lease, registryValue(first.snapshot()).revision))
            val ticket = registryValue(first.install(binding, NativeWorkKind.TIMER, "acknowledged-callback") { PortResult.Value(Unit) })
            registryValue(first.close()); registryValue(work.close())
            val reopenedWork = fixture.openWork(initialize = false)
            val faults = WorkAcknowledgementFaultStore(reopenedWork)
            val reopened = fixture.registry(faults, boundary, NativeWorkCancellationPort { fail("Callback must not cancel native work") })
            val original = registryValue(reopenedWork.read())!!
            var effects = 0
            for (reason in listOf(FailureReason.OUTCOME_UNKNOWN, FailureReason.STORAGE_FAILURE)) {
                faults.nextFailure = reason
                assertEquals(PortResult.Failure(reason), reopened.runLocalEffect(ticket) { effects++; PortResult.Value(Unit) })
                assertEquals(0, effects)
                assertContentEquals(original.payload.copyForCodec(), registryValue(reopenedWork.read())!!.payload.copyForCodec())
            }
            registryValue(reopened.runLocalEffect(ticket) {
                effects++
                assertEquals(original.revision + 3, faults.lastSuccessfulRevision)
                PortResult.Value(Unit)
            })
            assertEquals(1, effects)
            fixture.assertNoPlaintext("acknowledged-callback")
            fixture.assertNoPlaintext(ticket.id)
        }
    }

    private suspend fun withFixture(dispatcher: CoroutineDispatcher, block: suspend (RegistrySqliteFixture) -> Unit) {
        val fixture = RegistrySqliteFixture(dispatcher)
        try { block(fixture) } finally { fixture.close() }
    }
}

private fun NativeWorkTicket.identity() = id to kind
private fun <T> registryValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> fail("Expected registry success, got ${result.reason}")
}

private class RegistrySqliteFixture(private val dispatcher: CoroutineDispatcher) {
    private val directory = Files.createTempDirectory("feedme-work-registry-sqlite-test-")
    private val file = directory.resolve("private.sqlite")
    val vault = RegistrySqliteTestVault()
    private val databases = mutableListOf<EncryptedStateDatabase>()
    private val registries = mutableListOf<SessionWorkRegistry>()

    suspend fun openDatabase(): EncryptedStateDatabase = registryValue(EncryptedStateDatabase.open(
        BundledSQLiteDriver().open(file.toString()), vault, dispatcher,
    )).also { databases += it }

    suspend fun openWork(initialize: Boolean) = registryValue(EncryptedSessionWorkStore.open(openDatabase(), initialize))

    suspend fun registry(
        store: SessionControlStore,
        boundary: SessionBoundary,
        cancellation: NativeWorkCancellationPort,
        ids: NativeWorkIdSource = NativeWorkIdSource { UUID.randomUUID().toString() },
        admission: NativeWorkAdmissionPolicy = NativeWorkAdmissionPolicy { PortResult.Value(true) },
        execution: NativeWorkExecutionPolicy = NativeWorkExecutionPolicy { _, _, _, _ -> PortResult.Value(true) },
    ): SessionWorkRegistry = registryValue(SessionWorkRegistry.open(
        store, boundary, dispatcher, cancellation, ids, admission, execution,
    )).also { registries += it }

    fun assertNoPlaintext(marker: String) {
        Files.list(directory).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { path ->
                val bytes = Files.readAllBytes(path)
                for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                    val needle = marker.toByteArray(encoding)
                    assertFalse(bytes.indices.any { start -> start + needle.size <= bytes.size && needle.indices.all { bytes[start + it] == needle[it] } })
                }
            }
        }
    }

    suspend fun close() {
        try {
            registries.forEach { it.close() }
            databases.forEach { it.close() }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/** Real encrypted SQLite commits followed by lost/failed port receipts. This is not a native
 * VFS/fsync-failure simulation; separate instrumented coverage owns that stronger evidence. */
private class WorkAcknowledgementFaultStore(private val delegate: SessionControlStore) : SessionControlStore {
    var nextFailure: FailureReason? = null
    var failMatching: ((PrivateBytes) -> FailureReason?)? = null
    var lastSuccessfulRevision = 0L
    override suspend fun read() = delegate.read()
    override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
        val result = delegate.compareAndSet(expectedRevision, payload)
        if (result is PortResult.Value) {
            val failure = nextFailure.also { nextFailure = null }
                ?: failMatching?.invoke(payload)?.also { failMatching = null }
            if (failure != null) return PortResult.Failure(failure)
            lastSuccessfulRevision = result.value.revision
        }
        return result
    }
}

/** Test-only JCA vault whose key objects survive controlled manager close/reopen. */
private class RegistrySqliteTestVault : StateVault {
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
