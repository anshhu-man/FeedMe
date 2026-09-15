package com.feedme.server.media

import com.feedme.server.cooking.*
import com.feedme.server.db.*
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlinx.serialization.json.*

/** TEST ONLY. Actual PostgreSQL; explicitly synthetic identity/terms/object metadata/local POST signer. */
class MediaTestFixture(val source: DataSource) {
    val base=CookingTestFixture(source)
    val faults get()=base.faults
    val account=actor(base.account)
    val authority=TestAuthority()
    val signer=TestSigner()
    val objects=TestObjects()
    val store:MediaStore
    init {
        sql("CREATE SCHEMA media_test; CREATE TABLE media_test.drafts(owner_id uuid NOT NULL,id uuid NOT NULL,generation bigint NOT NULL,eligible boolean NOT NULL,PRIMARY KEY(owner_id,id)); " +
            "CREATE TABLE media_test.attached(owner_id uuid NOT NULL,media_id uuid NOT NULL,PRIMARY KEY(owner_id,media_id))")
        store=newStore()
    }
    fun policy(maxResponseBytes:Int=65536,reservationLifetimeSeconds:Int=3600,capabilityLifetimeSeconds:Int=60)=
        MediaServicePolicy(10_000_000,setOf("image/png","image/jpeg"),reservationLifetimeSeconds,capabilityLifetimeSeconds,4096,maxResponseBytes,512,setOf("https://upload.invalid"))
    fun newStore(policy:MediaServicePolicy=policy())=MediaStore("test",PgTransactions(faults.wrap(source)),authority,signer,objects,policy)
    fun principal()=actor(base.principal(CommandActor.ACCOUNT))
    fun secondDevice(p:VerifiedMediaAccount=account)=actor(base.secondDevice(cooking(p)))
    fun draft(p:VerifiedMediaAccount=account):UUID {
        val id=UUID.randomUUID();source.connection.use{c->c.prepareStatement("INSERT INTO media_test.drafts VALUES(?,?,1,true)").use{it.setObject(1,p.accountId);it.setObject(2,id);it.executeUpdate()}};return id
    }
    fun prepareBody(p:VerifiedMediaAccount=account,clientDraftId:UUID=draft(p))=buildJsonObject {
        put("kind","photo");put("contentType","image/png");put("bytes",128);put("sha256",CHECKSUM);put("clientDraftId",clientDraftId.toString())
    }
    fun prepare(key:UUID=UUID.randomUUID(),body:JsonObject=prepareBody(),p:VerifiedMediaAccount=account,selected:MediaStore=store)=reply(selected.prepareMediaUpload(p,key,body)).body!!.jsonObject
    /** Install explicit synthetic immutable object metadata after reservation, never an accepting verifier fallback. */
    fun upload(media:JsonObject,version:String="object-version-1"):JsonObject {
        val id=UUID.fromString(media.getValue("id").jsonPrimitive.content)
        source.connection.use{c->c.prepareStatement("SELECT quarantine_key,expected_bytes,content_type,expected_sha256 FROM platform.media_assets WHERE id=?").use{
            it.setObject(1,id);it.executeQuery().use{r->check(r.next());val proof=VerifiedMediaObject(r.getString(1),version,r.getLong(2),r.getString(3),r.getString(4));objects.versions[proof.objectKey to version]=proof}
        }}
        return buildJsonObject{put("objectVersionId",version);put("sha256",CHECKSUM)}
    }
    /** Synthetic processor transition only: no decoder, scanner, moderation or ready-provider proof. */
    fun finishProcessing(media:JsonObject,state:String) {
        require(state in setOf("ready","rejected"))
        val id=UUID.fromString(media.getValue("id").jsonPrimitive.content)
        val derivatives=buildJsonObject{put("version",1);put("variants",buildJsonArray{
            add(buildJsonObject{put("variant","display");put("key","derivatives/test/$id/display");put("objectVersionId","synthetic-sanitized-version")})
        })}
        lifecycle{c->
            val draft=c.prepareStatement("SELECT client_draft_id FROM platform.media_assets WHERE environment='test' AND owner_user_id=? AND id=?").use{
                it.setObject(1,account.accountId);it.setObject(2,id);it.executeQuery().use{r->check(r.next());r.getObject(1,UUID::class.java)}}
            authority.lockDraftLifecycle(c,account,draft,true)
            c.prepareStatement("SELECT generation FROM platform.media_draft_lifecycles WHERE environment='test' AND owner_user_id=? AND client_draft_id=? FOR UPDATE").use{
                it.setObject(1,account.accountId);it.setObject(2,draft);it.executeQuery().use{r->check(r.next())}}
            c.prepareStatement("UPDATE platform.media_assets SET state=?,version=version+1,updated_at=clock_timestamp(),derivative_set=?::jsonb,rejection_code=? WHERE environment='test' AND owner_user_id=? AND id=? AND state='processing'").use{
                it.setString(1,state);it.setString(2,if(state=="ready")derivatives.toString() else null)
                it.setString(3,if(state=="rejected")"SYNTHETIC_REJECTION" else null);it.setObject(4,account.accountId);it.setObject(5,id)
                check(it.executeUpdate()==1)}
        }
    }
    /** Explicit fixture clock edit bypasses only the reservation trigger; no production clock override. */
    fun expire(mediaId:UUID)=sql("BEGIN; ALTER TABLE platform.media_assets DISABLE TRIGGER media_reservation_immutable; " +
        "UPDATE platform.media_assets SET created_at=clock_timestamp()-interval '2 hours',reservation_expires_at=clock_timestamp()-interval '1 second' WHERE id='$mediaId'; " +
        "ALTER TABLE platform.media_assets ENABLE TRIGGER media_reservation_immutable; COMMIT")
    fun sql(sql:String)=base.sql(sql)
    fun value(sql:String)=base.value(sql)
    fun count(table:String)=base.count(table)
    fun cooking(p:VerifiedMediaAccount)=VerifiedCookingPrincipal(p.environment,CommandActor.ACCOUNT,p.accountId,p.deviceSessionId)
    fun lifecycle(p:VerifiedMediaAccount=account,action:(Connection)->Unit) {
        PgTransactions(source).run{c->base.authority.lockPrincipal(c,cooking(p));action(c)}
    }
    inner class TestAuthority:MediaAuthority {
        var uploadsAllowed=true;var termsAccepted=true;var admissions=0
        var afterPrincipal:(()->Unit)?=null
        override fun lockPrincipal(connection:Connection,actor:VerifiedMediaAccount){
            try{base.authority.lockPrincipal(connection,cooking(actor))}catch(_:CookingFailure){throw MediaFailure(MediaFailureCode.UNAUTHENTICATED)}
            afterPrincipal?.invoke()
        }
        override fun requireUploadEnabled(connection:Connection,actor:VerifiedMediaAccount,newReservation:Boolean){
            if(!termsAccepted)throw MediaFailure(MediaFailureCode.FORBIDDEN)
            if(!uploadsAllowed)throw MediaFailure(MediaFailureCode.NOT_CONFIGURED)
            if(newReservation)admissions++
        }
        override fun lockDraftLifecycle(connection:Connection,actor:VerifiedMediaAccount,clientDraftId:UUID,forUpload:Boolean):Long=
            connection.prepareStatement("SELECT generation,eligible FROM media_test.drafts WHERE owner_id=? AND id=? FOR UPDATE").use{
                it.setObject(1,actor.accountId);it.setObject(2,clientDraftId);it.executeQuery().use{r->
                    if(!r.next()||(forUpload&&!r.getBoolean(2)))throw MediaFailure(MediaFailureCode.DRAFT_UNAVAILABLE);r.getLong(1)}
            }
        override fun requireUnattached(connection:Connection,actor:VerifiedMediaAccount,mediaId:UUID){
            connection.prepareStatement("SELECT 1 FROM media_test.attached WHERE owner_id=? AND media_id=? FOR SHARE").use{
                it.setObject(1,actor.accountId);it.setObject(2,mediaId);it.executeQuery().use{r->if(r.next())throw MediaFailure(MediaFailureCode.MEDIA_ATTACHED)}
            }
        }
    }
    class TestSigner:MediaUploadCapabilities {
        val authorizations=mutableListOf<MediaUploadAuthorization>()
        var beforeSign:(()->Unit)?=null
        var transform:((MediaUploadCapability)->MediaUploadCapability)?=null
        override fun sign(authorization:MediaUploadAuthorization):MediaUploadCapability {
            authorizations+=authorization;beforeSign?.invoke()
            val result=MediaUploadCapability("https://upload.invalid/quarantine",mapOf("key" to authorization.objectKey,
                "policy" to "synthetic-secret-post-${authorizations.size}","checksum" to authorization.sha256,
                "Content-Type" to authorization.contentType,"bytes" to authorization.expectedBytes.toString()),authorization.expiresAt)
            return transform?.invoke(result)?:result
        }
    }
    class TestObjects:MediaObjectVerifier {
        val versions=ConcurrentHashMap<Pair<String,String>,VerifiedMediaObject>()
        val requests=mutableListOf<MediaObjectVerificationRequest>()
        var beforeVerify:(()->Unit)?=null
        var transform:((VerifiedMediaObject)->VerifiedMediaObject)?=null
        override fun verify(request:MediaObjectVerificationRequest):VerifiedMediaObject {
            requests+=request;beforeVerify?.invoke()
            val result=versions[request.objectKey to request.objectVersionId]?:throw MediaFailure(MediaFailureCode.OBJECT_MISMATCH)
            return transform?.invoke(result)?:result
        }
    }
    companion object {
        const val CHECKSUM="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        fun actor(p:VerifiedCookingPrincipal)=VerifiedMediaAccount(p.environment,p.principalId,checkNotNull(p.deviceSessionId))
        fun reply(result:CommandResult):StoredReply=when(result){is CommandResult.Applied->result.reply;is CommandResult.Replayed->result.reply;else->error("Expected acknowledged media fixture command")}
    }
}
