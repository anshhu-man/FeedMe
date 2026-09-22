package com.feedme.server.memory

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.*

/** Account-only manual organization using V006 rows and the existing actual Saved-copy
 * authority. Does not create a recipe copy, grant smart tools, or infer paid entitlement.
 * Mutation result, ordinary outbox event and original idempotency receipt share one commit. */
internal class CollectionMutationStore(private val environment: String, private val transactions: PgTransactions,
    private val saved: SavedRecipeStore, private val cursors: SavedRecipeCursors, private val policy: SavedRecipeServicePolicy) {
    fun execute(c: Connection, actor: VerifiedSavedRecipePrincipal, operation: String, key: UUID,
        id: UUID? = null, savedId: UUID? = null, ifMatch: String? = null, body: JsonObject? = null): SavedRecipeStore.Pending<CommandResult> {
        if (actor.environment != environment || actor.kind != CommandActor.ACCOUNT || actor.deviceSessionId == null)
            fail(SavedRecipeFailureCode.UNAUTHENTICATED)
        check(operation in operations && !c.autoCommit)
        if ((operation in setOf("createCollection","updateCollection","addCollectionItem")) != (body != null)) fail(SavedRecipeFailureCode.INPUT_INVALID)
        val input = body?.let { request(operation,it) }
        val targetSaved = if (operation == "addCollectionItem") UUID.fromString(input!!.getValue("savedRecipeId").jsonPrimitive.content) else savedId
        val expectedVersion = ifMatch?.let { text ->
            if (!text.matches(Regex("\"[0-9]{1,64}\""))) fail(SavedRecipeFailureCode.INPUT_INVALID)
            text.removeSurrounding("\"").trimStart('0').toLongOrNull()?.takeIf { it > 0 } ?: fail(SavedRecipeFailureCode.INPUT_INVALID)
        }
        if ((operation in setOf("updateCollection","deleteCollection","removeCollectionItem")) != (expectedVersion != null)) fail(SavedRecipeFailureCode.INPUT_INVALID)
        if ((operation != "createCollection") != (id != null) || operation == "removeCollectionItem" && targetSaved == null) fail(SavedRecipeFailureCode.INPUT_INVALID)
        val paths = buildMap { id?.let { put("collectionId",it.toString()) }; if (operation=="removeCollectionItem") put("savedRecipeId",targetSaved.toString()) }
        val identity = CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,actor.principalId),operation,key,paths,body=input,ifMatch=ifMatch)
        val thread=Thread.currentThread(); val transaction=transactionId(c); val first=now(c)
        val reads=mutableListOf<SavedRecipeStore.Pending<StoredReply>>()
        val observations=mutableListOf<Pair<JsonElement,()->JsonElement>>()
        val events=mutableListOf<UUID>()
        var receiptExpiry: Instant?=null
        var resultCursor: String?=null; var resultCollection:UUID?=null; var resultVersion:Long?=null
        fun observe(read:()->JsonElement) { observations += read() to read }
        fun readSaved(savedId:UUID,retain:Boolean=true) { val read=saved.getSavedRecipe(c,actor,savedId);if(retain) reads+=read }
        fun presentation(collectionId:UUID): StoredReply {
            val pending=saved.getCollection(c,actor,collectionId,null,20); reads+=pending
            return pending.result
        }
        fun resultReply(collectionId:UUID,status:Int):StoredReply {
            val actual=presentation(collectionId); return checked(operation,StoredReply(status,actual.body,actual.etag))
        }
        fun event(collectionId:UUID,version:Long,action:String) {
            val event=EventDraft(UUID.randomUUID(),"memory.collection.changed.v1",1,"collection",collectionId,version,"memory",
                UUID.randomUUID().toString(),key,buildJsonObject { put("principalId",actor.principalId.toString());put("collectionId",collectionId.toString());put("action",action) },
                owner=EventOwner.principal(environment,CommandActor.ACCOUNT,actor.principalId))
            OutboxStore(transactions).append(c,event);events+=event.eventId
        }
        val result=DurableCommands(transactions).executeInTransaction(c,identity,validatePrincipal={ current(c,thread) },authorizeNew={},
            authorizeReplay={ _,reply ->
                checked(operation,reply)
                when(operation) {
                    "deleteCollection" -> if (collection(c,actor,id!!,false)!=null || items(c,actor,id).isNotEmpty()) fail(SavedRecipeFailureCode.VERSION_CONFLICT)
                    "removeCollectionItem" -> {
                        val col=collection(c,actor,id!!)!!
                        if (col.getValue("version").jsonPrimitive.long != increment(expectedVersion!!) || items(c,actor,id).any { it.saved==targetSaved })
                            fail(SavedRecipeFailureCode.VERSION_CONFLICT)
                    }
                    else -> {
                        val collectionId=id ?: UUID.fromString(reply.body!!.jsonObject.getValue("id").jsonPrimitive.content)
                        if (operation=="addCollectionItem") { readSaved(targetSaved!!)
                            if (items(c,actor,collectionId).none { it.saved==targetSaved }) fail(SavedRecipeFailureCode.COPY_CONFLICT) }
                        val actual=presentation(collectionId)
                        if (actual.etag!=reply.etag || (actual.body!!.jsonObject-"nextItemCursor") != (reply.body!!.jsonObject-"nextItemCursor"))
                            fail(SavedRecipeFailureCode.VERSION_CONFLICT)
                        val token=reply.body.jsonObject["nextItemCursor"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content
                        val freshToken=actual.body.jsonObject["nextItemCursor"]?.takeUnless { it==JsonNull }
                        if ((token==null)!=(freshToken==null)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                        token?.let { cursors.decode(actor,"items",collectionId.toString(),reply.body.jsonObject.getValue("version").jsonPrimitive.long,it,now(c)) }
                    }
                }
            },mutate={ _ ->
                val at=now(c)
                if (operation=="createCollection") {
                    val count=c.prepareStatement("SELECT count(*) FROM memory.collections WHERE environment=? AND actor_kind='account' AND principal_id=?").use {
                        it.owner(actor);it.executeQuery().use { r -> check(r.next());r.getLong(1) } }
                    if(count>=50) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
                    c.prepareStatement("INSERT INTO memory.library_heads(environment,actor_kind,principal_id,revision) VALUES(?,'account',?,1) ON CONFLICT DO NOTHING").use { it.owner(actor);it.executeUpdate() }
                    val created=UUID.randomUUID()
                    write(c,"INSERT INTO memory.collections(environment,actor_kind,principal_id,id,version,is_default,name,description,created_at,updated_at) VALUES(?,'account',?, ?,1,false,?,?,?,?)") {
                        owner(actor);setObject(3,created);setString(4,input!!.getValue("name").jsonPrimitive.content);setString(5,input["description"]?.jsonPrimitive?.content)
                        setObject(6,time(at));setObject(7,time(at)) }
                    advance(c,actor);event(created,1,"created");resultReply(created,201)
                } else {
                    // Copy/source locks precede the collection. This authorizes only an
                    // already-owned Saved row; the input UUID is never a new-copy grant.
                    if(operation=="addCollectionItem") readSaved(targetSaved!!,retain=false)
                    val col=collection(c,actor,id!!)!!; val version=col.getValue("version").jsonPrimitive.long
                    if(expectedVersion!=null && version!=expectedVersion) fail(SavedRecipeFailureCode.VERSION_CONFLICT)
                    val members=items(c,actor,id)
                    when(operation) {
                        "updateCollection" -> {
                            write(c,"UPDATE memory.collections SET name=?,description=?,version=?,updated_at=? WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? AND version=?") {
                                setString(1,input!!.getValue("name").jsonPrimitive.content);setString(2,input["description"]?.jsonPrimitive?.content ?: col["description"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content)
                                setLong(3,increment(version));setObject(4,time(at));owner(actor,5);setObject(7,id);setLong(8,version) }
                            advance(c,actor);event(id,increment(version),"updated");resultReply(id,200)
                        }
                        "deleteCollection" -> {
                            if(col["is_default"]==JsonPrimitive(true)) fail(SavedRecipeFailureCode.FORBIDDEN)
                            val linked=c.prepareStatement("SELECT 1 FROM memory.save_commands WHERE environment=? AND actor_kind='account' AND principal_id=? AND collection_id=? LIMIT 1 FOR SHARE").use {
                                it.owner(actor);it.setObject(3,id);it.executeQuery().use { r -> r.next() } }
                            if(linked) fail(SavedRecipeFailureCode.COPY_CONFLICT)
                            c.prepareStatement("DELETE FROM memory.collection_items WHERE environment=? AND actor_kind='account' AND principal_id=? AND collection_id=?").use {
                                it.owner(actor);it.setObject(3,id);if(it.executeUpdate()!=members.size) fail() }
                            write(c,"DELETE FROM memory.collections WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? AND version=?") { owner(actor);setObject(3,id);setLong(4,version) }
                            advance(c,actor);event(id,increment(version),"deleted");checked(operation,StoredReply(204))
                        }
                        "addCollectionItem" -> {
                            if(members.none { it.saved==targetSaved }) {
                                if(members.size>=1000) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
                                val count=c.prepareStatement("SELECT count(*) FROM memory.collection_items WHERE environment=? AND actor_kind='account' AND principal_id=? AND saved_recipe_id=?").use {
                                    it.owner(actor);it.setObject(3,targetSaved);it.executeQuery().use { r -> check(r.next());r.getLong(1) } }
                                if(count>=50) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
                                write(c,"INSERT INTO memory.collection_items(environment,actor_kind,principal_id,collection_id,saved_recipe_id,position) VALUES(?,'account',?,?,?,?)") {
                                    owner(actor);setObject(3,id);setObject(4,targetSaved);setLong(5,increment(members.maxOfOrNull { it.position } ?: 0)) }
                                bump(c,actor,id,version,at);advance(c,actor);event(id,increment(version),"itemAdded")
                            }
                            readSaved(targetSaved!!);resultReply(id,201)
                        }
                        "removeCollectionItem" -> {
                            if(members.none { it.saved==targetSaved }) fail(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE)
                            // Removing membership is not content disclosure and must remain
                            // possible when an established copy is recalled.
                            write(c,"DELETE FROM memory.collection_items WHERE environment=? AND actor_kind='account' AND principal_id=? AND collection_id=? AND saved_recipe_id=?") { owner(actor);setObject(3,id);setObject(4,targetSaved) }
                            bump(c,actor,id,version,at);advance(c,actor);event(id,increment(version),"itemRemoved");checked(operation,StoredReply(204))
                        }
                        else -> fail(SavedRecipeFailureCode.FORBIDDEN)
                    }
                }
            })
        val reply=when(result) { is CommandResult.Applied->result.reply;is CommandResult.Replayed->result.reply;else->null }
        if(reply!=null) {
            val collectionId=id ?: UUID.fromString(reply.body!!.jsonObject.getValue("id").jsonPrimitive.content)
            observe { collection(c,actor,collectionId,false) ?: JsonNull }
            observe { JsonArray(items(c,actor,collectionId).map { it.document }) }
            observe { row(c,"memory.library_heads","environment=? AND actor_kind='account' AND principal_id=?") { owner(actor) } ?: fail() }
            fun receipt(): JsonObject = row(c,"platform.idempotency","principal_scope=? AND operation_id=? AND key=?") {
                setString(1,identity.scope.storageKey);setString(2,operation);setObject(3,key) } ?: fail()
            val original=receipt()
            if(original["request_hash"]!=JsonPrimitive(identity.requestHash) || original["state"]!=JsonPrimitive("completed") ||
                original["response_code"]!=JsonPrimitive(reply.status) || original["response_json"]!=(reply.body ?: JsonNull) ||
                original["response_etag"]!=(reply.etag?.let(::JsonPrimitive) ?: JsonNull)) fail()
            receiptExpiry=OffsetDateTime.parse(original.getValue("expires_at").jsonPrimitive.content).toInstant();observe(::receipt)
            events.forEach { event -> observe { row(c,"platform.outbox","event_id=?") { setObject(1,event) } ?: fail() } }
            reply.body?.jsonObject?.let { b -> resultCollection=collectionId;resultVersion=b.getValue("version").jsonPrimitive.long
                resultCursor=b["nextItemCursor"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.content }
        }
        fun checkAt(connection:Connection,actual:VerifiedSavedRecipePrincipal,at:Instant) {
            if(connection!==c || actual!==actor) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
            current(c,thread);if(at<first || receiptExpiry?.let { !at.isBefore(it) }==true) fail()
            resultCursor?.let { cursors.decode(actor,"items",resultCollection.toString(),resultVersion!!,it,at) }
            reads.forEach { it.checkAt(c,actor,at) }
        }
        return SavedRecipeStore.Pending(result,{ connection,actual ->
            if(connection!==c || actual!==actor) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
            current(c,thread);if(transactionId(c)!=transaction) fail();reads.forEach { it.revalidate(c,actor) }
            observations.forEach { (expected,read) -> if(expected!=read()) fail() }
            checkAt(c,actor,now(c))
        },::checkAt)
    }
    private fun request(op:String,body:JsonObject):JsonObject {
        val bytes=body.toString().encodeToByteArray(throwOnInvalidSequence=true)
        if(bytes.size>16384 || validator.validateRequest(op,bytes,"application/json")!=BodyValidationResult.Valid) fail(SavedRecipeFailureCode.INPUT_INVALID)
        if(op in setOf("createCollection","updateCollection")) {
            if(body["smartRule"]?.let { it!=JsonPrimitive("none") }==true) fail(SavedRecipeFailureCode.FORBIDDEN)
            for(name in listOf("name","description")) body[name]?.jsonPrimitive?.content?.let {
                if(it.any(Char::isISOControl) || name=="name" && it.isBlank()) fail(SavedRecipeFailureCode.INPUT_INVALID) }
        };return body
    }
    private fun checked(op:String,reply:StoredReply):StoredReply {
        val status=when(op) { "createCollection","addCollectionItem"->201;"deleteCollection","removeCollectionItem"->204;else->200 }
        val bytes=reply.body?.toString()?.encodeToByteArray(throwOnInvalidSequence=true)
        if(reply.status!=status || bytes!=null && bytes.size>policy.maxResponseBytes ||
            validator.validateResponse(op,status,bytes,if(bytes==null)null else "application/json")!=BodyValidationResult.Valid) fail()
        if(status==204) { if(reply.etag!=null || reply.body!=null) fail() }
        else if(reply.etag!="\"${reply.body!!.jsonObject.getValue("version").jsonPrimitive.long}\"") fail()
        return reply
    }
    private fun collection(c:Connection,a:VerifiedSavedRecipePrincipal,id:UUID,required:Boolean=true):JsonObject? =
        row(c,"memory.collections","environment=? AND actor_kind='account' AND principal_id=? AND id=?",true) { owner(a);setObject(3,id) }
            ?: if(required) fail(SavedRecipeFailureCode.COLLECTION_UNAVAILABLE) else null
    private class Item(val saved:UUID,val position:Long,val document:JsonObject)
    private fun items(c:Connection,a:VerifiedSavedRecipePrincipal,id:UUID):List<Item> = c.prepareStatement(
        "SELECT to_jsonb(i)::text FROM memory.collection_items i WHERE environment=? AND actor_kind='account' AND principal_id=? AND collection_id=? ORDER BY position,saved_recipe_id LIMIT 1001 FOR SHARE").use {
        it.owner(a);it.setObject(3,id);it.executeQuery().use { r -> buildList { while(r.next()) {
            if(size==1000) fail(SavedRecipeFailureCode.NOT_CONFIGURED);val d=Json.parseToJsonElement(r.getString(1)).jsonObject
            add(Item(UUID.fromString(d.getValue("saved_recipe_id").jsonPrimitive.content),d.getValue("position").jsonPrimitive.long,d)) } } }
    }
    private fun bump(c:Connection,a:VerifiedSavedRecipePrincipal,id:UUID,version:Long,at:Instant)=write(c,
        "UPDATE memory.collections SET version=?,updated_at=? WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? AND version=?") {
        setLong(1,increment(version));setObject(2,time(at));owner(a,3);setObject(5,id);setLong(6,version) }
    private fun advance(c:Connection,a:VerifiedSavedRecipePrincipal) {
        val head=row(c,"memory.library_heads","environment=? AND actor_kind='account' AND principal_id=?",true) { owner(a) } ?: fail()
        write(c,"UPDATE memory.library_heads SET revision=? WHERE environment=? AND actor_kind='account' AND principal_id=? AND revision=?") {
            val old=head.getValue("revision").jsonPrimitive.long;setLong(1,increment(old));owner(a,2);setLong(4,old) }
    }
    private fun row(c:Connection,table:String,where:String,update:Boolean=false,bind:PreparedStatement.()->Unit):JsonObject? = c.prepareStatement(
        "SELECT to_jsonb(r)::text FROM $table r WHERE $where FOR ${if(update)"UPDATE" else "SHARE"}").use {
        it.bind();it.executeQuery().use { r -> if(!r.next()) null else Json.parseToJsonElement(r.getString(1)).jsonObject.also { if(r.next()) fail() } } }
    private fun write(c:Connection,sql:String,bind:PreparedStatement.()->Unit)=c.prepareStatement(sql).use { it.bind();if(it.executeUpdate()!=1) fail() }
    private fun PreparedStatement.owner(a:VerifiedSavedRecipePrincipal,start:Int=1) { setString(start,environment);setObject(start+1,a.principalId) }
    private fun current(c:Connection,thread:Thread) { if(Thread.currentThread().isInterrupted) throw InterruptedException("Collection interrupted")
        if(Thread.currentThread()!==thread || c.isClosed || c.autoCommit) fail() }
    private fun now(c:Connection)=c.createStatement().use { s->s.executeQuery("SELECT clock_timestamp()").use { check(it.next());it.getObject(1,OffsetDateTime::class.java).toInstant() } }
    private fun transactionId(c:Connection):Long=c.createStatement().use { s->s.executeQuery("SELECT txid_current()").use {
        check(it.next());it.getLong(1).also { _ -> check(!it.next()) } } }
    private fun time(at:Instant)=at.atOffset(ZoneOffset.UTC)
    private fun increment(value:Long)=if(value==Long.MAX_VALUE) fail() else value+1
    private fun fail(code:SavedRecipeFailureCode=SavedRecipeFailureCode.STORAGE_UNAVAILABLE):Nothing=throw SavedRecipeFailure(code)
    private companion object { val validator by lazy { ContractBodyValidator.bundled() }
        val operations=setOf("createCollection","updateCollection","deleteCollection","addCollectionItem","removeCollectionItem") }
}
