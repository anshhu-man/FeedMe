package com.feedme.session

import com.feedme.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Read-only transport projection, not sign-in, token refresh, expiry policy, or native storage. */
class CredentialTransportViewTest {
    @Test fun accountProjectionKeepsAccessDeviceScopeAndExpiryButOmitsRefreshToken() = runTest {
        val f = Fixture()
        val original = account()
        f.store.result = PortResult.Value(CredentialSnapshot(INCARNATION, 7, original))
        val projected = assertIs<StoredCredentials.Account>(assertNotNull(value(f.view.read(ACCOUNT))))
        assertEquals(ACCOUNT, projected.scope)
        assertEquals(EXPIRY, projected.expiresAtMillis)
        assertEquals("private-access-token", projected.accessToken.use { it })
        assertEquals(DEVICE, projected.deviceSessionId!!.use { it })
        assertNull(projected.refreshToken)
        assertEquals("private-refresh-token", original.refreshToken!!.use { it })
        assertEquals(1, f.store.reads)
        assertEquals(listOf(ACCOUNT), f.store.scopes)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun accountProjectionPreservesUnbootstrappedNullDeviceAndNullRefresh() = runTest {
        val f = Fixture()
        f.store.result = PortResult.Value(CredentialSnapshot(INCARNATION, 1, account(refresh = null, device = null)))
        val projected = assertIs<StoredCredentials.Account>(assertNotNull(value(f.view.read(ACCOUNT))))
        assertNull(projected.refreshToken)
        assertNull(projected.deviceSessionId)
        assertEquals("private-access-token", projected.accessToken.use { it })
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun guestProjectionPreservesGuestIdentityWithoutInventingAccountCredentials() = runTest {
        val f = Fixture(GUEST)
        val guest = StoredCredentials.Guest(GUEST, SecretText(GUEST_SESSION), SecretText("private-guest-token"), EXPIRY)
        f.store.result = PortResult.Value(CredentialSnapshot(INCARNATION, 3, guest))
        val projected = assertIs<StoredCredentials.Guest>(assertNotNull(value(f.view.read(GUEST))))
        assertEquals(GUEST, projected.scope)
        assertEquals(GUEST_SESSION, projected.guestSessionId.use { it })
        assertEquals("private-guest-token", projected.guestToken.use { it })
        assertEquals(EXPIRY, projected.expiresAtMillis)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun missingCredentialRemainsAbsentAndDoesNotTriggerCreateOrRefresh() = runTest {
        val f = Fixture()
        f.store.result = PortResult.Value(null)
        assertNull(value(f.view.read(ACCOUNT)))
        assertEquals(1, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun requestedWrongOwnerEnvironmentOrKindFailsBeforeSecureRead() = runTest {
        val f = Fixture()
        for (wrong in listOf(ACCOUNT.copy(actorId = "other-owner"), ACCOUNT.copy(environment = "other-environment"),
            ACCOUNT.copy(actorKind = ActorKind.GUEST), ACCOUNT.copy(actorKind = ActorKind.DEMO)))
            failure(FailureReason.STALE_SESSION, f.view.read(wrong))
        assertEquals(0, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun clearedLeaseIsFencedBeforeSecureStoreAccess() = runTest {
        val f = Fixture()
        f.boundary.clear()
        failure(FailureReason.STALE_SESSION, f.view.read(ACCOUNT))
        assertEquals(0, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun replacementLeaseForSameOwnerCannotReuseOldTransportProjection() = runTest {
        val f = Fixture()
        val replacement = f.boundary.activate(ACCOUNT)
        failure(FailureReason.STALE_SESSION, f.view.read(ACCOUNT))
        assertSame(replacement, f.boundary.current())
        assertEquals(0, f.store.reads)
    }

    @Test fun returnedCredentialFromWrongIncarnationIsRejectedWithoutRetiringIt() = runTest {
        val f = Fixture()
        f.store.result = PortResult.Value(CredentialSnapshot(OTHER_INCARNATION, 19, account()))
        failure(FailureReason.STALE_SESSION, f.view.read(ACCOUNT))
        assertEquals(1, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun returnedSnapshotForWrongOwnerOrKindCannotLeakIntoExpectedProjection() = runTest {
        for (credentials in listOf<StoredCredentials>(
            account(scope = ACCOUNT.copy(actorId = "other-owner")),
            account(scope = ACCOUNT.copy(environment = "other-environment")),
            StoredCredentials.Guest(GUEST, SecretText(GUEST_SESSION), SecretText("other-guest-token"), EXPIRY),
        )) {
            val f = Fixture()
            f.store.result = PortResult.Value(CredentialSnapshot(INCARNATION, 9, credentials))
            failure(FailureReason.STALE_SESSION, f.view.read(ACCOUNT))
            f.store.assertNoMutationsOrStateReads()
        }
    }

    @Test fun logoutWhileNativeReadSuspendsRejectsPreviouslyCapturedPrivateCredentials() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store.beforeReturn = { entered.complete(Unit); release.await() }
        val pending = async { f.view.read(ACCOUNT) }
        entered.await()
        f.boundary.clear()
        release.complete(Unit)
        failure(FailureReason.STALE_SESSION, pending.await())
        assertEquals(1, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun sameOwnerRebootstrapDuringNativeReadDoesNotAcceptOldLeaseResult() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store.beforeReturn = { entered.complete(Unit); release.await() }
        val pending = async { f.view.read(ACCOUNT) }
        entered.await()
        val newLease = f.boundary.activate(ACCOUNT)
        release.complete(Unit)
        failure(FailureReason.STALE_SESSION, pending.await())
        assertSame(newLease, f.boundary.current())
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun staleLeaseAfterFailedStoreReadWinsOverUnderlyingFailureMetadata() = runTest {
        val f = Fixture()
        f.store.result = PortResult.Failure(FailureReason.UNAVAILABLE, 45)
        f.store.beforeReturn = { f.boundary.clear() }
        val failed = failure(FailureReason.STALE_SESSION, f.view.read(ACCOUNT))
        assertNull(failed.retryAfterSeconds)
    }

    @Test fun currentLeasePreservesStoreFailuresAndRetryGuidanceWithoutRetrying() = runTest {
        val f = Fixture()
        for (reason in FailureReason.entries) {
            f.store.result = PortResult.Failure(reason, 23)
            assertEquals(23L, failure(reason, f.view.read(ACCOUNT)).retryAfterSeconds)
        }
        assertEquals(FailureReason.entries.size, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun unexpectedNativeExceptionIsSanitizedWithoutExposingPrivateDiagnostics() = runTest {
        val f = Fixture()
        f.store.beforeReturn = { error("native-path/private-owner/private-refresh-token/$INCARNATION") }
        val failure = failure(FailureReason.STORAGE_FAILURE, f.view.read(ACCOUNT))
        assertNull(failure.retryAfterSeconds)
        for (privateValue in listOf(ACCOUNT.actorId, INCARNATION, "private-refresh-token", "native-path"))
            assertFalse(failure.toString().contains(privateValue))
        assertEquals(1, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun lateLeaseFenceWinsOverSanitizedThrownNativeException() = runTest {
        val f = Fixture()
        f.store.beforeReturn = {
            f.boundary.clear()
            error("private-refresh-token/$INCARNATION")
        }
        val failure = failure(FailureReason.STALE_SESSION, f.view.read(ACCOUNT))
        assertNull(failure.retryAfterSeconds)
        assertFalse(failure.toString().contains(INCARNATION))
        assertEquals(1, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun expiredTimestampIsPreservedWithoutLocalErasureRefreshOrExtension() = runTest {
        for (expires in listOf(0L, 1L, EXPIRY, Long.MAX_VALUE)) {
            val f = Fixture()
            f.store.result = PortResult.Value(CredentialSnapshot(INCARNATION, 1, account(expiry = expires)))
            val credential = assertNotNull(value(f.view.read(ACCOUNT)))
            assertEquals(expires, credential.expiresAtMillis)
            f.store.assertNoMutationsOrStateReads()
        }
    }

    @Test fun refreshWithinSameIncarnationCanAdvanceRevisionWithoutExposingRefreshMaterial() = runTest {
        val f = Fixture()
        f.store.result = PortResult.Value(CredentialSnapshot(INCARNATION, 2, account()))
        assertEquals("private-access-token", assertIs<StoredCredentials.Account>(value(f.view.read(ACCOUNT))).accessToken.use { it })
        f.store.result = PortResult.Value(CredentialSnapshot(INCARNATION, 3,
            StoredCredentials.Account(ACCOUNT, SecretText("new-access-token"), SecretText("new-refresh-token"), EXPIRY + 1000, SecretText(DEVICE))))
        val projected = assertIs<StoredCredentials.Account>(value(f.view.read(ACCOUNT)))
        assertEquals("new-access-token", projected.accessToken.use { it })
        assertEquals(EXPIRY + 1000, projected.expiresAtMillis)
        assertNull(projected.refreshToken)
        assertEquals(2, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun replaceAndEraseAreAlwaysUnconfiguredAndNeverDelegateNativeMutations() = runTest {
        val f = Fixture()
        failure(FailureReason.NOT_CONFIGURED, f.view.replace(ACCOUNT, account()))
        failure(FailureReason.NOT_CONFIGURED, f.view.erase(ACCOUNT))
        failure(FailureReason.NOT_CONFIGURED, f.view.replace(ACCOUNT.copy(actorId = "other"), account()))
        f.boundary.clear()
        failure(FailureReason.NOT_CONFIGURED, f.view.erase(ACCOUNT))
        failure(FailureReason.NOT_CONFIGURED, f.view.replace(ACCOUNT, account()))
        assertEquals(0, f.store.reads)
        f.store.assertNoMutationsOrStateReads()
    }

    @Test fun cancellationDuringNativeReadPropagatesWithoutMutations() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.store.beforeReturn = { entered.complete(Unit); awaitCancellation() }
        val pending = async { f.view.read(ACCOUNT) }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        f.store.assertNoMutationsOrStateReads()
        assertSame(f.lease, f.boundary.current())
    }

    @Test fun viewRejectsDemoAndNoncanonicalLocalIncarnationsAtConstruction() {
        val boundary = SessionBoundary()
        val store = TestStore()
        val lease = boundary.activate(ACCOUNT)
        for (incarnation in listOf("", INCARNATION.uppercase(), "$INCARNATION ", INCARNATION.dropLast(1)))
            assertFailsWith<IllegalArgumentException> { CredentialTransportView(store, boundary, lease, incarnation) }
        val demo = boundary.activate(ACCOUNT.copy(actorKind = ActorKind.DEMO))
        assertFailsWith<IllegalArgumentException> { CredentialTransportView(store, boundary, demo, INCARNATION) }
        assertEquals(0, store.reads)
        store.assertNoMutationsOrStateReads()
    }

    @Test fun viewDebugRepresentationDoesNotExposeOwnerIncarnationOrSecrets() {
        val text = Fixture().view.toString()
        for (privateValue in listOf(ACCOUNT.actorId, INCARNATION, DEVICE, "private-access-token", "private-refresh-token"))
            assertFalse(text.contains(privateValue))
    }

    private class Fixture(scope: StorageScope = ACCOUNT) {
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        val store = TestStore()
        val view = CredentialTransportView(store, boundary, lease, INCARNATION)
    }

    private class TestStore : IncarnationCredentialStore {
        var reads = 0
        var stateReads = 0
        var creates = 0
        var replacements = 0
        var attachments = 0
        var retirements = 0
        val scopes = mutableListOf<StorageScope>()
        var result: PortResult<CredentialSnapshot?> = PortResult.Value(CredentialSnapshot(INCARNATION, 7, account()))
        var beforeReturn: suspend () -> Unit = {}
        override suspend fun state(): PortResult<CredentialSlotState> {
            stateReads++
            error("Transport view must not inspect mutable ownership slots")
        }
        override suspend fun read(scope: StorageScope): PortResult<CredentialSnapshot?> {
            reads++
            scopes += scope
            val captured = result
            beforeReturn()
            return captured
        }
        override suspend fun create(expectedSlotRevision: Long, credentials: StoredCredentials): PortResult<CredentialSnapshot> {
            creates++
            error("Transport projection cannot create credentials")
        }
        override suspend fun replace(expected: CredentialSnapshot, credentials: StoredCredentials): PortResult<CredentialSnapshot> {
            replacements++
            error("Transport projection cannot replace credentials")
        }
        override suspend fun attachDeviceSession(expected: CredentialSnapshot, deviceSessionId: SecretText): PortResult<CredentialSnapshot> {
            attachments++
            error("Transport projection cannot bootstrap a device session")
        }
        override suspend fun retire(scope: StorageScope, credentialIncarnation: String): PortResult<Unit> {
            retirements++
            error("Transport projection cannot retire credentials")
        }
        fun assertNoMutationsOrStateReads() {
            assertEquals(0, stateReads)
            assertEquals(0, creates)
            assertEquals(0, replacements)
            assertEquals(0, attachments)
            assertEquals(0, retirements)
        }
    }

    companion object {
        private val ACCOUNT = StorageScope("test", ActorKind.ACCOUNT, "private-owner")
        private val GUEST = StorageScope("test", ActorKind.GUEST, "private-guest-owner")
        private const val INCARNATION = "123e4567-e89b-12d3-a456-426614174abc"
        private const val OTHER_INCARNATION = "123e4567-e89b-12d3-a456-426614174abd"
        private const val DEVICE = "123e4567-e89b-12d3-a456-426614174080"
        private const val GUEST_SESSION = "123e4567-e89b-12d3-a456-426614174081"
        private const val EXPIRY = 1_800_000_000_000L
        private fun account(scope: StorageScope = ACCOUNT, refresh: SecretText? = SecretText("private-refresh-token"),
            device: SecretText? = SecretText(DEVICE), expiry: Long = EXPIRY) =
            StoredCredentials.Account(scope, SecretText("private-access-token"), refresh, expiry, device)
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>): PortResult.Failure =
            assertIs<PortResult.Failure>(result).also { assertEquals(reason, it.reason) }
    }
}
