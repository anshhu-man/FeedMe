package com.feedme.session

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.core.ports.*
import com.feedme.storage.AndroidSessionWorkStore
import com.feedme.storage.EncryptedSessionWorkStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual public session facade, existing-only native work owner, Keystore proof and bundled
 * SQLite. The ordinary registry exists only to create synthetic prior state and is closed before
 * recovery. No fake storage success, scheduler, provider, secret or lease is used by recovery.
 * This is not independent confirmation, startup completion, process death or native fault proof.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionWorkRecoveryTest {
    @Test fun publicConstructionAndCloseBeforeOpenPerformNoContextOrNativeIo() = integration {
        initialize(Stage.PREPARED)
        var accesses = 0
        val trap = object : ContextWrapper(sandbox.context) {
            override fun getApplicationContext(): Context { accesses++; throw IOException("Private fixture path unavailable") }
        }
        val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        val recovery = owner(context = trap)
        assertEquals(0, accesses)
        assertEquals("SessionWorkOriginRecoveryOwner(<redacted>)", recovery.toString())
        failure(recovery.inspect(), FailureReason.STALE_SESSION)
        failure(recovery.abort(), FailureReason.STALE_SESSION)
        recovery.close().checked(); recovery.close().checked()
        failure(recovery.open(), FailureReason.CONFLICT)
        assertEquals(0, accesses); assertEquals(0, descriptors())
        sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases()); assertNoEffects()

        // A public factory must not create even its parent when this exact path is absent.
        val missingRoot = File(sandbox.context.noBackupFilesDir, "missing-recovery-parent")
        val missing = object : ContextWrapper(sandbox.context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = missingRoot
        }
        val absent = owner(context = missing)
        failure(absent.open(), FailureReason.STORAGE_FAILURE)
        failure(absent.inspect(), FailureReason.STALE_SESSION)
        absent.close().checked()
        assertFalse(missingRoot.exists())
        sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
    }

    @Test fun everyAuthenticatedStageIsReadOnlyExactAndRetainedUntilExplicitClose() {
        for (stage in Stage.entries) integration {
            initialize(stage)
            val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            val recovery = owner(); recovery.open().checked()
            val observed = recovery.inspect().checked()
            assertEquals(stage.status, observed.status); assertEquals(revision, observed.revision)
            assertEquals("SessionWorkOriginObservation(<redacted>)", observed.toString())
            repeat(2) { same(observed, recovery.inspect().checked()) }
            assertEquals(1, descriptors())
            failure(recovery.open(), FailureReason.CONFLICT)
            val blocked = owner(); failure(blocked.open(), FailureReason.STORAGE_FAILURE)
            blocked.close().checked(); assertEquals(1, descriptors())
            sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
            recovery.close().checked(); assertEquals(0, descriptors())
            failure(recovery.inspect(), FailureReason.STALE_SESSION)
            val reopened = owner(); reopened.open().checked(); same(observed, reopened.inspect().checked())
            sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun abortFromEveryStageAndReopenedAbortedReplayObtainFreshExactNextRevisions() {
        for (stage in Stage.entries) integration {
            initialize(stage)
            val aliases = sandbox.aliases()
            val recovery = owner(); recovery.open().checked()
            recovery.abort().checked()
            val first = recovery.inspect().checked()
            assertEquals(SessionWorkOriginPlanStatus.ABORTED, first.status)
            assertEquals(revision + 1, first.revision)
            recovery.abort().checked()
            assertEquals(first.revision + 1, recovery.inspect().checked().revision)
            recovery.close().checked(); assertEquals(0, descriptors())
            val reopened = owner(); reopened.open().checked(); reopened.abort().checked()
            val final = reopened.inspect().checked()
            assertEquals(SessionWorkOriginPlanStatus.ABORTED, final.status)
            assertEquals(first.revision + 2, final.revision)
            reopened.close().checked()
            val raw = normalStore()
            val record = raw.read().checked()!!
            assertEquals(final.revision, record.revision)
            val marker = SessionWorkCodec.decode(record.payload) as SessionWorkState.SetupAborted
            assertArrayEquals(plan.copyForStorage().copyForCodec(), marker.plan.copyForStorage().copyForCodec())
            raw.close().checked(); normal = null
            assertEquals(aliases, sandbox.aliases()); assertNoEffects()
        }
    }

    @Test fun wrongScopeFailsBeforeNativeOpenAndInvalidNativeProofRetainsCloseOnlyLock() = integration {
        initialize(Stage.SELECTED)
        val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        for (scope in listOf(SCOPE.copy(actorId = "another-private-owner"), SCOPE.copy(actorKind = ActorKind.DEMO))) {
            val wrong = owner(scope = scope)
            failure(wrong.open(), FailureReason.INVALID_DATA)
            assertEquals(0, descriptors())
            failure(wrong.abort(), FailureReason.STALE_SESSION); wrong.close().checked()
            sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
        }
        val details = SessionWorkOriginPlanCodec.decode(plan.copyForStorage())
        val proof = details.proof.copyForCodec().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val invalid = owner(plan = SessionWorkOriginPlan.create(details.copy(proof = PrivateBytes(proof))))
        failure(invalid.open(), FailureReason.INVALID_DATA)
        assertEquals(1, descriptors())
        failure(invalid.inspect(), FailureReason.STALE_SESSION); failure(invalid.abort(), FailureReason.STALE_SESSION)
        failure(invalid.open(), FailureReason.CONFLICT)
        val blocked = owner(); failure(blocked.open(), FailureReason.STORAGE_FAILURE); blocked.close().checked()
        assertEquals(1, descriptors()); sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
        invalid.close().checked(); assertEquals(0, descriptors())
        val correct = owner(); correct.open().checked()
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, correct.inspect().checked().status)
        assertNoEffects()
    }

    @Test fun authenticatedAbortedPredecessorSupportsSuccessorButNewSelectionFencesOriginal() = integration {
        initialize(Stage.ABORTED)
        val original = plan
        val raw = normalStore(); val registry = planner(raw)
        val successor = registry.planOrigin(SCOPE, raw.read().checked()!!.revision).checked()
        registry.close().checked(); raw.close().checked(); normal = null
        val prepared = owner(plan = successor); prepared.open().checked()
        val next = prepared.inspect().checked()
        assertEquals(SessionWorkOriginPlanStatus.PREPARED, next.status)
        prepared.close().checked()
        // Planning is not reservation; only selecting the successor consumes the old marker.
        val old = owner(plan = original); old.open().checked()
        assertEquals(SessionWorkOriginPlanStatus.ABORTED, old.inspect().checked().status); old.close().checked()
        val currentStore = normalStore(); val selector = planner(currentStore)
        selector.selectOrigin(successor).checked(); selector.close().checked()
        currentStore.close().checked(); normal = null
        val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        val stale = owner(plan = original); failure(stale.open(), FailureReason.STALE_SESSION)
        failure(stale.abort(), FailureReason.STALE_SESSION)
        assertEquals(1, descriptors()); sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
        stale.close().checked()
        val selected = owner(plan = successor); selected.open().checked()
        assertEquals(SessionWorkOriginPlanStatus.SELECTED, selected.inspect().checked().status)
        assertNoEffects()
    }

    @Test fun malformedNoncanonicalAndOrdinaryUsedLedgersNeverBecomeRecoveryAuthority() {
        for (change in listOf("malformed", "noncanonical", "used")) integration {
            initialize(Stage.SEALED)
            val raw = normalStore(); val previous = raw.read().checked()!!
            val payload = when (change) {
                "malformed" -> PrivateBytes("{\"version\":1,\"state\":\"unknown\"}".encodeToByteArray())
                "noncanonical" -> PrivateBytes((" " + previous.payload.copyForCodec().decodeToString()).encodeToByteArray())
                else -> SessionWorkCodec.encode((SessionWorkCodec.decode(previous.payload) as SessionWorkState.Origin).used())
            }
            raw.compareAndSet(previous.revision, payload).checked(); raw.close().checked(); normal = null
            val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
            val recovery = owner()
            failure(recovery.open(), if (change == "malformed") FailureReason.STORAGE_FAILURE else FailureReason.STALE_SESSION)
            failure(recovery.inspect(), FailureReason.STALE_SESSION); failure(recovery.abort(), FailureReason.STALE_SESSION)
            assertEquals(1, descriptors()); sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
            recovery.close().checked(); assertEquals(0, descriptors()); assertNoEffects()
        }
    }

    @Test fun currentLeaseBlocksOpeningOrLateAbortAndClosingNeverClearsNewerSession() = integration {
        initialize(Stage.SEALED)
        val before = sandbox.fileSnapshot(); val aliases = sandbox.aliases()
        val firstLease = boundary.activate(SCOPE)
        val blocked = owner(); failure(blocked.open(), FailureReason.STALE_SESSION)
        assertEquals(0, descriptors()); blocked.close().checked(); assertSame(firstLease, boundary.current())
        boundary.clear()
        val recovery = owner(); recovery.open().checked()
        val newer = boundary.activate(SCOPE.copy(actorId = "newer-private-owner"))
        failure(recovery.inspect(), FailureReason.STALE_SESSION); failure(recovery.abort(), FailureReason.STALE_SESSION)
        assertSame(newer, boundary.current()); assertEquals(1, descriptors())
        recovery.close().checked(); assertSame(newer, boundary.current()); assertEquals(0, descriptors())
        sandbox.assertFilesEqual(before); assertEquals(aliases, sandbox.aliases())
        boundary.clear(); assertNoEffects()
    }

    private fun integration(action: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val sandbox = AndroidRetirementIntegrationSandbox.create(InstrumentationRegistry.getInstrumentation().targetContext)
            val fixture = Fixture(sandbox)
            try { fixture.action() } finally { withContext(NonCancellable) { fixture.finish() } }
        }
    }

    private enum class Stage(val status: SessionWorkOriginPlanStatus) {
        PREPARED(SessionWorkOriginPlanStatus.PREPARED), SELECTED(SessionWorkOriginPlanStatus.SELECTED),
        SEALED(SessionWorkOriginPlanStatus.SEALED), ABORTED(SessionWorkOriginPlanStatus.ABORTED),
    }

    private class Fixture(val sandbox: AndroidRetirementIntegrationSandbox) {
        val boundary = SessionBoundary()
        lateinit var plan: SessionWorkOriginPlan
        var revision = 0L
        var normal: EncryptedSessionWorkStore? = null
        private var nextId = 1
        private var plannedIds = 0
        private var idsAtRecoveryEntry: Int? = null
        private var policyOrNativeEffects = 0
        private val owners = mutableListOf<SessionWorkOriginRecoveryOwner>()
        private val registries = mutableListOf<SessionWorkRegistry>()

        suspend fun initialize(stage: Stage) {
            val store = normalStore(); val registry = planner(store)
            plan = registry.planOrigin(SCOPE, store.read().checked()!!.revision).checked()
            when (stage) {
                Stage.PREPARED -> Unit
                Stage.SELECTED -> registry.selectOrigin(plan).checked()
                Stage.SEALED -> { registry.selectOrigin(plan).checked(); registry.sealOrigin(plan).checked() }
                Stage.ABORTED -> registry.abortOrigin(plan).checked()
            }
            revision = store.read().checked()!!.revision
            registry.close().checked(); store.close().checked(); normal = null
            assertEquals(0, descriptors()); assertNoEffects()
        }

        suspend fun normalStore(): EncryptedSessionWorkStore {
            check(normal == null)
            return AndroidSessionWorkStore.open(sandbox.context).checked().also { normal = it }
        }

        suspend fun planner(store: EncryptedSessionWorkStore): SessionWorkRegistry {
            // Only an explicit closed-owner fixture transition may prepare another plan.
            assertEquals(1, descriptors())
            idsAtRecoveryEntry?.let { assertEquals(it, plannedIds) }
            idsAtRecoveryEntry = null
            return SessionWorkRegistry.open(
                store, boundary, Dispatchers.Main.immediate,
                NativeWorkCancellationPort { forbidden() },
                NativeWorkIdSource { plannedIds++; "00000000-0000-4000-8000-${(nextId++).toString().padStart(12, '0')}" },
                NativeWorkAdmissionPolicy { forbidden() }, NativeWorkExecutionPolicy { _, _, _, _ -> forbidden() },
            ).checked().also { registries += it }
        }

        fun owner(scope: StorageScope = SCOPE, plan: SessionWorkOriginPlan = this.plan,
            context: Context = sandbox.context): SessionWorkOriginRecoveryOwner {
            val beforeIds = plannedIds
            idsAtRecoveryEntry?.let { assertEquals(it, plannedIds) }
            idsAtRecoveryEntry = beforeIds
            return AndroidSessionWorkRecovery.createOwner(context, scope, plan, boundary, Dispatchers.Main.immediate)
                .also { assertEquals(beforeIds, plannedIds); owners += it }
        }

        fun descriptors(): Int {
            val exactLock = File(sandbox.context.noBackupFilesDir, "feedme-session-work/state.lock").canonicalPath
            return File("/proc/self/fd").listFiles().orEmpty().count { entry ->
                // Readlink only; never open another descriptor to the POSIX lifetime-lock inode.
                val target = try { Os.readlink(entry.absolutePath) } catch (_: Exception) { null }
                target == exactLock
            }
        }

        fun assertNoEffects() {
            assertEquals(0, policyOrNativeEffects)
            idsAtRecoveryEntry?.let { assertEquals(it, plannedIds) }
            assertNull(boundary.current())
        }
        private fun forbidden(): Nothing { policyOrNativeEffects++; throw AssertionError("Unexpected fixture policy or native effect") }

        suspend fun finish() {
            var acknowledged = false
            try {
                owners.asReversed().forEach { it.close().checked() }
                registries.asReversed().forEach { it.close().checked() }
                normal?.close()?.checked(); normal = null
                assertEquals("Never remove fixture with retained lock descriptor", 0, descriptors())
                acknowledged = true
            } finally {
                if (acknowledged) sandbox.removeOwnedFixture() else sandbox.releasePreservingFixture()
            }
        }
    }

    companion object {
        private val SCOPE = StorageScope("native-public-work-recovery", ActorKind.ACCOUNT, "private-work-owner")
        private fun same(expected: SessionWorkOriginObservation, actual: SessionWorkOriginObservation) {
            assertEquals(expected.status, actual.status); assertEquals(expected.revision, actual.revision)
        }
        private fun failure(result: PortResult<*>, reason: FailureReason) {
            assertTrue(result is PortResult.Failure); assertEquals(reason, (result as PortResult.Failure).reason)
            assertNull(result.retryAfterSeconds)
        }
        private fun <T> PortResult<T>.checked(): T = when (this) {
            is PortResult.Value -> value
            is PortResult.Failure -> throw AssertionError("Native work recovery fixture failed: $reason")
        }
    }
}
