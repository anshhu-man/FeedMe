package com.feedme.server.db

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.*

class DurableEventsIntegrationTest {
    companion object {
        private lateinit var cluster: PostgresTestCluster
        @JvmStatic @BeforeClass fun start() { cluster=PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { if(::cluster.isInitialized) cluster.close() }
    }
    private lateinit var dataSource: DataSource
    private lateinit var transactions: PgTransactions
    private lateinit var outbox: OutboxStore
    @Before fun database() {
        dataSource=cluster.database(); PlatformMigrations(dataSource).migrate()
        transactions=PgTransactions(dataSource);outbox=OutboxStore(transactions)
        sql("CREATE TABLE fixture_effects(id uuid PRIMARY KEY, version bigint NOT NULL, count integer NOT NULL)")
    }
    private fun draft(version:Long=1,id:UUID=UUID.randomUUID(),type:String="planning.plan.created.v1") = EventDraft(
        UUID.randomUUID(),type,1,"plan",id,version,"planning",UUID.randomUUID().toString(),UUID.randomUUID(),
        buildJsonObject { put("planId",id.toString());put("futureField",true) },
    )
    private fun append(event:EventDraft=draft()):EventDraft { transactions.run { outbox.append(it,event) };return event }
    private fun sql(statement:String) { dataSource.connection.use { c->c.createStatement().use{it.execute(statement)} } }
    private fun count(table:String):Int = dataSource.connection.use { c->c.createStatement().use{s->s.executeQuery("SELECT count(*) FROM $table").use{it.next();it.getInt(1)}} }
    private fun expireLeases()=sql("UPDATE platform.outbox SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE lease_token IS NOT NULL")
    private fun readyRetries()=sql("UPDATE platform.outbox SET available_at=clock_timestamp()-interval '1 second'")
    private fun effect(connection:Connection,event:CommittedEvent) {
        connection.prepareStatement("INSERT INTO fixture_effects VALUES(?,?,1) ON CONFLICT(id) DO UPDATE SET count=fixture_effects.count+1").use {
            it.setObject(1,event.draft.aggregateId);it.setLong(2,event.draft.aggregateVersion);it.executeUpdate()
        }
    }

    @Test fun committedEnvelopeHasEveryCanonicalFieldAndRollbackEmitsNothing() {
        val event=append()
        val lease=outbox.claim().single()
        assertEquals(event.eventId,lease.event.draft.eventId)
        assertEquals(event.data,lease.event.draft.data)
        assertEquals(setOf("eventId","eventType","schemaVersion","aggregateType","aggregateId","aggregateVersion",
            "occurredAt","producer","correlationId","causationId","data"),lease.event.envelope().keys)
        assertFailsWith<IllegalStateException> { transactions.run { outbox.append(it,draft());error("injected rollback") } }
        assertEquals(1,count("platform.outbox"))
        dataSource.connection.use { c->assertFailsWith<IllegalArgumentException> { outbox.append(c,draft()) } }
    }

    @Test fun commandDomainEffectReceiptAndOutboxShareOneCommit() {
        val command=CommandIdentity(PrincipalScope("local",CommandActor.ACCOUNT,UUID.randomUUID()),"createPlan",UUID.randomUUID())
        val commands=DurableCommands(transactions)
        val event=draft()
        assertFailsWith<IllegalStateException> {
            commands.execute(command,{}, {}, {_,_->}) { c->
                effect(c,CommittedEvent(event,java.time.Instant.now()));outbox.append(c,event);error("before receipt")
            }
        }
        assertEquals(0,count("fixture_effects"));assertEquals(0,count("platform.outbox"));assertEquals(0,count("platform.idempotency"))
        assertIs<CommandResult.Applied>(commands.execute(command,{}, {}, {_,_->}) { c->
            effect(c,CommittedEvent(event,java.time.Instant.now()));outbox.append(c,event);StoredReply(201,buildJsonObject{put("id",event.aggregateId.toString())},"\"1\"")
        })
        assertIs<CommandResult.Replayed>(commands.execute(command,{}, {}, {_,_->}){error("must not execute")})
        assertEquals(1,count("fixture_effects"));assertEquals(1,count("platform.outbox"));assertEquals(1,count("platform.idempotency"))
    }

    @Test fun twoWorkersReceiveDisjointBoundedClaims() {
        repeat(10){append()}
        val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try {
            val jobs=(1..2).map { pool.submit(Callable { start.await(5,TimeUnit.SECONDS);outbox.claim(5) }) }
            start.countDown();val batches=jobs.map { it.get(10,TimeUnit.SECONDS) }
            assertEquals(listOf(5,5),batches.map{it.size})
            assertEquals(10,batches.flatten().map{it.event.draft.eventId}.toSet().size)
            assertTrue(outbox.claim().isEmpty())
        } finally {pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS)}
    }

    @Test fun lockedRowDoesNotBlockAnotherWorkersBatch() {
        val first=append();append()
        dataSource.connection.use { c->
            c.autoCommit=false
            c.prepareStatement("SELECT event_id FROM platform.outbox WHERE event_id=? FOR UPDATE").use { it.setObject(1,first.eventId);it.executeQuery().close() }
            val lease=outbox.claim(1).single()
            assertNotEquals(first.eventId,lease.event.draft.eventId)
            c.rollback()
        }
    }

    @Test fun expiredLeaseRecoversAndStaleWorkerCannotAcknowledgeOrFailNewOwner() {
        append();val old=outbox.claim().single();expireLeases()
        val fresh=outbox.claim().single()
        assertNotEquals(old.token,fresh.token);assertEquals(2,fresh.attempt)
        assertFalse(outbox.acknowledge(old));assertFalse(outbox.fail(old,DeliveryFailure.PERMANENT_FAILURE))
        assertTrue(outbox.acknowledge(fresh));assertFalse(outbox.acknowledge(fresh));assertTrue(outbox.claim().isEmpty())
    }

    @Test fun retryWaitsThenFifthFailureQuarantines() {
        append()
        repeat(5){ index->
            val lease=outbox.claim().single();assertEquals(index+1,lease.attempt)
            assertTrue(outbox.fail(lease,DeliveryFailure.DELIVERY_FAILED));assertTrue(outbox.claim().isEmpty())
            readyRetries()
        }
        assertTrue(outbox.claim().isEmpty())
        assertEquals(1,count("platform.outbox WHERE quarantined_at IS NOT NULL"))
    }

    @Test fun fiveCrashedLeasesQuarantineWithoutUnboundedRetries() {
        append()
        repeat(5){index->assertEquals(index+1,outbox.claim().single().attempt);expireLeases()}
        assertTrue(outbox.claim().isEmpty())
        assertEquals(1,count("platform.outbox WHERE last_failure_code='ATTEMPTS_EXHAUSTED'"))
    }

    @Test fun unknownTypeQuarantinesBeforePublishingAndKnownFailureRemainsPending() {
        append(draft(type="planning.future.unknown.v1"))
        val known=append()
        val relay=OutboxRelay(outbox,setOf("planning.plan.created.v1" to 1))
        val offered=mutableListOf<UUID>()
        assertEquals(0,relay.deliverBatch { offered+=it.draft.eventId;throw IllegalStateException("provider timeout, acceptance unknown") })
        assertEquals(listOf(known.eventId),offered)
        assertEquals(1,count("platform.outbox WHERE last_failure_code='UNSUPPORTED_EVENT'"))
        assertEquals(0,count("platform.outbox WHERE published_at IS NOT NULL"))
        readyRetries()
        assertEquals(1,relay.deliverBatch{})
        assertEquals(1,count("platform.outbox WHERE published_at IS NOT NULL"))
    }

    @Test fun malformedImportedRowIsQuarantinedWithoutPoisoningHealthyClaims() {
        val invalid=append();val valid=append()
        sql("UPDATE platform.outbox SET event_type='fixture.created' WHERE event_id='${invalid.eventId}'")
        assertEquals(listOf(valid.eventId),outbox.claim().map{it.event.draft.eventId})
        assertEquals(1,count("platform.outbox WHERE last_failure_code='INVALID_ENVELOPE'"))
    }

    @Test fun slowPublishDoesNotReserveOrSendStaleClaimsForRemainingEvents() {
        repeat(3){append()}
        val relay=OutboxRelay(outbox,setOf("planning.plan.created.v1" to 1))
        var ownCalls=0;val otherSent=mutableSetOf<UUID>()
        assertEquals(0,relay.deliverBatch { event->
            ownCalls++
            // Model a long first send without sleeping, then another worker completing every event.
            expireLeases()
            outbox.claim().forEach{otherSent+=it.event.draft.eventId;assertTrue(outbox.acknowledge(it))}
            assertTrue(event.draft.eventId in otherSent)
        })
        assertEquals(1,ownCalls);assertEquals(3,otherSent.size)
    }

    @Test fun crashAfterSendAllowsRedeliveryButInboxEffectOccursOnce() {
        append();val first=outbox.claim().single()
        val inbox=ConsumerInbox(transactions)
        assertTrue(inbox.consume("fixture-consumer",first.event,::effect))
        // Simulate relay death after queue acceptance: no acknowledgment reaches the outbox.
        expireLeases();val second=outbox.claim().single()
        assertFalse(inbox.consume("fixture-consumer",second.event,::effect))
        assertTrue(outbox.acknowledge(second))
        assertEquals(1,count("fixture_effects WHERE count=1"));assertEquals(1,count("platform.consumer_inbox"))
    }

    @Test fun concurrentConsumerDuplicatesAndFollowupEventsAreAtomic() {
        append();val original=outbox.claim().single().event
        val inbox=ConsumerInbox(transactions)
        val pool=Executors.newFixedThreadPool(10);val start=CountDownLatch(1)
        try {
            val jobs=(1..10).map{pool.submit(Callable{start.await(5,TimeUnit.SECONDS);inbox.consume("fixture-consumer",original){c,e->
                effect(c,e);outbox.append(c,draft(type="planning.plan.updated.v1"))
            }})}
            start.countDown();assertEquals(1,jobs.count{it.get(15,TimeUnit.SECONDS)})
        }finally{pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS)}
        assertEquals(1,count("platform.consumer_inbox"));assertEquals(1,count("fixture_effects WHERE count=1"));assertEquals(2,count("platform.outbox"))
    }

    @Test fun consumerFailureRollsBackInboxEffectAndFollowupEvent() {
        append();val event=outbox.claim().single().event;val inbox=ConsumerInbox(transactions)
        assertFailsWith<IllegalStateException>{inbox.consume("fixture-consumer",event){c,e->effect(c,e);outbox.append(c,draft());error("fail after followup")}}
        assertEquals(0,count("platform.consumer_inbox"));assertEquals(0,count("fixture_effects"));assertEquals(1,count("platform.outbox"))
        assertTrue(inbox.consume("fixture-consumer",event,::effect))
    }

    @Test fun consumerCanPreventOutOfOrderRegressionAndResurrectionUsingCurrentRows() {
        val aggregate=UUID.randomUUID();append(draft(2,aggregate));append(draft(1,aggregate))
        val events=outbox.claim().map{it.event}.sortedByDescending{it.draft.aggregateVersion}
        val inbox=ConsumerInbox(transactions)
        val projection:(Connection,CommittedEvent)->Unit={c,e->
            c.prepareStatement("UPDATE fixture_effects SET version=? WHERE id=? AND version<?").use {
                it.setLong(1,e.draft.aggregateVersion);it.setObject(2,e.draft.aggregateId);it.setLong(3,e.draft.aggregateVersion);it.executeUpdate()
            }
        }
        sql("INSERT INTO fixture_effects VALUES('$aggregate',0,0)")
        for(event in events)assertTrue(inbox.consume("projection",event,projection))
        assertEquals(1,count("fixture_effects WHERE version=2"))
        sql("DELETE FROM fixture_effects WHERE id='$aggregate'")
        val next=CommittedEvent(draft(3,aggregate),java.time.Instant.now())
        assertTrue(inbox.consume("projection",next,projection));assertEquals(0,count("fixture_effects"))
        // This fixture proves the extension point; each real consumer must implement its own lifecycle guards.
    }

    @Test fun sqlConstraintsRejectDuplicateEventAndBrokenEnvelopeAndLeaseState() {
        val event=append()
        assertFailsWith<SQLException>{transactions.run{outbox.append(it,event)}}
        assertFailsWith<SQLException>{sql("UPDATE platform.outbox SET aggregate_version=0")}
        assertFailsWith<SQLException>{sql("UPDATE platform.outbox SET payload='[]'::jsonb")}
        assertFailsWith<SQLException>{sql("UPDATE platform.outbox SET lease_token='${UUID.randomUUID()}'")}
        assertEquals(1,count("platform.outbox"))
        assertFailsWith<IllegalArgumentException>{outbox.claim(101)}
        assertFailsWith<IllegalArgumentException>{outbox.claim(1,0)}
    }
}
