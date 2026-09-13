package com.feedme.server.db

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.*

class CommitAndRetentionIntegrationTest {
    companion object {
        private lateinit var cluster:PostgresTestCluster
        @JvmStatic @BeforeClass fun start(){cluster=PostgresTestCluster.start()}
        @JvmStatic @AfterClass fun stop(){if(::cluster.isInitialized)cluster.close()}
    }
    private lateinit var database:DataSource
    private lateinit var commands:DurableCommands
    @Before fun setup(){
        database=cluster.database();PlatformMigrations(database).migrate();commands=DurableCommands(PgTransactions(database))
        sql("CREATE TABLE fixture_mutations(id uuid PRIMARY KEY)")
    }
    private val scope=PrincipalScope("local",CommandActor.ACCOUNT,UUID.randomUUID())
    private fun identity(key:UUID=UUID.randomUUID(),body:JsonElement=JsonNull)=CommandIdentity(scope,"createPlan",key,body=body)
    private fun run(command:CommandIdentity,executor:DurableCommands=commands)=executor.execute(command,{}, {}, {_,_->}){connection->
        connection.prepareStatement("INSERT INTO fixture_mutations VALUES(?)").use{it.setObject(1,UUID.randomUUID());it.executeUpdate()}
        StoredReply(201,buildJsonObject{put("fixture",true)},"\"1\"")
    }
    private fun sql(query:String){database.connection.use{c->c.createStatement().use{it.execute(query)}}}
    private fun count(table:String)=database.connection.use{c->c.createStatement().use{s->s.executeQuery("SELECT count(*) FROM $table").use{it.next();it.getInt(1)}}}
    private fun wrappingConnection(wrapper:(Connection)->Connection):DataSource=object:DataSource by database{
        override fun getConnection():Connection=wrapper(database.connection)
    }
    private fun proxy(connection:Connection,intercept:(String,()->Any?)->Any?):Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,arrayOf(Connection::class.java),
    ){_,method,args->intercept(method.name){try{method.invoke(connection,*(args?:emptyArray()))}catch(e:InvocationTargetException){throw e.targetException}}} as Connection

    @Test fun acceptedCommitThenLostResponseIsNotAutomaticallyExecutedAgain() {
        val inject=AtomicBoolean(true)
        val source=wrappingConnection{real->proxy(real){method,call->
            val result=call()
            if(method=="commit"&&inject.compareAndSet(true,false))throw SQLException("Synthetic connection loss after committed data","08006")
            result
        }}
        val command=identity()
        assertFailsWith<CommitOutcomeUnknown>{run(command,DurableCommands(PgTransactions(source)))}
        assertEquals(1,count("fixture_mutations"));assertEquals(1,count("platform.idempotency WHERE state='completed'"))
        assertIs<CommandResult.Replayed>(run(command))
        assertEquals(1,count("fixture_mutations"))
    }

    @Test fun closeFailureCannotTurnKnownCommitIntoNewMutation() {
        val source=wrappingConnection{real->proxy(real){method,call->
            val result=call()
            if(method=="close")throw SQLException("Synthetic close failure","08006")
            result
        }}
        val command=identity()
        assertIs<CommandResult.Applied>(run(command,DurableCommands(PgTransactions(source))))
        assertIs<CommandResult.Replayed>(run(command));assertEquals(1,count("fixture_mutations"))
    }

    @Test fun expirySweepIsBoundedAndKeepsLiveResponsesAndCommandTombstones() {
        val all=(1..4).map{identity().also{command->run(command)}}
        sql("UPDATE platform.idempotency SET expires_at=clock_timestamp()-interval '1 second' WHERE key IN (${all.take(3).joinToString{ "'${it.key}'" }})")
        assertEquals(2,commands.compactExpired(2));assertEquals(1,commands.compactExpired(2));assertEquals(0,commands.compactExpired(2))
        assertEquals(3,count("platform.idempotency WHERE state='tombstone' AND response_json IS NULL AND response_etag IS NULL"))
        assertIs<CommandResult.Replayed>(run(all.last()))
        assertEquals(CommandResult.ReceiptExpired,run(all.first()))
        assertEquals(4,count("fixture_mutations"))
    }

    @Test fun concurrentSweepSkipsLockedReceiptAndReplaysCannotResurrectIt() {
        val first=identity();val second=identity();run(first);run(second)
        sql("UPDATE platform.idempotency SET expires_at=clock_timestamp()-interval '1 second'")
        database.connection.use{connection->
            connection.autoCommit=false
            connection.prepareStatement("SELECT key FROM platform.idempotency WHERE key=? FOR UPDATE").use{it.setObject(1,first.key);it.executeQuery().close()}
            assertEquals(1,commands.compactExpired())
            connection.rollback()
        }
        assertEquals(CommandResult.ReceiptExpired,run(first));assertEquals(CommandResult.ReceiptExpired,run(second))
        assertEquals(2,count("platform.idempotency WHERE state='tombstone'"));assertEquals(2,count("fixture_mutations"))
    }

    @Test fun expiryIsRecheckedAfterReplayAuthorizationWait() {
        val command=identity();run(command)
        val result=commands.execute(command,{}, {}, {connection,_->
            // Model crossing the TTL boundary while replay authorization waited, without wall-clock sleep.
            connection.prepareStatement("UPDATE platform.idempotency SET expires_at=clock_timestamp()-interval '1 second' WHERE key=?").use{
                it.setObject(1,command.key);it.executeUpdate()
            }
        }){error("Must not execute")}
        assertEquals(CommandResult.ReceiptExpired,result)
        assertEquals(1,count("platform.idempotency WHERE state='tombstone' AND response_json IS NULL"))
    }

    @Test fun racingDifferentPayloadsHaveOneWinnerAndOneMismatch() {
        val key=UUID.randomUUID();val start=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try{
            val jobs=(1..2).map{number->pool.submit(Callable{start.await(5,TimeUnit.SECONDS);run(identity(key,buildJsonObject{put("servings",number)}))})}
            start.countDown();val results=jobs.map{it.get(15,TimeUnit.SECONDS)}
            assertEquals(1,results.count{it is CommandResult.Applied});assertEquals(1,results.count{it==CommandResult.Mismatch})
            assertEquals(1,count("fixture_mutations"));assertEquals(1,count("platform.idempotency"))
        }finally{pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS)}
    }
}
