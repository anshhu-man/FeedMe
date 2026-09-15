package com.feedme.development.progress

import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.app.mealflow.MealInputChoices
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.*
import com.feedme.session.PrivateSessionAccess
import com.feedme.storage.PostDraftHttpSessionFixture
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual encrypted SQLite/runtime-minted access plus the actual durable synthetic service.
 * No reflection/access constructor, fake accepted binding, seed journal or live provider.
 * These JVM tests do not prove Android keystore/process death or Activity/native rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressReviewedPostIntegrationTest {
    @Test fun oneFactoryOneMappingOwnerAndBorrowerCloseNeverClosesNativeSession() = runTest { fixture { f ->
        val experience = f.compose()
        assertEquals(1, f.builders)
        assertSame(f.integration.principals, f.integration.principals)
        assertSame(f.integration.delivery, f.integration.delivery)
        assertSame(f.integration.principals, f.integration.delivery.principals)
        value(experience.openPostDrafts())
        val local = f.local()
        val disclosure = value(f.entry.loadDisclosure(local.clientDraftId, ReviewedPostPurpose.PUBLICATION))
        assertEquals(ProgressPostPreviewContract.disclosureText, disclosure.text)
        val current = f.choices(local)
        val review = value(f.entry.prepareSelectedPublication(current.clientDraftId, current.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
        assertEquals(ProgressPostPreviewContract.authorId, review.snapshot.publisherUserId)
        assertNotEquals(f.session.access.scope.actorId, review.snapshot.publisherUserId)
        value(experience.close()); f.integration.close(); f.integration.close()
        assertTrue(f.session.boundary.isCurrent(f.session.access.lease))
        assertEquals(0, f.calls.size)
        assertIs<PortResult.Failure>(f.integration.disclosure(f.observed.contexts.last()))
    } }

    @Test fun differentWrapperOverSameNativeAccessIsNotTheFactoryBinding() = runTest { fixture { f ->
        f.compose { supplied ->
            val clone = AuthenticatedMealPlanningAccess.fromSession(f.session.access, f.transport)
            assertNotSame(supplied, clone)
            f.createIntegration(clone)
        }
        val before = f.session.localWriteStatements
        assertIs<PortResult.Failure>(f.experience.openPostDrafts())
        assertEquals(before, f.session.localWriteStatements); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
    } }

    @Test fun wrongBoundaryCannotLoadOrProjectReviewedDrafts() = runTest { fixture { f ->
        f.compose { supplied -> f.createIntegration(supplied, boundary = SessionBoundary()) }
        val before = f.session.localWriteStatements
        assertIs<PortResult.Failure>(f.experience.openPostDrafts())
        assertEquals(before, f.session.localWriteStatements); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
    } }

    @Test fun differentRealRuntimeAndOriginCannotBorrowTheRetainedClientBinding() = runTest { fixture { f ->
        // Separate fixtures otherwise mint the same deterministic first origin. Keep this
        // different-origin case on actual runtime minting, with a distinct test-only sequence.
        val other = PostDraftHttpSessionFixture.open(f.dispatcher, credentials(), initialIdSequence = 100)
        try {
            assertNotSame(f.session.access, other.access)
            assertNotEquals(f.session.access.originBinding, other.access.originBinding)
            f.compose { supplied -> f.createIntegration(supplied, access = other.access, boundary = other.boundary) }
            val before = f.session.localWriteStatements
            assertIs<PortResult.Failure>(f.experience.openPostDrafts())
            assertEquals(before, f.session.localWriteStatements); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
        } finally { other.close() }
    } }

    @Test fun revokeFencesActualReviewBeforeNativeOwnerOrBoundaryChanges() = runTest { fixture { f ->
        f.compose(); value(f.experience.openPostDrafts())
        val local = f.choices(f.local())
        val review = value(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
        assertTrue(review.isCurrentForNavigation)
        val writes = f.session.localWriteStatements; val ids = f.ids
        f.integration.revokeBeforeOwnerChange()
        // Real mapped-owner revocation has happened while native lifetime is still intact.
        assertTrue(f.ownerCurrent); assertTrue(f.session.boundary.isCurrent(f.session.access.lease))
        assertFalse(review.isCurrentForNavigation)
        assertIs<PortResult.Failure>(f.entry.publications.confirmPublish(review.token))
        assertIs<PortResult.Failure>(f.entry.loadDisclosure(local.clientDraftId, ReviewedPostPurpose.PUBLICATION))
        assertEquals(ids, f.ids); assertEquals(writes, f.session.localWriteStatements); assertTrue(f.calls.isEmpty())
        f.ownerCurrent = false
    } }

    @Test fun serviceFromAnotherRealRuntimeCannotSupplyThisSessionsDisclosure() = runTest { fixture { f ->
        val other = PostDraftHttpSessionFixture.open(f.dispatcher, credentials())
        val otherService = ProgressCanonicalService(other.access, other.boundary, f.dispatcher, EpochClock { f.now })
        try {
            value(otherService.open(true))
            f.compose { actual -> ProgressReviewedPostIntegration(f.session.access, actual, f.session.boundary, otherService) {
                f.ownerCurrent
            }.also { f.integration = it } }
            val before = f.session.localWriteStatements
            assertIs<PortResult.Failure>(f.experience.openPostDrafts())
            assertEquals(before, f.session.localWriteStatements); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
        } finally { value(otherService.close()); other.close() }
    } }

    @Test fun lateDisclosureReturnAfterRevokeCannotRestoreCurrentReviewOrAllocate() = runTest { fixture { f ->
        f.compose(); value(f.experience.openPostDrafts()); val local = f.choices(f.local())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.observed.afterDisclosure = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val ids = f.ids; val writes = f.session.localWriteStatements
        val preparing = async { f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL) }
        entered.await()
        f.integration.revokeBeforeOwnerChange(); f.ownerCurrent = false
        release.complete(Unit)
        assertIs<PortResult.Failure>(preparing.await())
        assertEquals(ids, f.ids); assertEquals(writes, f.session.localWriteStatements); assertTrue(f.calls.isEmpty())
        assertNull(f.entry.publications.states.value.review)
    } }

    @Test fun cancelledActualPreparationNeverAllocatesOrSendsALateDisclosure() = runTest { fixture { f ->
        f.compose(); value(f.experience.openPostDrafts()); val local = f.choices(f.local())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.observed.afterDisclosure = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        val ids = f.ids; val writes = f.session.localWriteStatements
        val preparing = async { f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL) }
        entered.await(); preparing.cancel(); release.complete(Unit); preparing.join()
        assertTrue(preparing.isCancelled)
        assertEquals(ids, f.ids); assertEquals(writes, f.session.localWriteStatements); assertTrue(f.calls.isEmpty())
        assertNull(f.entry.publications.states.value.review)
    } }

    @Test fun actualDirectReviewIsReadOnlyAndExplicitConfirmationPublishesSelfOnly() = runTest { fixture { f ->
        f.compose(); value(f.experience.openPostDrafts()); val local = f.choices(f.local(alt = ""))
        val writes = f.session.localWriteStatements; val ids = f.ids
        val review = value(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
        assertEquals(writes, f.session.localWriteStatements); assertEquals(ids, f.ids); assertTrue(f.calls.isEmpty())
        val result = value(f.entry.publications.confirmPublish(review.token))
        assertTrue(result.acknowledged)
        assertEquals(listOf("publishPost"), f.calls.map { it.operationId })
        val post = Json.parseToJsonElement(result.exactCanonicalPost!!.encodeUtf8().decodeToString()).jsonObject
        assertEquals(JsonPrimitive(""), post["altText"])
        assertEquals(JsonPrimitive(ProgressPostPreviewContract.authorId), post.getValue("author").jsonObject["userId"])
        assertEquals(JsonPrimitive(false), post.getValue("savePolicy").jsonObject["allowFutureSaves"])
    } }

    @Test fun actualPrivatePatchUsesSavedBaselineAndOnlyThenSavedPublication() = runTest { fixture { f ->
        f.compose(); value(f.experience.openPostDrafts()); val local = f.local()
        assertTrue(value(f.entry.drafts.saveExplicitly()).serverAcknowledged)
        val current = f.choices(f.entry.drafts.states.value.selected!!)
        val review = value(f.entry.prepareSelectedReviewedSave(current.clientDraftId, current.localRevision))
        assertEquals(listOf("createPostDraft"), f.calls.map { it.operationId })
        val saved = value(f.entry.drafts.confirmReviewedSave(review.token))
        assertTrue(saved.serverAcknowledged)
        val selected = saved.selected!!
        val publication = value(f.entry.prepareSelectedPublication(selected.clientDraftId, selected.localRevision, ReviewedPostBranch.SAVED_DRAFT))
        val published = value(f.entry.publications.confirmPublish(publication.token))
        assertTrue(published.acknowledged)
        assertEquals(listOf("createPostDraft", "updatePostDraft", "publishPost"), f.calls.map { it.operationId })
        assertNotNull(f.observed.saveCheck)
        assertNotNull(f.observed.publicationCheck)
        val wrongPurpose = f.observed.contexts.first { it.purpose == ReviewedPostPurpose.PUBLICATION }
        assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(
            f.integration.requireNewPrivateSave(wrongPurpose, f.observed.saveCheck!!)).reason)
        assertEquals(local.clientDraftId, selected.clientDraftId)
    } }

    @Test fun attemptedPrivateReplayDoesNotRequireTheExpiredOldBaselineOrAnotherGet() = runTest { fixture { f ->
        f.compose(); value(f.experience.openPostDrafts()); f.local()
        value(f.entry.drafts.saveExplicitly())
        val selected = f.choices(f.entry.drafts.states.value.selected!!)
        // The update occurs near the old expiry and renews the successor's lifetime.
        f.now += ProgressPostPreviewContract.lifetimeMillis - 30_000
        val fresh = value(f.entry.prepareSelectedReviewedSave(selected.clientDraftId, selected.localRevision))
        f.loseOperation = "updatePostDraft"
        value(f.entry.drafts.confirmReviewedSave(fresh.token))
        val original = f.calls.last(); val ids = f.ids; val newChecks = f.observed.newSaveChecks
        f.now += 60_000 // Old baseline expired; actual successor remains live.
        val retry = value(f.entry.drafts.prepareReviewedOriginalRetry())
        assertEquals(ReviewedDraftRetryKind.ATTEMPTED_ORIGINAL_REPLAY, retry.snapshot.kind)
        val result = value(f.entry.drafts.confirmReviewedOriginalRetry(retry.token))
        assertTrue(result.serverAcknowledged)
        assertEquals(ids, f.ids)
        assertEquals(original.idempotencyKey!!.use { it }, f.calls.last().idempotencyKey!!.use { it })
        assertContentEquals(original.body!!.copyForCodec(), f.calls.last().body!!.copyForCodec())
        assertEquals(original.ifMatch, f.calls.last().ifMatch)
        assertNotNull(f.observed.saveReplay)
        assertEquals(newChecks, f.observed.newSaveChecks) // GET is exclusively in the NEW callback.
    } }

    @Test fun attemptedPublicationReplayKeepsExactOriginalAndDoesNotBecomeNewAdmission() = runTest { fixture { f ->
        f.compose(); value(f.experience.openPostDrafts()); val local = f.choices(f.local())
        val review = value(f.entry.prepareSelectedPublication(local.clientDraftId, local.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
        f.loseOperation = "publishPost"
        value(f.entry.publications.confirmPublish(review.token))
        val original = f.calls.single(); val ids = f.ids; val newChecks = f.observed.newPublicationChecks
        f.now += 60_000
        val retry = value(f.entry.publications.prepareOriginalRetry()).retry!!
        val result = value(f.entry.publications.retryOriginal(retry.token))
        assertTrue(result.acknowledged)
        assertEquals(ids, f.ids); assertEquals(2, f.calls.size)
        assertEquals(original.idempotencyKey!!.use { it }, f.calls.last().idempotencyKey!!.use { it })
        assertContentEquals(original.body!!.copyForCodec(), f.calls.last().body!!.copyForCodec())
        assertNotNull(f.observed.publicationReplay)
        assertEquals(newChecks, f.observed.newPublicationChecks)
    } }

    @Test fun freshPrivateSaveStillRefusesExpiredActualBaselineBeforeAllocation() = runTest { fixture { f ->
        f.compose(); value(f.experience.openPostDrafts()); f.local(); value(f.entry.drafts.saveExplicitly())
        val local = f.choices(f.entry.drafts.states.value.selected!!)
        val ids = f.ids; val calls = f.calls.size; val writes = f.session.localWriteStatements
        f.now += ProgressPostPreviewContract.lifetimeMillis + 1
        assertIs<PortResult.Failure>(f.entry.prepareSelectedReviewedSave(local.clientDraftId, local.localRevision))
        assertEquals(ids, f.ids); assertEquals(calls, f.calls.size); assertEquals(writes, f.session.localWriteStatements)
    } }

    @Test fun declaredSelfOnlyChoicesRejectUnsupportedOrAmbiguousValuesWithoutCoercion() {
        val good = selection()
        for (replacement in listOf(
            "mediaIds" to JsonArray(listOf(JsonPrimitive(UUID.randomUUID().toString()))),
            "audience" to buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(listOf(JsonPrimitive(UUID.randomUUID().toString())))) },
            "keepOnPlate" to JsonPrimitive("false"), "allowRecipeSaves" to JsonPrimitive(true),
            "allowRecipeSaves" to JsonPrimitive("false"), "altText" to JsonNull,
            "attachment" to JsonNull, "sourcePostId" to JsonPrimitive(UUID.randomUUID().toString()),
            "saveDisclosureVersion" to JsonPrimitive("unknown"), "unknown" to JsonPrimitive(false))) {
            assertFails { ProgressReviewedPostChecks.validateSelection(JsonObject(good + replacement), true) }
        }
        ProgressReviewedPostChecks.validateSelection(good, true)
        ProgressReviewedPostChecks.validateSelection(JsonObject(good + ("keepOnPlate" to JsonPrimitive(true))), true)
        ProgressReviewedPostChecks.validateSelection(JsonObject(good + ("altText" to JsonPrimitive(""))), true)
    }

    @Test fun disclosureRequiresExactVersionAndTextWithNoAcceptingFallback() {
        ProgressReviewedPostChecks.disclosure(disclosure(), true)
        ProgressReviewedPostChecks.disclosure(null, false)
        assertFails { ProgressReviewedPostChecks.disclosure(null, true) }
        assertFails { ProgressReviewedPostChecks.disclosure(PublicationDisclosure("foreign", disclosure().text), true) }
        assertFails { ProgressReviewedPostChecks.disclosure(PublicationDisclosure(disclosure().version, "Another legal statement"), true) }
    }

    @Test fun identityRejectsLookalikeScopeTokenExpiryAndDeviceWithoutCanonicalInference() = runTest {
        val actual = credentials()
        assertTrue(ProgressIdentity.matches(actual))
        val bad = listOf(
            StoredCredentials.Account(actual.scope, SecretText("lookalike"), actual.refreshToken, actual.expiresAtMillis, actual.deviceSessionId),
            StoredCredentials.Account(actual.scope, actual.accessToken, actual.refreshToken, 1, actual.deviceSessionId),
            StoredCredentials.Account(actual.scope, actual.accessToken, actual.refreshToken, actual.expiresAtMillis, SecretText(UUID.randomUUID().toString())),
            StoredCredentials.Account(StorageScope(actual.scope.environment, ActorKind.ACCOUNT, ProgressPostPreviewContract.authorId),
                actual.accessToken, actual.refreshToken, actual.expiresAtMillis, actual.deviceSessionId))
        bad.forEach { assertFalse(ProgressIdentity.matches(it)) }
        assertFalse(ProgressIdentity.matches(null))
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = PostDraftHttpSessionFixture.open(dispatcher, credentials())
        val f = Fixture(session, dispatcher)
        try { withContext(dispatcher) { value(f.service.open(true)); block(f) }; session.requireNoNativeWork() }
        finally { withContext(NonCancellable + dispatcher) { f.close(); session.close() } }
    }
    private class Fixture(val session: PostDraftHttpSessionFixture, val dispatcher: CoroutineDispatcher) {
        var now = 1_800_000_000_000L; var ownerCurrent = true; var builders = 0; var ids = 0
        val service = ProgressCanonicalService(session.access, session.boundary, dispatcher, EpochClock { now })
        val calls = mutableListOf<ApiCall>(); var loseOperation: String? = null
        val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call
                val result = service.execute(lease, call)
                return if (call.operationId == loseOperation && result is PortResult.Value && result.value.status in 200..299) {
                    loseOperation = null; PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
                } else result
            }
        }
        lateinit var experience: MealFlowExperience; lateinit var integration: ProgressReviewedPostIntegration
        lateinit var observed: ObservedIntegration
        val entry get() = experience.reviewedPosts!!
        fun createIntegration(actual: AuthenticatedMealPlanningAccess, access: PrivateSessionAccess = session.access,
            boundary: SessionBoundary = session.boundary): ProgressReviewedPostIntegration =
            ProgressReviewedPostIntegration(access, actual, boundary, service) { ownerCurrent }.also { integration = it }
        fun compose(build: (AuthenticatedMealPlanningAccess) -> ProgressReviewedPostIntegration = { createIntegration(it) }): MealFlowExperience {
            experience = MealFlowExperience.fromSessionWithReviewedPosts(session.access, transport, session.boundary, dispatcher,
                EpochClock { now }, ConnectivityPort { Connectivity.ONLINE }, MealOperationIds { ids++; UUID.randomUUID().toString() },
                MealFlowPolicy(86_400_000, 60_000, 4, 32), IngredientPickerPolicy(2, 2, 20, 86_400_000),
                MealInputChoices(emptyList(), emptyList()), KitchenInputPolicy(2, 2, 20, 8, 65_536),
                CookingFlowPolicy(60_000, 65_536, 65_536), CookbookPolicy(2, 60_000),
                ProgressReviewedPostPolicies.drafts, ProgressReviewedPostPolicies.publications,
                integrationForAccess = { actual -> builders++; ObservedIntegration(build(actual)).also { observed = it } })
            return experience
        }
        suspend fun local(alt: String? = null) = value(entry.drafts.newLocalDraft("Synthetic progress dinner", alt)).selected!!
        suspend fun choices(local: LocalPostDraft): LocalPostDraft = value(entry.editChoices(local.clientDraftId, local.localRevision,
            ReviewedPostChoices(local.caption, local.altText?.let { OptionalValue.Present(it) } ?: OptionalValue.Absent,
                emptyList(), PublicationAudience.OnlyYou, false, OptionalValue.Absent, false, disclosure(), OptionalValue.Absent))).selected!!
        suspend fun close() {
            if (::integration.isInitialized) integration.revokeBeforeOwnerChange()
            ownerCurrent = false
            if (::experience.isInitialized) value(experience.close())
            if (::integration.isInitialized) integration.close()
            value(service.close())
        }
    }
    private class ObservedIntegration(val actual: ProgressReviewedPostIntegration) : ReviewedKitchenIntegration by actual {
        val contexts = mutableListOf<ReviewedPostPrerequisiteContext>()
        var saveCheck: ReviewedPrivateSaveCheck? = null; var saveReplay: ReviewedPrivateSaveReplayCheck? = null
        var publicationCheck: ReviewedPublicationCheck? = null; var publicationReplay: ReviewedPublicationReplayCheck? = null
        var newSaveChecks = 0; var newPublicationChecks = 0
        var afterDisclosure: suspend () -> Unit = {}
        override suspend fun disclosure(context: ReviewedPostPrerequisiteContext): PortResult<PublicationDisclosure> {
            contexts += context; val result = actual.disclosure(context); afterDisclosure(); return result
        }
        override suspend fun requireNewPrivateSave(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveCheck): PortResult<Unit> {
            contexts += context; saveCheck = check; newSaveChecks++; return actual.requireNewPrivateSave(context, check)
        }
        override suspend fun requirePrivateSaveReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveReplayCheck): PortResult<Unit> {
            contexts += context; saveReplay = check; return actual.requirePrivateSaveReplay(context, check)
        }
        override suspend fun requireNewPublication(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationCheck): PortResult<Unit> {
            contexts += context; publicationCheck = check; newPublicationChecks++; return actual.requireNewPublication(context, check)
        }
        override suspend fun requirePublicationReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationReplayCheck): PortResult<Unit> {
            contexts += context; publicationReplay = check; return actual.requirePublicationReplay(context, check)
        }
    }
    companion object {
        private suspend fun credentials() = value(ProgressIdentity.acquire()) as StoredCredentials.Account
        private fun disclosure() = PublicationDisclosure(ProgressPostPreviewContract.disclosureVersion, ProgressPostPreviewContract.disclosureText)
        private fun selection() = buildJsonObject {
            put("caption", "Exact caption"); put("mediaIds", JsonArray(emptyList()))
            put("audience", buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) })
            put("keepOnPlate", false); put("allowRecipeSaves", false); put("saveDisclosureVersion", ProgressPostPreviewContract.disclosureVersion)
        }
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    }
}
