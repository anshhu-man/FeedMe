package com.feedme.server.db

import com.feedme.server.contract.ContractCatalog
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the command boundary against a disposable PostgreSQL server. The ACL and mutation
 * tables below are synthetic test fixtures; these tests do not claim to implement product policy.
 */
class DurableCommandsIntegrationTest {
    private lateinit var database: DataSource
    private lateinit var commands: DurableCommands
    private lateinit var account: PrincipalScope
    private val validations = AtomicInteger()
    private val newAuthorizations = AtomicInteger()
    private val replayAuthorizations = AtomicInteger()
    private val mutationCalls = AtomicInteger()

    @Before
    fun createIsolatedDatabase() {
        database = checkNotNull(cluster).database()
        PlatformMigrations(database).migrate()
        commands = DurableCommands(PgTransactions(database))
        database.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE SCHEMA command_test;
                    CREATE TABLE command_test.principal (
                        scope text PRIMARY KEY,
                        active boolean NOT NULL DEFAULT true
                    );
                    CREATE TABLE command_test.permission (
                        scope text NOT NULL REFERENCES command_test.principal(scope),
                        operation_id text NOT NULL,
                        allow_new boolean NOT NULL DEFAULT true,
                        allow_replay boolean NOT NULL DEFAULT true,
                        PRIMARY KEY (scope, operation_id)
                    );
                    CREATE TABLE command_test.mutation (
                        id uuid PRIMARY KEY,
                        scope text NOT NULL,
                        operation_id text NOT NULL,
                        command_key uuid NOT NULL
                    );
                    CREATE TABLE command_test.target (
                        id uuid PRIMARY KEY,
                        owner_scope text NOT NULL REFERENCES command_test.principal(scope)
                    );
                    """.trimIndent(),
                )
            }
        }
        account = PrincipalScope("integration", CommandActor.ACCOUNT, UUID.randomUUID())
        grant(account, "createPlan", "updateCookSession", "saveRecipe", "deleteSavedRecipe", "createCollection")
    }

    @Test(timeout = 45_000)
    fun twentyConcurrentIdenticalCommandsCommitOneMutationAndReplayOneReply() {
        val command = identity()
        val workers = Executors.newFixedThreadPool(20)
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)
        try {
            val futures = (1..20).map {
                workers.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "Concurrent command start timed out" }
                    execute(command)
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "All command callers must reach the start barrier")
            start.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            val results = futures.map { future ->
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Concurrent command completion exceeded 15 seconds" }
                future.get(remaining, TimeUnit.NANOSECONDS)
            }
            assertEquals(1, results.count { it is CommandResult.Applied })
            assertEquals(19, results.count { it is CommandResult.Replayed })
            results.forEach { assertReply(successReply, replyOf(it)) }
            assertEquals(1, count("command_test.mutation"))
            assertEquals(1, count("platform.idempotency"))
            assertEquals(20, validations.get())
            assertEquals(1, newAuthorizations.get())
            assertEquals(19, replayAuthorizations.get())
            assertEquals(1, mutationCalls.get())
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "Command workers did not terminate")
        }
    }

    @Test(timeout = 30_000)
    fun changedBodyRejectsKeyReuseWithoutSecondMutation() {
        val key = UUID.randomUUID()
        val original = identity(key = key, body = json("""{"servings":2}"""))
        assertIs<CommandResult.Applied>(execute(original))

        val changed = identity(key = key, body = json("""{"servings":3}"""))
        assertEquals(CommandResult.Mismatch, execute(changed))
        assertEquals(1, count("command_test.mutation"))
        assertEquals(1, mutationCalls.get())
        assertEquals(1, newAuthorizations.get())
        assertEquals(0, replayAuthorizations.get())
        assertEquals(original.requestHash, receipt(original).requestHash)
        assertIs<CommandResult.Replayed>(execute(original))
    }

    @Test(timeout = 30_000)
    fun changedPathRejectsKeyReuseWithoutSecondMutation() {
        val key = UUID.randomUUID()
        val original = identity(
            operation = "updateCookSession", key = key,
            path = mapOf("sessionId" to UUID.randomUUID().toString()), ifMatch = "\"revision-1\"",
        )
        assertIs<CommandResult.Applied>(execute(original))

        val changed = identity(
            operation = "updateCookSession", key = key,
            path = mapOf("sessionId" to UUID.randomUUID().toString()), ifMatch = "\"revision-1\"",
        )
        assertEquals(CommandResult.Mismatch, execute(changed))
        assertEquals(1, count("command_test.mutation"))
        assertEquals(1, mutationCalls.get())
        assertEquals(original.requestHash, receipt(original).requestHash)
    }

    @Test(timeout = 30_000)
    fun changedOrOmittedIfMatchRejectsKeyReuseWithoutSecondMutation() {
        val key = UUID.randomUUID()
        val path = mapOf("sessionId" to UUID.randomUUID().toString())
        val original = identity(operation = "updateCookSession", key = key, path = path, ifMatch = "\"revision-1\"")
        assertIs<CommandResult.Applied>(execute(original))

        listOf("\"revision-2\"", null).forEach { ifMatch ->
            val changed = identity(operation = "updateCookSession", key = key, path = path, ifMatch = ifMatch)
            assertEquals(CommandResult.Mismatch, execute(changed))
        }
        assertEquals(1, count("command_test.mutation"))
        assertEquals(1, mutationCalls.get())
        assertEquals(original.requestHash, receipt(original).requestHash)
    }

    @Test(timeout = 30_000)
    fun canonicalJsonAndQueryOrderReplayButChangedQueryDoesNot() {
        val key = UUID.randomUUID()
        val original = identity(
            key = key, body = json("""{"servings":2.0,"preferences":{"b":true,"a":null}}"""),
            query = linkedMapOf("locale" to listOf("en"), "mode" to listOf("quick")),
        )
        assertIs<CommandResult.Applied>(execute(original))
        val reordered = identity(
            key = key, body = json("""{"preferences":{"a":null,"b":true},"servings":2}"""),
            query = linkedMapOf("mode" to listOf("quick"), "locale" to listOf("en")),
        )
        assertEquals(original.requestHash, reordered.requestHash)
        assertIs<CommandResult.Replayed>(execute(reordered))
        val changed = identity(
            key = key, body = json("""{"preferences":{"a":null,"b":true},"servings":2}"""),
            query = mapOf("mode" to listOf("slow"), "locale" to listOf("en")),
        )
        assertEquals(CommandResult.Mismatch, execute(changed))
        assertEquals(1, count("command_test.mutation"))
    }

    @Test(timeout = 30_000)
    fun principalIdActorAndEnvironmentEachIsolateTheSameCommandKey() {
        val sharedId = UUID.randomUUID()
        val key = UUID.randomUUID()
        val scopes = listOf(
            PrincipalScope("integration", CommandActor.ACCOUNT, sharedId),
            PrincipalScope("integration", CommandActor.GUEST, sharedId),
            PrincipalScope("integration", CommandActor.ACCOUNT, UUID.randomUUID()),
            PrincipalScope("second-environment", CommandActor.ACCOUNT, sharedId),
        )
        scopes.forEach { grant(it, "createPlan") }
        val identities = scopes.map { identity(scope = it, key = key) }
        identities.forEach { assertIs<CommandResult.Applied>(execute(it)) }
        identities.forEach { assertIs<CommandResult.Replayed>(execute(it)) }
        assertEquals(4, count("command_test.mutation"))
        assertEquals(4, count("platform.idempotency"))
        assertEquals(scopes.map { it.storageKey }.toSet(), persistedScopes())
    }

    @Test(timeout = 30_000)
    fun canonicalOperationsIsolateTheSamePrincipalAndKey() {
        val key = UUID.randomUUID()
        val first = identity(key = key)
        val second = identity(key = key, operation = "saveRecipe")
        assertIs<CommandResult.Applied>(execute(first))
        assertIs<CommandResult.Applied>(execute(second))
        assertIs<CommandResult.Replayed>(execute(first))
        assertIs<CommandResult.Replayed>(execute(second))
        assertEquals(2, count("command_test.mutation"))
        assertEquals(2, count("platform.idempotency"))
    }

    @Test(timeout = 30_000)
    fun accountGuestAndStaffScopesRespectCanonicalPrincipalRules() {
        val guest = PrincipalScope("integration", CommandActor.GUEST, UUID.randomUUID())
        val staff = PrincipalScope("integration", CommandActor.STAFF, UUID.randomUUID())
        grant(guest, "createPlan")
        grant(staff, "adminCreateRecipe")
        listOf(
            identity(scope = account),
            identity(scope = guest),
            identity(scope = account, operation = "createCollection"),
            identity(scope = staff, operation = "adminCreateRecipe"),
        ).forEach { assertIs<CommandResult.Applied>(execute(it)) }

        assertFailsWith<IllegalArgumentException> { identity(scope = guest, operation = "createCollection") }
        assertFailsWith<IllegalArgumentException> { identity(scope = account, operation = "adminCreateRecipe") }
        assertFailsWith<IllegalArgumentException> { identity(scope = guest, operation = "adminCreateRecipe") }
        assertFailsWith<IllegalArgumentException> { identity(scope = staff) }
        assertFailsWith<IllegalArgumentException> { identity(scope = staff, operation = "createCollection") }
        assertEquals(4, count("command_test.mutation"))
        assertEquals(4, count("platform.idempotency"))
    }

    @Test(timeout = 30_000)
    fun exceptionAfterFixtureWriteRollsBackMutationAndReceiptBeforeRetry() {
        val command = identity()
        assertFailsWith<IllegalStateException> {
            execute(command, mutation = { connection ->
                insertMutation(connection, command)
                throw IllegalStateException("Synthetic failure after the fixture mutation")
            })
        }
        assertEquals(1, mutationCalls.get())
        assertEquals(0, count("command_test.mutation"))
        assertEquals(0, count("platform.idempotency"))

        assertIs<CommandResult.Applied>(execute(command))
        assertIs<CommandResult.Replayed>(execute(command))
        assertEquals(1, count("command_test.mutation"))
        assertEquals(1, count("platform.idempotency"))
    }

    @Test(timeout = 30_000)
    fun serializationFailuresRetryTheOriginalIdentityWithOnlyOneCommittedMutation() {
        val command = identity()
        val attempts = AtomicInteger()
        val result = execute(command, mutation = { connection ->
            insertMutation(connection, command)
            if (attempts.incrementAndGet() < 3) throw SQLException("Synthetic serialization conflict", "40001")
            successReply
        })
        assertIs<CommandResult.Applied>(result)
        assertEquals(3, attempts.get())
        assertEquals(3, validations.get())
        assertEquals(3, mutationCalls.get())
        assertEquals(1, count("command_test.mutation"))
        assertEquals(1, count("platform.idempotency"))
        assertEquals(command.requestHash, receipt(command).requestHash)
        assertIs<CommandResult.Replayed>(execute(command))
    }

    @Test(timeout = 30_000)
    fun exhaustedSerializationRetriesLeaveNoMutationOrReceipt() {
        val command = identity()
        val failure = assertFailsWith<SQLException> {
            execute(command, mutation = { connection ->
                insertMutation(connection, command)
                throw SQLException("Synthetic serialization conflict", "40001")
            })
        }
        assertEquals("40001", failure.sqlState)
        assertEquals(3, mutationCalls.get())
        assertEquals(3, validations.get())
        assertEquals(0, count("command_test.mutation"))
        assertEquals(0, count("platform.idempotency"))
        assertIs<CommandResult.Applied>(execute(command))
        assertEquals(1, count("command_test.mutation"))
    }

    @Test(timeout = 30_000)
    fun replayRevalidatesPrincipalAndUsesDedicatedPermissionWithoutNewAuthorization() {
        val command = identity()
        assertIs<CommandResult.Applied>(execute(command))
        setPermission(command, allowNew = false, allowReplay = true)

        val replay = assertIs<CommandResult.Replayed>(execute(command))
        assertReply(successReply, replay.reply)
        assertEquals(2, validations.get())
        assertEquals(1, newAuthorizations.get())
        assertEquals(1, replayAuthorizations.get())
        assertEquals(1, mutationCalls.get())

        setPermission(command, allowNew = false, allowReplay = false)
        assertFailsWith<PermissionDenied> { execute(command) }
        assertEquals(3, validations.get())
        assertEquals(1, newAuthorizations.get())
        assertEquals(2, replayAuthorizations.get())
        assertEquals(1, count("command_test.mutation"))
        assertEquals("completed", receipt(command).state)
    }

    @Test(timeout = 30_000)
    fun deniedNewAuthorizationIsNotCachedAndDoesNotWriteMutation() {
        val command = identity()
        setPermission(command, allowNew = false, allowReplay = true)
        assertFailsWith<PermissionDenied> { execute(command) }
        assertEquals(0, count("command_test.mutation"))
        assertEquals(0, count("platform.idempotency"))
        assertEquals(0, mutationCalls.get())

        setPermission(command, allowNew = true, allowReplay = true)
        assertIs<CommandResult.Applied>(execute(command))
        assertEquals(1, count("command_test.mutation"))
    }

    @Test(timeout = 30_000)
    fun revokedIdentityCannotReadItsPreviouslyCompletedReply() {
        val command = identity()
        assertIs<CommandResult.Applied>(execute(command))
        database.connection.use { connection ->
            connection.prepareStatement("UPDATE command_test.principal SET active=false WHERE scope=?").use { statement ->
                statement.setString(1, command.scope.storageKey)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertFailsWith<PrincipalRevoked> { execute(command) }
        assertEquals(2, validations.get())
        assertEquals(0, replayAuthorizations.get())
        assertEquals(1, newAuthorizations.get())
        assertEquals(1, mutationCalls.get())
        assertEquals(1, count("command_test.mutation"))
        assertEquals("completed", receipt(command).state)
    }

    @Test(timeout = 30_000)
    fun successfulDeleteReplays204AfterTargetHasDisappeared() {
        val targetId = UUID.randomUUID()
        database.connection.use { connection ->
            connection.prepareStatement("INSERT INTO command_test.target(id,owner_scope) VALUES (?,?)").use { statement ->
                statement.setObject(1, targetId)
                statement.setString(2, account.storageKey)
                statement.executeUpdate()
            }
        }
        val command = identity(
            operation = "deleteSavedRecipe", path = mapOf("savedRecipeId" to targetId.toString()), body = null,
        )
        val requireExistingOwnedTarget: (Connection) -> Unit = { connection ->
            connection.prepareStatement("SELECT owner_scope FROM command_test.target WHERE id=? FOR UPDATE").use { statement ->
                statement.setObject(1, targetId)
                statement.executeQuery().use { rows ->
                    if (!rows.next() || rows.getString(1) != account.storageKey) throw PermissionDenied()
                }
            }
        }
        val deleteMutation: (Connection) -> StoredReply = { connection ->
            connection.prepareStatement("DELETE FROM command_test.target WHERE id=?").use { statement ->
                statement.setObject(1, targetId)
                assertEquals(1, statement.executeUpdate())
            }
            insertMutation(connection, command)
            StoredReply(204)
        }
        val first = execute(command, newCheck = requireExistingOwnedTarget, mutation = deleteMutation)
        assertIs<CommandResult.Applied>(first)
        assertEquals(0, count("command_test.target"))

        val replay = execute(command, newCheck = requireExistingOwnedTarget, mutation = deleteMutation)
        assertIs<CommandResult.Replayed>(replay)
        assertEquals(204, replay.reply.status)
        assertNull(replay.reply.body)
        assertNull(replay.reply.etag)
        assertEquals(1, newAuthorizations.get())
        assertEquals(1, replayAuthorizations.get())
        assertEquals(1, mutationCalls.get())
        assertEquals(1, count("command_test.mutation"))
    }

    @Test(timeout = 30_000)
    fun expiredReceiptCompactsToTombstoneAndNeverExecutesAgain() {
        val command = identity()
        assertIs<CommandResult.Applied>(execute(command))
        database.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE platform.idempotency SET expires_at=clock_timestamp()-interval '1 second' " +
                    "WHERE principal_scope=? AND operation_id=? AND key=?",
            ).use { statement ->
                statement.setString(1, command.scope.storageKey)
                statement.setString(2, command.operationId)
                statement.setObject(3, command.key)
                assertEquals(1, statement.executeUpdate())
            }
        }

        assertEquals(CommandResult.ReceiptExpired, execute(command))
        val compacted = receipt(command)
        assertEquals("tombstone", compacted.state)
        assertEquals(command.requestHash, compacted.requestHash)
        assertNull(compacted.status)
        assertNull(compacted.body)
        assertNull(compacted.etag)
        assertNotNull(compacted.tombstonedAt)
        repeat(2) { assertEquals(CommandResult.ReceiptExpired, execute(command)) }
        val mismatched = identity(key = command.key, body = json("""{"servings":99}"""))
        assertEquals(CommandResult.Mismatch, execute(mismatched))
        assertEquals(compacted, receipt(command))
        assertEquals(1, count("platform.idempotency"))
        assertEquals(1, count("command_test.mutation"))
        assertEquals(1, newAuthorizations.get())
        assertEquals(0, replayAuthorizations.get())
        assertEquals(1, mutationCalls.get())
    }

    @Test(timeout = 30_000)
    fun statusNestedJsonAndEtagSurviveDatabaseReloadInANewExecutor() {
        val command = identity()
        assertReply(successReply, assertIs<CommandResult.Applied>(execute(command)).reply)
        commands = DurableCommands(PgTransactions(database))
        val replay = assertIs<CommandResult.Replayed>(execute(command))
        assertReply(successReply, replay.reply)
        val stored = receipt(command)
        assertEquals(successReply.status, stored.status)
        assertEquals(successReply.body, stored.body?.let(::json))
        assertEquals(successReply.etag, stored.etag)
        assertEquals(1, count("command_test.mutation"))
    }

    @Test(timeout = 30_000)
    fun jsonNullSuccessIsDistinctFromNoContentOnReplay() {
        val command = identity()
        val jsonNullReply = StoredReply(200, JsonNull, "\"json-null-1\"")
        assertReply(jsonNullReply, assertIs<CommandResult.Applied>(execute(command, reply = jsonNullReply)).reply)
        val replay = assertIs<CommandResult.Replayed>(execute(command))
        assertReply(jsonNullReply, replay.reply)
        assertNotNull(replay.reply.body)
        assertEquals(JsonNull, replay.reply.body)
    }

    @Test(timeout = 30_000)
    fun preexistingPendingReceiptFailsClosedWithoutExecutingMutation() {
        val command = identity()
        database.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO platform.idempotency(principal_scope,operation_id,key,request_hash,state,expires_at) " +
                    "VALUES (?,?,?,?,'pending',clock_timestamp()+interval '7 days')",
            ).use { statement ->
                statement.setString(1, command.scope.storageKey)
                statement.setString(2, command.operationId)
                statement.setObject(3, command.key)
                statement.setString(4, command.requestHash)
                statement.executeUpdate()
            }
        }
        assertEquals(CommandResult.IncompleteReceipt, execute(command))
        assertEquals(1, validations.get())
        assertEquals(0, newAuthorizations.get())
        assertEquals(0, replayAuthorizations.get())
        assertEquals(0, mutationCalls.get())
        assertEquals(0, count("command_test.mutation"))
        assertEquals("pending", receipt(command).state)
    }

    private fun identity(
        scope: PrincipalScope = account,
        operation: String = "createPlan",
        key: UUID = UUID.randomUUID(),
        path: Map<String, String> = emptyMap(),
        query: Map<String, List<String>> = emptyMap(),
        body: JsonElement? = json("""{"servings":2}"""),
        ifMatch: String? = null,
    ) = CommandIdentity(scope, operation, key, path, query, body, ifMatch, catalog)

    private fun execute(
        command: CommandIdentity,
        reply: StoredReply = successReply,
        newCheck: (Connection) -> Unit = {},
        mutation: ((Connection) -> StoredReply)? = null,
    ): CommandResult = commands.execute(
        command = command,
        validatePrincipal = { connection ->
            validations.incrementAndGet()
            connection.prepareStatement("SELECT active FROM command_test.principal WHERE scope=? FOR SHARE").use { statement ->
                statement.setString(1, command.scope.storageKey)
                statement.executeQuery().use { rows ->
                    if (!rows.next() || !rows.getBoolean(1)) throw PrincipalRevoked()
                }
            }
        },
        authorizeNew = { connection ->
            newAuthorizations.incrementAndGet()
            authorize(connection, command, replay = false)
            newCheck(connection)
        },
        authorizeReplay = { connection, _ ->
            replayAuthorizations.incrementAndGet()
            authorize(connection, command, replay = true)
        },
        mutate = { connection ->
            mutationCalls.incrementAndGet()
            if (mutation != null) mutation(connection) else {
                insertMutation(connection, command)
                reply
            }
        },
    )

    private fun authorize(connection: Connection, command: CommandIdentity, replay: Boolean) {
        connection.prepareStatement(
            "SELECT allow_new,allow_replay FROM command_test.permission WHERE scope=? AND operation_id=? FOR SHARE",
        ).use { statement ->
            statement.setString(1, command.scope.storageKey)
            statement.setString(2, command.operationId)
            statement.executeQuery().use { rows ->
                if (!rows.next() || !rows.getBoolean(if (replay) "allow_replay" else "allow_new")) throw PermissionDenied()
            }
        }
    }

    private fun grant(scope: PrincipalScope, vararg operations: String) {
        database.connection.use { connection ->
            connection.prepareStatement("INSERT INTO command_test.principal(scope) VALUES (?) ON CONFLICT DO NOTHING").use { statement ->
                statement.setString(1, scope.storageKey)
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO command_test.permission(scope,operation_id) VALUES (?,?)").use { statement ->
                operations.forEach { operation ->
                    statement.setString(1, scope.storageKey)
                    statement.setString(2, operation)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun setPermission(command: CommandIdentity, allowNew: Boolean, allowReplay: Boolean) {
        database.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE command_test.permission SET allow_new=?,allow_replay=? WHERE scope=? AND operation_id=?",
            ).use { statement ->
                statement.setBoolean(1, allowNew)
                statement.setBoolean(2, allowReplay)
                statement.setString(3, command.scope.storageKey)
                statement.setString(4, command.operationId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertMutation(connection: Connection, command: CommandIdentity) {
        connection.prepareStatement("INSERT INTO command_test.mutation(id,scope,operation_id,command_key) VALUES (?,?,?,?)").use { statement ->
            // A distinct row on every invocation ensures duplicate execution cannot hide behind a fixture constraint.
            statement.setObject(1, UUID.randomUUID())
            statement.setString(2, command.scope.storageKey)
            statement.setString(3, command.operationId)
            statement.setObject(4, command.key)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun count(table: String): Int = database.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }
    }

    private fun persistedScopes(): Set<String> = database.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT principal_scope FROM platform.idempotency").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun receipt(command: CommandIdentity): Receipt = database.connection.use { connection ->
        connection.prepareStatement(
            "SELECT state,request_hash,response_code,response_json,response_etag,tombstoned_at " +
                "FROM platform.idempotency WHERE principal_scope=? AND operation_id=? AND key=?",
        ).use { statement ->
            statement.setString(1, command.scope.storageKey)
            statement.setString(2, command.operationId)
            statement.setObject(3, command.key)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Expected a durable command receipt" }
                val status = rows.getInt("response_code").let { if (rows.wasNull()) null else it }
                Receipt(
                    rows.getString("state"), rows.getString("request_hash"), status,
                    rows.getString("response_json"), rows.getString("response_etag"),
                    rows.getTimestamp("tombstoned_at")?.toInstant()?.toString(),
                ).also { check(!rows.next()) }
            }
        }
    }

    private data class Receipt(
        val state: String,
        val requestHash: String,
        val status: Int?,
        val body: String?,
        val etag: String?,
        val tombstonedAt: String?,
    )

    private class PrincipalRevoked : RuntimeException("Synthetic principal is inactive")
    private class PermissionDenied : RuntimeException("Synthetic ACL denies this operation")

    private fun replyOf(result: CommandResult): StoredReply = when (result) {
        is CommandResult.Applied -> result.reply
        is CommandResult.Replayed -> result.reply
        else -> error("Expected a successful command response, got ${result::class.simpleName}")
    }

    private fun assertReply(expected: StoredReply, actual: StoredReply) {
        assertEquals(expected.status, actual.status)
        assertEquals(expected.body, actual.body)
        assertEquals(expected.etag, actual.etag)
    }

    private fun json(value: String) = Json.parseToJsonElement(value)

    companion object {
        private var cluster: PostgresTestCluster? = null
        private val catalog by lazy { ContractCatalog.bundled() }
        private val successReply = StoredReply(
            201,
            Json.parseToJsonElement(
                """{"planId":"fixture-plan","steps":[{"name":"Sauté","ready":true},null],"servings":2}""",
            ),
            "\"revision-7\"",
        )

        @ClassRule
        @JvmField
        val suiteTimeout: Timeout = Timeout.builder().withTimeout(8, TimeUnit.MINUTES).build()

        @BeforeClass
        @JvmStatic
        fun startCluster() {
            cluster = PostgresTestCluster.start()
        }

        @AfterClass
        @JvmStatic
        fun closeCluster() {
            cluster?.close()
            cluster = null
        }
    }
}
