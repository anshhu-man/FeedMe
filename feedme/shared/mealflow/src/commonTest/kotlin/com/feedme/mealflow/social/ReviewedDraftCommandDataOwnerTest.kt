package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure-data owner delegation over actual composition; no transport response or consent fixture. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReviewedDraftCommandDataOwnerTest {
    @Test fun configuredDelegationPreservesExactOriginalAndReviewBytesWithoutIoOrAdmission() = runTest {
        val f = Fixture(this)
        try {
            val original = f.original(); val snapshot = f.snapshot()
            val result = f.owner.reviewedCommandData(original, snapshot, f.disclosure)
            assertSame(original, result.original)
            assertContentEquals(f.rawPatch.copyForCodec(), result.historicalCall().body!!.copyForCodec())
            assertEquals(mapOf("draftId" to SERVER.uppercase()), result.historicalCall().pathParameters)
            assertTrue(result.historicalCall().queryParameters.isEmpty())
            assertEquals(COMMAND, result.historicalCall().idempotencyKey!!.use { it })
            assertEquals(ETAG, result.historicalCall().ifMatch)
            assertEquals(CREATED, result.created)
            assertContentEquals(snapshot.exactUtf8.copyForCodec(), result.historicalReview.exactReviewedLocalSnapshot.exactUtf8.copyForCodec())
            assertEquals("Updated caption", postString(result.expectedFields, "caption"))
            assertEquals("", postString(result.expectedFields, "altText"))
            assertEquals("9007199254740994", postVersion(result.expectedFields))
            assertEquals(f.disclosure.text, result.historicalReview.displayedDisclosure!!.text)
            assertFalse("updatedAt" in result.expectedFields.json().jsonObject)
            assertFalse("expiresAt" in result.expectedFields.json().jsonObject)
            f.noIo(); assertEquals(0, f.checks)
        } finally { f.close() }
    }

    @Test fun unconfiguredOwnerDeniesWithoutReadingClaimingOrWriting() = runTest {
        val f = Fixture(this, configured = false)
        try {
            failure(FailureReason.NOT_CONFIGURED) { f.owner.reviewedCommandData(f.original(), f.snapshot(), f.disclosure) }
            f.noIo(); assertEquals(0, f.checks)
        } finally { f.close() }
    }

    @Test fun mismatchedPathEtagClientRevisionTextAndForgedSnapshotCannotPassDelegation() = runTest {
        val f = Fixture(this)
        try {
            val original = f.original(); val snapshot = f.snapshot(); val exact = snapshot.exactUtf8.copyForCodec()
            for (invalid in listOf(f.original(path = mapOf("draftId" to OTHER)), f.original(etag = "\"1\""),
                f.original(client = OTHER), f.original(revision = 4)))
                failure(FailureReason.INVALID_DATA) { f.owner.reviewedCommandData(invalid, snapshot, f.disclosure) }
            val wrongText = f.snapshots.create(CLIENT, 3, DraftLocalContentV1.TextV1("Unreviewed", ""), snapshot.serverAssociation)
            val forged = DraftLocalSnapshotV1(snapshot.exactUtf8, OTHER, snapshot.localRevision, snapshot.content, snapshot.serverAssociation)
            for (invalid in listOf(wrongText, forged))
                failure(FailureReason.INVALID_DATA) { f.owner.reviewedCommandData(original, invalid, f.disclosure) }
            assertContentEquals(exact, snapshot.exactUtf8.copyForCodec())
            assertContentEquals(f.rawPatch.copyForCodec(), original.body.copyForCodec())
            f.noIo(); assertEquals(0, f.checks)
        } finally { f.close() }
    }

    @Test fun disclosureMustMatchActualChangedVersionAndAbsenceIsNotFilledByDefault() = runTest {
        val f = Fixture(this)
        try {
            failure(FailureReason.INVALID_DATA) { f.owner.reviewedCommandData(f.original(), f.snapshot(), null) }
            failure(FailureReason.INVALID_DATA) { f.owner.reviewedCommandData(f.original(), f.snapshot(), PublicationDisclosure("wrong", "Other displayed text")) }
            val unchanged = f.original(body = PrivateBytes(" {\"caption\":\"Updated caption\",\"altText\":\"\"} \n".encodeToByteArray()))
            failure(FailureReason.INVALID_DATA) { f.owner.reviewedCommandData(unchanged, f.snapshot(), f.disclosure) }
            val historical = f.owner.reviewedCommandData(unchanged, f.snapshot(), null)
            assertNull(historical.historicalReview.displayedDisclosure)
            assertFalse("saveDisclosureVersion" in historical.expectedFields.json().jsonObject)
            f.noIo(); assertEquals(0, f.checks)
        } finally { f.close() }
    }

    @Test fun oversizedInvalidUtf8IsBoundedBeforeParsingAndDoesNotChangeInputsOrState() = runTest {
        val f = Fixture(this)
        try {
            val oversized = PrivateBytes(ByteArray(f.publicationPolicy.maxOriginalBytes + 1) { 0xff.toByte() })
            val original = f.original(body = oversized)
            failure(FailureReason.UNAVAILABLE) { f.owner.reviewedCommandData(original, f.snapshot(), f.disclosure) }
            assertContentEquals(oversized.copyForCodec(), original.body.copyForCodec())
            f.noIo(); assertEquals(0, f.checks)
        } finally { f.close() }
    }

    @Test fun validatedCommandDataNeitherClaimsNamespaceNorRegistersACommitMutation() = runTest {
        val f = Fixture(this)
        try {
            val command = f.owner.reviewedCommandData(f.original(), f.snapshot(), f.disclosure)
            val borrower = f.composition.bind(MealKitchenFeature.POST_DRAFTS, object : MealKitchenHooks {
                override suspend fun checkCurrent() { f.checks++ }
            })
            val participant = f.owner.register(borrower)
            // A second real owner can still claim the namespace; pure data construction did not.
            val second = PostDraftJournalOwner(f.composition, f.policy, f.publicationPolicy)
            val otherParticipant = second.register(borrower)
            f.composition.operate(borrower) {
                val use = second.enter(f.composition.composerPermit(borrower), otherParticipant)
                second.leave(use)
            }
            second.release(otherParticipant)
            f.composition.operate(borrower) {
                val use = f.owner.enter(f.composition.composerPermit(borrower), participant)
                try {
                    val value = PostDraftV2Record(CREATED, listOf(f.snapshot()), listOf(CLIENT, COMMAND), command)
                    f.owner.preflightCurrentData(value)
                    val merelyEncoded = StoreMutation.Put(RecordKey("mealflow.post-drafts.v1", ORIGIN), null, 2, f.codec.encode(value))
                    failure(FailureReason.CONFLICT) { f.owner.beforeCurrentCommit(use, listOf(merelyEncoded)) }
                } finally { f.owner.leave(use) }
            }
            f.owner.release(participant); f.composition.release(borrower)
            f.noIo()
        } finally { f.close() }
    }

    private class Fixture(test: TestScope, configured: Boolean = true) {
        val policy = PostDraftClientPolicy(8, 1_048_576, 262_144, 64, 64, 2, 100, 60_000)
        val publicationPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 8, 32, 8, 8, 8, 8, 65_536, 4096, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(policy, publicationPolicy)
        val links = PublicationOriginalLinkCodecV1(publicationPolicy, snapshots)
        val codec = PostDraftV2Codec("synthetic-owner-command-data", ORIGIN, policy, publicationPolicy, snapshots, links)
        val scope = StorageScope("synthetic-owner-command-data", ActorKind.ACCOUNT, "opaque-private-account")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val store = PostDraftControllerTest.Store(scope)
        var calls = 0; var checks = 0
        val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls++; return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
        }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, transport, true)
        val composition = MealKitchenComposition(access, boundary, StandardTestDispatcher(test.testScheduler), EpochClock { CREATED }, ConnectivityPort { Connectivity.ONLINE })
        val owner = PostDraftJournalOwner(composition, policy, if (configured) publicationPolicy else null)
        val disclosure = PublicationDisclosure("actual-v3", "Actual displayed disclosure data")
        val baseline: WireDocument = WireDocument.parse(" \n" + document(draft(CLIENT, "Baseline", "9007199254740993").json().jsonObject +
            ("id" to JsonPrimitive(SERVER))).encodeUtf8().decodeToString() + "\n")
        val rawPatch = PrivateBytes(" { \"caption\" : \"Updated caption\", \"altText\" : \"\", \"saveDisclosureVersion\" : \"actual-v3\", \"keepOnPlate\" : true }\n".encodeToByteArray())
        fun snapshot() = snapshots.create(CLIENT, 3, DraftLocalContentV1.TextV1("Updated caption", ""), DraftServerAssociationV1.Observed(baseline, ETAG))
        fun original(path: Map<String, String> = mapOf("draftId" to SERVER.uppercase()), etag: String = ETAG,
            client: String = CLIENT, revision: Long = 3, body: PrivateBytes = rawPatch) =
            ReviewedDraftOriginalFieldsV2(COMMAND, client, revision, path, body, baseline, etag, CREATED)
        fun noIo() { assertEquals(0, store.reads); assertEquals(0, store.writes); assertEquals(0, store.erases); assertEquals(0, calls); assertTrue(store.records.isEmpty()) }
        fun close() { boundary.clear() }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000081"
        const val CLIENT = "00000000-0000-4000-8000-000000000082"
        const val COMMAND = "00000000-0000-4000-8000-000000000084"
        const val OTHER = "00000000-0000-4000-8000-000000000089"
        const val SERVER = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        const val ETAG = "\"9007199254740993\""
        const val CREATED = 1_800_000_000_000L
        fun failure(reason: FailureReason, action: () -> Unit) = assertEquals(reason, assertFailsWith<MealFailure>(block = action).reason)
    }
}
