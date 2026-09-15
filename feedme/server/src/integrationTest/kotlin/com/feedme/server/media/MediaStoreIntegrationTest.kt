package com.feedme.server.media

import com.feedme.server.db.*
import java.sql.SQLException
import java.time.Instant
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

/** Real PostgreSQL; provider, object metadata and signer are explicitly synthetic fixture adapters. */
class MediaStoreIntegrationTest {
    @Test fun reservationCommitsOnlyOwnedCoreAndNeverImplicitSocialDraftOrOutbox() {
        val f=fixture();val input=f.prepareBody();val media=f.prepare(body=input)
        assertEquals("awaitingUpload",media.text("status"));assertEquals("1",media.text("version"))
        assertEquals("POST",media.text("uploadMethod"));assertEquals(1,f.count("platform.media_assets"));assertEquals(1,f.count("platform.media_draft_lifecycles"))
        assertEquals(1,f.count("platform.idempotency"));assertEquals(0,f.count("platform.outbox"))
        val receipt=f.value("SELECT response_json::text FROM platform.idempotency")
        for(secret in listOf("uploadUrl","uploadFields","synthetic-secret","quarantine/"))assertFalse(receipt.contains(secret))
        assertEquals(core(media),Json.parseToJsonElement(receipt));assertNull(f.base.source.connection.use{c->c.createStatement().use{it.executeQuery("SELECT to_regclass('social.post_drafts')").use{r->r.next();r.getString(1)}}})
    }
    @Test fun sameKeyKeepsExactCoreAndDeadlineButRefreshesOnlyEphemeralCapability() {
        val f=fixture();val key=UUID.randomUUID();val input=f.prepareBody();val first=f.prepare(key,input);val second=f.prepare(key,input)
        assertEquals(core(first),core(second));assertNotEquals(first["uploadFields"],second["uploadFields"])
        val a=f.signer.authorizations;assertEquals(2,a.size);assertEquals(a[0].objectKey,a[1].objectKey);assertEquals(a[0].sha256,a[1].sha256)
        assertEquals(1,f.count("platform.media_assets"));assertEquals(1,f.authority.admissions)
        val deadline=Instant.parse(f.value("SELECT reservation_expires_at::text FROM platform.media_assets").replace(' ','T').replace("+00","Z"))
        assertTrue(Instant.parse(second.text("uploadExpiresAt"))<=deadline)
    }
    @Test fun changedPayloadSameKeyIsMismatchWithoutSignerOrAnotherReservation() {
        val f=fixture();val key=UUID.randomUUID();val input=f.prepareBody();f.prepare(key,input)
        assertIs<CommandResult.Mismatch>(f.store.prepareMediaUpload(f.account,key,JsonObject(input+("bytes" to JsonPrimitive(129)))))
        assertEquals(1,f.signer.authorizations.size);assertEquals(1,f.count("platform.media_assets"))
    }
    @Test fun oneDraftCanOwnDistinctExplicitMediaWithoutAccidentalContentDeduplication() {
        val f=fixture();val body=f.prepareBody();val one=f.prepare(body=body);val two=f.prepare(body=body)
        assertNotEquals(one["id"],two["id"]);assertEquals(1,f.count("platform.media_draft_lifecycles"));assertEquals(2,f.count("platform.media_assets"))
    }
    @Test fun freshIdentityAndDeviceAreCheckedBeforeCoreReceiptDisclosure() {
        val f=fixture();val key=UUID.randomUUID();val body=f.prepareBody();f.prepare(key,body)
        f.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='${f.account.deviceSessionId}'")
        denied(MediaFailureCode.UNAUTHENTICATED){f.store.prepareMediaUpload(f.account,key,body)}
        assertEquals(1,f.signer.authorizations.size)
    }
    @Test fun foreignAndMissingMediaAreEquallyUnavailableAndNeverVerifiedOrSigned() {
        val f=fixture();val media=f.prepare();val other=f.principal()
        for(id in listOf(id(media),UUID.randomUUID())){
            denied(MediaFailureCode.MEDIA_UNAVAILABLE){f.store.getMediaStatus(other,id)}
            denied(MediaFailureCode.MEDIA_UNAVAILABLE){f.store.completeMediaUpload(other,UUID.randomUUID(),id,complete())}
            denied(MediaFailureCode.MEDIA_UNAVAILABLE){f.store.deleteDraftMedia(other,UUID.randomUUID(),id,"\"1\"")}
        }
        assertEquals(0,f.objects.requests.size);assertEquals(1,f.signer.authorizations.size)
    }
    @Test fun wrongEnvironmentCannotBorrowVerifiedAccountIdentity() {
        val f=fixture();val wrong=VerifiedMediaAccount("other",f.account.accountId,f.account.deviceSessionId)
        denied(MediaFailureCode.UNAUTHENTICATED){f.store.prepareMediaUpload(wrong,UUID.randomUUID(),f.prepareBody())}
        assertEquals(0,f.count("platform.media_assets"))
    }
    @Test fun revokedTermsOrUploadFlagDenyReissuanceButPreserveStatusAndOwnedCleanup() {
        val f=fixture();val key=UUID.randomUUID();val body=f.prepareBody();val media=f.prepare(key,body)
        f.authority.termsAccepted=false;denied(MediaFailureCode.FORBIDDEN){f.store.prepareMediaUpload(f.account,key,body)}
        f.authority.termsAccepted=true;f.authority.uploadsAllowed=false
        denied(MediaFailureCode.NOT_CONFIGURED){f.store.prepareMediaUpload(f.account,key,body)}
        assertEquals(core(media),f.store.getMediaStatus(f.account,id(media)).body)
        assertIs<CommandResult.Applied>(f.store.deleteDraftMedia(f.account,UUID.randomUUID(),id(media),"\"1\""))
    }
    @Test fun foreignDraftAndChangedLifecycleCannotReserveOrRenew() {
        val f=fixture();val input=f.prepareBody();val key=UUID.randomUUID();val media=f.prepare(key,input)
        denied(MediaFailureCode.DRAFT_UNAVAILABLE){f.store.prepareMediaUpload(f.principal(),UUID.randomUUID(),input)}
        f.lifecycle{c->c.createStatement().use{it.executeUpdate("UPDATE media_test.drafts SET generation=2 WHERE id='${input.text("clientDraftId")}'")}}
        denied(MediaFailureCode.DRAFT_UNAVAILABLE){f.store.prepareMediaUpload(f.account,key,input)}
        assertEquals(1,f.count("platform.media_assets"));assertEquals(core(media),f.store.getMediaStatus(f.account,id(media)).body)
    }
    @Test fun disabledExistingDraftNeverCreatesImplicitLifecycleOrMedia() {
        val f=fixture();val input=f.prepareBody();f.sql("UPDATE media_test.drafts SET eligible=false")
        denied(MediaFailureCode.DRAFT_UNAVAILABLE){f.store.prepareMediaUpload(f.account,UUID.randomUUID(),input)}
        for(table in listOf("platform.media_assets","platform.media_draft_lifecycles","platform.idempotency"))assertEquals(0,f.count(table))
    }
    @Test fun inputFormatSizeAndUnsupportedKindsFailBeforeDatabaseOrSignerEffects() {
        val f=fixture();val input=f.prepareBody()
        for(changed in listOf(JsonObject(input+("kind" to JsonPrimitive("clip"))),JsonObject(input+("kind" to JsonPrimitive("avatar")))))
            denied(MediaFailureCode.NOT_CONFIGURED){f.store.prepareMediaUpload(f.account,UUID.randomUUID(),changed)}
        denied(MediaFailureCode.UNSUPPORTED_FORMAT){f.store.prepareMediaUpload(f.account,UUID.randomUUID(),JsonObject(input+("contentType" to JsonPrimitive("video/mp4"))))}
        for(value in listOf(JsonPrimitive(10_000_001),Json.parseToJsonElement("1e30")))denied(MediaFailureCode.MEDIA_TOO_LARGE){f.store.prepareMediaUpload(f.account,UUID.randomUUID(),JsonObject(input+("bytes" to value)))}
        for(changed in listOf(JsonObject(input+("bytes" to JsonPrimitive(0))),JsonObject(input+("sha256" to JsonPrimitive("bad"))),JsonObject(input+("extra" to JsonPrimitive(true)))))
            denied(MediaFailureCode.INPUT_INVALID){f.store.prepareMediaUpload(f.account,UUID.randomUUID(),changed)}
        assertEquals(0,f.count("platform.idempotency"));assertTrue(f.signer.authorizations.isEmpty())
    }
    @Test fun responseCapabilityAdmissionFailsBeforeReservationCommit() {
        val f=fixture();val store=f.newStore(f.policy(maxResponseBytes=1000))
        denied(MediaFailureCode.RESPONSE_TOO_LARGE){store.prepareMediaUpload(f.account,UUID.randomUUID(),f.prepareBody())}
        for(table in listOf("platform.media_assets","platform.media_draft_lifecycles","platform.idempotency"))assertEquals(0,f.count(table))
    }
    @Test fun signerFailureAfterCoreCommitRetainsOriginalRetryWithoutCompensation() {
        val f=fixture();val key=UUID.randomUUID();val input=f.prepareBody();f.signer.beforeSign={throw IllegalStateException("private-provider-detail")}
        denied(MediaFailureCode.CAPABILITY_UNAVAILABLE){f.store.prepareMediaUpload(f.account,key,input)}
        assertEquals(1,f.count("platform.media_assets"));assertEquals(1,f.count("platform.idempotency"));assertEquals(0,f.count("platform.media_cleanup_jobs"))
        val coreText=f.value("SELECT response_json::text FROM platform.idempotency");f.signer.beforeSign=null
        val recovered=f.prepare(key,input);assertEquals(Json.parseToJsonElement(coreText),core(recovered));assertEquals(1,f.count("platform.media_assets"))
    }
    @Test fun unsafeExpiredExtendedOrOversizedCapabilityNeverEscapesAndCoreRemains() {
        val f=fixture();val key=UUID.randomUUID();val input=f.prepareBody()
        val bad=listOf<(MediaUploadCapability)->MediaUploadCapability>(
            {MediaUploadCapability("https://foreign.invalid",it.fields,it.expiresAt)},
            {MediaUploadCapability(it.url,it.fields,it.expiresAt.plusSeconds(3601))},
            {MediaUploadCapability(it.url,it.fields,Instant.EPOCH)},
            {MediaUploadCapability(it.url,mapOf("policy" to "x".repeat(5000)),it.expiresAt)})
        for(change in bad){f.signer.transform=change;denied(MediaFailureCode.CAPABILITY_UNAVAILABLE){f.store.prepareMediaUpload(f.account,key,input)}}
        assertEquals(1,f.count("platform.media_assets"));f.signer.transform=null;assertEquals("awaitingUpload",f.prepare(key,input).text("status"))
    }
    @Test fun expiredReservationDoesNotRenewButStatusAndDeletionRemainAvailable() {
        val f=fixture();val key=UUID.randomUUID();val input=f.prepareBody();val media=f.prepare(key,input);f.expire(id(media))
        denied(MediaFailureCode.UPLOAD_EXPIRED){f.store.prepareMediaUpload(f.account,key,input)}
        denied(MediaFailureCode.UPLOAD_EXPIRED){f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),complete())}
        assertEquals("awaitingUpload",f.store.getMediaStatus(f.account,id(media)).body!!.jsonObject.text("status"))
        assertIs<CommandResult.Applied>(f.store.deleteDraftMedia(f.account,UUID.randomUUID(),id(media),"\"1\""));assertEquals(1,f.signer.authorizations.size)
    }
    @Test fun immutableVersionCompletionQueuesProcessingNotReadyOrPublication() {
        val f=fixture();val media=f.prepare();val body=f.upload(media);val result=MediaTestFixture.reply(f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body))
        assertEquals("processing",result.body!!.jsonObject.text("status"));assertEquals("\"2\"",result.etag)
        assertFalse(result.body.toString().contains("uploadUrl"));assertEquals(1,f.objects.requests.size)
        assertEquals("platform.media.upload_completed.v1",f.value("SELECT event_type FROM platform.outbox"));assertEquals(1,f.count("platform.outbox"))
        assertEquals("0",f.value("SELECT count(*) FROM platform.media_assets WHERE state='ready' OR derivative_set IS NOT NULL"))
    }
    @Test fun sameCompletionKeyReplaysWithoutReverifyingButOldPrepareCannotReissue() {
        val f=fixture();val prepareKey=UUID.randomUUID();val input=f.prepareBody();val media=f.prepare(prepareKey,input);val body=f.upload(media);val key=UUID.randomUUID()
        val first=MediaTestFixture.reply(f.store.completeMediaUpload(f.account,key,id(media),body))
        val second=MediaTestFixture.reply(f.store.completeMediaUpload(f.account,key,id(media),body));assertEquals(first.body,second.body)
        denied(MediaFailureCode.MEDIA_CONFLICT){f.store.prepareMediaUpload(f.account,prepareKey,input)}
        denied(MediaFailureCode.MEDIA_CONFLICT){f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body)}
        assertEquals(1,f.objects.requests.size);assertEquals(1,f.count("platform.outbox"));assertEquals(1,f.signer.authorizations.size)
    }
    @Test fun completionReplaysOriginalAcknowledgementAfterReadyOrRejectedWithoutRewritingCurrentStatus() {
        for(state in listOf("ready","rejected")) {
            val f=fixture();val media=f.prepare();val body=f.upload(media);val key=UUID.randomUUID()
            val accepted=MediaTestFixture.reply(f.store.completeMediaUpload(f.account,key,id(media),body))
            f.finishProcessing(media,state)
            val before=completionSnapshot(f,key)
            val replay=assertIs<CommandResult.Replayed>(f.store.completeMediaUpload(f.account,key,id(media),body)).reply
            assertEquals(accepted.body,replay.body);assertEquals(accepted.etag,replay.etag);assertEquals(accepted.status,replay.status)
            assertEquals("processing",replay.body!!.jsonObject.text("status"));assertEquals("\"2\"",replay.etag)
            val current=f.store.getMediaStatus(f.account,id(media));assertEquals(state,current.body!!.jsonObject.text("status"));assertEquals("\"3\"",current.etag)
            for(privateField in listOf("errorCode","uploadUrl","derivative","completion_"))assertFalse(replay.body.toString().contains(privateField))
            assertEquals(before,completionSnapshot(f,key));assertEquals(1,f.count("platform.outbox"));assertEquals(2,f.count("platform.idempotency"))
            assertEquals(1,f.objects.requests.size);assertEquals(1,f.signer.authorizations.size)
        }
    }
    @Test fun lostActualCompletionCommitReceiptRemainsRecoverableAfterEitherProcessorOutcome() {
        for(state in listOf("ready","rejected")) {
            val f=fixture();val media=f.prepare();val body=f.upload(media);val key=UUID.randomUUID()
            f.objects.beforeVerify={f.faults.loseCommit=true}
            assertFailsWith<CommitOutcomeUnknown>{f.store.completeMediaUpload(f.account,key,id(media),body)}
            f.objects.beforeVerify=null
            val original=Json.parseToJsonElement(f.value("SELECT response_json::text FROM platform.idempotency WHERE key='$key'"))
            f.finishProcessing(media,state)
            val replay=assertIs<CommandResult.Replayed>(f.store.completeMediaUpload(f.account,key,id(media),body)).reply
            assertEquals(original,replay.body);assertEquals("\"2\"",replay.etag)
            assertEquals(state,f.store.getMediaStatus(f.account,id(media)).body!!.jsonObject.text("status"))
            assertEquals(1,f.count("platform.media_assets"));assertEquals(2,f.count("platform.idempotency"));assertEquals(1,f.count("platform.outbox"))
            assertEquals(1,f.objects.requests.size);assertEquals(1,f.signer.authorizations.size)
        }
    }
    @Test fun processorTransitionBetweenInitialReadAndReceiptLockDoesNotInvalidateAcknowledgement() {
        val f=fixture();val media=f.prepare();val body=f.upload(media);val key=UUID.randomUUID()
        val accepted=MediaTestFixture.reply(f.store.completeMediaUpload(f.account,key,id(media),body))
        // Actual first read transaction commits and releases its locks before this synthetic worker.
        f.faults.afterCommit={f.finishProcessing(media,"ready")}
        val replay=assertIs<CommandResult.Replayed>(f.store.completeMediaUpload(f.account,key,id(media),body)).reply
        assertEquals(accepted.body,replay.body);assertEquals(accepted.etag,replay.etag)
        assertEquals("ready",f.store.getMediaStatus(f.account,id(media)).body!!.jsonObject.text("status"))
        assertEquals(1,f.objects.requests.size);assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun committedCompletionOutlivesReservationButNeverExtendsItsOwnReceiptLifetime() {
        val f=fixture();val selected=f.newStore(f.policy(reservationLifetimeSeconds=3,capabilityLifetimeSeconds=1))
        val media=f.prepare(selected=selected);val body=f.upload(media);val key=UUID.randomUUID()
        val accepted=MediaTestFixture.reply(selected.completeMediaUpload(f.account,key,id(media),body));f.finishProcessing(media,"rejected")
        // Wait for the real original database deadline; do not mutate its immutable lineage.
        f.sql("SELECT pg_sleep(GREATEST(0,EXTRACT(EPOCH FROM reservation_expires_at-clock_timestamp()))+0.02) FROM platform.media_assets WHERE id='${id(media)}'")
        val before=completionSnapshot(f,key)
        val replay=assertIs<CommandResult.Replayed>(selected.completeMediaUpload(f.account,key,id(media),body)).reply
        assertEquals(accepted.body,replay.body);assertEquals(before,completionSnapshot(f,key))
        f.sql("UPDATE platform.idempotency SET expires_at=clock_timestamp()-interval '1 second' WHERE key='$key'")
        assertIs<CommandResult.ReceiptExpired>(selected.completeMediaUpload(f.account,key,id(media),body))
        assertIs<CommandResult.ReceiptExpired>(selected.completeMediaUpload(f.account,key,id(media),body))
        assertEquals("tombstone",f.value("SELECT state FROM platform.idempotency WHERE key='$key'"))
        assertEquals(1,f.count("platform.outbox"));assertEquals(1,f.objects.requests.size);assertEquals(1,f.signer.authorizations.size)
    }
    @Test fun advancedCompletionRequiresOriginalKeyBodyOwnerAndUndeletedIncarnation() {
        val f=fixture();val media=f.prepare();val body=f.upload(media);val key=UUID.randomUUID()
        f.store.completeMediaUpload(f.account,key,id(media),body);f.finishProcessing(media,"ready")
        val before=completionSnapshot(f,key)
        for(changed in listOf(JsonObject(body+("objectVersionId" to JsonPrimitive("other-version"))),JsonObject(body+("sha256" to JsonPrimitive("b".repeat(64))))))
            assertIs<CommandResult.Mismatch>(f.store.completeMediaUpload(f.account,key,id(media),changed))
        denied(MediaFailureCode.MEDIA_CONFLICT){f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body)}
        denied(MediaFailureCode.MEDIA_UNAVAILABLE){f.store.completeMediaUpload(f.principal(),key,id(media),body)}
        assertEquals(before,completionSnapshot(f,key));assertEquals(2,f.count("platform.idempotency"))
        f.store.deleteDraftMedia(f.account,UUID.randomUUID(),id(media),"\"3\"")
        denied(MediaFailureCode.MEDIA_CONFLICT){f.store.completeMediaUpload(f.account,key,id(media),body)}
        assertEquals("deleted",f.store.getMediaStatus(f.account,id(media)).body!!.jsonObject.text("status"))
        assertEquals(1,f.count("platform.outbox"));assertEquals(1,f.objects.requests.size)
    }
    @Test fun advancedCompletionStillRechecksDeviceTermsAndExactDraftGeneration() {
        val f=fixture();val media=f.prepare();val body=f.upload(media);val key=UUID.randomUUID()
        f.store.completeMediaUpload(f.account,key,id(media),body);f.finishProcessing(media,"rejected")
        val before=completionSnapshot(f,key)
        f.authority.termsAccepted=false;denied(MediaFailureCode.FORBIDDEN){f.store.completeMediaUpload(f.account,key,id(media),body)};f.authority.termsAccepted=true
        f.authority.uploadsAllowed=false;denied(MediaFailureCode.NOT_CONFIGURED){f.store.completeMediaUpload(f.account,key,id(media),body)};f.authority.uploadsAllowed=true
        f.lifecycle{c->c.createStatement().use{it.executeUpdate("UPDATE media_test.drafts SET generation=generation+1")}}
        denied(MediaFailureCode.DRAFT_UNAVAILABLE){f.store.completeMediaUpload(f.account,key,id(media),body)}
        f.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='${f.account.deviceSessionId}'")
        denied(MediaFailureCode.UNAUTHENTICATED){f.store.completeMediaUpload(f.account,key,id(media),body)}
        assertEquals(before,completionSnapshot(f,key));assertEquals(1,f.objects.requests.size);assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun completionPinsCannotBeReplacedAndCorruptCachedAcknowledgementFailsClosed() {
        val f=fixture();val media=f.prepare();val body=f.upload(media);val key=UUID.randomUUID()
        f.store.completeMediaUpload(f.account,key,id(media),body)
        for(change in listOf("completion_key='${UUID.randomUUID()}'","completion_version=3","completion_version=NULL","completion_accepted_at=completion_accepted_at+interval '1 microsecond'","quarantine_version_id='replacement'"))
            assertFailsWith<SQLException>{f.sql("UPDATE platform.media_assets SET state='rejected',version=3,rejection_code='SYNTHETIC_REJECTION',updated_at=clock_timestamp(),$change WHERE id='${id(media)}'")}
        f.finishProcessing(media,"rejected")
        f.sql("UPDATE platform.idempotency SET response_json=jsonb_set(response_json,'{updatedAt}','\"2000-01-01T00:00:00Z\"') WHERE key='$key'")
        denied(MediaFailureCode.MEDIA_CONFLICT){f.store.completeMediaUpload(f.account,key,id(media),body)}
        assertEquals("rejected",f.store.getMediaStatus(f.account,id(media)).body!!.jsonObject.text("status"));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun wrongChecksumVersionOrImmutableObjectEvidenceCannotAdvanceState() {
        val f=fixture();val media=f.prepare();val body=f.upload(media)
        denied(MediaFailureCode.OBJECT_MISMATCH){f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),JsonObject(body+("objectVersionId" to JsonPrimitive("unowned-version"))))}
        val changes=listOf<(VerifiedMediaObject)->VerifiedMediaObject>(
            {VerifiedMediaObject("other-key",it.objectVersionId,it.bytes,it.contentType,it.sha256)},
            {VerifiedMediaObject(it.objectKey,"other-version",it.bytes,it.contentType,it.sha256)},
            {VerifiedMediaObject(it.objectKey,it.objectVersionId,129,it.contentType,it.sha256)},
            {VerifiedMediaObject(it.objectKey,it.objectVersionId,it.bytes,"image/jpeg",it.sha256)},
            {VerifiedMediaObject(it.objectKey,it.objectVersionId,it.bytes,it.contentType,"b".repeat(64))})
        for(change in changes){f.objects.transform=change;denied(MediaFailureCode.OBJECT_MISMATCH){f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body)}}
        assertEquals("awaitingUpload",f.store.getMediaStatus(f.account,id(media)).body!!.jsonObject.text("status"));assertEquals(0,f.count("platform.outbox"))
    }
    @Test fun externalVerificationHasNoHeldTransactionAndChangedDraftIsRechecked() {
        val f=fixture();val input=f.prepareBody();val media=f.prepare(body=input);val body=f.upload(media)
        f.objects.beforeVerify={f.lifecycle{c->c.createStatement().use{it.executeUpdate("UPDATE media_test.drafts SET eligible=false WHERE id='${input.text("clientDraftId")}'")}}}
        denied(MediaFailureCode.DRAFT_UNAVAILABLE){f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body)}
        assertEquals(0,f.count("platform.outbox"));assertEquals(1,f.count("platform.idempotency"))
    }
    @Test fun deletionDuringExternalVerificationCannotBeResurrected() {
        val f=fixture();val media=f.prepare();val body=f.upload(media)
        f.objects.beforeVerify={f.store.deleteDraftMedia(f.account,UUID.randomUUID(),id(media),"\"1\"")}
        denied(MediaFailureCode.MEDIA_CONFLICT){f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body)}
        assertEquals("deleted",f.store.getMediaStatus(f.account,id(media)).body!!.jsonObject.text("status"));assertEquals(0,f.count("platform.outbox"))
    }
    @Test fun expiryAfterVerificationStartedStillPreventsProcessingCommit() {
        val f=fixture();val selected=f.newStore(f.policy(reservationLifetimeSeconds=2,capabilityLifetimeSeconds=1));val media=f.prepare(selected=selected);val body=f.upload(media)
        f.objects.beforeVerify={Thread.sleep(2200)}
        denied(MediaFailureCode.UPLOAD_EXPIRED){selected.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body)}
        assertEquals(0,f.count("platform.outbox"));assertEquals(1,f.count("platform.idempotency"))
    }
    @Test fun externalFailureIsTypedAndNeverRetainsProviderDetailOrEffects() {
        val f=fixture();val media=f.prepare();val body=f.upload(media);f.objects.beforeVerify={throw IllegalStateException("secret-provider-error")}
        val failure=assertFailsWith<MediaFailure>{f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body)}
        assertEquals(MediaFailureCode.OBJECT_VERIFICATION_UNAVAILABLE,failure.code);assertFalse(failure.message!!.contains("secret"));assertEquals(0,f.count("platform.outbox"))
    }
    @Test fun outboxFailureRollsBackProcessingAndReceiptTogether() {
        val f=fixture();val media=f.prepare();val body=f.upload(media);val key=UUID.randomUUID();f.faults.outboxFailure=true
        denied(MediaFailureCode.STORAGE_UNAVAILABLE){f.store.completeMediaUpload(f.account,key,id(media),body)}
        assertEquals("awaitingUpload",f.store.getMediaStatus(f.account,id(media)).body!!.jsonObject.text("status"));assertEquals(1,f.count("platform.idempotency"))
        assertIs<CommandResult.Applied>(f.store.completeMediaUpload(f.account,key,id(media),body));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun lostActualCommitReceiptRecoversSameReservationAndCompletion() {
        val f=fixture();val input=f.prepareBody();val key=UUID.randomUUID();f.faults.loseCommit=true
        assertFailsWith<CommitOutcomeUnknown>{f.store.prepareMediaUpload(f.account,key,input)}
        assertTrue(f.signer.authorizations.isEmpty());val media=f.prepare(key,input);assertEquals(1,f.count("platform.media_assets"))
        val body=f.upload(media);val completeKey=UUID.randomUUID();f.objects.beforeVerify={f.faults.loseCommit=true}
        assertFailsWith<CommitOutcomeUnknown>{f.store.completeMediaUpload(f.account,completeKey,id(media),body)}
        f.objects.beforeVerify=null;assertIs<CommandResult.Replayed>(f.store.completeMediaUpload(f.account,completeKey,id(media),body));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun cancellationDuringExternalVerificationCannotWriteProcessing() {
        val f=fixture();val media=f.prepare();val body=f.upload(media);f.objects.beforeVerify={throw CancellationException("synthetic caller cancellation")}
        assertFailsWith<CancellationException>{f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),body)}
        assertEquals(0,f.count("platform.outbox"));assertEquals(1,f.count("platform.idempotency"))
    }
    @Test fun interruptedPostCommitSigningLeavesExactlyOneRecoverableCore() {
        val f=fixture();val input=f.prepareBody();val key=UUID.randomUUID();f.signer.beforeSign={Thread.currentThread().interrupt()}
        try{assertFailsWith<InterruptedException>{f.store.prepareMediaUpload(f.account,key,input)}}finally{Thread.interrupted()}
        assertEquals(1,f.count("platform.media_assets"));f.signer.beforeSign=null;assertEquals("awaitingUpload",f.prepare(key,input).text("status"))
    }
    @Test fun exactDeleteReceiptRetainsLateUploadCleanupAndCannotDeleteSibling() {
        val f=fixture();val first=f.prepare();val second=f.prepare();val key=UUID.randomUUID()
        denied(MediaFailureCode.VERSION_CONFLICT){f.store.deleteDraftMedia(f.account,key,id(first),"\"2\"")}
        assertIs<CommandResult.Applied>(f.store.deleteDraftMedia(f.account,key,id(first),"\"1\""));assertIs<CommandResult.Replayed>(f.store.deleteDraftMedia(f.account,key,id(first),"\"1\""))
        assertEquals("awaitingUpload",f.store.getMediaStatus(f.account,id(second)).body!!.jsonObject.text("status"));assertEquals(1,f.count("platform.media_cleanup_jobs"))
        assertEquals("1",f.value("SELECT CASE WHEN j.final_sweep_after=m.reservation_expires_at AND j.completed_at IS NULL AND j.quarantine_key=m.quarantine_key THEN 1 ELSE 0 END FROM platform.media_cleanup_jobs j JOIN platform.media_assets m ON m.id=j.media_id"))
        f.upload(first,"late-write-after-delete")
        denied(MediaFailureCode.MEDIA_CONFLICT){f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(first),complete("late-write-after-delete"))}
        denied(MediaFailureCode.MEDIA_UNAVAILABLE){f.store.deleteDraftMedia(f.account,UUID.randomUUID(),id(first),"\"1\"")}
    }
    @Test fun attachedMediaCannotBeDeletedEvenWhenSameOwnerAndVersionMatches() {
        val f=fixture();val media=f.prepare();f.sql("INSERT INTO media_test.attached VALUES('${f.account.accountId}','${id(media)}')")
        denied(MediaFailureCode.MEDIA_ATTACHED){f.store.deleteDraftMedia(f.account,UUID.randomUUID(),id(media),"\"1\"")}
        assertEquals(0,f.count("platform.media_cleanup_jobs"));assertEquals(1,f.count("platform.idempotency"))
    }
    @Test fun syntheticReadyAssetDeletionRetainsExactDerivativeManifestAndReplayPin() {
        val f=fixture();val media=f.prepare();f.store.completeMediaUpload(f.account,UUID.randomUUID(),id(media),f.upload(media))
        val derivatives=buildJsonObject{put("version",1);put("variants",buildJsonArray{add(buildJsonObject{put("variant","display");put("key","derivatives/test/${id(media)}/display");put("objectVersionId","sanitized-version-1")})})}
        // Synthetic processor output, not proof an actual decoder/scanner/moderator exists.
        f.sql("UPDATE platform.media_assets SET state='ready',version=3,derivative_set='$derivatives'::jsonb WHERE id='${id(media)}'")
        val key=UUID.randomUUID();f.store.deleteDraftMedia(f.account,key,id(media),"\"3\"")
        assertEquals(derivatives,Json.parseToJsonElement(f.value("SELECT derivative_set::text FROM platform.media_cleanup_jobs")))
        assertEquals("0",f.value("SELECT count(*) FROM platform.media_assets WHERE derivative_set IS NOT NULL"))
        assertIs<CommandResult.Replayed>(f.store.deleteDraftMedia(f.account,key,id(media),"\"3\""))
        f.sql("UPDATE platform.media_cleanup_jobs SET derivative_set=jsonb_set(derivative_set,'{variants,0,objectVersionId}','\"replacement\"')")
        denied(MediaFailureCode.STORAGE_UNAVAILABLE){f.store.deleteDraftMedia(f.account,key,id(media),"\"3\"")}
    }
    @Test fun databaseRejectsReservationMutationAndDeletionResurrection() {
        val f=fixture();val media=f.prepare()
        assertFailsWith<SQLException>{f.sql("UPDATE platform.media_assets SET expected_bytes=129 WHERE id='${id(media)}'")}
        f.store.deleteDraftMedia(f.account,UUID.randomUUID(),id(media),"\"1\"")
        assertFailsWith<SQLException>{f.sql("UPDATE platform.media_assets SET state='awaitingUpload',version=3,deletion_key=NULL,cleanup_manifest_hash=NULL WHERE id='${id(media)}'")}
    }
    @Test fun concurrentSameKeyReservationsSerializeOneCoreWithCurrentCapabilityPerCaller() {
        val f=fixture();val input=f.prepareBody();val key=UUID.randomUUID();val start=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        try{val futures=(1..2).map{executor.submit<JsonObject>{check(start.await(3,TimeUnit.SECONDS));f.prepare(key,input)}};start.countDown()
            val replies=futures.map{it.get(10,TimeUnit.SECONDS)};assertEquals(core(replies[0]),core(replies[1]));assertEquals(1,f.count("platform.media_assets"));assertEquals(1,f.count("platform.idempotency"))
        }finally{start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS))}
    }
    companion object {
        private lateinit var cluster:PostgresTestCluster
        @JvmField @ClassRule val timeout=Timeout(10,TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun startCluster(){cluster=PostgresTestCluster.start()}
        @JvmStatic @AfterClass fun closeCluster(){if(::cluster.isInitialized)cluster.close()}
        private fun fixture()=MediaTestFixture(cluster.database())
        private fun completionSnapshot(f:MediaTestFixture,key:UUID)=listOf(
            f.value("SELECT row_to_json(r)::text FROM (SELECT response_json,response_etag,updated_at,expires_at FROM platform.idempotency WHERE key='$key') r"),
            f.value("SELECT row_to_json(m)::text FROM platform.media_assets m"))
        private fun id(body:JsonObject)=UUID.fromString(body.text("id"))
        private fun core(body:JsonObject)=JsonObject(body.filterKeys{!it.startsWith("upload")})
        private fun JsonObject.text(name:String)=getValue(name).jsonPrimitive.content
        private fun complete(version:String="object-version-1")=buildJsonObject{put("objectVersionId",version);put("sha256",MediaTestFixture.CHECKSUM)}
        private fun denied(code:MediaFailureCode,action:()->Any?)=assertEquals(code,assertFailsWith<MediaFailure>{action()}.code)
    }
}
