package com.feedme.server.social.drafts

import com.feedme.server.social.VerifiedSocialAccount
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Exact immutable V008 cleanup lineage, not progress/settlement/absence acknowledgement. */
internal object PostDraftCleanupEvidence {
    fun capture(c:Connection,actor:VerifiedSocialAccount,mediaId:UUID,version:Long):JsonObject {
        val asset=c.prepareStatement("SELECT quarantine_key,reservation_expires_at,updated_at,state,version FROM platform.media_assets WHERE environment=? AND owner_user_id=? AND id=? FOR UPDATE").use{
            it.owner(actor);it.setObject(3,mediaId);it.executeQuery().use{r->
                if(!r.next()||r.getString("state")!="deleted"||r.getLong("version")!=version)invalid()
                Triple(r.getString("quarantine_key"),r.getObject("reservation_expires_at",OffsetDateTime::class.java).toInstant(),r.getObject("updated_at",OffsetDateTime::class.java).toInstant())
            }
        }
        val job=c.prepareStatement("SELECT * FROM platform.media_processing_jobs WHERE environment=? AND owner_user_id=? AND media_id=? FOR UPDATE").use{
            it.owner(actor);it.setObject(3,mediaId);it.executeQuery().use{r->if(!r.next())null else {
                val state=r.getString("state");val terminalVersion=r.getObject("terminal_media_version") as? Number ?: invalid()
                if(state !in setOf("ready","rejected","cancelled")||r.getObject("terminal_at")==null||r.getObject("lease_token")!=null||r.getObject("lease_expires_at")!=null||
                    (if(state=="cancelled")terminalVersion.toLong()!=version else terminalVersion.toLong() !in 1 until version))invalid()
                buildJsonObject{
                    put("id",r.getObject("id",UUID::class.java).toString());put("state",state);put("terminalVersion",terminalVersion.toLong())
                    put("terminalAt",r.getObject("terminal_at",OffsetDateTime::class.java).toInstant().toString())
                    put("terminalToken",r.getObject("terminal_token",UUID::class.java)?.let{JsonPrimitive(it.toString())}?:JsonNull)
                    put("terminalGeneration",r.getObject("terminal_generation")?.let{JsonPrimitive((it as Number).toLong())}?:JsonNull)
                }
            }}
        }
        val intents=if(job==null)emptyList() else c.prepareStatement("SELECT id,object_key,acceptance_deadline,cleanup_required FROM platform.media_derivative_intents WHERE job_id=? ORDER BY id FOR UPDATE").use{
            it.setObject(1,UUID.fromString(job.getValue("id").jsonPrimitive.content));it.executeQuery().use{r->buildList{while(r.next()){
                if(!r.getBoolean("cleanup_required")||size>=2)invalid()
                add(buildJsonObject{put("id",r.getObject("id",UUID::class.java).toString());put("key",r.getString("object_key"));put("notBefore",r.getObject("acceptance_deadline",OffsetDateTime::class.java).toInstant().toString())})
            }}}
        }
        val manifest=c.prepareStatement("SELECT derivative_set FROM platform.media_cleanup_jobs WHERE environment=? AND owner_user_id=? AND media_id=? FOR UPDATE").use{
            it.owner(actor);it.setObject(3,mediaId);it.executeQuery().use{r->if(!r.next())invalid();r.getString(1)?.let{Json.parseToJsonElement(it).jsonObject.getValue("variants").jsonArray}?:JsonArray(emptyList())}
        }
        if(manifest.size>2)invalid()
        val expectedKeys=setOf(asset.first)+intents.map{it.getValue("key").jsonPrimitive.content}+manifest.map{it.jsonObject.getValue("key").jsonPrimitive.content}
        val targets=c.prepareStatement("SELECT * FROM platform.media_processing_cleanup WHERE environment=? AND owner_user_id=? AND media_id=? ORDER BY object_key FOR UPDATE").use{
            it.owner(actor);it.setObject(3,mediaId);it.executeQuery().use{r->buildList{while(r.next()){
                if(size>=5)invalid()
                val key=r.getString("object_key");val intent=r.getObject("derivative_intent_id",UUID::class.java)
                val notBefore=r.getObject("not_before",OffsetDateTime::class.java).toInstant()
                val expected=intents.singleOrNull{it.getValue("key").jsonPrimitive.content==key}
                if(key !in expectedKeys||intent?.toString()!=expected?.get("id")?.jsonPrimitive?.content||
                    (expected!=null&&notBefore.toString()!=expected.getValue("notBefore").jsonPrimitive.content)||
                    (key==asset.first&&(intent!=null||notBefore!=asset.second))||
                    (expected==null&&key!=asset.first&&notBefore<asset.third))invalid()
                val state=r.getString("state")
                if(state !in setOf("pending","working","done","quarantined")||
                    (state=="done")!=(r.getObject("completed_at")!=null))invalid()
                add(buildJsonObject{put("id",r.getObject("id",UUID::class.java).toString());put("key",key)
                    put("intent",intent?.let{JsonPrimitive(it.toString())}?:JsonNull);put("notBefore",notBefore.toString())})
            }}}
        }
        if(targets.map{it.getValue("key").jsonPrimitive.content}.toSet()!=expectedKeys)invalid()
        return buildJsonObject{put("job",job?:JsonNull);put("intents",JsonArray(intents));put("targets",JsonArray(targets))}
    }

    fun verify(expected:JsonObject,actual:JsonObject) {
        if(expected.keys!=setOf("job","intents","targets"))invalid()
        if(expected.getValue("intents")!=actual.getValue("intents")||expected.getValue("targets")!=actual.getValue("targets"))invalid()
        val old=expected.getValue("job");val current=actual.getValue("job")
        if(old!=current) {
            // A committed upload event can be ingested only AFTER its asset was discarded.
            // That permitted inbox/job addition is cancelled, has no derivative intents and
            // cannot change any already captured exact cleanup target or receipt.
            if(old!=JsonNull||current==JsonNull||current.jsonObject.getValue("state")!=JsonPrimitive("cancelled")||
                actual.getValue("intents").jsonArray.isNotEmpty())invalid()
        }
    }
    private fun PreparedStatement.owner(actor:VerifiedSocialAccount){setString(1,actor.environment);setObject(2,actor.accountId)}
    private fun invalid():Nothing=throw PostDraftFailure(PostDraftFailureCode.STORAGE_UNAVAILABLE)
}
