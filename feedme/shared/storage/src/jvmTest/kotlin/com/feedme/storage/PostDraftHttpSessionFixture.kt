package com.feedme.storage

import com.feedme.core.ports.*
import com.feedme.session.*
import kotlinx.coroutines.CoroutineDispatcher

/** TEST-ONLY bridge, not a public production sign-in or access-minting escape hatch.
 * Actual SQLite/encrypted data/control/work and PrivateSessionRuntime mint the returned access.
 * Existing fixture vault keys live only in process; its synthetic credential adapter reflectively
 * constructs CredentialSnapshot, never PrivateSessionAccess or mealflow access. No OS-keystore,
 * real provider verification, physical durability or native background work is asserted here.
 */
class PostDraftHttpSessionFixture private constructor(
    private val fixture: PrivateSessionRuntimeTest.RuntimeFixture,
    access: PrivateSessionAccess,
    private val nativeAdmissions: () -> Int,
) {
    var access: PrivateSessionAccess = access
        private set
    val boundary: SessionBoundary get() = fixture.boundary
    val localWriteStatements: Int get() = fixture.dataConnection.writeStatements
    fun requireNoNativeWork() { check(nativeAdmissions() == 0 && fixture.cancelled.isEmpty()) }
    /** Actual next data-record SQLite COMMIT boundary, not a fake store acknowledgement. */
    fun failNextDataCommit(afterCommit: Boolean) {
        if (afterCommit) fixture.dataConnection.failNextBindingCommitAfter = true
        else fixture.dataConnection.failNextBindingCommitBefore = true
    }
    /** Controlled runtime/database reopen; vault keys remain in this test process. */
    suspend fun reopen(): PrivateSessionAccess {
        requireNoNativeWork()
        fixture.reopen()
        check((fixture.runtime.recover() as? PortResult.Value)?.value == PrivateSessionPhase.RESTORE_REQUIRED)
        val restored = (fixture.runtime.restore() as? PortResult.Value)?.value ?: error("Synthetic runtime restore failed")
        check(restored.scope == access.scope && restored.originBinding == access.originBinding)
        access = restored
        return restored
    }
    suspend fun closeRuntimeAndStores() { requireNoNativeWork(); fixture.closeRuntimeAndStores() }
    fun assertNoPlaintext(markers: List<String>) { fixture.assertNoPlaintext(markers) }
    /** Existing owned temporary SQLite fixture cleanup only, never any PostgreSQL cluster. */
    suspend fun close() { requireNoNativeWork(); fixture.close() }

    companion object {
        suspend fun open(dispatcher: CoroutineDispatcher, credentials: StoredCredentials.Account,
            initialIdSequence: Int = 0,
        ): PostDraftHttpSessionFixture {
            require(initialIdSequence in 0..1_000_000)
            var admissions = 0
            val fixture = PrivateSessionRuntimeTest.RuntimeFixture(dispatcher,
                NativeWorkExecutionPolicy { _, _, _, _ -> admissions++; PortResult.Failure(FailureReason.NOT_CONFIGURED) },
                initialIdSequence = initialIdSequence)
            fixture.acquire = { PortResult.Value(credentials) }
            try {
                fixture.start()
                val access = fixture.create()
                check(access.scope == credentials.scope && fixture.boundary.isCurrent(access.lease))
                return PostDraftHttpSessionFixture(fixture, access) { admissions }
            } catch (failure: Throwable) { fixture.close(); throw failure }
        }
    }
}
