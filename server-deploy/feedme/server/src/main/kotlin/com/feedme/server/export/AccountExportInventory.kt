package com.feedme.server.export

import com.feedme.server.catalog.*
import com.feedme.server.memory.SavedRecipeFailure
import com.feedme.server.memory.SavedRecipeFailureCode
import com.feedme.server.memory.accountSavedRecipeHash
import com.feedme.server.memory.requireAccountSavedMaterial
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit owned-data projection, not a database dump. No provider subject, installation
 * hash, bearer, receipt request, staff evidence, peer profile, incoming message, invitation
 * secret, media object reference or other person's recipe-copy attribution is exported.
 * User-authored free text remains their own submitted data; it is not mined for identities.
 * Limits fail the whole snapshot: omitted/truncated records never masquerade as completion. */
class AccountExportInventory(val maxRowsPerSection:Int = 1000,
    val maxPlaintextBytes:Int = AccountExportEncryption.MAX_PLAINTEXT_BYTES,
    private val recipeRights:RecipeCopyRightsStore? = null) {
    init { require(maxRowsPerSection in 1..10_000 && maxPlaintextBytes in 1024..AccountExportEncryption.MAX_PLAINTEXT_BYTES) }

    internal fun snapshot(c:Connection, lease:ExportLease):ByteArray {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        AccountExportServingCompatibility.check(c,worker=true)
        // Exact ordinary-table writer fences make the multi-table READ COMMITTED snapshot
        // coherent. NOWAIT does not sit holding an account root while awaiting other owners.
        try { c.createStatement().use { s -> sections.map { it.table }.distinct().sorted().forEach {
            s.execute("LOCK TABLE ONLY $it IN SHARE MODE NOWAIT")
        } } } catch (f:SQLException) {
            if(f.sqlState=="55P03") throw SQLException("Export snapshot fence contended","40001")
            throw f
        }
        var budget = 2048
        val retainedRights=mutableListOf<RecipeCopyRightsHandle>()
        val data = buildJsonObject {
            put("format", "feedme-account-json-v1");put("accountId",lease.accountId.toString())
            put("jobId",lease.jobId.toString());put("policyRevision",lease.policyRevision)
            put("includeMedia",false)
            put("omittedCategories", JsonArray(listOf("mediaBytes","storageReferences","providerCredentials",
                "otherPeopleProfilesAndMessages","otherPeopleAttribution","restrictedSafetyAndStaffEvidence","unretainedPlanRecipeText",
                "internalIdempotencyAndOperationalLedgers").map(::JsonPrimitive)))
            for(section in sections) {
                val items = c.prepareStatement("SELECT ${section.projection} AS document FROM ${section.table} " +
                    "WHERE environment=? AND ${section.owner}=? ${section.filter} ORDER BY ${section.order} LIMIT ?").use { s ->
                    s.setString(1,lease.environment);s.setObject(2,if(section.privateOwner) lease.principalId else lease.accountId)
                    s.setInt(3,maxRowsPerSection+1)
                    s.executeQuery().use { rows -> buildList {
                        while(rows.next()) {
                            if(size>=maxRowsPerSection) throw ExportWorkFailure(ExportWorkIssue.LIMIT_EXCEEDED)
                            val text=rows.getString(1) ?: throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
                            val bytes=text.toByteArray(Charsets.UTF_8)
                            try { budget=Math.addExact(budget,bytes.size+1)
                                if(budget>maxPlaintextBytes) throw ExportWorkFailure(ExportWorkIssue.LIMIT_EXCEEDED)
                                val document=Json.parseToJsonElement(text)
                                add(redact(if(section.name=="savedRecipes")saved(c,lease,document.jsonObject,retainedRights)else document))
                            } finally { bytes.fill(0) }
                        }
                    } }
                }
                put(section.name,JsonArray(items))
            }
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            if(data.size>maxPlaintextBytes)throw ExportWorkFailure(ExportWorkIssue.LIMIT_EXCEEDED)
            retainedRights.forEach{it.revalidate(c)}
            val acceptedAt=c.createStatement().use{s->s.executeQuery("SELECT clock_timestamp()").use{check(it.next());it.getObject(1,OffsetDateTime::class.java).toInstant()}}
            retainedRights.forEach{it.checkAt(c,acceptedAt)}
            return data
        } catch(t:Throwable){data.fill(0);throw t}
    }
    /** The job owns the account/principal; the independent registry owns recipe permission.
     * This is a private export of an existing authorized copy, never a new/public copy grant.
     * Ordinary source-post removal is deliberately not consulted; recall/revocation is. */
    private fun saved(c:Connection,lease:ExportLease,row:JsonObject,retained:MutableList<RecipeCopyRightsHandle>):JsonObject {
        val snapshot=row["saved"] as? JsonObject
        val metadata=buildJsonObject { put("id",row.getValue("id"));put("version",row.getValue("version"));put("deleted",row.getValue("deleted"))
            put("saved",snapshot?.let{JsonObject(it-"snapshot")}?:JsonNull) }
        if(row["deleted"]==JsonPrimitive(true))return metadata
        fun omitted(reason:String)=JsonObject(metadata+ ("recipeTextOmitted" to JsonPrimitive(reason)))
        val rights=recipeRights?:return omitted("rightsUnavailable")
        if(rights.environment!=lease.environment)throw ExportWorkFailure(ExportWorkIssue.NOT_CONFIGURED)
        val body=snapshot?:throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
        val evidence=row["evidence"] as? JsonObject?:throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
        val source=row.getValue("sourceType").jsonPrimitive.content
        val post=source=="postGrant"
        if(source !in setOf("catalog","ownPlan","postGrant") || evidence.keys!=(setOf("formatVersion","grant","recipeHash")+(if(post)setOf("postGrant")else emptySet())) ||
            evidence["formatVersion"]!=JsonPrimitive(if(post)2 else 1) || evidence["recipeHash"]!=row["recipeHash"] ||
            (source=="ownPlan")!=(row["originPlanId"]!=JsonNull))throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
        val recipe=body["snapshot"] as? JsonObject?:throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
        if(body["id"]!=row["id"] || body["version"]!=row["version"] || recipe["id"]!=row["recipeVersionId"] ||
            accountSavedRecipeHash(recipe)!=row.getValue("recipeHash").jsonPrimitive.content)throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
        if(post) {
            val g=evidence["postGrant"] as? JsonObject?:throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
            if(g.keys!=setOf("id","postId","postVersion","authorId","creatorLabel","policyVersion","disclosureVersion","authorizedAt","rights") ||
                g["postId"]!=row["sourceId"] || body["sourcePostId"]!=g["postId"] || body["grantId"]!=g["id"] || body["creatorLabel"]!=g["creatorLabel"] ||
                g["rights"]!=JsonPrimitive("PRIVATE_RECIPE_COPY") || (g["postVersion"]?.jsonPrimitive?.longOrNull?:0)<1 ||
                (g["policyVersion"]?.jsonPrimitive?.longOrNull?:0)<1)throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
            try { UUID.fromString(g.getValue("id").jsonPrimitive.content);UUID.fromString(g.getValue("authorId").jsonPrimitive.content)
                java.time.Instant.parse(g.getValue("authorizedAt").jsonPrimitive.content)
            } catch(_:Exception){throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)}
        }
        try {
            val grant=rights.openExisting(c,evidence.getValue("grant").jsonObject,guest=false)
            if(grant.recipeVersionId.toString()!=row.getValue("recipeVersionId").jsonPrimitive.content ||
                row["license"]!=JsonPrimitive(if(post)"privateCopyOnly"else grant.contentLicense))throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
            requireAccountSavedMaterial(grant.source.entry,recipe,grant.allowReviewedScaling && source=="ownPlan")
            grant.revalidate(c);retained.add(grant)
            return JsonObject(metadata+("saved" to body))
        } catch(f:RecipeCopyRightsFailure) {
            return when(f.code) {
                RecipeCopyRightsFailureCode.RECALLED -> omitted("recalled")
                RecipeCopyRightsFailureCode.EXPIRED -> omitted("retainedPermissionExpired")
                RecipeCopyRightsFailureCode.NOT_ALLOWED,RecipeCopyRightsFailureCode.AUTHORITY_DENIED -> omitted("retainedPermissionUnavailable")
                else -> throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
            }
        } catch(f:SavedRecipeFailure) {
            if(f.code==SavedRecipeFailureCode.RECIPE_UNAVAILABLE)return omitted("sourceMaterialUnavailable")
            throw ExportWorkFailure(ExportWorkIssue.SOURCE_UNAVAILABLE)
        }
    }
    /** Deep exclusion is defensive, not a generic identity scanner. Only explicitly selected
     * known user-content columns above ever reach it; peer tables/credentials never do. */
    private fun redact(value:JsonElement):JsonElement = when(value) {
        is JsonObject -> JsonObject(value.filterKeys { it !in redactedFields }.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        else -> value
    }
    override fun toString()="AccountExportInventory(<bounded>)"
    private data class Section(val name:String,val table:String,val owner:String,val privateOwner:Boolean,
        val projection:String,val order:String="id",val filter:String="")
    private companion object {
        val redactedFields=setOf("author","creatorLabel","reviewerLabel","reviewerId","sourcePostId","sourceCredit",
            "grantId","grantReceiptId","rightsReference","copyEvidence","postSource","participantIds","sender",
            "capabilities","reactionCounts","mediaIds","mediaId","avatarMediaId","derivatives","objectKey","quarantineKey",
            "downloadUrl","uploadUrl","url","imageUrl","sourceUrl","providerSubject","providerIssuer")
        fun obj(vararg fields:String)= "jsonb_build_object("+fields.joinToString(",") { "'$it',$it" }+")"
        val sections=listOf(
            Section("account","identity.users","id",false,obj("id","status","eligibility_state","eligibility_policy_version","terms_version","terms_accepted_at","created_at","updated_at")),
            Section("profile","profile.profiles","user_id",false,obj("display_name","normalized_handle","bio","onboarding_step","version","created_at","updated_at"),"user_id"),
            Section("sessions","identity.device_sessions","user_id",false,obj("id","platform","device_label","app_version","created_at","last_seen_at","revoked_at")),
            Section("preferences","profile.preferences","principal_id",true,"jsonb_build_object('id',id,'version',version,'fields',fields,'updatedAt',updated_at)",filter="AND actor_kind='account'"),
            Section("pantry","pantry.pantry_items","principal_id",true,"jsonb_build_object('id',id,'ingredientId',ingredient_id,'version',version,'fields',fields,'deleted',deleted,'updatedAt',updated_at)",filter="AND actor_kind='account'"),
            Section("savedRecipes","memory.saved_recipes","principal_id",true,"jsonb_build_object('id',id,'version',version,'deleted',deleted,'saved',snapshot,'evidence',copy_evidence,'sourceType',source_type,'sourceId',source_id,'originPlanId',origin_plan_id,'recipeVersionId',recipe_version_id,'recipeHash',recipe_hash,'license',content_license)",filter="AND actor_kind='account'"),
            Section("plans","planning.plans","principal_id",true,obj("id","request_id","parent_plan_id","version","recipe_version_id","status","created_at"),filter="AND actor_kind='account'"),
            Section("planningInputs","planning.plan_requests","principal_id",true,"jsonb_build_object('id',id,'version',version,'request',request_text::jsonb,'createdAt',created_at)",filter="AND actor_kind='account'"),
            Section("collections","memory.collections","principal_id",true,obj("id","version","name","description","is_default","created_at","updated_at"),filter="AND actor_kind='account'"),
            Section("collectionItems","memory.collection_items","principal_id",true,obj("collection_id","saved_recipe_id","position"),"collection_id,position", "AND actor_kind='account'"),
            Section("cooking","cooking.cook_sessions","principal_id",true,"snapshot",filter="AND actor_kind='account'"),
            Section("feedback","memory.feedback","principal_id",true,"jsonb_build_object('id',id,'version',version,'deleted',deleted,'feedback',snapshot)",filter="AND actor_kind='account'"),
            Section("memories","memory.memories","principal_id",true,"jsonb_build_object('id',id,'version',version,'deleted',deleted,'memory',snapshot)",filter="AND actor_kind='account'"),
            Section("ownedCircles","social.circles","owner_id",false,obj("id","name","description","status","version","created_at","updated_at")),
            Section("memberships","social.circle_members","user_id",false,obj("id","circle_id","role","status","version","joined_at","updated_at")),
            Section("posts","social.posts","owner_user_id",false,"jsonb_build_object('id',id,'version',version,'status',status,'caption',content->'caption','keepOnPlate',content->'keepOnPlate','publishedAt',published_at,'updatedAt',updated_at)"),
            Section("postDrafts","platform.post_drafts","owner_user_id",false,"jsonb_build_object('id',id,'version',version,'status',status,'caption',content->'caption','keepOnPlate',content->'keepOnPlate','createdAt',created_at,'updatedAt',updated_at)"),
            Section("mediaMetadata","platform.media_assets","owner_user_id",false,obj("id","kind","state","version","expected_bytes","content_type","created_at","updated_at")),
            Section("postSavePolicy","social.recipe_save_policies","owner_user_id",false,obj("post_id","version","allow_future_saves","disclosure_version","accepted_at"),"post_id"),
            Section("sentMessages","social.thread_messages","sender_user_id",false,obj("id","thread_id","text","created_at")),
            Section("recipeRequestsMade","social.recipe_requests","requester_user_id",false,obj("id","version","status","created_at","updated_at","expires_at")),
            Section("recipeRequestsReceivedMetadata","social.recipe_requests","author_user_id",false,obj("id","version","status","created_at","updated_at","expires_at")),
            Section("privacy","social.account_privacy","user_id",false,obj("version","allow_circle_member_messages","allow_recipe_requests","analytics_consent","allow_coordination_invites","social_discovery_visible","created_at","updated_at")),
            Section("notifications","profile.notification_settings","user_id",false,obj("version","replies","reactions","invitations","remixes","cooking_reminders","quiet_start_local","quiet_end_local","time_zone","updated_at")),
            // Recipient-owned read metadata only. Peer, message, event and target IDs are
            // not exported, and an incoming message/preview is never duplicated here.
            Section("notificationReceipts","platform.account_notifications","recipient_user_id",false,
                obj("id","version","created_at","updated_at","read_at")),
            Section("notificationReadWatermark","platform.notification_read_watermarks","user_id",false,
                obj("through_created_at","read_at","version","updated_at"),"user_id")
        )
    }
}
