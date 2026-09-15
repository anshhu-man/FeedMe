package com.feedme.server.social.drafts

import com.feedme.server.db.*
import com.feedme.server.media.*
import com.feedme.server.media.processing.*
import com.feedme.server.social.VerifiedSocialAccount
import java.sql.SQLException
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

/** Actual PostgreSQL transactions. Identity, content, upload and object providers are labelled synthetic. */
class PostDraftStoreIntegrationTest {
    @Test fun absentOwnedReadsDoNotCreateDefaultsOrUploadRoots() {
        val f=f();val page=f.store.listPostDrafts(f.account)
        assertTrue(page.body!!.jsonObject.getValue("items").jsonArray.isEmpty());assertEquals(JsonNull,page.body!!.jsonObject["nextCursor"])
        denied(PostDraftFailureCode.DRAFT_UNAVAILABLE){f.store.getPostDraft(f.account,UUID.randomUUID())}
        for(t in listOf("platform.post_drafts","platform.post_draft_heads","platform.media_draft_lifecycles","platform.idempotency","platform.outbox"))assertEquals(0,f.count(t))
    }
    @Test fun minimalCreateCommitsExplicitSelfDefaultsAndOnlyPrivateInternalChange() {
        val f=f();val body=f.body();val draft=f.create(body)
        assertEquals("draft",draft.text("status"));assertEquals("1",draft.text("version"));assertEquals("",draft.text("caption"))
        assertEquals(JsonPrimitive(false),draft["keepOnPlate"]);assertEquals(JsonPrimitive(false),draft["allowRecipeSaves"])
        assertEquals("self",draft.getValue("audience").jsonObject.text("kind"));assertNull(draft["publishedPostId"])
        assertEquals(body["clientDraftId"],draft["clientDraftId"]);assertEquals(0,f.count("platform.media_assets"))
        assertEquals(1,f.count("platform.post_drafts"));assertEquals(1,f.count("platform.idempotency"));assertEquals(1,f.count("platform.outbox"))
        val event=Json.parseToJsonElement(f.value("SELECT payload::text FROM platform.outbox")).jsonObject
        assertEquals(setOf("environment","ownerId","draftId","version","state"),event.keys)
        assertEquals("social.post_draft.changed.v1",f.value("SELECT event_type FROM platform.outbox"))
        assertEquals("post-draft",f.value("SELECT aggregate_type FROM platform.outbox"));assertEquals("social",f.value("SELECT producer FROM platform.outbox"))
        assertEquals(draft,f.store.getPostDraft(f.account,id(draft)).body)
    }
    @Test fun originalCreateKeyReplaysExactBodyEtagWithoutTouchOrExtraEvent() {
        val f=f();val input=f.body(caption="private dinner caption");val key=UUID.randomUUID()
        val first=reply(f.store.createPostDraft(f.account,key,input));val before=f.value("SELECT row_to_json(d)::text FROM platform.post_drafts d")
        val again=assertIs<CommandResult.Replayed>(f.store.createPostDraft(f.account,key,input)).reply
        assertEquals(first.body,again.body);assertEquals(first.etag,again.etag);assertEquals(201,again.status)
        assertEquals(before,f.value("SELECT row_to_json(d)::text FROM platform.post_drafts d"));assertEquals(1,f.count("platform.outbox"))
        assertFalse(f.value("SELECT payload::text FROM platform.outbox").contains("private dinner caption"))
    }
    @Test fun changedOriginalBodyMismatchesAndNewKeyCannotRecycleClientIdentity() {
        val f=f();val input=f.body();val key=UUID.randomUUID();f.create(input,key)
        assertIs<CommandResult.Mismatch>(f.store.createPostDraft(f.account,key,JsonObject(input+("caption" to JsonPrimitive("changed")))))
        denied(PostDraftFailureCode.DRAFT_CONFLICT){f.create(input)}
        assertEquals(1,f.count("platform.idempotency"));assertEquals(1,f.count("platform.post_drafts"));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun foreignAndMissingObjectsDoNotEnumerateAndWrongEnvironmentCannotUseVerifiedIdentity() {
        val f=f();val d=f.create();val other=f.principal()
        for(target in listOf(id(d),UUID.randomUUID())) {
            denied(PostDraftFailureCode.DRAFT_UNAVAILABLE){f.store.getPostDraft(other,target)}
            denied(PostDraftFailureCode.DRAFT_UNAVAILABLE){f.store.updatePostDraft(other,UUID.randomUUID(),target,"\"1\"",patch())}
            denied(PostDraftFailureCode.DRAFT_UNAVAILABLE){f.store.deletePostDraft(other,UUID.randomUUID(),target,"\"1\"")}
        }
        denied(PostDraftFailureCode.UNAUTHENTICATED){f.store.getPostDraft(VerifiedSocialAccount("other",f.account.accountId,f.account.deviceSessionId),id(d))}
        assertEquals(1,f.count("platform.idempotency"))
    }
    @Test fun currentRegisteredDeviceAndPrincipalAreRecheckedBeforeReceiptDisclosure() {
        val f=f();val body=f.body();val key=UUID.randomUUID();val d=f.create(body,key)
        f.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='${f.account.deviceSessionId}'")
        denied(PostDraftFailureCode.UNAUTHENTICATED){f.create(body,key)}
        denied(PostDraftFailureCode.UNAUTHENTICATED){f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"")}
        assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun patchPreservesAbsenceAndAppliesExplicitFalseWithoutPublishing() {
        val f=f();val d=f.create(JsonObject(f.body(caption="original")+("keepOnPlate" to JsonPrimitive(true))))
        val result=reply(f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"",buildJsonObject{put("altText","photo alt");put("keepOnPlate",false)}))
        val b=result.body!!.jsonObject
        assertEquals("original",b.text("caption"));assertEquals("photo alt",b.text("altText"));assertEquals(JsonPrimitive(false),b["keepOnPlate"])
        assertEquals("draft",b.text("status"));assertEquals("\"2\"",result.etag);assertEquals(2,f.count("platform.outbox"))
    }
    @Test fun originalPatchKeyAndPreconditionAreNotRebasedAfterAnotherEdit() {
        val f=f();val d=f.create();val key=UUID.randomUUID();val body=patch("first")
        val first=reply(f.store.updatePostDraft(f.account,key,id(d),"\"1\"",body))
        assertEquals(first.body,assertIs<CommandResult.Replayed>(f.store.updatePostDraft(f.account,key,id(d),"\"1\"",body)).reply.body)
        assertIs<CommandResult.Mismatch>(f.store.updatePostDraft(f.account,key,id(d),"\"2\"",body))
        f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"2\"",patch("second"))
        denied(PostDraftFailureCode.VERSION_CONFLICT){f.store.updatePostDraft(f.account,key,id(d),"\"1\"",body)}
        assertEquals(3,f.count("platform.outbox"))
    }
    @Test fun clientDraftIdentityIsStableAcrossPatchAndTerminalDeletion() {
        val f=f();val input=f.body();val d=f.create(input)
        denied(PostDraftFailureCode.DRAFT_CONFLICT){f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"",f.body())}
        f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"",input)
        f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"2\"")
        denied(PostDraftFailureCode.DRAFT_CONFLICT){f.create(input)}
        assertEquals(1,f.count("platform.post_drafts"))
    }
    @Test fun actualSchemaUnicodeAndAmbiguousAttachmentControlsFailBeforeMutation() {
        val f=f();val client=f.client();f.create(f.body(client,"😀".repeat(500)))
        for(body in listOf(f.body(f.client(),"😀".repeat(501)),f.body(f.client(),"\uD800"),JsonObject(f.body()+("unknown" to JsonPrimitive(true)))))
            denied(PostDraftFailureCode.INPUT_INVALID){f.create(body)}
        val attachment=attachment(f.grant("attachment"))
        denied(PostDraftFailureCode.INPUT_INVALID){f.create(JsonObject(f.body()+mapOf("attachment" to attachment,"removeAttachment" to JsonPrimitive(true))))}
        assertEquals(1,f.count("platform.post_drafts"));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun sourceAndCircleRightsAreExplicitCurrentAndClientBindingsNeverBecomeAuthority() {
        val f=f();val circle=f.grant("circle");val source=f.grant("source");val recipe=f.grant("attachment")
        val input=JsonObject(f.body()+mapOf("sourcePostId" to JsonPrimitive(source.toString()),"attachment" to attachment(recipe),"audience" to buildJsonObject{
            put("kind","circles");put("circleIds",arr(circle));put("bindings",buildJsonArray{add(buildJsonObject{put("circleId",circle.toString());put("authorMembershipGeneration",999)})})
        }))
        val key=UUID.randomUUID();val d=f.create(input,key)
        assertNull(d.getValue("audience").jsonObject["bindings"])
        f.lifecycle{c->c.createStatement().use{it.executeUpdate("UPDATE post_draft_test.content_allow SET active=false WHERE kind='circle'")}}
        denied(PostDraftFailureCode.FORBIDDEN){f.create(input,key)}
        denied(PostDraftFailureCode.FORBIDDEN){f.store.getPostDraft(f.account,id(d))}
        // Owned removal is not a new social publication/content grant.
        assertIs<CommandResult.Applied>(f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\""))
    }
    @Test fun removedAttachmentCanBeClearedWithoutBorrowingRevokedCopyRights() {
        val f=f();val recipe=f.grant("attachment");val d=f.create(JsonObject(f.body()+("attachment" to attachment(recipe))))
        f.sql("UPDATE post_draft_test.content_allow SET active=false")
        val changed=reply(f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"",buildJsonObject{put("removeAttachment",true)})).body!!.jsonObject
        assertNull(changed["attachment"]);assertEquals("draft",changed.text("status"))
    }
    @Test fun postingDisabledPreservesReadsOriginalReceiptAndExplicitOwnedDiscard() {
        val f=f();val input=f.body();val key=UUID.randomUUID();val d=f.create(input,key)
        f.authority.enabled=false;f.authority.terms=false
        assertEquals(d,f.store.getPostDraft(f.account,id(d)).body);assertEquals(d,f.create(input,key))
        denied(PostDraftFailureCode.FORBIDDEN){f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"",patch())}
        assertIs<CommandResult.Applied>(f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\""))
    }
    @Test fun expiryNeverRecyclesIdentityButDoesNotTrapOwnerCleanup() {
        for(explicitState in listOf(false,true)) {
            val f=f();val input=f.body(caption="private expired draft");val key=UUID.randomUUID();val d=f.create(input,key);f.expire(id(d))
            if(explicitState)f.sql("UPDATE platform.post_drafts SET status='expired',version=2 WHERE id='${id(d)}'")
            val etag=if(explicitState)"\"2\"" else "\"1\""
            denied(PostDraftFailureCode.DRAFT_EXPIRED){f.create(input,key)}
            denied(PostDraftFailureCode.DRAFT_EXPIRED){f.create(input)}
            denied(PostDraftFailureCode.DRAFT_EXPIRED){f.store.getPostDraft(f.account,id(d))}
            denied(PostDraftFailureCode.DRAFT_EXPIRED){f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),etag,patch())}
            assertEquals(0,f.store.listPostDrafts(f.account).body!!.jsonObject.getValue("items").jsonArray.size)
            val deletion=UUID.randomUUID();assertIs<CommandResult.Applied>(f.store.deletePostDraft(f.account,deletion,id(d),etag))
            assertIs<CommandResult.Replayed>(f.store.deletePostDraft(f.account,deletion,id(d),etag))
            assertEquals("{}",f.value("SELECT content::text FROM platform.post_drafts"))
            assertTrue(f.value("SELECT response_json::text FROM platform.idempotency WHERE operation_id='createPostDraft'").contains("private expired draft")) // Normal receipt retention, not instant erasure.
            denied(PostDraftFailureCode.DRAFT_UNAVAILABLE){f.store.getPostDraft(f.account,id(d))}
        }
    }
    @Test fun publishedTerminalDraftIsReconcilableButNotEditableOrDiscardable() {
        val f=f();val d=f.create();val post=UUID.randomUUID()
        // Explicit synthetic publisher transition. No publish implementation or feed claim.
        f.sql("UPDATE platform.post_drafts SET status='published',version=2,published_post_id='$post' WHERE id='${id(d)}'")
        assertEquals(post.toString(),f.store.getPostDraft(f.account,id(d)).body!!.jsonObject.text("publishedPostId"))
        denied(PostDraftFailureCode.DRAFT_CONFLICT){f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"2\"",patch())}
        denied(PostDraftFailureCode.DRAFT_CONFLICT){f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"2\"")}
    }
    @Test fun listCursorPreservesOwnerHeadLimitAndCompleteBoundedOrdering() {
        val f=f();val all=(1..3).map{f.create()};val page=f.store.listPostDrafts(f.account,limit=2).body!!.jsonObject
        val next=page.text("nextCursor");val rest=f.store.listPostDrafts(f.account,next,2).body!!.jsonObject
        assertEquals(all.map(::id).toSet(),(page.getValue("items").jsonArray+rest.getValue("items").jsonArray).map{ id(it.jsonObject) }.toSet())
        assertEquals(JsonNull,rest["nextCursor"])
        denied(PostDraftFailureCode.CURSOR_INVALID){f.store.listPostDrafts(f.principal(),next,2)}
        denied(PostDraftFailureCode.CURSOR_INVALID){f.store.listPostDrafts(f.account,next,1)}
        f.store.updatePostDraft(f.account,UUID.randomUUID(),id(all.first()),"\"1\"",patch())
        denied(PostDraftFailureCode.CURSOR_INVALID){f.store.listPostDrafts(f.account,next,2)}
    }
    @Test fun shortCursorExpiryIsNotRefreshedByContinuationOrRead() {
        val f=f();f.create();f.create();val store=f.newStore(f.policy(cursorLifetime=1))
        val cursor=store.listPostDrafts(f.account,limit=1).body!!.jsonObject.text("nextCursor")
        f.sql("SELECT pg_sleep(1.1)")
        denied(PostDraftFailureCode.CURSOR_EXPIRED){store.listPostDrafts(f.account,cursor,1)}
        assertEquals(2,f.count("platform.outbox"))
    }
    @Test fun responseBudgetRejectsBeforeDomainReceiptOrHeadAndDoesNotTruncatePages() {
        val f=f();val tooSmall=f.newStore(f.policy(max=400))
        denied(PostDraftFailureCode.RESPONSE_TOO_LARGE){f.create(store=tooSmall)}
        assertEquals(0,f.count("platform.post_drafts"));assertEquals(0,f.count("platform.post_draft_heads"));assertEquals(0,f.count("platform.idempotency"));assertEquals(0,f.count("platform.outbox"))
        val small=f.newStore(f.policy(max=1300));repeat(3){f.create(f.body(caption="x".repeat(200)),store=small)}
        denied(PostDraftFailureCode.RESPONSE_TOO_LARGE){small.listPostDrafts(f.account,limit=3)}
        assertEquals(1,small.listPostDrafts(f.account,limit=1).body!!.jsonObject.getValue("items").jsonArray.size)
    }
    @Test fun editableDraftMayRetainPendingProcessingRejectedOrReadyMetadataWithoutPublishing() {
        for(state in listOf("awaitingUpload","processing","rejected","ready")) {
            val f=f();val client=f.client();val m=f.prepare(client)
            if(state!="awaitingUpload")f.mediaStore.completeMediaUpload(f.mediaFixture.account,UUID.randomUUID(),id(m),f.mediaFixture.upload(m))
            if(state in setOf("rejected","ready"))f.mediaFixture.finishProcessing(m,state)
            val d=f.create(JsonObject(f.body(client)+("mediaIds" to arr(id(m)))))
            assertEquals("draft",d.text("status"));assertEquals(arr(id(m)),d["mediaIds"])
            assertEquals(0,f.count("platform.post_draft_discard_media"))
        }
    }
    @Test fun foreignWrongRootDeletedAndAttachedMediaCannotBeSelectedForDraft() {
        val f=f();val client=f.client();val m=f.prepare(client)
        denied(PostDraftFailureCode.MEDIA_UNAVAILABLE){f.create(JsonObject(f.body()+("mediaIds" to arr(id(m)))))}
        val other=f.principal();denied(PostDraftFailureCode.MEDIA_UNAVAILABLE){f.create(JsonObject(f.body(f.client(other))+("mediaIds" to arr(id(m)))),actor=other)}
        f.sql("INSERT INTO media_test.attached VALUES('${f.account.accountId}','${id(m)}')")
        denied(PostDraftFailureCode.MEDIA_UNAVAILABLE){f.create(JsonObject(f.body(client)+("mediaIds" to arr(id(m)))))}
        f.sql("DELETE FROM media_test.attached");f.mediaStore.deleteDraftMedia(f.mediaFixture.account,UUID.randomUUID(),id(m),"\"1\"")
        denied(PostDraftFailureCode.MEDIA_UNAVAILABLE){f.create(JsonObject(f.body(client)+("mediaIds" to arr(id(m)))))}
        assertEquals(0,f.count("platform.post_drafts"))
    }
    @Test fun discardCapturesUnlistedUploadsButNeverSiblingRootOrReadyManifestLoss() {
        val f=f();val client=f.client();val one=f.prepare(client);val two=f.prepare(client);val sibling=f.prepare(f.client())
        f.mediaStore.completeMediaUpload(f.mediaFixture.account,UUID.randomUUID(),id(two),f.mediaFixture.upload(two));f.mediaFixture.finishProcessing(two,"ready")
        val derivative=f.value("SELECT derivative_set::text FROM platform.media_assets WHERE id='${id(two)}'")
        val d=f.create(JsonObject(f.body(client)+("mediaIds" to arr(id(one)))));val key=UUID.randomUUID()
        val response=reply(f.store.deletePostDraft(f.account,key,id(d),"\"1\""));assertEquals(204,response.status);assertNull(response.body);assertNull(response.etag)
        assertEquals(2,f.count("platform.post_draft_discard_media"));assertEquals(2,f.count("platform.media_cleanup_jobs"))
        assertEquals(derivative,f.value("SELECT derivative_set::text FROM platform.media_cleanup_jobs WHERE media_id='${id(two)}'"))
        assertEquals("{}",f.value("SELECT content::text FROM platform.post_drafts WHERE id='${id(d)}'"))
        assertEquals("awaitingUpload",f.mediaStore.getMediaStatus(f.mediaFixture.account,id(sibling)).body!!.jsonObject.text("status"))
        assertIs<CommandResult.Replayed>(f.store.deletePostDraft(f.account,key,id(d),"\"1\""))
    }
    @Test fun previouslyDeletedMediaKeepsItsExactPublicReceiptWhileDraftDiscardReplays() {
        val f=f();val client=f.client();val m=f.prepare(client);val d=f.create(f.body(client));val mediaKey=UUID.randomUUID()
        f.mediaStore.deleteDraftMedia(f.mediaFixture.account,mediaKey,id(m),"\"1\"")
        val before=f.value("SELECT row_to_json(j)::text FROM platform.media_cleanup_jobs j");val key=UUID.randomUUID()
        f.store.deletePostDraft(f.account,key,id(d),"\"1\"")
        assertEquals(before,f.value("SELECT row_to_json(j)::text FROM platform.media_cleanup_jobs j"))
        assertIs<CommandResult.Replayed>(f.mediaStore.deleteDraftMedia(f.mediaFixture.account,mediaKey,id(m),"\"1\""))
        assertIs<CommandResult.Replayed>(f.store.deletePostDraft(f.account,key,id(d),"\"1\""))
    }
    @Test fun deletionReplayChecksExactCleanupManifestAndCapturedMembershipWithoutFreshEffects() {
        for(damage in listOf("cleanup","capture","targetMissing","targetChanged")) {
            val f=f();val client=f.client();f.prepare(client);val d=f.create(f.body(client));val key=UUID.randomUUID()
            f.store.deletePostDraft(f.account,key,id(d),"\"1\"")
            when(damage) {
                "cleanup"->f.sql("UPDATE platform.media_cleanup_jobs SET manifest_hash=repeat('0',64)")
                "capture"->f.sql("DELETE FROM platform.post_draft_discard_media")
                "targetMissing"->f.sql("DELETE FROM platform.media_processing_cleanup")
                // Explicit corruption fixture: bypass only the immutable target trigger.
                else->f.sql("BEGIN; ALTER TABLE platform.media_processing_cleanup DISABLE TRIGGER media_processing_cleanup_identity; UPDATE platform.media_processing_cleanup SET not_before=not_before+interval '1 second'; ALTER TABLE platform.media_processing_cleanup ENABLE TRIGGER media_processing_cleanup_identity; COMMIT")
            }
            denied(PostDraftFailureCode.STORAGE_UNAVAILABLE){f.store.deletePostDraft(f.account,key,id(d),"\"1\"")}
            assertEquals(2,f.count("platform.outbox"))
        }
    }
    @Test fun attachedUnlistedUploadRollsBackWholeDraftDeletionAndAllEarlierCleanup() {
        val f=f();val client=f.client();f.prepare(client);val attached=f.prepare(client);val d=f.create(f.body(client))
        f.sql("INSERT INTO media_test.attached VALUES('${f.account.accountId}','${id(attached)}')")
        denied(PostDraftFailureCode.MEDIA_UNAVAILABLE){f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"")}
        assertEquals("draft",f.store.getPostDraft(f.account,id(d)).body!!.jsonObject.text("status"));assertEquals(0,f.count("platform.media_cleanup_jobs"))
        assertEquals(0,f.count("platform.post_draft_discard_media"));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun lateUploadCapabilityAndCompletionCannotResurrectDiscardedDraft() {
        val f=f();val client=f.client();val key=UUID.randomUUID();val input=f.mediaFixture.prepareBody(clientDraftId=client)
        val m=f.mediaFixture.prepare(key,input,selected=f.mediaStore);val d=f.create(f.body(client))
        f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"")
        val late=f.mediaFixture.upload(m,"late-object-version")
        assertEquals(MediaFailureCode.DRAFT_UNAVAILABLE,assertFailsWith<MediaFailure>{f.mediaStore.prepareMediaUpload(f.mediaFixture.account,key,input)}.code)
        assertFailsWith<MediaFailure>{f.mediaStore.completeMediaUpload(f.mediaFixture.account,UUID.randomUUID(),id(m),late)}
        assertEquals(1,f.mediaFixture.signer.authorizations.size)
        assertEquals("1",f.value("SELECT CASE WHEN j.final_sweep_after=m.reservation_expires_at AND j.completed_at IS NULL THEN 1 ELSE 0 END FROM platform.media_cleanup_jobs j JOIN platform.media_assets m ON m.id=j.media_id"))
    }
    @Test fun outboxFailureRollsBackDraftReceiptHeadAndSameTransactionMediaCleanup() {
        val f=f();f.faults.outboxFailure=true
        denied(PostDraftFailureCode.STORAGE_UNAVAILABLE){f.create()}
        assertEquals(0,f.count("platform.post_drafts"));assertEquals(0,f.count("platform.idempotency"))
        val client=f.client();f.prepare(client);val d=f.create(f.body(client));val before=f.value("SELECT revision FROM platform.post_draft_heads")
        f.faults.outboxFailure=true
        denied(PostDraftFailureCode.STORAGE_UNAVAILABLE){f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"")}
        assertEquals("draft",f.store.getPostDraft(f.account,id(d)).body!!.jsonObject.text("status"));assertEquals(before,f.value("SELECT revision FROM platform.post_draft_heads"))
        assertEquals(0,f.count("platform.media_cleanup_jobs"));assertEquals(0,f.count("platform.post_draft_discard_media"))
    }
    @Test fun unknownCommitAtCreateEditAndDiscardRequiresOnlyExactOriginalCommands() {
        val f=f();val input=f.body();val create=UUID.randomUUID();f.faults.loseCommit=true
        assertFailsWith<CommitOutcomeUnknown>{f.create(input,create)};val d=f.create(input,create)
        val edit=UUID.randomUUID();f.faults.loseCommit=true
        assertFailsWith<CommitOutcomeUnknown>{f.store.updatePostDraft(f.account,edit,id(d),"\"1\"",patch())}
        assertIs<CommandResult.Replayed>(f.store.updatePostDraft(f.account,edit,id(d),"\"1\"",patch()))
        val delete=UUID.randomUUID();f.faults.loseCommit=true
        assertFailsWith<CommitOutcomeUnknown>{f.store.deletePostDraft(f.account,delete,id(d),"\"2\"")}
        assertIs<CommandResult.Replayed>(f.store.deletePostDraft(f.account,delete,id(d),"\"2\""))
        assertEquals(3,f.count("platform.outbox"));assertEquals(3,f.count("platform.idempotency"));assertEquals(1,f.count("platform.post_drafts"))
    }
    @Test fun cancellationBeforeWriteAndInterruptedCallerAfterCommitNeverInventNoncommit() {
        val f=f();val input=f.body();val key=UUID.randomUUID()
        f.authority.afterContent={throw CancellationException("synthetic caller cancellation")}
        assertFailsWith<CancellationException>{f.create(input,key)};assertEquals(0,f.count("platform.post_drafts"))
        f.authority.afterContent=null;f.faults.afterCommit={Thread.currentThread().interrupt()}
        try{assertFailsWith<InterruptedException>{f.create(input,key)}}finally{Thread.interrupted()}
        assertIs<CommandResult.Replayed>(f.store.createPostDraft(f.account,key,input));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun concurrentSameKeyCreateAndOriginalVersionEditsSerializeAtActualPrincipalLock() {
        val f=f();val input=f.body();val key=UUID.randomUUID()
        val creates=race({f.store.createPostDraft(f.account,key,input)},{f.store.createPostDraft(f.account,key,input)})
        assertEquals(1,creates.count{it is CommandResult.Applied});assertEquals(1,creates.count{it is CommandResult.Replayed})
        val d=reply(creates.first()).body!!.jsonObject
        val edits=race({runCatching{f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"",patch("one"))}},
            {runCatching{f.store.updatePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"",patch("two"))}})
        assertEquals(1,edits.count{it.isSuccess});assertEquals(PostDraftFailureCode.VERSION_CONFLICT,(edits.single{it.isFailure}.exceptionOrNull() as PostDraftFailure).code)
        assertEquals(2,f.count("platform.outbox"))
    }
    @Test fun uploadWaitingBehindDiscardCannotCreateAnotherAssetInTerminalRoot() {
        val f=f();val client=f.client();f.prepare(client);val d=f.create(f.body(client));val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            f.authority.afterPrincipal={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            val deletion=pool.submit<CommandResult>{f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"")}
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val upload=pool.submit<Result<JsonObject>>{runCatching{f.prepare(client)}}
            release.countDown();assertIs<CommandResult.Applied>(deletion.get(5,TimeUnit.SECONDS));assertTrue(upload.get(5,TimeUnit.SECONDS).isFailure)
            assertEquals(1,f.count("platform.media_assets"));assertEquals(1,f.count("platform.post_draft_discard_media"))
        } finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS))}
    }
    @Test fun completionVerifiedOutsideTransactionMustLoseToExactConcurrentDraftDiscard() {
        val f=f();val client=f.client();val m=f.prepare(client);val d=f.create(f.body(client));val body=f.mediaFixture.upload(m)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val pool=Executors.newSingleThreadExecutor()
        try {
            f.mediaFixture.objects.beforeVerify={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            val completion=pool.submit<Result<CommandResult>>{runCatching{f.mediaStore.completeMediaUpload(f.mediaFixture.account,UUID.randomUUID(),id(m),body)}}
            assertTrue(entered.await(3,TimeUnit.SECONDS));f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"")
            release.countDown();assertTrue(completion.get(5,TimeUnit.SECONDS).isFailure)
            assertEquals("deleted",f.mediaStore.getMediaStatus(f.mediaFixture.account,id(m)).body!!.jsonObject.text("status"));assertEquals(2,f.count("platform.outbox"))
        } finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS))}
    }
    @Test fun leasedWorkerAndAlreadyAuthorizedLateDerivativeCannotEscapeDraftCleanup() {
        val p=MediaProcessingTestFixture(cluster.database());val event=p.seed();p.store.ingest(event)
        val f=PostDraftTestFixture(p.database,p.media);val client=UUID.fromString(f.value("SELECT client_draft_id FROM platform.media_assets WHERE id='${p.mediaId}'"))
        p.authority.beforeProcessing={c->PostDraftLifecycle.requireEditable(c,"test",f.account.accountId,client)}
        val d=f.create(f.body(client));val lease=assertNotNull(p.store.claim());val variants=(p.codec.result as PhotoDecodeResult.Decoded).variants
        val intents=p.store.prepareDerivatives(lease,variants);p.store.authorizeWrite(lease,intents.first())
        val key=UUID.randomUUID();f.store.deletePostDraft(f.account,key,id(d),"\"1\"")
        assertEquals("cancelled",f.value("SELECT state FROM platform.media_processing_jobs"));assertFailsWith<MediaProcessingFailure>{p.store.renew(lease)}
        val intent=intents.first();val bytes=variants.single{it.variant==intent.variant}.copyBytes();val receipt=p.objects.create(intent,bytes)
        p.store.acknowledge(intent,receipt) // Receipt retention is allowed after deletion; it is not READY.
        assertEquals("deleted",f.value("SELECT state FROM platform.media_assets"))
        assertEquals("1",f.value("SELECT count(*) FROM platform.media_processing_cleanup WHERE derivative_intent_id='${intent.id}' AND not_before>clock_timestamp()"))
        assertEquals(3,f.count("platform.media_processing_cleanup"));assertIs<CommandResult.Replayed>(f.store.deletePostDraft(f.account,key,id(d),"\"1\""))
        // A late immutable receipt is legitimate, clearing its cleanup ownership is not.
        f.sql("BEGIN; ALTER TABLE platform.media_derivative_intents DISABLE TRIGGER media_derivative_identity; UPDATE platform.media_derivative_intents SET cleanup_required=false WHERE id='${intent.id}'; ALTER TABLE platform.media_derivative_intents ENABLE TRIGGER media_derivative_identity; COMMIT")
        denied(PostDraftFailureCode.STORAGE_UNAVAILABLE){f.store.deletePostDraft(f.account,key,id(d),"\"1\"")}
    }
    @Test fun nativeConstraintsRejectDraftIdentityVersionMutationAndTerminalResurrection() {
        val f=f();val d=f.create()
        assertFailsWith<SQLException>{f.sql("UPDATE platform.post_drafts SET client_draft_id='${UUID.randomUUID()}',version=2 WHERE id='${id(d)}'")}
        assertFailsWith<SQLException>{f.sql("UPDATE platform.post_drafts SET version=3 WHERE id='${id(d)}'")}
        f.store.deletePostDraft(f.account,UUID.randomUUID(),id(d),"\"1\"")
        assertFailsWith<SQLException>{f.sql("UPDATE platform.post_drafts SET status='draft',version=3,deletion_key=NULL WHERE id='${id(d)}'")}
    }
    @Test fun lateCancelledEventIngestionAndExactCleanupCompletionPreserveOriginalDiscardReplay() {
        val p=MediaProcessingTestFixture(cluster.database());val event=p.seed(ttl=2)
        val f=PostDraftTestFixture(p.database,p.media)
        val client=UUID.fromString(f.value("SELECT client_draft_id FROM platform.media_assets WHERE id='${p.mediaId}'"))
        val d=f.create(f.body(client));val key=UUID.randomUUID();f.store.deletePostDraft(f.account,key,id(d),"\"1\"")
        val original=f.value("SELECT row_to_json(j)::text FROM platform.media_cleanup_jobs j")
        assertEquals(0,f.count("platform.media_processing_jobs"));p.store.ingest(event)
        assertEquals("cancelled",f.value("SELECT state FROM platform.media_processing_jobs"))
        assertIs<CommandResult.Replayed>(f.store.deletePostDraft(f.account,key,id(d),"\"1\""))
        f.sql("SELECT pg_sleep(2.1)");p.cleanup.clean(assertNotNull(p.cleanup.claim()))
        assertEquals("done",f.value("SELECT state FROM platform.media_processing_cleanup"))
        assertIs<CommandResult.Replayed>(f.store.deletePostDraft(f.account,key,id(d),"\"1\""))
        assertEquals(original,f.value("SELECT row_to_json(j)::text FROM platform.media_cleanup_jobs j"))
        assertEquals(3,f.count("platform.outbox")) // Upload completed + draft created/discarded only.
    }
    companion object {
        private lateinit var cluster:PostgresTestCluster
        @ClassRule @JvmField val timeout:Timeout=Timeout(8,TimeUnit.MINUTES)
        @BeforeClass @JvmStatic fun start(){cluster=PostgresTestCluster.start()}
        @AfterClass @JvmStatic fun stop(){if(::cluster.isInitialized)cluster.close()}
        private fun f()=PostDraftTestFixture(cluster.database())
        private fun id(body:JsonObject)=PostDraftTestFixture.id(body)
        private fun reply(result:CommandResult)=PostDraftTestFixture.reply(result)
        private fun JsonObject.text(name:String)=getValue(name).jsonPrimitive.content
        private fun arr(vararg ids:UUID)=JsonArray(ids.map{JsonPrimitive(it.toString())})
        private fun patch(caption:String="changed")=buildJsonObject{put("caption",caption)}
        private fun attachment(id:UUID)=buildJsonObject{put("recipeVersionId",id.toString());put("confirmedChanges",JsonArray(emptyList()));put("reviewStatus","reviewed");put("rightsBasis","catalogRedistributable")}
        private fun denied(code:PostDraftFailureCode,action:()->Unit)=assertEquals(code,assertFailsWith<PostDraftFailure>{action()}.code)
        private fun <T> race(a:()->T,b:()->T):List<T> {
            val start=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
            try{val futures=listOf(a,b).map{f->pool.submit<T>{check(start.await(3,TimeUnit.SECONDS));f()}};start.countDown();return futures.map{it.get(8,TimeUnit.SECONDS)}}
            finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS))}
        }
    }
}
