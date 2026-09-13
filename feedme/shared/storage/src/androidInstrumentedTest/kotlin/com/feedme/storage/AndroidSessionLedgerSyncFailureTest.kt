package com.feedme.storage

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.system.Os
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.session.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run separately from other instrumentation classes. These are actual bundled-engine VFS faults,
 * not Kotlin response loss, physical power loss or OS errno injection. Connections select the
 * non-default test VFS explicitly. Internal owned-connection reopen lets SQLite recover its journal;
 * it does not demonstrate that the strict public activation-recovery factory accepts journals.
 * Credential abort uses the real public Android factory in this test APK's isolated UID. Work
 * effect ports record admission only: no claim of AlarmManager/WorkManager integration is made here.
 * The synthetic lease/credential fixture is not provider authentication or production composition.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionLedgerSyncFailureTest {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ledgers = mutableListOf<Ledger>()
    private val registries = mutableListOf<SessionWorkRegistry>()
    private val credentials = mutableListOf<NativeCredentials>()

    @After fun cleanup() = runBlocking(dispatcher) {
        SqliteSyncFailureInjector.restore()
        // A failed close deliberately stops deletion and retains the owned fixture for diagnosis.
        registries.asReversed().forEach { it.close().valueOrFail() }
        registries.clear()
        ledgers.asReversed().forEach { it.close() }
        credentials.asReversed().forEach { it.closeHandles() }
        SqliteSyncFailureInjector.unregisterVfs()
        credentials.asReversed().forEach { it.destroyOwnedFixture() }
        credentials.clear()
        ledgers.asReversed().forEach { it.box.close() }
        ledgers.clear()
    }

    @Test fun controlJournalSyncFailureCannotAuthorizeCredentialAbort() = runBlocking(dispatcher) {
        controlFailure(SyncPoint.JOURNAL, "control-journal")
    }

    @Test fun controlDatabaseSyncFailureCannotAuthorizeCredentialAbort() = runBlocking(dispatcher) {
        controlFailure(SyncPoint.DATABASE, "control-database")
    }

    @Test fun controlDirectorySyncFailureCannotAuthorizeCredentialAbort() = runBlocking(dispatcher) {
        controlFailure(SyncPoint.DIRECTORY, "control-directory")
    }

    @Test fun workReservationJournalSyncFailureCannotInstallNativeWork() = runBlocking(dispatcher) {
        reservationFailure(SyncPoint.JOURNAL, "work-reservation-journal")
    }

    @Test fun workReservationDatabaseSyncFailureCannotInstallNativeWork() = runBlocking(dispatcher) {
        reservationFailure(SyncPoint.DATABASE, "work-reservation-database")
    }

    @Test fun workReservationDirectorySyncFailureCannotInstallNativeWork() = runBlocking(dispatcher) {
        reservationFailure(SyncPoint.DIRECTORY, "work-reservation-directory")
    }

    @Test fun workRetirementJournalSyncFailureCannotCancelNativeWork() = runBlocking(dispatcher) {
        retirementFailure(SyncPoint.JOURNAL, "work-retirement-journal")
    }

    @Test fun workRetirementDatabaseSyncFailureCannotCancelNativeWork() = runBlocking(dispatcher) {
        retirementFailure(SyncPoint.DATABASE, "work-retirement-database")
    }

    @Test fun workRetirementDirectorySyncFailureCannotCancelNativeWork() = runBlocking(dispatcher) {
        retirementFailure(SyncPoint.DIRECTORY, "work-retirement-directory")
    }

    private suspend fun controlFailure(point: SyncPoint, label: String) {
        val ledger = newLedger(work = false)
        val credential = NativeCredentials().also(credentials::add)
        credential.prepare()
        val initial = ledger.record()
        val notRequested = pendingPayload(credential.plan, false)
        val requested = pendingPayload(credential.plan, true)
        val seeded = ledger.trace.compareAndSet(initial.revision, notRequested).valueOrFail()
        credential.select() // The independent pending plan already has an acknowledged commit.
        credential.beforeEffect = { ledger.assertAcknowledgedBeyond(credential.effectFloor, requested) }
        credential.effectFloor = seeded.revision
        val boundary = SessionBoundary() // Startup has no live identity or lease.
        fun newCoordinator() = CredentialCreateCoordinator(ledger.trace, boundary, dispatcher, credential.factory)
        var coordinator = newCoordinator()
        val proposal = coordinator.inspectPending().valueOrFail()!!
        assertFalse(proposal.abortRequested)
        injected(ledger, point, label, { credential.effects }) { coordinator.requestAbort(proposal) }
        assertTrue(credential.selectedKeyPresent())
        assertEquals(0, credential.opens)
        assertEquals(0, credential.aborts)
        ledger.reopen()
        coordinator = newCoordinator()
        var current = ledger.record()
        assertTrue(sameBytes(current.payload, notRequested) || sameBytes(current.payload, requested))
        if (point == SyncPoint.DIRECTORY) {
            // The failed post-unlink commit is visible, including the exact confirmation bytes.
            assertTrue(sameBytes(current.payload, requested))
            assertEquals(seeded.revision + 1, current.revision)
            assertTrue(coordinator.inspectPending().valueOrFail()!!.abortRequested)
            // A second failed changed-CAS acknowledgement must still not even open credentials.
            injected(ledger, point, null, { credential.effects }) { coordinator.recoverAbort() }
            assertTrue(credential.selectedKeyPresent())
            ledger.reopen()
            coordinator = newCoordinator()
            current = ledger.record()
        }
        credential.effectFloor = current.revision
        val retry = coordinator.inspectPending().valueOrFail()!!
        if (retry.abortRequested) coordinator.recoverAbort().valueOrFail()
        else coordinator.requestAbort(retry).valueOrFail() // Explicit test confirmation, never inferred.
        assertEquals(1, credential.opens)
        assertEquals(1, credential.aborts)
        assertFalse(credential.selectedKeyPresent())
        assertEquals(null, coordinator.inspectPending().valueOrFail())
        assertTrue(ledger.record().revision > credential.effectFloor)
        assertEquals(null, boundary.current())
    }

    private suspend fun reservationFailure(point: SyncPoint, label: String) {
        val ledger = newLedger(work = true)
        val boundary = SessionBoundary()
        val lease = boundary.activate(OWNER) // Synthetic test fixture, not an identity-verification shortcut.
        val effects = WorkEffects(ledger)
        var registry = openRegistry(ledger, boundary, effects)
        var binding = registry.createOrigin(lease, ledger.record().revision).valueOrFail()
        val before = ledger.record()
        effects.floor = before.revision
        injected(ledger, point, label, { effects.total }) {
            registry.install(binding, NativeWorkKind.TIMER, "fixture-timer", effects::install)
        }
        val attempted = ledger.trace.lastAttempt!!.payload
        val idsAtFailure = effects.allocatedIds.toList()
        assertEquals(2, idsAtFailure.size) // One origin and one exact reserved ticket, no replacement.
        closeRegistry(registry)
        ledger.reopen()
        registry = openRegistry(ledger, boundary, effects)
        val observed = registry.snapshot().valueOrFail()
        if (point == SyncPoint.DIRECTORY) {
            assertTrue(sameBytes(ledger.record().payload, attempted))
            assertEquals(before.revision + 1, observed.revision)
            assertEquals(1, observed.entries.size)
            assertEquals(NativeWorkPhase.RESERVED, observed.entries.single().phase)
        }
        if (observed.entries.isEmpty()) {
            // A rolled-back reservation has no native effect to cancel. Do not invent a new ID.
            val prior = ledger.record()
            assertEquals(OWNER, observed.scope)
            assertEquals(binding.originBinding, observed.originBinding)
            assertFalse(observed.retiring)
            assertEquals(before.revision, observed.revision)
            assertEquals(before.revision, prior.revision)
            assertTrue(sameBytes(before.payload, prior.payload))
            val acknowledged = ledger.trace.compareAndSet(prior.revision, prior.payload).valueOrFail()
            assertEquals(prior.revision + 1, acknowledged.revision)
            assertTrue(sameBytes(prior.payload, ledger.record().payload))
            assertEquals(0, effects.total)
        } else {
            val exact = observed.entries.single().ticket
            assertEquals(idsAtFailure.last(), exact.id)
            assertEquals(NativeWorkKind.TIMER, exact.kind)
            binding = registry.resume(lease).valueOrFail()
            if (point == SyncPoint.DIRECTORY) {
                injected(ledger, point, null, { effects.total }) { registry.reconcilePending(binding) }
                assertEquals(exact.id, registry.snapshot().valueOrFail().entries.single().ticket.id)
                closeRegistry(registry)
                ledger.reopen()
                registry = openRegistry(ledger, boundary, effects)
                binding = registry.resume(lease).valueOrFail()
            }
            effects.floor = ledger.record().revision
            registry.reconcilePending(binding).valueOrFail()
            assertEquals(0, effects.installed.size)
            assertEquals(listOf(exact.id), effects.cancelled.map { it.id })
            assertTrue(registry.snapshot().valueOrFail().entries.isEmpty())
        }
        assertEquals(idsAtFailure, effects.allocatedIds)
        assertEquals(0, effects.installed.size)
    }

    private suspend fun retirementFailure(point: SyncPoint, label: String) {
        val ledger = newLedger(work = true)
        val boundary = SessionBoundary()
        val lease = boundary.activate(OWNER)
        val effects = WorkEffects(ledger)
        var registry = openRegistry(ledger, boundary, effects)
        val binding = registry.createOrigin(lease, ledger.record().revision).valueOrFail()
        effects.floor = ledger.record().revision
        val ticket = registry.install(binding, NativeWorkKind.WORKER, "fixture-worker", effects::install).valueOrFail()
        assertEquals(listOf(ticket.id), effects.installed.map { it.id })
        effects.installed.clear()
        boundary.clear()
        val before = ledger.record()
        effects.floor = before.revision
        injected(ledger, point, label, { effects.total }) { registry.retire(OWNER, binding.originBinding) }
        val attempted = ledger.trace.lastAttempt!!.payload
        closeRegistry(registry)
        ledger.reopen()
        registry = openRegistry(ledger, boundary, effects)
        val observed = registry.snapshot().valueOrFail()
        assertEquals(ticket.id, observed.entries.single().ticket.id)
        if (point == SyncPoint.DIRECTORY) {
            assertTrue(observed.retiring)
            assertEquals(before.revision + 1, observed.revision)
            assertTrue(sameBytes(ledger.record().payload, attempted))
            injected(ledger, point, null, { effects.total }) { registry.retire(OWNER, binding.originBinding) }
            closeRegistry(registry)
            ledger.reopen()
            registry = openRegistry(ledger, boundary, effects)
        }
        effects.floor = ledger.record().revision
        registry.retire(OWNER, binding.originBinding).valueOrFail()
        assertEquals(listOf(ticket.id), effects.cancelled.map { it.id })
        assertEquals(NativeWorkKind.WORKER, effects.cancelled.single().kind)
        assertEquals(0, effects.installed.size)
        val completed = registry.snapshot().valueOrFail()
        assertEquals(null, completed.originBinding)
        assertEquals(null, completed.scope)
        assertTrue(completed.entries.isEmpty())
        assertTrue(completed.revision > effects.floor)
        assertEquals(2, effects.allocatedIds.size)
        assertEquals(null, boundary.current())
    }

    private suspend fun <T> injected(
        ledger: Ledger, point: SyncPoint, label: String?, effects: () -> Int,
        action: suspend () -> PortResult<T>,
    ) {
        assertEquals(0, effects())
        ledger.sql.nativeCodes.clear()
        SqliteSyncFailureInjector.install(ledger.file.path, point.nativeKind, 1)
        val outcome: PortResult<T>
        val stats: LongArray
        try {
            outcome = action()
            stats = SqliteSyncFailureInjector.statistics()
        } finally { SqliteSyncFailureInjector.restore() }
        assertTrue("An actual failed sync must not authorize effects", outcome is PortResult.Failure)
        val failure = outcome as PortResult.Failure
        assertTrue(failure.reason in setOf(FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN))
        assertEquals(null, failure.retryAfterSeconds)
        assertEquals(7, stats.size)
        assertEquals(1L, stats[4])
        assertEquals(point.sqliteCode.toLong(), stats[5])
        assertTrue(stats[point.nativeKind - 1] >= 1L)
        val unlinked = if (point == SyncPoint.DIRECTORY) 1L else 0L
        assertEquals(unlinked, stats[6])
        assertTrue("Bundled SQLite must surface the actual extended numeric sync error",
            ledger.sql.nativeCodes.contains(point.sqliteCode))
        assertEquals("No credential open/abort or native work effect before acknowledged CAS", 0, effects())
        for (privateValue in listOf(OWNER.environment, OWNER.actorId, ledger.file.path,
                ledger.box.keyPrefix, point.sqliteCode.toString())) assertFalse(failure.toString().contains(privateValue))
        if (label != null) InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putString("session_ledger_sync_$label",
                "sqlite=${point.sqliteCode};failures=${stats[4]};effects=0;vfs=1;delegatedUnlink=$unlinked")
        })
    }

    private suspend fun newLedger(work: Boolean): Ledger = Ledger(AndroidStateTestSandbox(), work)
        .also { ledgers += it; it.initialize(); it.reopen() }

    private suspend fun openRegistry(ledger: Ledger, boundary: SessionBoundary, effects: WorkEffects): SessionWorkRegistry =
        SessionWorkRegistry.open(ledger.trace, boundary, dispatcher,
            NativeWorkCancellationPort(effects::cancel),
            NativeWorkIdSource { UUID.randomUUID().toString().also(effects.allocatedIds::add) },
            NativeWorkAdmissionPolicy { scope -> PortResult.Value(scope == OWNER && boundary.current()?.scope == scope) },
            NativeWorkExecutionPolicy { scope, _, _, _ -> PortResult.Value(scope == OWNER && boundary.current()?.scope == scope) },
        ).valueOrFail().also(registries::add)

    private suspend fun closeRegistry(registry: SessionWorkRegistry) {
        registry.close().valueOrFail()
        registries.remove(registry)
    }

    private class Ledger(val box: AndroidStateTestSandbox, private val work: Boolean) {
        val file get() = File(box.directory, "state.sqlite").canonicalFile
        lateinit var sql: NativeSqlTrace
        lateinit var trace: TraceControlStore
        private var closeCurrent: (suspend () -> PortResult<Unit>)? = null

        suspend fun initialize() {
            val database = AndroidStateDatabase.openForTests(box.directory, box.keyPrefix).valueOrFail()
            selectWrapper(database, initialize = true)
            close()
        }

        suspend fun reopen() {
            close()
            val vault = AndroidStateVault.createOrOpen(box.keyPrefix, databaseExisted = true)
            SqliteSyncFailureInjector.registerVfs()
            // SQLITE_OPEN_URI is a documented SQLite flag; this connection is test-owned only.
            sql = NativeSqlTrace(BundledSQLiteDriver().open("${file.toURI()}?vfs=feedme-test-sync-failure-v1",
                SQLITE_OPEN_READWRITE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_NOFOLLOW or 0x00000040))
            val database = EncryptedStateDatabase.open(sql, vault).valueOrFail()
            selectWrapper(database, initialize = false)
        }

        private suspend fun selectWrapper(database: EncryptedStateDatabase, initialize: Boolean) {
            if (work) {
                val selected = EncryptedSessionWorkStore.open(database, initialize).valueOrFail()
                closeCurrent = selected::close
                trace = TraceControlStore(selected)
            } else {
                val selected = EncryptedSessionControlStore.open(database, initialize).valueOrFail()
                closeCurrent = selected::close
                trace = TraceControlStore(selected)
            }
        }

        suspend fun close() {
            closeCurrent?.invoke()?.valueOrFail()
            closeCurrent = null
        }

        suspend fun record(): SessionControlRecord = trace.read().valueOrFail().also { assertNotNull(it) }!!

        suspend fun assertAcknowledgedBeyond(floor: Long, payload: PrivateBytes? = null) {
            val current = record()
            val acknowledged = trace.acknowledged.lastOrNull()
            assertNotNull("Native effect requires this open's successful changed-CAS receipt", acknowledged)
            assertTrue(current.revision > floor)
            assertEquals(current.revision, acknowledged!!.revision)
            assertTrue(sameBytes(current.payload, acknowledged.payload))
            if (payload != null) assertTrue(sameBytes(payload, current.payload))
        }
    }

    /** Real adapter delegation only; no simulated CAS outcome or readback mutation. */
    private class TraceControlStore(private val native: SessionControlStore) : SessionControlStore {
        var lastAttempt: SessionControlRecord? = null
        val acknowledged = mutableListOf<SessionControlRecord>()
        override suspend fun read() = native.read()
        override suspend fun compareAndSet(expectedRevision: Long?, payload: PrivateBytes): PortResult<SessionControlRecord> {
            lastAttempt = SessionControlRecord((expectedRevision ?: 0) + 1, payload)
            val result = native.compareAndSet(expectedRevision, payload)
            if (result is PortResult.Value) {
                assertEquals((expectedRevision ?: 0) + 1, result.value.revision)
                assertTrue(sameBytes(payload, result.value.payload))
                acknowledged += result.value
            }
            return result
        }
    }

    private class WorkEffects(private val ledger: Ledger) {
        var floor = 0L
        val installed = mutableListOf<NativeWorkTicket>()
        val cancelled = mutableListOf<NativeWorkTicket>()
        val allocatedIds = mutableListOf<String>()
        val total get() = installed.size + cancelled.size
        suspend fun install(ticket: NativeWorkTicket): PortResult<Unit> {
            ledger.assertAcknowledgedBeyond(floor)
            installed += ticket
            return PortResult.Value(Unit)
        }
        suspend fun cancel(ticket: NativeWorkTicket): PortResult<Unit> {
            ledger.assertAcknowledgedBeyond(floor)
            cancelled += ticket
            return PortResult.Value(Unit)
        }
    }

    /** Public credential factory uses a fixed prefix, preflighted within the isolated test APK UID. */
    private class NativeCredentials {
        private val box = AndroidStateTestSandbox()
        private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private var ownsRoot = false
        private var ownsNamespace = false
        private var store: AndroidCredentialStore? = null
        private val recoveryHandles = mutableListOf<CredentialCreateRecoveryHandle>()
        private lateinit var context: Context
        private lateinit var selectedAlias: String
        private val fixture = StoredCredentials.Account(OWNER, SecretText("synthetic-ledger-access"),
            SecretText("synthetic-ledger-refresh"), 9_000_000_000_000L,
            SecretText("00000000-0000-4000-8000-000000000001"))
        lateinit var plan: CredentialCreatePlan
        var effectFloor = 0L
        var beforeEffect: suspend () -> Unit = {}
        var opens = 0
        var aborts = 0
        val effects get() = opens + aborts

        suspend fun prepare() {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("com.feedme.storage.test", base.packageName)
            assertTrue("This fixture exclusively owns the test UID's fixed credential namespace",
                credentialNamespace.compareAndSet(false, true))
            ownsNamespace = true
            assertTrue("Unknown existing credential state must be preserved", aliases().isEmpty())
            Os.mkdir(box.directory.path, 0x1c0)
            ownsRoot = true
            context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = box.directory
            }
            val native = AndroidCredentialStore.open(context).valueOrFail().also { store = it }
            plan = native.planCreate(native.state().valueOrFail().revision, fixture).valueOrFail()
        }

        suspend fun select() {
            val native = store ?: throw AssertionError("Owned credential plan must be prepared first")
            native.commitPlannedCreate(plan, fixture).valueOrFail()
            selectedAlias = aliases().filter { it.startsWith("$CREDENTIAL_PREFIX.credential.") }.single()
            native.close().valueOrFail()
            store = null
        }

        val factory = CredentialCreateRecoveryFactory { supplied ->
            beforeEffect()
            opens++
            assertTrue(sameBytes(plan.copyForStorage(), supplied.copyForStorage()))
            when (val opened = AndroidCredentialStore.openCreateRecovery(context, supplied)) {
                is PortResult.Failure -> opened
                is PortResult.Value -> {
                    val native = opened.value.also(recoveryHandles::add)
                    PortResult.Value(object : CredentialCreateRecoveryHandle {
                        override suspend fun inspect() = native.inspect()
                        override suspend fun abort(): PortResult<Unit> {
                            beforeEffect()
                            aborts++
                            return native.abort()
                        }
                        override suspend fun close(): PortResult<Unit> {
                            val result = native.close()
                            if (result is PortResult.Value) recoveryHandles.remove(native)
                            return result
                        }
                    })
                }
            }
        }

        fun selectedKeyPresent(): Boolean = keyStore.containsAlias(selectedAlias)
        private fun aliases() = keyStore.aliases().toList().filter { it == CREDENTIAL_PREFIX || it.startsWith("$CREDENTIAL_PREFIX.") }.toSet()
        suspend fun closeHandles() {
            recoveryHandles.toList().asReversed().forEach { it.close().valueOrFail(); recoveryHandles.remove(it) }
            store?.close()?.valueOrFail()
            store = null
        }
        fun destroyOwnedFixture() {
            // No deletion if preflight failed, even when the unrelated aliases share the prefix.
            if (ownsRoot) {
                val exact = aliases()
                assertTrue(exact.all { it == "$CREDENTIAL_PREFIX.index" || it == "$CREDENTIAL_PREFIX.manifest" ||
                    it.matches(Regex("${Regex.escape(CREDENTIAL_PREFIX)}\\.credential\\.[0-9a-f]{64}")) })
                exact.forEach(keyStore::deleteEntry)
                assertTrue(aliases().isEmpty())
                box.close()
                ownsRoot = false
            }
            if (ownsNamespace) { credentialNamespace.set(false); ownsNamespace = false }
        }
    }

    private class NativeSqlTrace(private val native: SQLiteConnection) : SQLiteConnection by native {
        val nativeCodes = mutableListOf<Int>()
        override fun prepare(sql: String): SQLiteStatement {
            val statement = native.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean = try { statement.step() } catch (failure: Exception) {
                    Regex("Error code: ([0-9]+)").find(failure.message.orEmpty())?.groupValues?.get(1)
                        ?.toIntOrNull()?.let(nativeCodes::add)
                    throw failure
                }
            }
        }
    }

    private enum class SyncPoint(val nativeKind: Int, val sqliteCode: Int) {
        JOURNAL(1, 1034), DATABASE(2, 1034), DIRECTORY(4, 1290),
    }

    companion object {
        private val OWNER = StorageScope("ledger-sync-fixture", ActorKind.ACCOUNT, "private-ledger-owner")
        private const val CREDENTIAL_PREFIX = "com.feedme.session.credentials.v1"
        private val credentialNamespace = AtomicBoolean(false)
        private fun sameBytes(a: PrivateBytes, b: PrivateBytes): Boolean {
            val left = a.copyForCodec(); val right = b.copyForCodec()
            return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
        }
        private fun pendingPayload(plan: CredentialCreatePlan, requested: Boolean): PrivateBytes {
            // Exact public persistence format fixture; production coordinator performs full decoding.
            val raw = plan.copyForStorage().copyForCodec()
            val hex = try { raw.joinToString("") { "%02x".format(it.toInt() and 255) } } finally { raw.fill(0) }
            return PrivateBytes(("{\"version\":1,\"state\":\"credential-create-pending\",\"plan\":\"$hex\"," +
                "\"abortRequested\":$requested}").encodeToByteArray())
        }
    }
}
