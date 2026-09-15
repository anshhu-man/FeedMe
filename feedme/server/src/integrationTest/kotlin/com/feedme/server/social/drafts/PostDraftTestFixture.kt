package com.feedme.server.social.drafts

import com.feedme.server.db.*
import com.feedme.server.media.*
import com.feedme.server.social.VerifiedSocialAccount
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*

/** Real PostgreSQL, explicitly synthetic verified identity/content eligibility and media providers. */
class PostDraftTestFixture(val source:DataSource, val mediaFixture:MediaTestFixture=MediaTestFixture(source)) {
    val account=actor(mediaFixture.account)
    val faults get()=mediaFixture.faults
    val authority=TestAuthority()
    val mediaAuthority=object:MediaAuthority by mediaFixture.authority {
        override fun lockDraftLifecycle(connection:Connection,actor:VerifiedMediaAccount,clientDraftId:UUID,forUpload:Boolean):Long {
            val generation=mediaFixture.authority.lockDraftLifecycle(connection,actor,clientDraftId,forUpload)
            if(forUpload)try{PostDraftLifecycle.requireEditable(connection,actor.environment,actor.accountId,clientDraftId)}
                catch(_:PostDraftFailure){throw MediaFailure(MediaFailureCode.DRAFT_UNAVAILABLE)}
            return generation
        }
    }
    val mediaStore=MediaStore("test",PgTransactions(faults.wrap(source)),mediaAuthority,mediaFixture.signer,mediaFixture.objects,mediaFixture.policy())
    val cursors=PostDraftCursors("test1",mapOf("test1" to ByteArray(32){(it+1).toByte()}))
    val store:PostDraftStore
    init {
        sql("CREATE SCHEMA post_draft_test; CREATE TABLE post_draft_test.content_allow(owner_id uuid NOT NULL,kind text NOT NULL,id uuid NOT NULL,active boolean NOT NULL,PRIMARY KEY(owner_id,kind,id))")
        store=newStore()
    }
    fun policy(max:Int=65536,lifetime:Int=2_592_000,cursorLifetime:Int=300)=PostDraftServicePolicy(max,lifetime,cursorLifetime)
    fun newStore(policy:PostDraftServicePolicy=policy())=PostDraftStore("test",PgTransactions(faults.wrap(source)),authority,mediaStore,policy,cursors)
    fun principal()=actor(mediaFixture.principal())
    fun client(actor:VerifiedSocialAccount=account)=mediaFixture.draft(mediaActor(actor))
    fun body(client:UUID=client(),caption:String?=null)=buildJsonObject{put("clientDraftId",client.toString());caption?.let{put("caption",it)}}
    fun create(body:JsonObject=body(),key:UUID=UUID.randomUUID(),actor:VerifiedSocialAccount=account,store:PostDraftStore=this.store)=reply(store.createPostDraft(actor,key,body)).body!!.jsonObject
    fun prepare(client:UUID,actor:VerifiedSocialAccount=account,key:UUID=UUID.randomUUID()):JsonObject =
        mediaFixture.prepare(key,mediaFixture.prepareBody(mediaActor(actor),client),mediaActor(actor),mediaStore)
    fun grant(kind:String,id:UUID=UUID.randomUUID(),actor:VerifiedSocialAccount=account):UUID {
        source.connection.use{c->c.prepareStatement("INSERT INTO post_draft_test.content_allow VALUES(?,?,?,true)").use{
            it.setObject(1,actor.accountId);it.setString(2,kind);it.setObject(3,id);it.executeUpdate()}}
        return id
    }
    fun expire(id:UUID) = sql("BEGIN; ALTER TABLE platform.post_drafts DISABLE TRIGGER post_draft_immutable; UPDATE platform.post_drafts SET created_at=clock_timestamp()-interval '31 days',updated_at=clock_timestamp()-interval '31 days',expires_at=clock_timestamp()-interval '1 second' WHERE id='$id'; ALTER TABLE platform.post_drafts ENABLE TRIGGER post_draft_immutable; COMMIT")
    fun sql(sql:String)=mediaFixture.sql(sql)
    fun value(sql:String)=mediaFixture.value(sql)
    fun count(table:String)=mediaFixture.count(table)
    fun lifecycle(actor:VerifiedSocialAccount=account,action:(Connection)->Unit) = PgTransactions(source).run{c->authority.lockPrincipal(c,actor);action(c)}
    inner class TestAuthority:PostDraftAuthority {
        var enabled=true
        var terms=true
        var afterPrincipal:(()->Unit)?=null
        var afterContent:(()->Unit)?=null
        override fun lockPrincipal(connection:Connection,actor:VerifiedSocialAccount) {
            try{mediaFixture.authority.lockPrincipal(connection,mediaActor(actor))}
            catch(_:MediaFailure){throw PostDraftFailure(PostDraftFailureCode.UNAUTHENTICATED)}
            afterPrincipal?.invoke()
        }
        override fun requireMutationEnabled(connection:Connection,actor:VerifiedSocialAccount) {
            if(!terms)throw PostDraftFailure(PostDraftFailureCode.FORBIDDEN)
            if(!enabled)throw PostDraftFailure(PostDraftFailureCode.NOT_CONFIGURED)
        }
        override fun lockDraftLifecycle(connection:Connection,actor:VerifiedSocialAccount,clientDraftId:UUID,forEdit:Boolean)=
            try{mediaFixture.authority.lockDraftLifecycle(connection,mediaActor(actor),clientDraftId,forEdit)}
            catch(_:MediaFailure){throw PostDraftFailure(PostDraftFailureCode.DRAFT_CONFLICT)}
        override fun authorizeContent(connection:Connection,actor:VerifiedSocialAccount,content:JsonObject,newSelection:Boolean) {
            fun check(kind:String,id:UUID)=connection.prepareStatement("SELECT active FROM post_draft_test.content_allow WHERE owner_id=? AND kind=? AND id=? FOR SHARE").use{
                it.setObject(1,actor.accountId);it.setString(2,kind);it.setObject(3,id)
                it.executeQuery().use{r->if(!r.next()||!r.getBoolean(1))throw PostDraftFailure(PostDraftFailureCode.FORBIDDEN)}
            }
            content["audience"]?.jsonObject?.get("circleIds")?.jsonArray?.map{UUID.fromString(it.jsonPrimitive.content)}?.sorted()?.forEach{check("circle",it)}
            content["attachment"]?.jsonObject?.let{a->
                val sources=listOf("recipeVersionId","planId","personalRecipe").filter{it in a}
                if(sources.size!=1 || "personalRecipe" in a)throw PostDraftFailure(PostDraftFailureCode.NOT_CONFIGURED)
                check("attachment",UUID.fromString(a.getValue(sources.single()).jsonPrimitive.content))
            }
            content["sourcePostId"]?.let{check("source",UUID.fromString(it.jsonPrimitive.content))}
            afterContent?.invoke()
        }
    }
    companion object {
        fun actor(p:VerifiedMediaAccount)=VerifiedSocialAccount(p.environment,p.accountId,p.deviceSessionId)
        fun mediaActor(p:VerifiedSocialAccount)=VerifiedMediaAccount(p.environment,p.accountId,p.deviceSessionId)
        fun reply(result:CommandResult)=when(result){is CommandResult.Applied->result.reply;is CommandResult.Replayed->result.reply;else->error("Expected acknowledged draft fixture command")}
        fun id(body:JsonObject)=UUID.fromString(body.getValue("id").jsonPrimitive.content)
    }
}
