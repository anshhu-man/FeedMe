package com.feedme.server.social.drafts

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.media.*
import com.feedme.server.social.VerifiedSocialAccount
import java.nio.charset.CharacterCodingException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Five canonical owner-only draft operations, not post publication or a media/content grant.
 * Original command receipts, draft versions, owner-page head and internal change events commit
 * atomically. No GET initializes a draft, renews its lifetime or signs an upload. Terminal identity
 * rows are retained: the same clientDraftId is never silently recycled. Expiry is observed using
 * fresh database time, not an unimplemented automatic-expiry worker. Owner DELETE can still clean
 * up an expired unpublished draft, with the original precondition and exact cleanup manifest.
 */
class PostDraftStore(val environment: String, private val transactions: PgTransactions,
    private val authority: PostDraftAuthority, private val media: MediaStore,
    val policy: PostDraftServicePolicy, private val cursors: PostDraftCursors) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && media.environment == environment) }

    fun createPostDraft(actor: VerifiedSocialAccount, key: UUID, body: JsonObject): CommandResult {
        val input = request("createPostDraft", body)
        val client = uuid(input.getValue("clientDraftId"))
        return command(actor, "createPostDraft", key, body = input, replay = { c, cached ->
            val row = locked(c, actor, replyId(cached), false)
            live(c, row); authority.authorizeContent(c, actor, row.content, false); current(); live(c, row)
            exact(cached, reply("createPostDraft", 201, row)); verifyClient(row, client)
        }) { c ->
            authority.requireMutationEnabled(c, actor); current()
            val generation = root(c, actor, client, true, true)
            query(c, "SELECT status,expires_at<=clock_timestamp() FROM platform.post_drafts WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE", {
                owner(actor); setObject(3, client)
            }) { if (it.next()) fail(if (it.getBoolean(2)) PostDraftFailureCode.DRAFT_EXPIRED else PostDraftFailureCode.DRAFT_CONFLICT) }
            val content = content(input, null)
            authorizeNew(c, actor, client, generation, content)
            val at = now(c); val id = UUID.randomUUID()
            exec(c, "INSERT INTO platform.post_drafts(environment,owner_user_id,id,client_draft_id,draft_generation,version,status,content,created_at,updated_at,expires_at) VALUES(?,?,?,?,?,1,'draft',?::jsonb,?,?,?)") {
                owner(actor); setObject(3,id); setObject(4,client); setLong(5,generation); setString(6,content.toString())
                setObject(7,time(at)); setObject(8,time(at)); setObject(9,time(at.plusSeconds(policy.draftLifetimeSeconds.toLong())))
            }
            val row = row(c, actor, id); val response = reply("createPostDraft", 201, row)
            changed(c, actor, row, key); response
        }
    }

    fun getPostDraft(actor: VerifiedSocialAccount, draftId: UUID): StoredReply = read(actor) { c ->
        val row = locked(c, actor, draftId, false)
        readable(c, row); authority.authorizeContent(c, actor, row.content, false); current(); readable(c, row)
        reply("getPostDraft", 200, row)
    }

    fun listPostDrafts(actor: VerifiedSocialAccount, cursor: String? = null, limit: Int = 50): StoredReply {
        if (limit !in 1..50) fail(PostDraftFailureCode.INPUT_INVALID)
        return read(actor) { c ->
            val head = head(c, actor, false); val at = now(c)
            val after = cursors.decode(actor, head, limit, cursor, at)
            val ids = query(c, "SELECT id FROM platform.post_drafts WHERE environment=? AND owner_user_id=? AND status='draft' AND expires_at>? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?", {
                owner(actor); setObject(3,time(at)); setObject(4,after); setObject(5,after); setInt(6,limit+1)
            }) { rows -> buildList { while (rows.next()) add(rows.getObject(1,UUID::class.java)) } }
            val selected = ids.take(limit).map { id ->
                val row = locked(c,actor,id,false); live(c,row)
                authority.authorizeContent(c,actor,row.content,false); current(); live(c,row); row
            }
            // Authority may wait. A page cannot return already-expired rows or a cursor which has
            // outlived its selected snapshot. No mutation is performed to hide an expired record.
            selected.forEach { live(c,it) }
            val end = now(c)
            val deadline = minOf(end.plusSeconds(policy.cursorLifetimeSeconds.toLong()), selected.minOfOrNull { it.expires } ?: Instant.MAX)
            val next = if (ids.size > limit) cursors.encode(actor,head,limit,selected.last().id,deadline) else null
            val body = buildJsonObject {
                put("items",JsonArray(selected.map(::json))); put("nextCursor",next?.let(::JsonPrimitive) ?: JsonNull); put("serverTime",end.toString())
            }
            response("listPostDrafts",200,body,"\"$head\"")
        }
    }

    fun updatePostDraft(actor: VerifiedSocialAccount, key: UUID, draftId: UUID, ifMatch: String, body: JsonObject): CommandResult {
        val expected = version(ifMatch); val input = request("updatePostDraft",body)
        return command(actor,"updatePostDraft",key,draftId,input,ifMatch,replay = { c,cached ->
            val row = locked(c,actor,draftId,false); live(c,row)
            if (row.version != increment(expected)) fail(PostDraftFailureCode.VERSION_CONFLICT)
            authority.authorizeContent(c,actor,row.content,false); current(); live(c,row)
            exact(cached,reply("updatePostDraft",200,row))
        }) { c ->
            val row = locked(c,actor,draftId,true); live(c,row)
            if (row.version != expected) fail(PostDraftFailureCode.VERSION_CONFLICT)
            input["clientDraftId"]?.let { verifyClient(row,uuid(it)) }
            authority.requireMutationEnabled(c,actor); current()
            val content = content(input,row.content)
            authorizeNew(c,actor,row.client,row.generation,content); live(c,row)
            val at = now(c)
            exec(c,"UPDATE platform.post_drafts SET version=?,content=?::jsonb,updated_at=?,expires_at=? WHERE environment=? AND owner_user_id=? AND id=? AND version=? AND status='draft'") {
                setLong(1,increment(expected));setString(2,content.toString());setObject(3,time(at));setObject(4,time(at.plusSeconds(policy.draftLifetimeSeconds.toLong())))
                owner(actor,5);setObject(7,draftId);setLong(8,expected)
            }
            val updated=row(c,actor,draftId); val response=reply("updatePostDraft",200,updated)
            changed(c,actor,updated,key);response
        }
    }

    fun deletePostDraft(actor: VerifiedSocialAccount, key: UUID, draftId: UUID, ifMatch: String): CommandResult {
        val expected=version(ifMatch)
        return command(actor,"deletePostDraft",key,draftId,ifMatch=ifMatch,replay={c,cached->
            val row=locked(c,actor,draftId,false)
            if(row.state!="discarded"||row.deletionKey!=key||row.version!=increment(expected)) fail(PostDraftFailureCode.VERSION_CONFLICT)
            var observed=0L
            media.verifyOwnedDraftDiscard(c,mediaActor(actor),row.client,row.generation){id,v->
                val original=query(c,"SELECT media_version,cleanup_evidence FROM platform.post_draft_discard_media WHERE environment=? AND owner_user_id=? AND draft_id=? AND media_id=?",{
                    owner(actor);setObject(3,draftId);setObject(4,id)
                }) { if(!it.next()||it.getLong(1)!=v) fail(PostDraftFailureCode.STORAGE_UNAVAILABLE);Json.parseToJsonElement(it.getString(2)).jsonObject }
                PostDraftCleanupEvidence.verify(original,PostDraftCleanupEvidence.capture(c,actor,id,v))
                observed++
            }
            val captured=query(c,"SELECT count(*) FROM platform.post_draft_discard_media WHERE environment=? AND owner_user_id=? AND draft_id=?",{owner(actor);setObject(3,draftId)}){it.next();it.getLong(1)}
            if(captured!=observed)fail(PostDraftFailureCode.STORAGE_UNAVAILABLE)
            exact(cached,StoredReply(204))
        }) {c->
            val row=locked(c,actor,draftId,false)
            if(row.state !in setOf("draft","expired"))fail(PostDraftFailureCode.DRAFT_CONFLICT)
            if(row.version!=expected)fail(PostDraftFailureCode.VERSION_CONFLICT)
            // No content-read, terms, new upload, new-copy or current publication permission.
            media.discardOwnedDraft(c,mediaActor(actor),row.client,row.generation,key){id,v->
                val cleanup=PostDraftCleanupEvidence.capture(c,actor,id,v)
                exec(c,"INSERT INTO platform.post_draft_discard_media VALUES(?,?,?,?,?,?::jsonb)"){
                    owner(actor);setObject(3,draftId);setObject(4,id);setLong(5,v);setString(6,cleanup.toString())
                }
            }
            exec(c,"UPDATE platform.post_drafts SET status='discarded',content='{}'::jsonb,version=?,deletion_key=?,updated_at=? WHERE environment=? AND owner_user_id=? AND id=? AND version=? AND status IN ('draft','expired')"){
                setLong(1,increment(expected));setObject(2,key);setObject(3,time(now(c)));owner(actor,4);setObject(6,draftId);setLong(7,expected)
            }
            changed(c,actor,row(c,actor,draftId),key);StoredReply(204)
        }
    }

    private fun authorizeNew(c:Connection,actor:VerifiedSocialAccount,client:UUID,generation:Long,content:JsonObject) {
        authority.authorizeContent(c,actor,content,true);current()
        media.validateDraftReferences(c,mediaActor(actor),client,generation,content.getValue("mediaIds").jsonArray.map(::uuid));current()
    }
    private fun content(input:JsonObject, old:JsonObject?):JsonObject {
        if(input["attachment"]!=null&&input["removeAttachment"]==JsonPrimitive(true))fail(PostDraftFailureCode.INPUT_INVALID)
        val fields=(old?:buildJsonObject{
            put("caption","");put("mediaIds",JsonArray(emptyList()));put("keepOnPlate",false);put("allowRecipeSaves",false)
            put("audience",buildJsonObject{put("kind","self");put("circleIds",JsonArray(emptyList()))})
        }).toMutableMap()
        input.filterKeys{it!="clientDraftId"&&it!="removeAttachment"}.forEach{(k,v)->fields[k]=v}
        if(input["removeAttachment"]==JsonPrimitive(true))fields.remove("attachment")
        val ids=fields.getValue("mediaIds").jsonArray.map(::uuid)
        if(ids.distinct().size!=ids.size)fail(PostDraftFailureCode.INPUT_INVALID)
        fields["mediaIds"]=JsonArray(ids.map{JsonPrimitive(it.toString())})
        val audience=fields.getValue("audience").jsonObject
        val circles=audience.getValue("circleIds").jsonArray.map(::uuid)
        if(circles.distinct().size!=circles.size || (audience.getValue("kind").jsonPrimitive.content=="self") != circles.isEmpty())
            fail(PostDraftFailureCode.INPUT_INVALID)
        // Canonical bindings are stamped only at publish, never trusted from a draft client.
        fields["audience"]=JsonObject(audience.filterKeys{it!="bindings"}+("circleIds" to JsonArray(circles.map{JsonPrimitive(it.toString())})))
        return JsonObject(fields)
    }

    private fun locked(c:Connection,actor:VerifiedSocialAccount,id:UUID,edit:Boolean):Row {
        val client=query(c,"SELECT client_draft_id FROM platform.post_drafts WHERE environment=? AND owner_user_id=? AND id=?",{owner(actor);setObject(3,id)}){
            if(!it.next())fail(PostDraftFailureCode.DRAFT_UNAVAILABLE);it.getObject(1,UUID::class.java)
        }
        val generation=root(c,actor,client,edit,false);val row=row(c,actor,id)
        if(row.client!=client||row.generation!=generation)fail(PostDraftFailureCode.DRAFT_CONFLICT)
        return row
    }
    private fun root(c:Connection,actor:VerifiedSocialAccount,client:UUID,edit:Boolean,create:Boolean):Long {
        val generation=authority.lockDraftLifecycle(c,actor,client,edit);current()
        if(generation<=0)fail(PostDraftFailureCode.STORAGE_UNAVAILABLE)
        if((create||edit)&&com.feedme.server.social.posts.PostPublicationLifecycle.isPublished(c,environment,actor.accountId,client))fail(PostDraftFailureCode.DRAFT_CONFLICT)
        val pinned=query(c,"SELECT generation FROM platform.media_draft_lifecycles WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR UPDATE",{owner(actor);setObject(3,client)}){if(it.next())it.getLong(1) else null}
        if(pinned==null&&create)exec(c,"INSERT INTO platform.media_draft_lifecycles VALUES(?,?,?,?,?)"){
            owner(actor);setObject(3,client);setLong(4,generation);setObject(5,time(now(c)))
        } else if(pinned!=generation)fail(PostDraftFailureCode.DRAFT_CONFLICT)
        return generation
    }
    private fun row(c:Connection,actor:VerifiedSocialAccount,id:UUID)=query(c,"SELECT * FROM platform.post_drafts WHERE environment=? AND owner_user_id=? AND id=? FOR UPDATE",{owner(actor);setObject(3,id)}){
        if(!it.next())fail(PostDraftFailureCode.DRAFT_UNAVAILABLE)
        Row(id,it.getObject("client_draft_id",UUID::class.java),it.getLong("draft_generation"),it.getLong("version"),it.getString("status"),
            Json.parseToJsonElement(it.getString("content")).jsonObject,instant(it,"created_at"),instant(it,"updated_at"),instant(it,"expires_at"),
            it.getObject("published_post_id",UUID::class.java),it.getObject("deletion_key",UUID::class.java))
    }
    private fun json(row:Row)=buildJsonObject{
        row.content.forEach{(k,v)->put(k,v)}
        put("id",row.id.toString());put("version",row.version);put("clientDraftId",row.client.toString());put("status",row.state)
        put("createdAt",row.created.toString());put("updatedAt",row.updated.toString());put("expiresAt",row.expires.toString())
        row.published?.let{put("publishedPostId",it.toString())}
    }
    private fun live(c:Connection,row:Row) {
        if(row.expires<=now(c)||row.state=="expired")fail(PostDraftFailureCode.DRAFT_EXPIRED)
        if(row.state!="draft")fail(PostDraftFailureCode.DRAFT_CONFLICT)
    }
    private fun readable(c:Connection,row:Row) {
        if(row.state=="discarded")fail(PostDraftFailureCode.DRAFT_UNAVAILABLE)
        if(row.state!="published"&&(row.state=="expired"||row.expires<=now(c)))fail(PostDraftFailureCode.DRAFT_EXPIRED)
    }
    private fun changed(c:Connection,actor:VerifiedSocialAccount,row:Row,key:UUID) {
        exec(c,"UPDATE platform.post_draft_heads SET revision=revision+1 WHERE environment=? AND owner_user_id=? AND revision<9223372036854775807"){owner(actor)}
        outbox.append(c,EventDraft(UUID.randomUUID(),"social.post_draft.changed.v1",1,"post-draft",row.id,row.version,"social",UUID.randomUUID().toString(),key,buildJsonObject{
            put("environment",environment);put("ownerId",actor.accountId.toString());put("draftId",row.id.toString());put("version",row.version);put("state",row.state)
        }))
    }
    private fun head(c:Connection,actor:VerifiedSocialAccount,create:Boolean):Long {
        if(create)c.prepareStatement("INSERT INTO platform.post_draft_heads VALUES(?,?,1) ON CONFLICT DO NOTHING").use{it.owner(actor);it.executeUpdate()}
        return query(c,"SELECT revision FROM platform.post_draft_heads WHERE environment=? AND owner_user_id=? FOR UPDATE",{owner(actor)}){if(it.next())it.getLong(1) else 0L}
    }
    private fun command(actor:VerifiedSocialAccount,op:String,key:UUID,id:UUID?=null,body:JsonObject?=null,ifMatch:String?=null,
        replay:(Connection,StoredReply)->Unit,mutate:(Connection)->StoredReply):CommandResult=safe{
        checkActor(actor);current()
        commands.execute(CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,actor.accountId),op,key,
            if(id==null)emptyMap() else mapOf("draftId" to id.toString()),body=body,ifMatch=ifMatch),
            {authority.lockPrincipal(it,actor);current()},
            {head(it,actor,true)}, {c,r->head(c,actor,false);replay(c,r);current()}, {mutate(it).also{current()}}).also{current()}
    }
    private fun <T> read(actor:VerifiedSocialAccount,action:(Connection)->T):T=safe{
        checkActor(actor);current();transactions.run{authority.lockPrincipal(it,actor);current();action(it).also{current()}}.also{current()}
    }
    private fun request(op:String,input:JsonObject):JsonObject {
        val bytes=bytes(input,PostDraftFailureCode.INPUT_INVALID)
        if(bytes.size>65536||validator.validateRequest(op,bytes,"application/json")!=BodyValidationResult.Valid)fail(PostDraftFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun reply(op:String,status:Int,row:Row)=response(op,status,json(row),"\"${row.version}\"")
    private fun response(op:String,status:Int,body:JsonObject,etag:String):StoredReply {
        val bytes=bytes(body)
        // Preserve room for a one-item canonical page so a successful draft cannot become
        // permanently unlistable under the very same service response profile.
        val pageReserve=if(op=="createPostDraft"||op=="updatePostDraft")256 else 0
        if(bytes.size.toLong()+pageReserve>policy.maxResponseBytes)fail(PostDraftFailureCode.RESPONSE_TOO_LARGE)
        if(validator.validateResponse(op,status,bytes,"application/json")!=BodyValidationResult.Valid)fail(PostDraftFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(status,body,etag)
    }
    private fun bytes(body:JsonObject,code:PostDraftFailureCode=PostDraftFailureCode.STORAGE_UNAVAILABLE)=try{body.toString().encodeToByteArray(throwOnInvalidSequence=true)}
        catch(_:IllegalArgumentException){fail(code)}catch(_:CharacterCodingException){fail(code)}
    private fun checkActor(actor:VerifiedSocialAccount){if(actor.environment!=environment)fail(PostDraftFailureCode.UNAUTHENTICATED)}
    private fun mediaActor(actor:VerifiedSocialAccount)=VerifiedMediaAccount(environment,actor.accountId,actor.deviceSessionId)
    private fun verifyClient(row:Row,client:UUID){if(row.client!=client)fail(PostDraftFailureCode.DRAFT_CONFLICT)}
    private fun exact(a:StoredReply,b:StoredReply){if(a.status!=b.status||a.etag!=b.etag||a.body!=b.body)fail(PostDraftFailureCode.DRAFT_CONFLICT)}
    private fun replyId(reply:StoredReply)=uuid(reply.body!!.jsonObject.getValue("id"))
    private fun uuid(value:JsonElement)=UUID.fromString(value.jsonPrimitive.content)
    private fun version(value:String):Long {
        if(!value.matches(Regex("\"[0-9]{1,64}\"")))fail(PostDraftFailureCode.INPUT_INVALID)
        return value.drop(1).dropLast(1).trimStart('0').toLongOrNull()?.takeIf{it>0}?:fail(PostDraftFailureCode.INPUT_INVALID)
    }
    private fun increment(value:Long)=if(value==Long.MAX_VALUE)fail(PostDraftFailureCode.STORAGE_UNAVAILABLE)else value+1
    private fun current(){if(Thread.currentThread().isInterrupted)throw InterruptedException("Post draft operation interrupted")}
    private fun <T> safe(action:()->T):T=try{action()}catch(e:PostDraftFailure){throw e}catch(e:CommitOutcomeUnknown){throw e}
        catch(e:CancellationException){throw e}catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
        catch(e:MediaFailure){fail(when(e.code){MediaFailureCode.UNAUTHENTICATED->PostDraftFailureCode.UNAUTHENTICATED
            MediaFailureCode.MEDIA_UNAVAILABLE,MediaFailureCode.MEDIA_CONFLICT,MediaFailureCode.MEDIA_ATTACHED,MediaFailureCode.DRAFT_UNAVAILABLE->PostDraftFailureCode.MEDIA_UNAVAILABLE
            MediaFailureCode.FORBIDDEN->PostDraftFailureCode.FORBIDDEN
            MediaFailureCode.NOT_CONFIGURED->PostDraftFailureCode.NOT_CONFIGURED
            else->PostDraftFailureCode.STORAGE_UNAVAILABLE})}
        catch(_:Exception){fail(PostDraftFailureCode.STORAGE_UNAVAILABLE)}
    private fun PreparedStatement.owner(actor:VerifiedSocialAccount,start:Int=1){setString(start,environment);setObject(start+1,actor.accountId)}
    private fun now(c:Connection)=c.createStatement().use{s->s.executeQuery("SELECT clock_timestamp()").use{it.next();it.getObject(1,OffsetDateTime::class.java).toInstant()}}
    private fun time(at:Instant)=OffsetDateTime.ofInstant(at,ZoneOffset.UTC)
    private fun instant(r:ResultSet,name:String)=r.getObject(name,OffsetDateTime::class.java).toInstant()
    private fun exec(c:Connection,sql:String,bind:PreparedStatement.()->Unit){current();c.prepareStatement(sql).use{it.bind();if(it.executeUpdate()!=1)fail(PostDraftFailureCode.STORAGE_UNAVAILABLE)}}
    private fun <T> query(c:Connection,sql:String,bind:PreparedStatement.()->Unit,read:(ResultSet)->T):T=c.prepareStatement(sql).use{it.bind();it.executeQuery().use(read)}
    private fun fail(code:PostDraftFailureCode):Nothing=throw PostDraftFailure(code)
    private data class Row(val id:UUID,val client:UUID,val generation:Long,val version:Long,val state:String,val content:JsonObject,
        val created:Instant,val updated:Instant,val expires:Instant,val published:UUID?,val deletionKey:UUID?)
    private companion object{val validator by lazy{ContractBodyValidator.bundled()}}
}
