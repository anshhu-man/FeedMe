package com.feedme.server.social.posts

import com.feedme.server.db.*
import com.feedme.server.media.*
import com.feedme.server.media.processing.*
import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.drafts.*
import java.sql.SQLException
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Actual SQL lifecycle/command/outbox/media operations. External authority/providers are
 * explicit synthetic fixture dependencies, not evidence of live publishing or social feed. */
class PostPublicationStoreIntegrationTest {
    @Test fun directTextPublicationHasOneCanonicalPostAndNoImplicitSavedDraft() {
        val f=f();val input=f.body();val post=f.publish(input)
        assertEquals("published",post.text("status"));assertEquals("1",post.text("version"));assertEquals(input["caption"],post["caption"])
        assertEquals(86400,Duration.between(Instant.parse(post.text("publishedAt")),Instant.parse(post.text("expiresAt"))).seconds)
        assertEquals(JsonArray(emptyList()),post["reactionCounts"]);assertEquals(JsonArray(emptyList()),post.getValue("audience").jsonObject["bindings"])
        assertEquals(0,f.count("platform.post_drafts"));assertEquals(0,f.count("platform.media_assets"));assertEquals(1,f.count("social.posts"));assertEquals(1,f.count("social.post_publications"))
        assertEquals(1,f.count("social.recipe_save_policies"));assertEquals(1,f.count("platform.idempotency"));assertEquals(1,f.count("platform.outbox"))
        val event=Json.parseToJsonElement(f.value("SELECT payload::text FROM platform.outbox")).jsonObject
        assertEquals(setOf("postId","authorUserId","aclVersion","expiresAt"),event.keys);assertEquals(post["id"],event["postId"])
        assertEquals("social.post.published.v1",f.value("SELECT event_type FROM platform.outbox"));assertFalse(event.toString().contains("Synthetic dinner"))
    }
    @Test fun exactOriginalKeyReplaysWithoutNewPostAudienceOrEvent() {
        val f=f();val input=f.body();val key=UUID.randomUUID();val first=reply(f.store.publishPost(f.account,key,input))
        val before=f.value("SELECT row_to_json(p)::text FROM social.posts p")
        val again=assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input)).reply
        assertEquals(first.body,again.body);assertEquals(first.etag,again.etag);assertEquals(201,again.status)
        assertEquals(before,f.value("SELECT row_to_json(p)::text FROM social.posts p"));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun changedOriginalBodyAndNewKeyCannotRepublishSameClientRoot() {
        val f=f();val input=f.body();val key=UUID.randomUUID();f.publish(input,key)
        assertIs<CommandResult.Mismatch>(f.store.publishPost(f.account,key,JsonObject(input+("caption" to JsonPrimitive("changed")))))
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(input) }
        assertEquals(1,f.count("platform.idempotency"));assertEquals(1,f.count("social.posts"))
    }
    @Test fun exactSavedRevisionIsMarkedPublishedAtomicallyWithoutEditingItsContent() {
        val f=f();val input=f.saved(f.body());val id=UUID.fromString(input.text("draftId"))
        val before=f.value("SELECT content::text FROM platform.post_drafts WHERE id='$id'");val key=UUID.randomUUID();val post=f.publish(input,key)
        assertEquals(before,f.value("SELECT content::text FROM platform.post_drafts WHERE id='$id'"))
        assertEquals(post.text("id"),f.value("SELECT published_post_id::text FROM platform.post_drafts WHERE id='$id'"));assertEquals("2",f.value("SELECT version FROM platform.post_drafts WHERE id='$id'"))
        assertEquals("published",f.value("SELECT status FROM platform.post_drafts WHERE id='$id'"));assertEquals("3",f.value("SELECT revision FROM platform.post_draft_heads"))
        assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input))
        assertEquals(2,f.count("platform.outbox"));assertEquals(1,f.count("platform.outbox WHERE event_type='social.post_draft.changed.v1'"))
        assertEquals("published",f.drafts.store.getPostDraft(f.account,id).body!!.jsonObject.text("status"))
    }
    @Test fun savedDraftCannotBeBypassedByOmittingItsPair() {
        val f=f();val input=f.body();f.drafts.create(input)
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(input) }
        assertEquals(0,f.count("social.posts"));assertEquals(1,f.count("platform.idempotency"))
    }
    @Test fun bothHalfPairsAreSemantic422WithoutAnyCommandOrRoot() {
        val f=f();val body=f.body()
        for(pair in listOf("draftId" to JsonPrimitive(UUID.randomUUID().toString()),"draftVersion" to JsonPrimitive(1)))
            denied(PostPublicationFailureCode.INPUT_INVALID) { f.publish(JsonObject(body+pair)) }
        assertEquals(0,f.count("platform.idempotency"));assertEquals(0,f.count("platform.media_draft_lifecycles"));assertEquals(0,f.count("social.posts"))
    }
    @Test fun savedPublicationRejectsChangedVersionAndExactOptionalFieldMismatch() {
        val f=f();val input=f.saved(f.body())
        denied(PostPublicationFailureCode.VERSION_CONFLICT) { f.publish(JsonObject(input+("draftVersion" to JsonPrimitive(2)))) }
        for(change in listOf("caption" to JsonPrimitive("changed"),"altText" to JsonPrimitive(""),"keepOnPlate" to JsonPrimitive(true),"sourcePostId" to JsonPrimitive(UUID.randomUUID().toString())))
            denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(JsonObject(input+change)) }
        assertEquals(0,f.count("social.posts"));assertEquals("draft",f.value("SELECT status FROM platform.post_drafts"))
    }
    @Test fun missingSavedDisclosureCannotBeInferredAtPublication() {
        val f=f();val input=f.body();val draft=f.drafts.create(JsonObject(input.filterKeys { it!="saveDisclosureVersion" }))
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(JsonObject(input+mapOf("draftId" to draft.getValue("id"),"draftVersion" to draft.getValue("version")))) }
        assertEquals(0,f.count("social.posts"))
    }
    @Test fun foreignMissingExpiredAndDiscardedDraftsDoNotGrantPublication() {
        val f=f();val input=f.saved(f.body());val id=UUID.fromString(input.text("draftId"))
        denied(PostPublicationFailureCode.DRAFT_UNAVAILABLE) { f.publish(JsonObject(input+("draftId" to JsonPrimitive(UUID.randomUUID().toString())))) }
        val other=f.principal();denied(PostPublicationFailureCode.DRAFT_UNAVAILABLE) { f.publish(input,actor=other) }
        f.drafts.expire(id);denied(PostPublicationFailureCode.DRAFT_EXPIRED) { f.publish(input) }
        f.drafts.store.deletePostDraft(f.account,UUID.randomUUID(),id,"\"1\"")
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(JsonObject(input.filterKeys { it !in setOf("draftId","draftVersion") })) }
        assertEquals(0,f.count("social.posts"))
    }
    @Test fun currentDeviceTermsAndUnavailablePublishingRemainClosed() {
        val f=f();val input=f.body();f.authority.enabled=false
        denied(PostPublicationFailureCode.NOT_CONFIGURED) { f.publish(input) };f.authority.enabled=true;f.authority.terms=false
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input) };f.authority.terms=true
        denied(PostPublicationFailureCode.UNAUTHENTICATED) { f.publish(input,actor=VerifiedSocialAccount("other",f.account.accountId,f.account.deviceSessionId)) }
        val key=UUID.randomUUID();f.publish(input,key);f.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='${f.account.deviceSessionId}'")
        denied(PostPublicationFailureCode.UNAUTHENTICATED) { f.publish(input,key) };assertEquals(1,f.count("social.posts"))
    }
    @Test fun audienceBindingsAreStampedFromCurrentMembershipAndDoNotResurrectOnRejoin() {
        val f=f();val circle=f.grantCircle(generation=7)
        val audience=buildJsonObject { put("kind","circles");put("circleIds",arr(circle));put("bindings",buildJsonArray { add(buildJsonObject { put("circleId",circle.toString());put("authorMembershipGeneration",999) }) }) }
        val input=JsonObject(f.body()+("audience" to audience));val key=UUID.randomUUID();val post=f.publish(input,key)
        assertEquals("7",post.getValue("audience").jsonObject.getValue("bindings").jsonArray.single().jsonObject.text("authorMembershipGeneration"))
        assertEquals("7",f.value("SELECT author_membership_generation FROM social.post_audiences"))
        f.sql("UPDATE post_publication_test.memberships SET generation=8 WHERE circle_id='$circle'")
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input,key) };assertEquals(1,f.count("social.post_audiences"))
    }
    @Test fun attachmentRecipeAndSavePermissionCommitExactReviewedMaterial() {
        val f=f();val attachment=f.grantRecipe();val input=JsonObject(f.body()+mapOf("attachment" to attachment,"allowRecipeSaves" to JsonPrimitive(true)))
        val key=UUID.randomUUID();val post=f.publish(input,key)
        assertEquals(attachment,post["attachment"]);assertEquals(JsonPrimitive(true),post.getValue("savePolicy").jsonObject["allowFutureSaves"])
        assertEquals(1,f.count("social.post_attachments"));assertEquals("true",f.value("SELECT allow_future_saves::text FROM social.recipe_save_policies"))
        assertEquals(f.value("SELECT recipe_sha256 FROM social.post_attachments"),f.value("SELECT recipe_sha256 FROM social.recipe_save_policies"))
        assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input))
        f.sql("UPDATE post_publication_test.recipes SET active=false")
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input,key) }
    }
    @Test fun noAttachmentCannotGrantSavesAndFalseReviewStringsAreNotAuthority() {
        val f=f();denied(PostPublicationFailureCode.INPUT_INVALID) { f.publish(JsonObject(f.body()+("allowRecipeSaves" to JsonPrimitive(true)))) }
        val attachment=f.grantRecipe();val forged=JsonObject(attachment+("confirmedChanges" to buildJsonArray { add("not actually reviewed") }))
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(JsonObject(f.body()+("attachment" to forged))) }
        assertEquals(0,f.count("social.posts"))
    }
    @Test fun noncanonicalOrWrongVersionRecipeEvidenceCannotBecomeAnAttachment() {
        val f=f();val attachment=f.grantRecipe();val input=JsonObject(f.body()+("attachment" to attachment))
        f.authority.transformEvidence={ e->PostPublicationEvidence(e.author,e.memberships,e.verifiedAttachment,buildJsonObject { put("not","a recipe") },e.capabilities,e.validUntil) }
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input) }
        f.authority.transformEvidence={ e->PostPublicationEvidence(e.author,e.memberships,e.verifiedAttachment,JsonObject(checkNotNull(e.recipeSnapshot)+("id" to JsonPrimitive(UUID.randomUUID().toString()))),e.capabilities,e.validUntil) }
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input) };assertEquals(0,f.count("social.post_attachments"))
    }
    @Test fun originalRecipeHashIsPinnedOutsideMutableSavePolicyAndSnapshotRows() {
        val f=f();val attachment=f.grantRecipe();val input=JsonObject(f.body()+("attachment" to attachment));val key=UUID.randomUUID();f.publish(input,key)
        assertFailsWith<SQLException> { f.sql("UPDATE social.post_attachments SET recipe_snapshot=jsonb_set(recipe_snapshot,'{title}','\"substituted\"')") }
        // Deliberate corruption injection bypasses only the new attachment trigger. Even rewriting
        // both current hashes cannot replace the original publication's independent immutable pin.
        val replacement=JsonObject(Json.parseToJsonElement(f.value("SELECT recipe_snapshot::text FROM social.post_attachments")).jsonObject+("title" to JsonPrimitive("substituted")))
        val digest=MessageDigest.getInstance("SHA-256").digest(canonical(replacement).encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        f.sql("BEGIN; ALTER TABLE social.post_attachments DISABLE TRIGGER original_publication_attachment; UPDATE social.post_attachments SET recipe_snapshot=jsonb_set(recipe_snapshot,'{title}','\"substituted\"'),recipe_sha256='$digest'; UPDATE social.recipe_save_policies SET recipe_sha256='$digest'; ALTER TABLE social.post_attachments ENABLE TRIGGER original_publication_attachment; COMMIT")
        denied(PostPublicationFailureCode.STORAGE_UNAVAILABLE) { f.publish(input,key) }
        assertEquals(1,f.count("social.post_publications"))
    }
    @Test fun directReadyPhotoUsesActualTwoImmutableAcknowledgedDerivativeIntents() {
        val f=f();val client=f.client();val photo=f.readyPhoto(client);val input=JsonObject(f.body(client)+("mediaIds" to arr(photo)));val key=UUID.randomUUID()
        val post=f.publish(input,key);assertEquals(arr(photo),post["mediaIds"]);assertEquals(1,f.count("social.post_media"));assertEquals(2,f.count("platform.media_derivative_intents"))
        assertEquals(0,f.count("social.post_publication_discard_media"));assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input))
        val failure=assertFailsWith<MediaFailure> { f.drafts.mediaStore.deleteDraftMedia(PostPublicationTestFixture.mediaActor(f.account),UUID.randomUUID(),photo,"\"3\"") }
        assertEquals(MediaFailureCode.MEDIA_ATTACHED,failure.code);assertEquals("ready",f.value("SELECT state FROM platform.media_assets"))
    }
    @Test fun pendingRejectedOrSyntheticReadyStringCannotServeAsPublishProof() {
        val f=f();val client=f.client();val prepared=f.drafts.prepare(client);val id=PostDraftTestFixture.id(prepared);val input=JsonObject(f.body(client)+("mediaIds" to arr(id)))
        denied(PostPublicationFailureCode.MEDIA_UNAVAILABLE) { f.publish(input) }
        val upload=f.mediaFixture.upload(prepared);f.drafts.mediaStore.completeMediaUpload(PostPublicationTestFixture.mediaActor(f.account),UUID.randomUUID(),id,upload)
        denied(PostPublicationFailureCode.MEDIA_UNAVAILABLE) { f.publish(input) }
        f.mediaFixture.finishProcessing(prepared,"ready")
        denied(PostPublicationFailureCode.MEDIA_UNAVAILABLE) { f.publish(input) }
        val rejectedClient=f.client();val rejected=f.drafts.prepare(rejectedClient);val rejectedId=PostDraftTestFixture.id(rejected)
        f.drafts.mediaStore.completeMediaUpload(PostPublicationTestFixture.mediaActor(f.account),UUID.randomUUID(),rejectedId,f.mediaFixture.upload(rejected))
        f.mediaFixture.finishProcessing(rejected,"rejected")
        denied(PostPublicationFailureCode.MEDIA_UNAVAILABLE) { f.publish(JsonObject(f.body(rejectedClient)+("mediaIds" to arr(rejectedId)))) }
        assertEquals(0,f.count("social.posts"))
    }
    @Test fun duplicateForeignAndWrongRootSelectedPhotosRollbackEverything() {
        val f=f();val client=f.client();val photo=f.readyPhoto(client)
        denied(PostPublicationFailureCode.INPUT_INVALID) { f.publish(JsonObject(f.body(client)+("mediaIds" to arr(photo,photo)))) }
        denied(PostPublicationFailureCode.MEDIA_UNAVAILABLE) { f.publish(JsonObject(f.body()+ ("mediaIds" to arr(photo)))) }
        val other=f.principal();denied(PostPublicationFailureCode.MEDIA_UNAVAILABLE) { f.publish(JsonObject(f.body(f.client(other))+("mediaIds" to arr(photo))),actor=other) }
        assertEquals(0,f.count("social.post_media"));assertEquals(0,f.count("social.posts"))
    }
    @Test fun unusedUploadsAreDiscardedWithoutDeletingSelectedReadyPhotos() {
        val f=f();val client=f.client();val photo=f.readyPhoto(client);val unused=PostDraftTestFixture.id(f.drafts.prepare(client))
        val input=JsonObject(f.body(client)+("mediaIds" to arr(photo)));val key=UUID.randomUUID();f.publish(input,key)
        assertEquals("ready",f.value("SELECT state FROM platform.media_assets WHERE id='$photo'"));assertEquals("deleted",f.value("SELECT state FROM platform.media_assets WHERE id='$unused'"))
        assertEquals(1,f.count("social.post_publication_discard_media"));assertEquals(1,f.count("platform.media_cleanup_jobs"))
        assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input));assertEquals(1,f.count("social.post_media"))
        val late=assertFailsWith<MediaFailure> { f.drafts.prepare(client) };assertEquals(MediaFailureCode.DRAFT_UNAVAILABLE,late.code)
        assertEquals(PostDraftFailureCode.DRAFT_CONFLICT,assertFailsWith<PostDraftFailure> { f.drafts.create(f.drafts.body(client)) }.code)
    }
    @Test fun textOnlyPublicationClosesRootToLaterUploadDraftCreationAndNewPublication() {
        val f=f();val client=f.client();val input=f.body(client);f.publish(input)
        assertEquals(MediaFailureCode.DRAFT_UNAVAILABLE,assertFailsWith<MediaFailure> { f.drafts.prepare(client) }.code)
        assertEquals(PostDraftFailureCode.DRAFT_CONFLICT,assertFailsWith<PostDraftFailure> { f.drafts.create(f.drafts.body(client)) }.code)
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(input) };assertEquals(0,f.count("platform.media_assets"))
    }
    @Test fun outstandingProcessingLeaseCannotWriteAfterPublicationDiscardsItsUpload() {
        val f=f();val client=f.client();val event=f.processing.seed(clientDraftId=client);f.processing.store.ingest(event);val lease=assertNotNull(f.processing.store.claim())
        val input=f.body(client);val key=UUID.randomUUID();f.publish(input,key)
        assertEquals("cancelled",f.value("SELECT state FROM platform.media_processing_jobs"))
        assertFailsWith<MediaProcessingFailure> { f.processing.store.prepareDerivatives(lease,(f.processing.codec.result as PhotoDecodeResult.Decoded).variants) }
        assertEquals(0,f.count("platform.media_derivative_intents"));assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input))
    }
    @Test fun lateUploadEventAddsOnlyCancelledJobAndPreservesOriginalCleanupReplay() {
        val f=f();val client=f.client();val event=f.processing.seed(clientDraftId=client);val input=f.body(client);val key=UUID.randomUUID();f.publish(input,key)
        assertEquals(0,f.count("platform.media_processing_jobs"));f.processing.store.ingest(event)
        assertEquals("cancelled",f.value("SELECT state FROM platform.media_processing_jobs"));assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input))
        assertEquals(0,f.count("platform.media_derivative_intents"));assertNull(f.processing.store.claim())
    }
    @Test fun responseBudgetAndOutboxFailureRollbackPostIdentityAndDraftTransition() {
        val f=f();val input=f.saved(f.body());val before=f.value("SELECT row_to_json(d)::text FROM platform.post_drafts d")
        denied(PostPublicationFailureCode.RESPONSE_TOO_LARGE) { f.publish(input,store=f.newStore(f.policy(1))) }
        f.faults.outboxFailure=true;denied(PostPublicationFailureCode.STORAGE_UNAVAILABLE) { f.publish(input) };f.faults.outboxFailure=false
        assertEquals(before,f.value("SELECT row_to_json(d)::text FROM platform.post_drafts d"));assertEquals(0,f.count("social.posts"));assertEquals(0,f.count("social.post_publications"));assertEquals(1,f.count("platform.idempotency"))
    }
    @Test fun lostCommitRecoversOnlyOriginalPublishAndCancellationDoesNotUndoCommit() {
        val f=f();val input=f.body();val key=UUID.randomUUID();f.faults.loseCommit=true
        assertFailsWith<CommitOutcomeUnknown> { f.store.publishPost(f.account,key,input) };assertEquals(1,f.count("social.posts"))
        assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input));assertEquals(1,f.count("platform.outbox"))
        val second=f.body();val secondKey=UUID.randomUUID();f.faults.afterCommit={ Thread.currentThread().interrupt() }
        try { assertFailsWith<InterruptedException> { f.store.publishPost(f.account,secondKey,second) } } finally { Thread.interrupted() }
        assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,secondKey,second));assertEquals(2,f.count("social.posts"))
    }
    @Test fun expiredAuthorityAfterWaitingOrReplayDeniesAndDoesNotRefreshReceipt() {
        val f=f();val input=f.body();f.authority.expirySeconds=0
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input) };assertEquals(0,f.count("social.posts"))
        f.authority.expirySeconds=300;val key=UUID.randomUUID();f.publish(input,key)
        val before=f.value("SELECT row_to_json(i)::text FROM platform.idempotency i")
        f.authority.expirySeconds=0;denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input,key) };assertEquals(before,f.value("SELECT row_to_json(i)::text FROM platform.idempotency i"))
    }
    @Test fun authorityCannotSubstituteAnotherAuthorOrInventAudienceMemberships() {
        val f=f();val input=f.body()
        f.authority.transformEvidence={ e->PostPublicationEvidence(JsonObject(e.author+("userId" to JsonPrimitive(UUID.randomUUID().toString()))),e.memberships,e.verifiedAttachment,e.recipeSnapshot,e.capabilities,e.validUntil) }
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input) }
        f.authority.transformEvidence={ e->PostPublicationEvidence(e.author,mapOf(UUID.randomUUID() to 1L),e.verifiedAttachment,e.recipeSnapshot,e.capabilities,e.validUntil) }
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input) };assertEquals(0,f.count("social.posts"))
    }
    @Test fun cancellationBeforeCommitRollsBackIdentityPostAndOriginalDraftRevision() {
        val f=f();val input=f.saved(f.body());f.authority.afterNew={ throw CancellationException("synthetic cancellation") }
        assertFailsWith<CancellationException> { f.publish(input) }
        assertEquals(0,f.count("social.posts"));assertEquals(0,f.count("social.post_publications"));assertEquals("draft",f.value("SELECT status FROM platform.post_drafts"))
        assertEquals(1,f.count("platform.idempotency"));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun currentSafetyDenialRollsBackPreparedPostAndOriginalReplay() {
        val f=f();val client=f.client();val photo=f.readyPhoto(client);val input=JsonObject(f.body(client)+("mediaIds" to arr(photo)))
        f.authority.mediaSafe=false;denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input) };assertEquals(0,f.count("social.posts"))
        f.authority.mediaSafe=true;val key=UUID.randomUUID();f.publish(input,key);f.authority.mediaSafe=false
        denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input,key) };assertEquals(1,f.count("social.posts"))
    }
    @Test fun firstPhotoSafetyExpiryDuringLaterAuthorityWaitCannotCommitOrReplay() {
        val f=f();val client=f.client();val one=f.readyPhoto(client);val two=f.readyPhoto(client)
        val input=JsonObject(f.body(client)+("mediaIds" to arr(one,two)));val key=UUID.randomUUID()
        var calls=0
        fun expireFirst() {
            calls=0
            f.authority.mediaDeadline={ _,at->calls++;at.plusSeconds(if(calls==1)1 else 300) }
            f.authority.afterReadyMedia={ c->if(calls==2)c.createStatement().use { it.execute("SELECT pg_sleep(1.2)") } }
        }
        expireFirst();denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input,key) };assertEquals(0,f.count("social.posts"))
        f.authority.mediaDeadline=null;f.authority.afterReadyMedia=null;f.publish(input,key)
        val before=f.value("SELECT row_to_json(i)::text FROM platform.idempotency i WHERE operation_id='publishPost'")
        expireFirst();denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input,key) }
        assertEquals(before,f.value("SELECT row_to_json(i)::text FROM platform.idempotency i WHERE operation_id='publishPost'"));assertEquals(1,f.count("social.posts"))
    }
    @Test fun authorizedLateDerivativeReceiptPreservesPurposeFixedPublicationCleanup() {
        val f=f();val client=f.client();val event=f.processing.seed(clientDraftId=client);f.processing.store.ingest(event)
        val lease=assertNotNull(f.processing.store.claim());val variants=(f.processing.codec.result as PhotoDecodeResult.Decoded).variants
        val intents=f.processing.store.prepareDerivatives(lease,variants);val intent=intents.first();f.processing.store.authorizeWrite(lease,intent)
        val input=f.body(client);val key=UUID.randomUUID();f.publish(input,key)
        val receipt=f.processing.objects.create(intent,variants.single { it.variant==intent.variant }.copyBytes())
        f.processing.store.acknowledge(intent,receipt)
        assertEquals("cancelled",f.value("SELECT state FROM platform.media_processing_jobs"));assertEquals("deleted",f.value("SELECT state FROM platform.media_assets"))
        assertEquals(3,f.count("platform.media_processing_cleanup"));assertEquals("1",f.value("SELECT count(*) FROM platform.media_processing_cleanup WHERE derivative_intent_id='${intent.id}' AND not_before>clock_timestamp()"))
        assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,input));assertEquals(0,f.count("social.post_media"))
        val before=f.value("SELECT row_to_json(j)::text FROM platform.media_processing_jobs j")
        val events=f.count("platform.outbox")
        val terminal=f.processing.store.ready(lease,f.processing.safety.proof(lease.source,variants))
        assertEquals(MediaProcessingTerminal.CANCELLED,terminal.result);assertEquals(lease.jobId,terminal.jobId)
        assertEquals(f.value("SELECT terminal_media_version FROM platform.media_processing_jobs").toLong(),terminal.mediaVersion);assertNull(terminal.eventId)
        assertEquals(before,f.value("SELECT row_to_json(j)::text FROM platform.media_processing_jobs j"));assertEquals("deleted",f.value("SELECT state FROM platform.media_assets"))
        assertEquals(events,f.count("platform.outbox"));assertEquals(3,f.count("platform.media_processing_cleanup"))
        assertFailsWith<MediaProcessingFailure> { f.processing.store.renew(lease) }
    }
    @Test fun disabledRootDeniesNewDirectAndSavedPublicationButPreservesOwnedOriginalReplay() {
        val f=f();val direct=f.body();val saved=f.saved(f.body())
        f.sql("UPDATE media_test.drafts SET eligible=false")
        for(input in listOf(direct,saved))denied(PostPublicationFailureCode.DRAFT_UNAVAILABLE) { f.publish(input) }
        assertEquals(0,f.count("social.posts"));assertEquals(1,f.count("platform.idempotency"))
        f.sql("UPDATE media_test.drafts SET eligible=true");val key=UUID.randomUUID();val original=f.publish(direct,key)
        f.sql("UPDATE media_test.drafts SET eligible=false");f.authority.enabled=false;f.authority.terms=false
        assertEquals(original,assertIs<CommandResult.Replayed>(f.store.publishPost(f.account,key,direct)).reply.body)
        assertEquals(1,f.count("social.posts"))
    }
    @Test fun currentIncarnationMismatchCannotPublishOrDiscloseAnOldRootReceipt() {
        val f=f();val client=f.client();f.drafts.prepare(client);val input=f.body(client)
        f.sql("UPDATE media_test.drafts SET generation=2 WHERE id='$client'")
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(input) };assertEquals(0,f.count("social.posts"))
        val second=f.body();val key=UUID.randomUUID();f.publish(second,key)
        f.sql("UPDATE media_test.drafts SET generation=2 WHERE id='${second.text("clientDraftId")}'")
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(second,key) }
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(second) };assertEquals(1,f.count("social.posts"))
    }
    @Test fun revokedSourceAndCircleRightsDenyNewAndOriginalPublicationDisclosure() {
        val f=f();val circle=f.grantCircle();val source=f.drafts.grant("source")
        val input=JsonObject(f.body()+mapOf("sourcePostId" to JsonPrimitive(source.toString()),"audience" to buildJsonObject { put("kind","circles");put("circleIds",arr(circle)) }))
        val key=UUID.randomUUID();f.publish(input,key)
        for(kind in listOf("circle","source")) {
            f.sql("UPDATE post_draft_test.content_allow SET active=false WHERE kind='$kind'")
            denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input,key) }
            val fresh=JsonObject(input+("clientDraftId" to JsonPrimitive(f.client().toString())))
            denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(fresh) }
            f.sql("UPDATE post_draft_test.content_allow SET active=true WHERE kind='$kind'")
        }
        assertEquals(1,f.count("social.posts"));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun receiptCompactionAndDeletedPostNeverRecyclePublicationIdentity() {
        val f=f();val input=f.body();val key=UUID.randomUUID();val post=f.publish(input,key)
        f.sql("UPDATE platform.idempotency SET expires_at=clock_timestamp()-interval '1 second'")
        assertIs<CommandResult.ReceiptExpired>(f.store.publishPost(f.account,key,input))
        f.sql("UPDATE social.posts SET status='deleted',content=NULL,version=2,updated_at=clock_timestamp() WHERE id='${post.text("id")}'")
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(input) }
        assertFailsWith<SQLException> { f.sql("DELETE FROM social.post_publications") };assertEquals(1,f.count("social.post_publications"))
    }
    @Test fun concurrentSameAndDifferentKeysProduceOnlyOneOriginalPost() {
        val f=f();val input=f.body();val key=UUID.randomUUID()
        val same=race({f.store.publishPost(f.account,key,input)},{f.store.publishPost(f.account,key,input)})
        assertEquals(1,same.count { it is CommandResult.Applied });assertEquals(1,same.count { it is CommandResult.Replayed })
        val second=f.body();val different=race({runCatching { f.store.publishPost(f.account,UUID.randomUUID(),second) }},{runCatching { f.store.publishPost(f.account,UUID.randomUUID(),second) }})
        assertEquals(1,different.count { it.isSuccess });assertEquals(PostPublicationFailureCode.PUBLICATION_CONFLICT,(different.single { it.isFailure }.exceptionOrNull() as PostPublicationFailure).code)
        assertEquals(2,f.count("social.posts"));assertEquals(2,f.count("social.post_publications"))
    }
    @Test fun concurrentDraftEditOrPublishCannotAdoptAnUnreviewedRevision() {
        val f=f();val input=f.saved(f.body());val id=UUID.fromString(input.text("draftId"))
        val results=race({runCatching { f.store.publishPost(f.account,UUID.randomUUID(),input) }},{runCatching { f.drafts.store.updatePostDraft(f.account,UUID.randomUUID(),id,"\"1\"",buildJsonObject { put("caption","later edit") }) }})
        assertEquals(1,results.count { it.isSuccess })
        if(f.count("social.posts")==1)assertEquals("Synthetic dinner",Json.parseToJsonElement(f.value("SELECT content::text FROM social.posts")).jsonObject.text("caption"))
        else assertEquals("later edit",Json.parseToJsonElement(f.value("SELECT content::text FROM platform.post_drafts")).jsonObject.text("caption"))
    }
    @Test fun concurrentDiscardAndPublishCommitExactlyOneTerminalOutcome() {
        val f=f();val input=f.saved(f.body());val id=UUID.fromString(input.text("draftId"))
        val results=race({runCatching { f.store.publishPost(f.account,UUID.randomUUID(),input) }},{runCatching { f.drafts.store.deletePostDraft(f.account,UUID.randomUUID(),id,"\"1\"") }})
        assertEquals(1,results.count { it.isSuccess })
        val state=f.value("SELECT status FROM platform.post_drafts")
        assertTrue(state in setOf("published","discarded"));assertEquals(if(state=="published")1 else 0,f.count("social.posts"))
        assertEquals(2,f.count("platform.idempotency"));assertEquals(2,f.count("platform.outbox"))
    }
    @Test fun concurrentCreateAndDirectPublishCannotLeaveAnEditableDraftOnPublishedRoot() {
        val f=f();val input=f.body()
        val results=race({runCatching { f.store.publishPost(f.account,UUID.randomUUID(),input) }},{runCatching { f.drafts.store.createPostDraft(f.account,UUID.randomUUID(),input) }})
        assertEquals(1,results.count { it.isSuccess });assertEquals(1,f.count("social.posts")+f.count("platform.post_drafts"))
        assertEquals(1,f.count("platform.idempotency"));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun recallAndMutatedOriginalPostRefuseCachedPositiveDisclosure() {
        val f=f();val input=f.body();val key=UUID.randomUUID();val post=f.publish(input,key)
        f.authority.recall=true;denied(PostPublicationFailureCode.FORBIDDEN) { f.publish(input,key) };f.authority.recall=false
        f.sql("UPDATE social.posts SET status='hidden',version=2,content=jsonb_set(jsonb_set(content,'{status}','\"hidden\"'),'{version}','2'),updated_at=clock_timestamp() WHERE id='${post.text("id")}'")
        denied(PostPublicationFailureCode.PUBLICATION_CONFLICT) { f.publish(input,key) };assertEquals(1,f.count("platform.outbox"))
    }
    companion object {
        private lateinit var cluster:PostgresTestCluster
        @ClassRule @JvmField val timeout:Timeout=Timeout(10,TimeUnit.MINUTES)
        @BeforeClass @JvmStatic fun start(){cluster=PostgresTestCluster.start()}
        @AfterClass @JvmStatic fun stop(){if(::cluster.isInitialized)cluster.close()}
        private fun f()=PostPublicationTestFixture(cluster.database())
        private fun reply(result:CommandResult)=PostPublicationTestFixture.reply(result)
        private fun JsonObject.text(name:String)=getValue(name).jsonPrimitive.content
        private fun arr(vararg ids:UUID)=JsonArray(ids.map { JsonPrimitive(it.toString()) })
        private fun canonical(v:JsonElement):String=when(v) {
            is JsonObject->v.toSortedMap().entries.joinToString(",","{","}") { (k,x)->"${JsonPrimitive(k)}:${canonical(x)}" }
            is JsonArray->v.joinToString(",","[","]",transform=::canonical)
            is JsonPrimitive->if(v.isString||v==JsonNull||v.booleanOrNull!=null)v.toString() else BigDecimal(v.content).stripTrailingZeros().toString()
        }
        private fun denied(code:PostPublicationFailureCode,action:()->Unit)=assertEquals(code,assertFailsWith<PostPublicationFailure> { action() }.code)
        private fun <T> race(a:()->T,b:()->T):List<T> {
            val start=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
            try { val futures=listOf(a,b).map { action->pool.submit<T> { check(start.await(3,TimeUnit.SECONDS));action() } };start.countDown();return futures.map { it.get(12,TimeUnit.SECONDS) } }
            finally { start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)) }
        }
    }
}
