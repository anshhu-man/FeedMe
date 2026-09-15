package com.feedme.server.cooking

import com.feedme.contracts.WireDocument
import com.feedme.server.db.*
import com.feedme.server.planning.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*

/** TEST ONLY: real PostgreSQL, explicitly synthetic identity/editorial adapters; never a production provider. */
class CookingTestFixture(val source: DataSource) {
    val authority = TestAuthority()
    val faults = Faults()
    val account: VerifiedCookingPrincipal
    val plans: PlansStore
    val store: CookingStore
    init {
        PlatformMigrations(source).migrate()
        sql("CREATE SCHEMA cooking_test; " +
            "CREATE TABLE cooking_test.principals(kind text NOT NULL,id uuid NOT NULL,active boolean NOT NULL,expires_at timestamptz NOT NULL,PRIMARY KEY(kind,id)); " +
            "CREATE TABLE cooking_test.sessions(kind text NOT NULL,id uuid NOT NULL,session_id uuid NOT NULL,active boolean NOT NULL,expires_at timestamptz NOT NULL,PRIMARY KEY(kind,id,session_id)); " +
            "CREATE TABLE cooking_test.inputs(kind text NOT NULL,id uuid NOT NULL,snapshot_text text NOT NULL,PRIMARY KEY(kind,id))")
        account = principal(CommandActor.ACCOUNT)
        plans = PlansStore("test", PgTransactions(source), authority, PlanningServicePolicy("test-rank-1", false, true, 86400, 600),
            PlanningCursors("v1", mapOf("v1" to ByteArray(32) { 7 })))
        store = newStore()
    }
    fun principal(kind: CommandActor, id: UUID = UUID.randomUUID()): VerifiedCookingPrincipal {
        val session = UUID.randomUUID(); val actor = VerifiedCookingPrincipal("test", kind, id,
            session.takeIf { kind == CommandActor.ACCOUNT }, session.takeIf { kind == CommandActor.GUEST })
        source.connection.use { c ->
            c.prepareStatement("INSERT INTO cooking_test.principals VALUES(?,?,true,clock_timestamp()+interval '1 day')").use {
                it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.executeUpdate() }
            c.prepareStatement("INSERT INTO cooking_test.sessions VALUES(?,?,?,true,clock_timestamp()+interval '1 day')").use {
                it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.setObject(3, session); it.executeUpdate() }
            c.prepareStatement("INSERT INTO cooking_test.inputs VALUES(?,?,?)").use {
                it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.setString(3, evidence().toString()); it.executeUpdate() }
        }
        return actor
    }
    fun secondDevice(actor: VerifiedCookingPrincipal = account): VerifiedCookingPrincipal {
        val session = UUID.randomUUID()
        source.connection.use { c -> c.prepareStatement("INSERT INTO cooking_test.sessions VALUES(?,?,?,true,clock_timestamp()+interval '1 day')").use {
            it.setString(1, actor.kind.name.lowercase()); it.setObject(2, actor.principalId); it.setObject(3, session); it.executeUpdate() } }
        return VerifiedCookingPrincipal("test", actor.kind, actor.principalId, session.takeIf { actor.kind == CommandActor.ACCOUNT }, session.takeIf { actor.kind == CommandActor.GUEST })
    }
    fun planningActor(actor: VerifiedCookingPrincipal) = VerifiedPlanningPrincipal(actor.environment, actor.kind, actor.principalId, actor.deviceSessionId)
    fun seedPlan(actor: VerifiedCookingPrincipal = account, body: JsonObject = planningRequest()): JsonObject =
        commandReply(plans.createPlan(planningActor(actor), UUID.randomUUID(), body)).body!!.jsonObject
    fun newStore(maxResponseBytes: Int = 65536) = CookingStore("test", PgTransactions(faults.wrap(source)), authority, plans, CookingServicePolicy(maxResponseBytes, 86400))
    fun changeEvidence(actor: VerifiedCookingPrincipal = account, transform: (JsonObject) -> JsonObject) {
        source.connection.use { c -> c.prepareStatement("SELECT snapshot_text FROM cooking_test.inputs WHERE kind=? AND id=?").use { s ->
            s.setString(1, actor.kind.name.lowercase()); s.setObject(2, actor.principalId); s.executeQuery().use { r ->
                check(r.next()); val changed = transform(Json.parseToJsonElement(r.getString(1)).jsonObject)
                c.prepareStatement("UPDATE cooking_test.inputs SET snapshot_text=? WHERE kind=? AND id=?").use {
                    it.setString(1, changed.toString()); it.setString(2, actor.kind.name.lowercase()); it.setObject(3, actor.principalId); it.executeUpdate() }
            }
        } }
    }
    fun changeRecipe(transform: (JsonObject) -> JsonObject) = changeEvidence { e ->
        val catalog = e.getValue("catalog").jsonObject; val candidate = catalog.getValue("candidates").jsonArray.single().jsonObject
        JsonObject(e + ("catalog" to JsonObject(catalog + ("candidates" to buildJsonArray {
            add(JsonObject(candidate + ("recipe" to transform(candidate.getValue("recipe").jsonObject))))
        }))))
    }
    fun expirePlans() = sql("UPDATE planning.plan_requests SET created_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second',cursor_expires_at=clock_timestamp()-interval '2 seconds'")
    /** Fixture clock control only: temporarily disable the immutable-lifetime trigger, never production behavior. */
    fun expireSession(id: UUID) = sql("BEGIN; ALTER TABLE cooking.cook_sessions DISABLE TRIGGER immutable_cooking_pin; " +
        "UPDATE cooking.cook_sessions SET created_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second' WHERE id='$id'; " +
        "ALTER TABLE cooking.cook_sessions ENABLE TRIGGER immutable_cooking_pin; COMMIT")
    fun sql(sql: String) { source.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
    fun value(sql: String): String = source.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } } }
    fun count(table: String) = value("SELECT count(*) FROM $table").toInt()

    class TestAuthority : CookingAuthority, PlanningAuthority {
        var enabled = true; var notesAllowed = true; var snapshotReads = 0
        var afterPrincipal: (() -> Unit)? = null; var afterSnapshot: (() -> Unit)? = null
        override fun lockPrincipal(connection: Connection, principal: VerifiedCookingPrincipal) {
            lock(connection, principal.kind, principal.principalId, checkNotNull(principal.deviceSessionId ?: principal.guestSessionId))
            afterPrincipal?.invoke()
        }
        override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal) = lock(connection, principal.kind, principal.principalId, principal.deviceSessionId)
        private fun lock(c: Connection, kind: CommandActor, id: UUID, session: UUID?) {
            c.prepareStatement("SELECT active,expires_at>clock_timestamp() FROM cooking_test.principals WHERE kind=? AND id=? FOR UPDATE").use {
                it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.executeQuery().use { r ->
                    if (!r.next() || !r.getBoolean(1) || !r.getBoolean(2)) throw CookingFailure(CookingFailureCode.UNAUTHENTICATED)
                }
            }
            if (session != null) c.prepareStatement("SELECT active,expires_at>clock_timestamp() FROM cooking_test.sessions WHERE kind=? AND id=? AND session_id=? FOR SHARE").use {
                it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.setObject(3, session); it.executeQuery().use { r ->
                    if (!r.next() || !r.getBoolean(1) || !r.getBoolean(2)) throw CookingFailure(CookingFailureCode.UNAUTHENTICATED)
                }
            }
        }
        override fun requireNewCookingEnabled(connection: Connection, principal: VerifiedCookingPrincipal) {
            if (!enabled) throw CookingFailure(CookingFailureCode.NOT_CONFIGURED)
        }
        override fun validatePersonalNotes(connection: Connection, principal: VerifiedCookingPrincipal, notes: JsonArray) {
            if (!notesAllowed || notes.any { it.jsonObject["shortcutId"] != null || it.jsonObject["label"] != JsonPrimitive("myNote") })
                throw CookingFailure(CookingFailureCode.NOT_CONFIGURED)
        }
        override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal) = Unit
        override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument): PlanningEvidenceSnapshot {
            snapshotReads++
            val result = connection.prepareStatement("SELECT snapshot_text FROM cooking_test.inputs WHERE kind=? AND id=? FOR SHARE").use {
                it.setString(1, principal.kind.name.lowercase()); it.setObject(2, principal.principalId); it.executeQuery().use { r ->
                    check(r.next()); PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse(r.getString(1))) }
            }
            afterSnapshot?.invoke(); return result
        }
    }
    class Faults {
        @Volatile var outboxFailure = false; @Volatile var loseCommit = false
        @Volatile var afterCommit: (() -> Unit)? = null
        fun wrap(source: DataSource): DataSource = object : DataSource by source {
            override fun getConnection(): Connection {
                val actual = source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement" && args?.firstOrNull() is String && (args[0] as String).contains("INSERT INTO platform.outbox") && outboxFailure) {
                        outboxFailure = false; throw SQLException("synthetic transaction fault", "XX000")
                    }
                    try { val result = method.invoke(actual, *(args ?: emptyArray()))
                        if (method.name == "commit") {
                            val callback = afterCommit; afterCommit = null; callback?.invoke()
                            if (loseCommit) { loseCommit = false; throw SQLException("synthetic lost application commit receipt", "08006") }
                        }; result
                    } catch (failure: InvocationTargetException) { throw failure.targetException }
                } as Connection
            }
        }
    }
    companion object {
        const val INGREDIENT = "00000000-0000-4000-8000-000000000011"
        const val VERSION = "00000000-0000-4000-8000-000000000021"
        const val RECIPE = "00000000-0000-4000-8000-000000000031"
        const val TIME = "2026-09-13T10:00:00Z"
        fun commandReply(result: CommandResult): StoredReply = when (result) {
            is CommandResult.Applied -> result.reply; is CommandResult.Replayed -> result.reply; else -> error("Test expected acknowledged command") }
        fun arr(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
        fun planningRequest() = buildJsonObject {
            put("mode", "assemble"); put("preferenceVersion", 1); put("constraints", buildJsonObject {
                put("ingredientIds", arr(INGREDIENT)); put("energy", "assemble"); put("equipmentIds", arr("bowl")); put("servings", 1)
                put("hardExcludedIngredientIds", arr()); put("tasteTags", arr())
            })
        }
        fun recipe() = buildJsonObject {
            put("id", VERSION); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("recipeId", RECIPE); put("title", "Synthetic cooking fixture")
            put("reviewStatus", "published"); put("reviewedAt", TIME); put("estimateBasis", "reviewerEstimate"); put("servings", 1)
            put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("quantity", 1); put("unit", "g"); put("optional", false) }) })
            put("steps", buildJsonArray { listOf("mix", "serve").forEachIndexed { i, id -> add(buildJsonObject {
                put("stepId", id); put("position", i + 1); put("instruction", "Synthetic reviewed instruction $id")
                put("ingredientIds", arr(INGREDIENT)); put("requiredEquipmentIds", arr("bowl")); put("mandatorySafetyStep", false)
            }) } })
            put("activeMinutes", 5); put("totalMinutes", 10); put("utensilCount", 1); put("equipmentIds", arr("bowl")); put("modes", arr("assemble")); put("tasteTags", arr("crunch"))
            put("preparationTags", arr("noHeat", "oneBowl")); put("cleanupMinutes", 2)
        }
        fun evidence() = buildJsonObject {
            put("version", 1); put("preferences", buildJsonObject { put("revision", "1"); put("excludedIngredientIds", arr()); put("dislikedIngredientIds", arr()) })
            put("pantry", buildJsonObject { put("revision", "pantry-1"); put("items", arr()) }); put("baseMeal", JsonNull)
            put("catalog", buildJsonObject {
                put("revision", "catalog-1"); put("taxonomyRevision", "taxonomy-1")
                put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("componentIds", arr()) }) })
                put("candidates", buildJsonArray { add(buildJsonObject {
                    put("recipe", recipe()); put("review", buildJsonObject {
                        put("reviewReference", "synthetic-independent-review"); put("policyVersion", "test-rank-1"); put("kind", "MEAL")
                        put("minimumEnergy", "ASSEMBLE"); put("heatingRequired", false); put("substantialPreparation", false); put("freeCatalogEligible", true)
                        put("compatibleBaseTypes", arr()); put("linearQuantityScalingReviewed", true); put("stepsValidForScalingRange", true)
                        put("effortValidForScalingRange", true); put("scalableUnits", arr("g"))
                    })
                }) })
            })
        }
    }
}
