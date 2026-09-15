package com.feedme.server.social.posts

import com.feedme.server.db.*
import com.feedme.server.cooking.CookingTestFixture
import com.feedme.server.media.*
import com.feedme.server.media.processing.*
import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.drafts.*
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*

/** TEST ONLY: real SQL/command/media-processing transactions, explicitly synthetic verified
 * identity, source redistribution, memberships, private objects, decoder and safety providers. */
class PostPublicationTestFixture(val source: DataSource, val drafts: PostDraftTestFixture=PostDraftTestFixture(source)) {
    val mediaFixture get()=drafts.mediaFixture
    val account get()=drafts.account
    val faults get()=drafts.faults
    val authority=TestAuthority()
    val processing=MediaProcessingTestFixture(source, media=mediaFixture)
    val store=newStore()
    init {
        sql("CREATE SCHEMA post_publication_test; CREATE TABLE post_publication_test.memberships(owner_id uuid NOT NULL,circle_id uuid NOT NULL,generation bigint NOT NULL,active boolean NOT NULL,PRIMARY KEY(owner_id,circle_id)); " +
            "CREATE TABLE post_publication_test.recipes(owner_id uuid NOT NULL,id uuid NOT NULL,attachment jsonb NOT NULL,recipe jsonb NOT NULL,active boolean NOT NULL,PRIMARY KEY(owner_id,id))")
    }
    fun policy(max: Int=65536)=PostPublicationPolicy(max)
    fun newStore(policy: PostPublicationPolicy=policy())=PostPublicationStore("test",PgTransactions(faults.wrap(source)),authority,drafts.mediaStore,policy)
    fun client(actor: VerifiedSocialAccount=account)=drafts.client(actor)
    fun principal()=drafts.principal()
    fun body(client: UUID=client(),caption: String="Synthetic dinner")=buildJsonObject {
        put("clientDraftId",client.toString());put("caption",caption);put("mediaIds",JsonArray(emptyList()))
        put("audience",buildJsonObject { put("kind","self");put("circleIds",JsonArray(emptyList())) })
        put("keepOnPlate",false);put("allowRecipeSaves",false);put("saveDisclosureVersion","synthetic-disclosure-1")
    }
    fun publish(body: JsonObject=body(),key: UUID=UUID.randomUUID(),actor: VerifiedSocialAccount=account,store: PostPublicationStore=this.store)=reply(store.publishPost(actor,key,body)).body!!.jsonObject
    fun readyPhoto(client: UUID): UUID {
        val event=processing.seed(clientDraftId=client);processing.store.ingest(event)
        processing.processor.process(checkNotNull(processing.store.claim()))
        check(value("SELECT state FROM platform.media_assets WHERE id='${processing.mediaId}'")=="ready")
        return processing.mediaId
    }
    fun grantCircle(id: UUID=UUID.randomUUID(),generation: Long=1,actor: VerifiedSocialAccount=account): UUID {
        drafts.grant("circle",id,actor)
        source.connection.use { c->c.prepareStatement("INSERT INTO post_publication_test.memberships VALUES(?,?,?,true)").use { it.setObject(1,actor.accountId);it.setObject(2,id);it.setLong(3,generation);it.executeUpdate() } }
        return id
    }
    fun grantRecipe(id: UUID=UUID.randomUUID(),actor: VerifiedSocialAccount=account): JsonObject {
        val attachment=buildJsonObject { put("recipeVersionId",id.toString());put("confirmedChanges",JsonArray(emptyList()));put("reviewStatus","reviewed");put("rightsBasis","catalogRedistributable") }
        // Canonical RecipeVersion shape, still no assertion of a real editorial/licence grant.
        val snapshot=JsonObject(CookingTestFixture.recipe()+("id" to JsonPrimitive(id.toString())))
        drafts.grant("attachment",id,actor)
        source.connection.use { c->c.prepareStatement("INSERT INTO post_publication_test.recipes VALUES(?,?,?::jsonb,?::jsonb,true)").use { it.setObject(1,actor.accountId);it.setObject(2,id);it.setString(3,attachment.toString());it.setString(4,snapshot.toString());it.executeUpdate() } }
        return attachment
    }
    fun saved(input: JsonObject): JsonObject {
        val draft=drafts.create(input)
        return JsonObject(input+mapOf("draftId" to draft.getValue("id"),"draftVersion" to draft.getValue("version")))
    }
    fun sql(sql: String)=drafts.sql(sql)
    fun value(sql: String)=drafts.value(sql)
    fun count(table: String)=drafts.count(table)
    inner class TestAuthority: PostPublicationAuthority {
        var enabled=true
        var terms=true
        var recall=false
        var mediaSafe=true
        var expirySeconds: Long=300
        var afterPrincipal: (()->Unit)?=null
        var afterNew: ((Connection)->Unit)?=null
        var afterReplay: ((Connection)->Unit)?=null
        var afterReadyMedia: ((Connection)->Unit)?=null
        var mediaDeadline: ((UUID,Instant)->Instant)?=null
        var transformEvidence: ((PostPublicationEvidence)->PostPublicationEvidence)?=null
        override fun lockPrincipal(connection: Connection,actor: VerifiedSocialAccount) {
            try { mediaFixture.authority.lockPrincipal(connection,mediaActor(actor)) } catch(_:MediaFailure) { throw PostPublicationFailure(PostPublicationFailureCode.UNAUTHENTICATED) }
            afterPrincipal?.invoke()
        }
        override fun requirePublishEnabled(connection: Connection,actor: VerifiedSocialAccount) {
            if(!enabled)throw PostPublicationFailure(PostPublicationFailureCode.NOT_CONFIGURED)
            if(!terms)throw PostPublicationFailure(PostPublicationFailureCode.FORBIDDEN)
        }
        override fun lockDraftLifecycle(connection: Connection,actor: VerifiedSocialAccount,clientDraftId: UUID,forPublish: Boolean)=
            try { mediaFixture.authority.lockDraftLifecycle(connection,mediaActor(actor),clientDraftId,forPublish) } catch(_:MediaFailure) { throw PostPublicationFailure(PostPublicationFailureCode.DRAFT_UNAVAILABLE) }
        override fun authorizeNew(connection: Connection,actor: VerifiedSocialAccount,selection: JsonObject): PostPublicationEvidence {
            if(recall||selection.getValue("saveDisclosureVersion")!=JsonPrimitive("synthetic-disclosure-1"))throw PostPublicationFailure(PostPublicationFailureCode.FORBIDDEN)
            content(connection,actor,selection,true)
            val memberships=memberships(connection,actor,selection.getValue("audience").jsonObject.getValue("circleIds").jsonArray)
            val pair=recipe(connection,actor,selection["attachment"]?.jsonObject)
            val evidence=PostPublicationEvidence(buildJsonObject { put("userId",actor.accountId.toString());put("displayName","Synthetic cook");put("handle","synthetic_cook") },memberships,pair?.first,pair?.second,
                setOf("view","edit","delete")+(if(pair!=null)setOf("makeMine") else emptySet())+(if(selection.getValue("allowRecipeSaves")==JsonPrimitive(true))setOf("saveRecipe") else emptySet()),now(connection).plusSeconds(expirySeconds))
            afterNew?.invoke(connection)
            return transformEvidence?.invoke(evidence)?:evidence
        }
        override fun authorizeReadyMedia(connection: Connection,actor: VerifiedSocialAccount,mediaId: UUID,version: Long,derivatives: JsonObject): Instant {
            if(!mediaSafe||recall)throw PostPublicationFailure(PostPublicationFailureCode.FORBIDDEN)
            connection.prepareStatement("SELECT 1 FROM platform.media_processing_jobs WHERE environment=? AND owner_user_id=? AND media_id=? AND state='ready' AND terminal_media_version=? FOR SHARE").use { it.setString(1,actor.environment);it.setObject(2,actor.accountId);it.setObject(3,mediaId);it.setLong(4,version);it.executeQuery().use { r->check(r.next()) } }
            val at=now(connection);val deadline=mediaDeadline?.invoke(mediaId,at)?:at.plusSeconds(300)
            afterReadyMedia?.invoke(connection);return deadline
        }
        override fun authorizeReplay(connection: Connection,actor: VerifiedSocialAccount,originalPost: JsonObject): Instant {
            if(recall)throw PostPublicationFailure(PostPublicationFailureCode.FORBIDDEN)
            content(connection,actor,originalPost,false)
            val actual=memberships(connection,actor,originalPost.getValue("audience").jsonObject.getValue("circleIds").jsonArray)
            val original=originalPost.getValue("audience").jsonObject.getValue("bindings").jsonArray.associate { val b=it.jsonObject;UUID.fromString(b.getValue("circleId").jsonPrimitive.content) to b.getValue("authorMembershipGeneration").jsonPrimitive.long }
            if(actual!=original)throw PostPublicationFailure(PostPublicationFailureCode.FORBIDDEN)
            recipe(connection,actor,originalPost["attachment"]?.jsonObject)
            val deadline=now(connection).plusSeconds(expirySeconds);afterReplay?.invoke(connection);return deadline
        }
        private fun memberships(c: Connection,actor: VerifiedSocialAccount,circles: JsonArray)=circles.map { UUID.fromString(it.jsonPrimitive.content) }.sorted().associateWith { id->
            c.prepareStatement("SELECT generation,active FROM post_publication_test.memberships WHERE owner_id=? AND circle_id=? FOR SHARE").use { it.setObject(1,actor.accountId);it.setObject(2,id);it.executeQuery().use { r->if(!r.next()||!r.getBoolean(2))throw PostPublicationFailure(PostPublicationFailureCode.FORBIDDEN);r.getLong(1) } }
        }
        private fun content(c: Connection,actor: VerifiedSocialAccount,selection: JsonObject,newSelection: Boolean) {
            try { drafts.authority.authorizeContent(c,actor,selection,newSelection) }
            catch(e:PostDraftFailure) { throw PostPublicationFailure(when(e.code) {
                PostDraftFailureCode.FORBIDDEN->PostPublicationFailureCode.FORBIDDEN
                PostDraftFailureCode.NOT_CONFIGURED->PostPublicationFailureCode.NOT_CONFIGURED
                else->PostPublicationFailureCode.STORAGE_UNAVAILABLE
            }) }
        }
        private fun recipe(c: Connection,actor: VerifiedSocialAccount,attachment: JsonObject?): Pair<JsonObject,JsonObject>? {
            if(attachment==null)return null
            val id=attachment["recipeVersionId"]?.jsonPrimitive?.content?.let(UUID::fromString)?:throw PostPublicationFailure(PostPublicationFailureCode.NOT_CONFIGURED)
            return c.prepareStatement("SELECT attachment,recipe,active FROM post_publication_test.recipes WHERE owner_id=? AND id=? FOR SHARE").use { it.setObject(1,actor.accountId);it.setObject(2,id);it.executeQuery().use { r->
                if(!r.next()||!r.getBoolean(3))throw PostPublicationFailure(PostPublicationFailureCode.FORBIDDEN)
                val exact=Json.parseToJsonElement(r.getString(1)).jsonObject
                if(exact!=attachment)throw PostPublicationFailure(PostPublicationFailureCode.FORBIDDEN)
                exact to Json.parseToJsonElement(r.getString(2)).jsonObject
            } }
        }
    }
    companion object {
        fun reply(result: CommandResult)=PostDraftTestFixture.reply(result)
        fun id(body: JsonObject)=PostDraftTestFixture.id(body)
        fun mediaActor(actor: VerifiedSocialAccount)=PostDraftTestFixture.mediaActor(actor)
        private fun now(c: Connection)=c.createStatement().use { s->s.executeQuery("SELECT clock_timestamp()").use { it.next();it.getObject(1,OffsetDateTime::class.java).toInstant() } }
    }
}
