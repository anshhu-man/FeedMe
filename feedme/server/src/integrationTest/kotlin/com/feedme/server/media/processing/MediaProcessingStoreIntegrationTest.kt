package com.feedme.server.media.processing

import com.feedme.server.db.*
import com.feedme.server.media.MediaTestFixture
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Real PostgreSQL and actual durable command/outbox/worker transactions. Synthetic authority,
 * object provider and safety are labelled in the fixture; actual JPEG/PNG child codec has its own
 * end-to-end case here. No deployed broker/moderation/object service is implied. */
class MediaProcessingStoreIntegrationTest {
    @Test fun committedEventAdmissionAndExactDuplicatePinOneJobWithoutChangingMediaOrSigning() {
        val f=f(); val event=f.seed(); val before=f.value("SELECT row_to_json(m)::text FROM platform.media_assets m")
        val job=f.store.ingest(event); assertEquals(job,f.store.ingest(event))
        assertEquals(1,f.count("platform.media_processing_jobs"));assertEquals(1,f.count("platform.media_processing_inbox"))
        assertEquals(before,f.value("SELECT row_to_json(m)::text FROM platform.media_assets m"));assertEquals(1,f.count("platform.outbox"))
        assertEquals(0,f.objects.reads);assertEquals(0,f.objects.writes);assertEquals(1,f.media.signer.authorizations.size)
    }
    @Test fun changedDuplicatePayloadAndUncommittedEventCannotAcquireAJob() {
        val f=f();val event=f.seed();f.store.ingest(event)
        val changed=changedEvent(event,data=JsonObject(event.draft.data+("checksum" to JsonPrimitive("b".repeat(64)))))
        fail(MediaProcessingFailureCode.INVALID_EVENT){f.store.ingest(changed)}
        fail(MediaProcessingFailureCode.INVALID_EVENT){f.store.ingest(changedEvent(event,id=UUID.randomUUID()))}
        assertEquals(1,f.count("platform.media_processing_jobs"));assertEquals(1,f.count("platform.media_processing_inbox"))
    }
    @Test fun malformedPurposeProducerDataAndForeignEnvironmentAreNotObjectAuthority() {
        val f=f();val original=f.seed()
        for(e in listOf(changedEvent(original,type="platform.media.ready.v1"),changedEvent(original,producer="social"),
            changedEvent(original,data=JsonObject(original.draft.data+("bucket" to JsonPrimitive("foreign-private")))),changedEvent(original,version=3)))
            fail(MediaProcessingFailureCode.INVALID_EVENT){f.store.ingest(e)}
        val foreign=MediaProcessingStore("other",PgTransactions(f.database),f.authority,f.policy)
        fail(MediaProcessingFailureCode.INVALID_EVENT){foreign.ingest(original)}
        assertEquals(0,f.count("platform.media_processing_jobs"));assertEquals(0,f.objects.reads)
    }
    @Test fun unknownIngestionCommitReconcilesSameFingerprintAndJob() {
        val f=f();val e=f.seed();f.media.faults.loseCommit=true
        assertFailsWith<CommitOutcomeUnknown>{f.store.ingest(e)}
        val before=f.value("SELECT id::text FROM platform.media_processing_jobs")
        assertEquals(before,f.store.ingest(e).toString());assertEquals(1,f.count("platform.media_processing_inbox"))
    }
    @Test fun concurrentClaimersCannotOwnTheSameUnexpiredProcessingLease() {
        val f=f();f.store.ingest(f.seed());val executor=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try { val futures=(1..2).map{executor.submit<MediaProcessingLease?>{check(start.await(3,TimeUnit.SECONDS));f.store.claim()}}
            start.countDown();val leases=futures.map{it.get(10,TimeUnit.SECONDS)}.filterNotNull();assertEquals(1,leases.size)
            assertEquals(1,leases.single().attempt);assertEquals(1L,leases.single().generation)
        }finally{start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS))}
    }
    @Test fun expiredLeaseIsReclaimedWithNewTokenAndOldLeaseCannotRenewOrFinalize() {
        val f=f();val old=f.claimed();f.expireLease(old.jobId);val next=assertNotNull(f.store.claim())
        assertEquals(old.jobId,next.jobId);assertNotEquals(old.token,next.token);assertEquals(old.generation+1,next.generation)
        fail(MediaProcessingFailureCode.STALE_LEASE){f.store.renew(old)}
        fail(MediaProcessingFailureCode.STALE_LEASE){f.store.reject(old,PhotoRejection.MALFORMED_IMAGE)}
        assertNull(f.store.begin(next));assertEquals("processing",state(f))
    }
    @Test fun exhaustedProcessingAttemptsQuarantineWorkWithoutContentRejectionOrReadyFact() {
        val f=f(MediaProcessingTestFixture.policy(attempts=1));val lease=f.claimed();f.expireLease(lease.jobId)
        assertNull(f.store.claim());assertEquals("quarantined",f.value("SELECT state FROM platform.media_processing_jobs"))
        assertEquals("processing",state(f));assertEquals(1,f.count("platform.outbox"));assertEquals(0,f.objects.writes)
    }
    @Test fun lifecycleOrPolicyRevocationAfterClaimDeniesEveryExternalProcessingEffect() {
        for(which in listOf("account","draft","policy")){val f=f();val lease=f.claimed()
            when(which){"account"->f.sql("UPDATE cooking_test.principals SET active=false");"draft"->f.sql("UPDATE media_test.drafts SET eligible=false");else->f.authority.enabled=false}
            assertFailsWith<MediaProcessingFailure>{f.processor.process(lease)}
            assertEquals(0,f.objects.reads);assertEquals(0,f.objects.writes);assertEquals("processing",state(f))
        }
    }
    @Test fun actualPngAndJpegChildProcessingProduceTwoDecodablePrivateDerivativesAndOneReadyFact() {
        for(format in listOf("png","jpeg")){val f=f();val event=f.seed(MediaProcessingTestFixture.image(format),"image/$format")
            f.store.ingest(event);val lease=assertNotNull(f.store.claim())
            val result=MediaProcessor(f.store,f.realCodec(),f.objects,f.safety).process(lease)
            assertEquals(MediaProcessingTerminal.READY,result.result);assertEquals(3L,result.mediaVersion)
            val outputs=f.objects.data.filterKeys{it.first.startsWith("derivatives/")};assertEquals(2,outputs.size)
            outputs.values.forEach{assertNotNull(ImageIO.read(it.inputStream()))}
            assertEquals(2,f.count("platform.media_derivative_intents"));assertEquals("ready",state(f))
            assertEquals(setOf("mediaId","derivativeSetVersion"),Json.parseToJsonElement(f.value("SELECT payload::text FROM platform.outbox WHERE event_type='platform.media.ready.v1'")).jsonObject.keys)
            assertEquals(1,f.count("platform.media_processing_cleanup")) // Only original quarantine; live derivatives remain owned.
        }
    }
    @Test fun immutableSourceReadsIgnoreANewerMutableKeyVersionAndRejectMissingOriginal() {
        val f=f();val lease=f.claimed();f.objects.data[lease.source.objectKey to "newer-version"]=byteArrayOf(99)
        assertEquals(MediaProcessingTerminal.READY,f.processor.process(lease).result)
        val missing=f();val other=missing.claimed();missing.objects.data.clear()
        fail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE){missing.processor.process(other)}
        assertEquals("processing",state(missing));assertEquals(0,missing.safety.calls);assertEquals(0,missing.objects.writes)
    }
    @Test fun actualReadLengthAndDigestAreCheckedBeforeCodecSafetyOrWrites() {
        for(bytes in listOf(ByteArray(129),ByteArray(128){7})){val f=f();val lease=f.claimed();f.objects.data[lease.source.objectKey to lease.source.objectVersionId]=bytes
            fail(MediaProcessingFailureCode.OBJECT_MISMATCH){f.processor.process(lease)}
            assertEquals(0,f.codec.calls);assertEquals(0,f.safety.calls);assertEquals(0,f.objects.writes);assertEquals("processing",state(f))
        }
    }
    @Test fun codecResourceFailureAndSafetyPendingNeverTurnIntoContentVerdicts() {
        for(mode in listOf("codec","pending","unavailable")){val f=f();val lease=f.claimed()
            when(mode){"codec"->f.codec.result=PhotoDecodeResult.Unavailable(PhotoCodecUnavailable.PROCESS_TIMEOUT);"pending"->f.safety.result=MediaSafetyResult.Pending;else->f.safety.result=MediaSafetyResult.Unavailable}
            assertFailsWith<MediaProcessingFailure>{f.processor.process(lease)}
            assertEquals("processing",state(f));assertEquals(1,f.count("platform.outbox"));assertEquals(0,f.objects.writes)
            assertEquals("retry",f.value("SELECT state FROM platform.media_processing_jobs"))
        }
    }
    @Test fun malformedDecodedVariantInventoryIsRejectedBeforeExternalSafetyAssessment() {
        val f=f();val lease=f.claimed();f.codec.result=PhotoDecodeResult.Decoded(listOf(EncodedPhotoVariant(PhotoVariant.DISPLAY,"image/png",1,1,ByteArray(10001))))
        assertFailsWith<MediaProcessingFailure>{f.processor.process(lease)}
        assertEquals(0,f.safety.calls);assertEquals(0,f.objects.writes);assertEquals(0,f.count("platform.media_derivative_intents"))
    }
    @Test fun validatedInputOrExplicitSafetyRejectionCommitsOneSafeFactAndCleanupWithoutWrites() {
        for(scan in listOf(false,true)){val f=f();val lease=f.claimed()
            if(scan)f.safety.result=MediaSafetyResult.Rejected(MediaSafetyRejection.MALWARE_DETECTED) else f.codec.result=PhotoDecodeResult.Rejected(PhotoRejection.MALFORMED_IMAGE)
            val result=f.processor.process(lease);assertEquals(MediaProcessingTerminal.REJECTED,result.result)
            assertEquals("rejected",state(f));assertEquals(0,f.objects.writes);assertEquals(1,f.count("platform.media_processing_cleanup"))
            assertEquals(setOf("mediaId","safeReasonCode"),Json.parseToJsonElement(f.value("SELECT payload::text FROM platform.outbox WHERE event_type='platform.media.rejected.v1'")).jsonObject.keys)
        }
    }
    @Test fun outputIntentsPrecedeWritesAndLostWriteReplyReconcilesWithoutAnotherDestination() {
        val f=f();val lease=f.claimed();f.objects.loseWrite=true
        f.objects.afterWrite={assertEquals(2,f.count("platform.media_derivative_intents"));assertEquals("processing",state(f))}
        fail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE){f.processor.process(lease)}
        val destinations=f.value("SELECT string_agg(object_key,',' ORDER BY variant) FROM platform.media_derivative_intents")
        assertEquals(1,f.objects.writes);f.eligible();val next=assertNotNull(f.store.claim())
        assertEquals(MediaProcessingTerminal.READY,f.processor.process(next).result);assertEquals(2,f.objects.writes)
        assertEquals(destinations,f.value("SELECT string_agg(object_key,',' ORDER BY variant) FROM platform.media_derivative_intents"))
    }
    @Test fun regeneratedDifferentBytesCannotReplaceAnOriginalWriteIntent() {
        val f=f();val lease=f.claimed();f.objects.loseWrite=true;assertFailsWith<MediaProcessingFailure>{f.processor.process(lease)}
        f.codec.result=PhotoDecodeResult.Decoded(outputs(9));f.eligible()
        fail(MediaProcessingFailureCode.CONFLICT){f.processor.process(assertNotNull(f.store.claim()))}
        assertEquals(1,f.objects.writes);assertEquals(2,f.count("platform.media_derivative_intents"));assertEquals("processing",state(f))
    }
    @Test fun wrongExistingImmutableDerivativeReceiptCannotBePromotedToReady() {
        val f=f();val lease=f.claimed();val intent=f.store.prepareDerivatives(lease,outputs()).first()
        f.objects.data[intent.objectKey to "foreign-version"]=byteArrayOf(88)
        fail(MediaProcessingFailureCode.OBJECT_MISMATCH){f.processor.process(lease)}
        assertEquals("processing",state(f));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun unknownDerivativeReceiptCommitReplaysOnlyExactObjectVersion() {
        val f=f();val lease=f.claimed();val intent=f.store.prepareDerivatives(lease,outputs()).first()
        val receipt=f.objects.create(intent,outputs().single{it.variant==intent.variant}.copyBytes());f.media.faults.loseCommit=true
        assertFailsWith<CommitOutcomeUnknown>{f.store.acknowledge(intent,receipt)};f.store.acknowledge(intent,receipt)
        fail(MediaProcessingFailureCode.OBJECT_MISMATCH){f.store.acknowledge(intent,MediaDerivativeReceipt(receipt.objectKey,"replacement",receipt.sha256,receipt.bytes,receipt.contentType))}
        assertEquals(1,f.objects.writes);assertEquals("1",f.value("SELECT count(*) FROM platform.media_derivative_intents WHERE object_version_id IS NOT NULL"))
    }
    @Test fun expiredOrRevokedSafetyCannotFinalizeAcknowledgedPrivateOutputs() {
        for(expired in listOf(false,true)){val f=f();val lease=f.claimed();val variants=outputs();val intents=f.store.prepareDerivatives(lease,variants)
            intents.forEach{f.store.acknowledge(it,f.objects.create(it,variants.single{v->v.variant==it.variant}.copyBytes()))}
            val proof=if(expired)MediaSafetyEvidence("synthetic-safety-receipt","synthetic-both-scans-v1",lease.source.sha256,variants.associate{it.variant to MediaProcessingTestFixture.hash(it.copyBytes())},Instant.now().minusSeconds(10),Instant.now().minusSeconds(1)) else f.safety.proof(lease.source,variants)
            if(!expired)f.authority.approved=false
            fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE){f.store.ready(lease,proof)}
            assertEquals("processing",state(f));assertEquals(1,f.count("platform.outbox"))
        }
    }
    @Test fun unknownReadyCommitKeepsExactTerminalReceiptAndCompletionReplayAfterLaterDeletion() {
        val f=f();val lease=f.claimed();val v=outputs();val intents=f.store.prepareDerivatives(lease,v)
        intents.forEach{f.store.acknowledge(it,f.objects.create(it,v.single{v->v.variant==it.variant}.copyBytes()))}
        f.media.faults.loseCommit=true;assertFailsWith<CommitOutcomeUnknown>{f.store.ready(lease,f.safety.proof(lease.source,v))}
        val receipt=assertNotNull(f.store.begin(lease));assertEquals(MediaProcessingTerminal.READY,receipt.result)
        assertEquals(f.completion.body,MediaTestFixture.reply(f.media.store.completeMediaUpload(f.media.account,f.completionKey,f.mediaId,f.completionBody)).body)
        f.delete();val replay=f.processor.process(lease);assertEquals(receipt.eventId,replay.eventId);assertEquals(3L,replay.mediaVersion)
        assertEquals("deleted",state(f));assertEquals(2,f.count("platform.outbox"));assertEquals(2,f.objects.writes)
    }
    @Test fun deleteBeforeClaimFencesQueuedJobWithoutAnyReadyOrObjectEffect() {
        val f=f();f.store.ingest(f.seed());f.delete();assertNull(f.store.claim())
        assertEquals("cancelled",f.value("SELECT state FROM platform.media_processing_jobs"));assertEquals(1,f.count("platform.media_processing_cleanup"))
        assertEquals(0,f.objects.writes);assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun deleteAfterUnacknowledgedWriteRetainsEveryIntentAndAcceptsLateReceiptOnlyForCleanup() {
        val f=f();val lease=f.claimed();var once=true
        f.objects.afterWrite={if(once){once=false;f.delete()}}
        assertFailsWith<MediaProcessingFailure>{f.processor.process(lease)}
        assertEquals("deleted",state(f));assertEquals(1,f.objects.writes);assertEquals(3,f.count("platform.media_processing_cleanup"))
        assertEquals("2",f.value("SELECT count(*) FROM platform.media_derivative_intents WHERE cleanup_required"))
        assertEquals("1",f.value("SELECT count(*) FROM platform.media_derivative_intents WHERE object_version_id IS NOT NULL"))
        assertEquals(1,f.count("platform.outbox"));assertEquals(MediaProcessingTerminal.CANCELLED,assertNotNull(f.store.begin(lease)).result)
    }
    @Test fun cancelledCallerAfterRealObjectWriteKeepsTheOriginalIntentWithoutCompensation() {
        val f=f();val lease=f.claimed();f.objects.afterWrite={throw CancellationException("synthetic caller cancellation")}
        assertFailsWith<CancellationException>{f.processor.process(lease)}
        assertEquals(1,f.objects.writes);assertEquals(0,f.objects.deletes);assertEquals(2,f.count("platform.media_derivative_intents"))
        assertEquals("processing",state(f));assertEquals(1,f.count("platform.outbox"))
    }
    @Test fun cleanupWaitsForOriginalPostDeadlineThenRemovesAllExactLateVersionsOnly() {
        val f=f();val event=f.seed(ttl=2);f.store.ingest(event);f.delete()
        val key=f.value("SELECT quarantine_key FROM platform.media_assets")
        f.objects.data[key to "late-post-version"]=byteArrayOf(4);f.objects.data["unrelated-owned-key" to "keep"]=byteArrayOf(5)
        assertNull(f.cleanup.claim());f.sql("SELECT pg_sleep(2.1)")
        f.cleanup.clean(assertNotNull(f.cleanup.claim()))
        assertTrue(f.objects.data.keys.none{it.first==key});assertNotNull(f.objects.data["unrelated-owned-key" to "keep"])
        assertEquals("done",f.value("SELECT state FROM platform.media_processing_cleanup"));assertEquals(2,f.objects.deletes)
    }
    @Test fun unavailableSettlementCannotGuessAbsenceAndDeleteReplayRemainsImmutable() {
        val f=f();f.seed(ttl=1);val key=UUID.randomUUID();f.media.store.deleteDraftMedia(f.media.account,key,f.mediaId,"\"2\"")
        val receipt=f.value("SELECT row_to_json(c)::text FROM platform.media_cleanup_jobs c");f.sql("SELECT pg_sleep(1.1)")
        f.objects.unavailableSettle=true;assertFails{f.cleanup.clean(assertNotNull(f.cleanup.claim()))}
        assertEquals(0,f.objects.deletes);assertEquals("0",f.value("SELECT count(*) FROM platform.media_processing_cleanup WHERE completed_at IS NOT NULL"))
        assertIs<CommandResult.Replayed>(f.media.store.deleteDraftMedia(f.media.account,key,f.mediaId,"\"2\""));assertEquals(receipt,f.value("SELECT row_to_json(c)::text FROM platform.media_cleanup_jobs c"))
    }
    @Test fun cleanupProviderFailuresAreSanitizedAndCancellationNeverAcknowledgesSettlementOrDeletion() {
        for (point in listOf("settle", "delete")) {
            val f=f(); f.seed(ttl=1); f.delete(); f.sql("SELECT pg_sleep(1.1)")
            val originalReceipt=f.value("SELECT row_to_json(c)::text FROM platform.media_cleanup_jobs c")
            var injected:Exception?=null
            val provider=object:MediaProcessingObjects by f.objects {
                override fun settle(cleanup:MediaCleanupLease):SettledMediaVersions {
                    if(point=="settle") injected?.let{throw it}
                    return f.objects.settle(cleanup)
                }
                override fun deleteVersion(cleanup:MediaCleanupLease,versionId:String) {
                    if(point=="delete") injected?.let{throw it}
                    f.objects.deleteVersion(cleanup,versionId)
                }
            }
            val cleanup=MediaObjectCleanup("test",PgTransactions(f.database),f.authority,provider,f.policy)
            val lease=assertNotNull(cleanup.claim())
            for(mode in listOf("failure","cancel","interrupt")) {
                injected=when(mode){"cancel"->CancellationException("private-provider-cancel");"interrupt"->InterruptedException("private-provider-interrupt");else->IllegalStateException("private-provider-url-and-credentials")}
                try {
                    when(mode) {
                        "cancel"->assertSame(injected,assertFailsWith<CancellationException>{cleanup.clean(lease)})
                        "interrupt"->{assertSame(injected,assertFailsWith<InterruptedException>{cleanup.clean(lease)});assertTrue(Thread.currentThread().isInterrupted)}
                        else->{val failure=assertFailsWith<MediaProcessingFailure>{cleanup.clean(lease)}
                            assertEquals(MediaProcessingFailureCode.OBJECT_UNAVAILABLE,failure.code)
                            assertNull(failure.cause);assertFalse(failure.toString().contains("private-provider"))}
                    }
                } finally { Thread.interrupted() }
                assertEquals(0,f.objects.deletes);assertEquals(1,f.objects.data.size)
                assertEquals("0",f.value("SELECT count(*) FROM platform.media_processing_cleanup WHERE completed_at IS NOT NULL"))
                assertEquals(if(point=="settle")"false" else "true",f.value("SELECT (settled_versions IS NOT NULL)::text FROM platform.media_processing_cleanup"))
                assertEquals(originalReceipt,f.value("SELECT row_to_json(c)::text FROM platform.media_cleanup_jobs c"))
            }
            injected=null;cleanup.clean(lease)
            assertEquals(1,f.objects.deletes);assertTrue(f.objects.data.isEmpty())
            assertEquals("done",f.value("SELECT state FROM platform.media_processing_cleanup"))
            assertEquals(originalReceipt,f.value("SELECT row_to_json(c)::text FROM platform.media_cleanup_jobs c"))
        }
    }
    @Test fun derivativeCleanupNeverDeletesLiveReadyManifestAndLaterDeleteUsesSeparateLedger() {
        val f=f(MediaProcessingTestFixture.policy(acceptance=1));val lease=f.claimed();f.processor.process(lease)
        assertNull(f.cleanup.claim()) // Original POST remains usable; live derivatives have no cleanup target.
        val receipt=f.value("SELECT terminal_event_id::text FROM platform.media_processing_jobs")
        f.delete();f.sql("SELECT pg_sleep(1.1)")
        repeat(2){f.cleanup.clean(assertNotNull(f.cleanup.claim()))}
        assertEquals(2,f.objects.deletes);assertEquals(receipt,f.value("SELECT terminal_event_id::text FROM platform.media_processing_jobs"))
        assertEquals("deleted",state(f));assertEquals(2,f.count("platform.outbox"))
    }
    @Test fun unknownCleanupCommitRetainsSettledVersionsAndRepeatsOnlyExactIdempotentDeletes() {
        val f=f(MediaProcessingTestFixture.policy(lease=1));f.seed(ttl=1);f.delete();f.sql("SELECT pg_sleep(1.1)")
        val first=assertNotNull(f.cleanup.claim());var armed=true
        f.objects.beforeDelete={if(armed){armed=false;f.media.faults.loseCommit=true}}
        assertFailsWith<CommitOutcomeUnknown>{f.cleanup.clean(first)}
        assertEquals("done",f.value("SELECT state FROM platform.media_processing_cleanup"));assertNull(f.cleanup.claim())
        assertEquals(1,f.objects.deletes)
    }
    @Test fun authorityDelayCannotReviveExpiredLeaseOrAdmitDerivativeWrites() {
        for(operation in listOf("begin","renew","prepare","authorize","safety")){val f=f(MediaProcessingTestFixture.policy(lease=1));val lease=f.claimed()
            val variants=outputs();val intents=if(operation=="authorize")f.store.prepareDerivatives(lease,variants)else emptyList()
            f.authority.beforeProcessing={c->f.authority.beforeProcessing=null;c.createStatement().use{it.execute("SELECT pg_sleep(1.1)")}}
            fail(MediaProcessingFailureCode.STALE_LEASE){when(operation){"begin"->f.store.begin(lease);"renew"->f.store.renew(lease);"prepare"->f.store.prepareDerivatives(lease,variants);"authorize"->f.store.authorizeWrite(lease,intents.first());else->f.store.validateSafety(lease,variants,f.safety.proof(lease.source,variants))}}
            assertEquals(if(operation=="authorize")2 else 0,f.count("platform.media_derivative_intents"));assertEquals(0,f.objects.writes)
            assertEquals("true",f.value("SELECT (lease_expires_at<clock_timestamp())::text FROM platform.media_processing_jobs"))
        }
    }
    @Test fun pinnedPolicyCodecAndDatabaseIntentIdentityCannotBeRewritten() {
        val f=f();val lease=f.claimed();f.store.prepareDerivatives(lease,outputs())
        for(sql in listOf("UPDATE platform.media_processing_jobs SET codec_revision='changed'","UPDATE platform.media_processing_jobs SET source=jsonb_set(source,'{key}','\"foreign\"')",
            "UPDATE platform.media_derivative_intents SET object_key='derivatives/test/foreign/x'","UPDATE platform.media_derivative_intents SET sha256=repeat('b',64)"))assertFailsWith<SQLException>{f.sql(sql)}
        assertEquals("processing",state(f));assertEquals(0,f.objects.writes)
    }
    @Test fun manifestBudgetFailureRollsBackReadyEventAndKeepsPrivateOutputsUnpublished() {
        val f=f(MediaProcessingTestFixture.policy(manifest=10));val lease=f.claimed()
        fail(MediaProcessingFailureCode.LIMIT_EXCEEDED){f.processor.process(lease)}
        assertEquals("processing",state(f));assertEquals(1,f.count("platform.outbox"));assertEquals(2,f.objects.writes)
    }
    companion object {
        private lateinit var cluster:PostgresTestCluster
        @JvmField @ClassRule val timeout=Timeout(10,TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun start(){cluster=PostgresTestCluster.start()}
        @JvmStatic @AfterClass fun stop(){if(::cluster.isInitialized)cluster.close()}
        private fun f(policy:MediaProcessingPolicy=MediaProcessingTestFixture.policy())=MediaProcessingTestFixture(cluster.database(),policy)
        private fun state(f:MediaProcessingTestFixture)=f.value("SELECT state FROM platform.media_assets")
        private fun outputs(value:Int=0)=listOf(EncodedPhotoVariant(PhotoVariant.THUMBNAIL,"image/png",1,1,byteArrayOf(1,(2+value).toByte())),EncodedPhotoVariant(PhotoVariant.DISPLAY,"image/png",2,2,byteArrayOf(3,(4+value).toByte())))
        private fun fail(code:MediaProcessingFailureCode,action:()->Any?)=assertEquals(code,assertFailsWith<MediaProcessingFailure>{action()}.code)
        private fun changedEvent(e:CommittedEvent,id:UUID=e.draft.eventId,type:String=e.draft.eventType,producer:String=e.draft.producer,version:Long=e.draft.aggregateVersion,data:JsonObject=e.draft.data)=
            CommittedEvent(EventDraft(id,type,e.draft.schemaVersion,e.draft.aggregateType,e.draft.aggregateId,version,producer,e.draft.correlationId,e.draft.causationId,data),e.occurredAt)
    }
}
