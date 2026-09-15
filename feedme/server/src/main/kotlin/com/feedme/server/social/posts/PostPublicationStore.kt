package com.feedme.server.social.posts

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.media.*
import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.drafts.PostDraftCleanupEvidence
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** One explicit original Publish, using the existing command transaction. No implicit server
 * draft, provider, feed or notification. The immutable publication identity survives receipt
 * compaction and post deletion. Publication and every cooperating draft/media writer hold the
 * same principal, owner head and lifecycle locks. Current replay disclosure is not a new grant. */
class PostPublicationStore(val environment: String, private val transactions: PgTransactions,
    private val authority: PostPublicationAuthority, private val media: MediaStore,
    val policy: PostPublicationPolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && media.environment == environment) }

    fun publishPost(actor: VerifiedSocialAccount, key: UUID, body: JsonObject): CommandResult = safe {
        if (actor.environment != environment) fail(PostPublicationFailureCode.UNAUTHENTICATED)
        val input = request(body)
        val client = uuid(input.getValue("clientDraftId"))
        val draftId = input["draftId"]?.let(::uuid)
        val draftVersion = input["draftVersion"]?.let(::version)
        if ((draftId == null) != (draftVersion == null)) fail(PostPublicationFailureCode.INPUT_INVALID)
        val selection = selection(input)
        val identity = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, actor.accountId), "publishPost", key, body = input)
        current()
        commands.execute(identity,
            { c -> authority.lockPrincipal(c, actor); current() },
            { c -> head(c, actor, true) },
            { c, original -> head(c, actor, false); replay(c, actor, client, draftId, draftVersion, identity, original) },
            { c ->
                authority.requirePublishEnabled(c, actor); current()
                val generation = root(c, actor, client, true)
                if (PostPublicationLifecycle.isPublished(c, environment, actor.accountId, client)) fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
                val draft = draft(c, actor, client)
                if (draftId == null && draft != null) fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
                if (draftId != null) {
                    if (draft == null || draft.id != draftId) fail(PostPublicationFailureCode.DRAFT_UNAVAILABLE)
                    live(c, draft)
                    if (draft.generation != generation) fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
                    if (draft.version != draftVersion) fail(PostPublicationFailureCode.VERSION_CONFLICT)
                    if (canonical(selection(draft.content)) != canonical(selection)) fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
                }
                val evidence = authority.authorizeNew(c, actor, selection); current()
                validateEvidence(actor, selection, evidence); fresh(c, evidence.validUntil)
                draft?.let { live(c, it) }
                val at = now(c)
                val postId = UUID.randomUUID()
                val post = post(postId, at, selection, evidence)
                val response = response(post)
                exec(c, "INSERT INTO social.posts(environment,owner_user_id,id,version,status,content,published_at,expires_at,updated_at) VALUES(?,?,?,1,'published',?::jsonb,?,?,?)") {
                    owner(actor); setObject(3, postId); setString(4, post.toString()); setObject(5, time(at)); setObject(6, time(at.plusSeconds(86400))); setObject(7, time(at))
                }
                var grantDeadline=evidence.validUntil
                val selected = media.preparePublication(c, mediaActor(actor), client, generation,
                    selection.getValue("mediaIds").jsonArray.map(::uuid), key,
                    { id, mediaVersion, derivatives ->
                        grantDeadline=minOf(grantDeadline,authority.authorizeReadyMedia(c, actor, id, mediaVersion, derivatives));current();fresh(c,grantDeadline)
                    },
                    { id, mediaVersion ->
                        val cleanup = PostDraftCleanupEvidence.capture(c, actor, id, mediaVersion)
                        exec(c, "INSERT INTO social.post_publication_discard_media VALUES(?,?,?,?,?,?::jsonb)") {
                            owner(actor); setObject(3, postId); setObject(4, id); setLong(5, mediaVersion); setString(6, cleanup.toString())
                        }
                    })
                selected.forEachIndexed { position, item ->
                    exec(c, "INSERT INTO social.post_media VALUES(?,?,?,?,?,?,?::jsonb)") {
                        owner(actor); setObject(3, postId); setObject(4, item.id); setInt(5, position); setLong(6, item.version); setString(7, item.derivatives.toString())
                    }
                }
                evidence.memberships.toSortedMap().forEach { (circle, membership) ->
                    exec(c, "INSERT INTO social.post_audiences VALUES(?,?,?,?,?)") {
                        owner(actor); setObject(3, postId); setObject(4, circle); setLong(5, membership)
                    }
                }
                val recipeHash = evidence.recipeSnapshot?.let(::hash)
                evidence.verifiedAttachment?.let { attachment ->
                    exec(c, "INSERT INTO social.post_attachments VALUES(?,?,?,?::jsonb,?::jsonb,?,?)") {
                        owner(actor); setObject(3, postId); setString(4, attachment.toString()); setString(5, checkNotNull(evidence.recipeSnapshot).toString())
                        setString(6, recipeHash); setObject(7, time(at))
                    }
                }
                exec(c, "INSERT INTO social.recipe_save_policies VALUES(?,?,?,1,?,?,?,?)") {
                    owner(actor); setObject(3, postId); setBoolean(4, selection.getValue("allowRecipeSaves").jsonPrimitive.boolean)
                    setString(5, recipeHash); setString(6, selection.getValue("saveDisclosureVersion").jsonPrimitive.content); setObject(7, time(at))
                }
                exec(c, "INSERT INTO social.post_publications VALUES(?,?,?,?,?,?,?,?,?,?,?,?)") {
                    owner(actor); setObject(3, client); setLong(4, generation); setObject(5, postId); setObject(6, key)
                    setString(7, identity.requestHash); setString(8, hash(post)); setString(9, recipeHash); setObject(10, draftId); setObject(11, draftVersion); setObject(12, time(at))
                }
                if (draft != null) {
                    live(c, draft)
                    if (draft.version == Long.MAX_VALUE) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                    exec(c, "UPDATE platform.post_drafts SET status='published',published_post_id=?,version=version+1,updated_at=clock_timestamp() WHERE environment=? AND owner_user_id=? AND id=? AND version=? AND status='draft'") {
                        setObject(1, postId); owner(actor, 2); setObject(4, draft.id); setLong(5, draft.version)
                    }
                    exec(c, "UPDATE platform.post_draft_heads SET revision=revision+1 WHERE environment=? AND owner_user_id=? AND revision<9223372036854775807") { owner(actor) }
                }
                outbox.append(c, EventDraft(UUID.randomUUID(), "social.post.published.v1", 1, "post", postId, 1, "social", UUID.randomUUID().toString(), key, buildJsonObject {
                    put("postId", postId.toString()); put("authorUserId", actor.accountId.toString()); put("aclVersion", 1); put("expiresAt", at.plusSeconds(86400).toString())
                }))
                fresh(c, grantDeadline); current(); response
            }).also { current() }
    }

    private fun replay(c: Connection, actor: VerifiedSocialAccount, client: UUID, draftId: UUID?, draftVersion: Long?, command: CommandIdentity, original: StoredReply) {
        val generation = root(c, actor, client, false)
        val post = original.body?.jsonObject ?: fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        val postId = uuid(post.getValue("id"))
        val originalRecipeHash = query(c, "SELECT * FROM social.post_publications WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR SHARE", { owner(actor); setObject(3, client) }) { r ->
            if (!r.next() || r.getLong("draft_generation") != generation || r.getObject("post_id", UUID::class.java) != postId ||
                r.getObject("command_key", UUID::class.java) != command.key || r.getString("request_sha256") != command.requestHash ||
                r.getString("response_sha256") != hash(post) || r.getObject("draft_id", UUID::class.java) != draftId ||
                (r.getObject("reviewed_draft_version") as? Number)?.toLong() != draftVersion) fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
            r.getString("recipe_sha256")
        }
        // No substitute newer Post and no resurrection after hiding/deletion. Receipt remains
        // historical; this bounded implementation permits exact unchanged published originals.
        query(c, "SELECT status,version,content FROM social.posts WHERE environment=? AND owner_user_id=? AND id=? FOR SHARE", { owner(actor); setObject(3, postId) }) { r ->
            if (!r.next() || r.getString("status") != "published" || r.getLong("version") != 1L ||
                r.getString("content")?.let { Json.parseToJsonElement(it) } != post) fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
        }
        if (draftId != null) query(c, "SELECT status,published_post_id,version,draft_generation FROM platform.post_drafts WHERE environment=? AND owner_user_id=? AND id=? FOR SHARE", { owner(actor); setObject(3, draftId) }) { r ->
            if (draftVersion == Long.MAX_VALUE || !r.next() || r.getString("status") != "published" || r.getObject("published_post_id", UUID::class.java) != postId ||
                r.getLong("version") != checkNotNull(draftVersion) + 1 || r.getLong("draft_generation") != generation) fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
        }
        var deadline = authority.authorizeReplay(c, actor, post); current(); fresh(c, deadline)
        val selected = query(c, "SELECT media_id,media_version,derivative_set,position FROM social.post_media WHERE environment=? AND owner_user_id=? AND post_id=? ORDER BY position FOR SHARE", { owner(actor); setObject(3, postId) }) { r -> buildList {
            while (r.next()) {
                if (r.getInt("position") != size) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                add(MediaStore.PublicationMedia(r.getObject("media_id", UUID::class.java), r.getLong("media_version"), Json.parseToJsonElement(r.getString("derivative_set")).jsonObject))
            }
        } }
        if (selected.map { it.id } != post.getValue("mediaIds").jsonArray.map(::uuid)) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        var unusedCount = 0L
        media.verifyPublicationMedia(c, mediaActor(actor), client, generation, selected) { id, mediaVersion ->
            val expected = query(c, "SELECT media_version,cleanup_evidence FROM social.post_publication_discard_media WHERE environment=? AND owner_user_id=? AND post_id=? AND media_id=?", { owner(actor); setObject(3, postId); setObject(4, id) }) {
                if (!it.next() || it.getLong(1) != mediaVersion) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                Json.parseToJsonElement(it.getString(2)).jsonObject
            }
            PostDraftCleanupEvidence.verify(expected, PostDraftCleanupEvidence.capture(c, actor, id, mediaVersion)); unusedCount++
        }
        selected.forEach { deadline=minOf(deadline,authority.authorizeReadyMedia(c, actor, it.id, it.version, it.derivatives));current();fresh(c,deadline) }
        val recorded = query(c, "SELECT count(*) FROM social.post_publication_discard_media WHERE environment=? AND owner_user_id=? AND post_id=?", { owner(actor); setObject(3, postId) }) { it.next(); it.getLong(1) }
        if (recorded != unusedCount) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        verifyMaterial(c, actor, post, originalRecipeHash)
        val checked = response(post)
        if (original.status != checked.status || original.etag != checked.etag) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        fresh(c, deadline); current()
    }

    private fun verifyMaterial(c: Connection, actor: VerifiedSocialAccount, post: JsonObject, originalRecipeHash: String?) {
        val id = uuid(post.getValue("id"))
        val memberships = query(c, "SELECT circle_id,author_membership_generation FROM social.post_audiences WHERE environment=? AND owner_user_id=? AND post_id=? ORDER BY circle_id FOR SHARE", { owner(actor); setObject(3, id) }) { r -> buildMap { while (r.next()) put(r.getObject(1, UUID::class.java), r.getLong(2)) } }
        val expected = post.getValue("audience").jsonObject.getValue("bindings").jsonArray.associate { val a=it.jsonObject; uuid(a.getValue("circleId")) to version(a.getValue("authorMembershipGeneration")) }
        if (memberships != expected) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        val recipeHash = query(c, "SELECT attachment,recipe_snapshot,recipe_sha256 FROM social.post_attachments WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE", { owner(actor); setObject(3, id) }) { r ->
            if (!r.next()) { if (post["attachment"] != null) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE); null }
            else {
                if (Json.parseToJsonElement(r.getString(1)) != post["attachment"] || hash(Json.parseToJsonElement(r.getString(2))) != r.getString(3)) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                r.getString(3)
            }
        }
        if (recipeHash != originalRecipeHash) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        val save = post.getValue("savePolicy").jsonObject
        query(c, "SELECT * FROM social.recipe_save_policies WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE", { owner(actor); setObject(3, id) }) { r ->
            if (!r.next() || r.getLong("version") != version(save.getValue("policyVersion")) || r.getBoolean("allow_future_saves") != save.getValue("allowFutureSaves").jsonPrimitive.boolean ||
                r.getString("disclosure_version") != save.getValue("disclosureVersion").jsonPrimitive.content || r.getString("recipe_sha256") != recipeHash) fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
        }
    }
    private fun request(body: JsonObject): JsonObject {
        val bytes = try { body.toString().encodeToByteArray(throwOnInvalidSequence=true) } catch (_: Exception) { fail(PostPublicationFailureCode.INPUT_INVALID) }
        if (bytes.size > 65536 || validator.validateRequest("publishPost", bytes, "application/json") != BodyValidationResult.Valid) fail(PostPublicationFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun selection(body: JsonObject): JsonObject {
        val fields=body.filterKeys { it !in setOf("clientDraftId","draftId","draftVersion") }.toMutableMap()
        fields["mediaIds"]?.let { ids ->
            val list=ids.jsonArray.map(::uuid)
            if (list.distinct().size != list.size) fail(PostPublicationFailureCode.INPUT_INVALID)
            fields["mediaIds"]=JsonArray(list.map { JsonPrimitive(it.toString()) })
        }
        fields["audience"]?.jsonObject?.let { audience ->
            val circles=audience.getValue("circleIds").jsonArray.map(::uuid)
            if (circles.distinct().size != circles.size || (audience.getValue("kind").jsonPrimitive.content=="self") != circles.isEmpty()) fail(PostPublicationFailureCode.INPUT_INVALID)
            fields["audience"]=JsonObject(audience.filterKeys { it != "bindings" }+("circleIds" to JsonArray(circles.map { JsonPrimitive(it.toString()) })))
        }
        fields["sourcePostId"]?.let { fields["sourcePostId"]=JsonPrimitive(uuid(it).toString()) }
        fields["attachment"]?.jsonObject?.let { attachment ->
            if (listOf("recipeVersionId","planId","personalRecipe").count { it in attachment } != 1) fail(PostPublicationFailureCode.INPUT_INVALID)
            fields["attachment"]=JsonObject(attachment.mapValues { (k,v) -> if(k in setOf("recipeVersionId","planId")) JsonPrimitive(uuid(v).toString()) else v })
        }
        if (fields["allowRecipeSaves"] == JsonPrimitive(true) && fields["attachment"] == null) fail(PostPublicationFailureCode.INPUT_INVALID)
        return JsonObject(fields)
    }
    private fun validateEvidence(actor: VerifiedSocialAccount, selection: JsonObject, evidence: PostPublicationEvidence) {
        if (evidence.author["userId"]?.let(::uuid) != actor.accountId || evidence.memberships.keys != selection.getValue("audience").jsonObject.getValue("circleIds").jsonArray.map(::uuid).toSet() ||
            evidence.verifiedAttachment?.let(::canonical) != selection["attachment"]?.let(::canonical)) fail(PostPublicationFailureCode.FORBIDDEN)
        evidence.recipeSnapshot?.let { snapshot ->
            val attachment=checkNotNull(evidence.verifiedAttachment)
            val personal=attachment["personalRecipe"]
            val schema=if(personal==null)"RecipeVersion" else "RecipeDraft"
            if(validator.validateSchema(schema,snapshot.toString().encodeToByteArray(throwOnInvalidSequence=true))!=BodyValidationResult.Valid)
                fail(PostPublicationFailureCode.FORBIDDEN)
            if(personal!=null) {
                if(canonical(snapshot)!=canonical(personal)||attachment["reviewStatus"]!=JsonPrimitive("personal")||attachment["rightsBasis"]!=JsonPrimitive("creatorOriginal"))fail(PostPublicationFailureCode.FORBIDDEN)
            } else {
                if(snapshot["reviewStatus"]!=JsonPrimitive("published")||attachment["reviewStatus"]!=JsonPrimitive("reviewed")||attachment["rightsBasis"]!=JsonPrimitive("catalogRedistributable"))fail(PostPublicationFailureCode.FORBIDDEN)
                attachment["recipeVersionId"]?.let { if(uuid(it)!=uuid(snapshot.getValue("id")))fail(PostPublicationFailureCode.FORBIDDEN) }
                // A planId-to-version resolution is a required same-connection authority check;
                // it cannot be inferred from the presence of a canonical RecipeVersion alone.
            }
        }
    }
    private fun post(id: UUID, at: Instant, selection: JsonObject, evidence: PostPublicationEvidence)=buildJsonObject {
        selection.filterKeys { it !in setOf("allowRecipeSaves","saveDisclosureVersion","audience") }.forEach { (k,v)->put(k,v) }
        put("id",id.toString());put("version",1);put("createdAt",at.toString());put("updatedAt",at.toString());put("author",evidence.author)
        put("status","published");put("publishedAt",at.toString());put("expiresAt",at.plusSeconds(86400).toString());put("aclVersion",1)
        put("audience",JsonObject(selection.getValue("audience").jsonObject+("bindings" to JsonArray(evidence.memberships.toSortedMap().map { (circle,generation)->buildJsonObject { put("circleId",circle.toString());put("authorMembershipGeneration",generation) } }))))
        put("capabilities",JsonArray(evidence.capabilities.sorted().map(::JsonPrimitive)))
        // No reactions exist in this transaction; an empty count set represents actual zero rows.
        put("reactionCounts",JsonArray(emptyList()))
        put("savePolicy",buildJsonObject { put("allowFutureSaves",selection.getValue("allowRecipeSaves"));put("policyVersion",1);put("disclosureVersion",selection.getValue("saveDisclosureVersion")) })
    }
    private fun response(post: JsonObject): StoredReply {
        val bytes=post.toString().encodeToByteArray(throwOnInvalidSequence=true)
        if(bytes.size>policy.maxResponseBytes)fail(PostPublicationFailureCode.RESPONSE_TOO_LARGE)
        if(validator.validateResponse("publishPost",201,bytes,"application/json")!=BodyValidationResult.Valid)fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(201,post,"\"1\"")
    }
    private fun head(c: Connection, actor: VerifiedSocialAccount, create: Boolean) {
        if(create)c.prepareStatement("INSERT INTO platform.post_draft_heads VALUES(?,?,1) ON CONFLICT DO NOTHING").use { it.owner(actor);it.executeUpdate() }
        query(c,"SELECT revision FROM platform.post_draft_heads WHERE environment=? AND owner_user_id=? FOR UPDATE",{owner(actor)}) { if(!it.next())fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE) }
    }
    private fun root(c: Connection, actor: VerifiedSocialAccount, client: UUID, create: Boolean): Long {
        val generation=authority.lockDraftLifecycle(c,actor,client,forPublish=create);current()
        if(generation<=0)fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        val pinned=query(c,"SELECT generation FROM platform.media_draft_lifecycles WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE",{owner(actor);setObject(3,client)}) { if(it.next())it.getLong(1) else null }
        if(pinned==null&&create)exec(c,"INSERT INTO platform.media_draft_lifecycles VALUES(?,?,?,?,clock_timestamp())") { owner(actor);setObject(3,client);setLong(4,generation) }
        else if(pinned!=generation)fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
        return generation
    }
    private fun draft(c: Connection, actor: VerifiedSocialAccount, client: UUID): Draft? = query(c,
        "SELECT * FROM platform.post_drafts WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE",{owner(actor);setObject(3,client)}) { r ->
        if(!r.next())null else Draft(r.getObject("id",UUID::class.java),r.getLong("draft_generation"),r.getLong("version"),r.getString("status"),Json.parseToJsonElement(r.getString("content")).jsonObject,r.getObject("expires_at",OffsetDateTime::class.java).toInstant())
    }
    private fun live(c: Connection, draft: Draft) {
        if(draft.state=="expired"||draft.expires<=now(c))fail(PostPublicationFailureCode.DRAFT_EXPIRED)
        if(draft.state!="draft")fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
    }
    private fun fresh(c: Connection, deadline: Instant) { if(deadline<=now(c))fail(PostPublicationFailureCode.FORBIDDEN) }
    private fun version(value: JsonElement): Long = try { BigDecimal(value.jsonPrimitive.content).longValueExact().takeIf { it>0 }?:fail(PostPublicationFailureCode.INPUT_INVALID) } catch(_:ArithmeticException) { fail(PostPublicationFailureCode.INPUT_INVALID) }
    private fun uuid(value: JsonElement)=UUID.fromString(value.jsonPrimitive.content)
    private fun canonical(value: JsonElement): String=when(value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",","{","}") { (k,v)->"${JsonPrimitive(k)}:${canonical(v)}" }
        is JsonArray -> value.joinToString(",","[","]",transform=::canonical)
        is JsonPrimitive -> if(value.isString||value==JsonNull||value.booleanOrNull!=null)value.toString() else BigDecimal(value.content).stripTrailingZeros().toString()
    }
    private fun hash(value: JsonElement)=MessageDigest.getInstance("SHA-256").digest(canonical(value).encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun mediaActor(actor: VerifiedSocialAccount)=VerifiedMediaAccount(environment,actor.accountId,actor.deviceSessionId)
    private fun PreparedStatement.owner(actor: VerifiedSocialAccount,start: Int=1) { setString(start,environment);setObject(start+1,actor.accountId) }
    private fun now(c: Connection)=c.createStatement().use { s->s.executeQuery("SELECT clock_timestamp()").use { it.next();it.getObject(1,OffsetDateTime::class.java).toInstant() } }
    private fun time(at: Instant)=OffsetDateTime.ofInstant(at,ZoneOffset.UTC)
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.()->Unit) { current();c.prepareStatement(sql).use { it.bind();if(it.executeUpdate()!=1)fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE) } }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.()->Unit, read: (ResultSet)->T): T=c.prepareStatement(sql).use { it.bind();it.executeQuery().use(read) }
    private fun current() { if(Thread.currentThread().isInterrupted)throw InterruptedException("Publication interrupted") }
    private fun <T> safe(action: ()->T): T=try { action() } catch(e:PostPublicationFailure) { throw e } catch(e:CommitOutcomeUnknown) { throw e }
        catch(e:CancellationException) { throw e } catch(e:InterruptedException) { Thread.currentThread().interrupt();throw e }
        catch(e:MediaFailure) { fail(when(e.code) {
            MediaFailureCode.UNAUTHENTICATED->PostPublicationFailureCode.UNAUTHENTICATED
            MediaFailureCode.FORBIDDEN->PostPublicationFailureCode.FORBIDDEN
            MediaFailureCode.NOT_CONFIGURED->PostPublicationFailureCode.NOT_CONFIGURED
            MediaFailureCode.MEDIA_UNAVAILABLE,MediaFailureCode.MEDIA_CONFLICT,MediaFailureCode.MEDIA_ATTACHED,MediaFailureCode.DRAFT_UNAVAILABLE->PostPublicationFailureCode.MEDIA_UNAVAILABLE
            else->PostPublicationFailureCode.STORAGE_UNAVAILABLE }) }
        catch(_:Exception) { fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: PostPublicationFailureCode): Nothing=throw PostPublicationFailure(code)
    private data class Draft(val id:UUID,val generation:Long,val version:Long,val state:String,val content:JsonObject,val expires:Instant)
    private companion object { val validator by lazy { ContractBodyValidator.bundled() } }
}
