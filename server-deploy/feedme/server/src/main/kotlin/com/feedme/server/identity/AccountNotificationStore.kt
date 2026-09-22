package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class AccountNotificationPolicy(val maxResponseBytes:Int=16384) {
    init { require(maxResponseBytes in 4096..262144) }
}
internal enum class NotificationFailureCode(val status:Int) { INPUT_INVALID(422),VERSION_CONFLICT(412),NOT_CONFIGURED(503),STORAGE_UNAVAILABLE(503) }
internal class NotificationFailure(val code:NotificationFailureCode):RuntimeException("Notification preferences unavailable: ${code.name}")

/** Explicit per-event opt-in settings, independent of OS permission and delivery.
 * Quiet hours are local wall-clock [start,end), overnight when start>end. Both
 * empty patch strings disable quiet hours; absent pair preserves it. Equal times
 * are invalid (never silently interpreted as all-day or no quiet hours). */
internal class AccountNotificationStore(private val environment:String,private val transactions:PgTransactions,
    private val accounts:AccountProfileStore,val policy:AccountNotificationPolicy) {
    private val commands=DurableCommands(transactions)
    private val validator=ContractBodyValidator.bundled()
    init { require(environment==accounts.environment) }
    fun getNotificationSettings(subject:VerifiedSupabaseSubject,device:UUID):StoredReply=safe {
        transactions.run { c ->
            NotificationServingCompatibility.check(c)
            val owner=accounts.lockAccountSafety(c,subject,device)
            initialize(c,owner);val result=reply(row(c,owner));current(c,subject,device,owner);result
        }
    }
    fun updateNotificationSettings(subject:VerifiedSupabaseSubject,device:UUID,key:UUID,ifMatch:String,body:JsonObject):CommandResult=safe {
        val bytes=body.toString().encodeToByteArray()
        if(bytes.size>4096||validator.validateRequest("updateNotificationSettings",bytes,"application/json")!=BodyValidationResult.Valid)fail(NotificationFailureCode.INPUT_INVALID)
        val expected=if(ifMatch.matches(Regex("\"[1-9][0-9]{0,18}\"")))ifMatch.removeSurrounding("\"").toLongOrNull() else null
        if(expected==null||expected==Long.MAX_VALUE)fail(NotificationFailureCode.INPUT_INVALID)
        val quietKeys=setOf("quietStartLocal","quietEndLocal")
        if(body.keys.intersect(quietKeys).size==1)fail(NotificationFailureCode.INPUT_INVALID)
        val start=body["quietStartLocal"]?.jsonPrimitive?.content;val end=body["quietEndLocal"]?.jsonPrimitive?.content
        if(start!=null && !(start.isEmpty()&&end=="")) {
            if(!start.matches(TIME)||end?.matches(TIME)!=true||start==end)fail(NotificationFailureCode.INPUT_INVALID)
        }
        body["timeZone"]?.jsonPrimitive?.content?.let { if(it.length !in 1..100 || it !in ZoneId.getAvailableZoneIds())fail(NotificationFailureCode.INPUT_INVALID) }
        transactions.run { c ->
            NotificationServingCompatibility.check(c)
            val owner=accounts.lockAccountSafety(c,subject,device)
            val command=CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,owner),"updateNotificationSettings",key,body=body,ifMatch=ifMatch)
            val result=commands.executeInTransaction(c,command,{current(it,subject,device,owner)}, {},{_,cached->
                val original=cached.body?.jsonObject?:fail(NotificationFailureCode.STORAGE_UNAVAILABLE)
                if(original["id"]!=JsonPrimitive(owner.toString())||original["version"]!=JsonPrimitive(expected+1))fail(NotificationFailureCode.STORAGE_UNAVAILABLE)
                requirePatch(original,body);validate("updateNotificationSettings",cached)
            }) { db ->
                initialize(db,owner);val before=row(db,owner)
                if(before.getValue("version").jsonPrimitive.long!=expected)fail(NotificationFailureCode.VERSION_CONFLICT)
                val merged=before.toMutableMap().apply{putAll(body);if(start==""){remove("quietStartLocal");remove("quietEndLocal")}}
                db.prepareStatement("""UPDATE profile.notification_settings SET replies=?,reactions=?,invitations=?,remixes=?,cooking_reminders=?,
                    quiet_start_local=?,quiet_end_local=?,time_zone=?,version=version+1,updated_at=clock_timestamp()
                    WHERE environment=? AND user_id=? AND version=?""").use{s->
                    listOf("replies","reactions","invitations","remixes","cookingReminders").forEachIndexed{i,k->s.setBoolean(i+1,merged.getValue(k).jsonPrimitive.boolean)}
                    s.setString(6,merged["quietStartLocal"]?.jsonPrimitive?.content);s.setString(7,merged["quietEndLocal"]?.jsonPrimitive?.content)
                    s.setString(8,merged.getValue("timeZone").jsonPrimitive.content);s.setString(9,environment);s.setObject(10,owner);s.setLong(11,expected)
                    if(s.executeUpdate()!=1)fail(NotificationFailureCode.VERSION_CONFLICT)
                }
                current(db,subject,device,owner);reply(row(db,owner),"updateNotificationSettings")
            }
            current(c,subject,device,owner);result
        }
    }
    private fun initialize(c:Connection,owner:UUID) { c.prepareStatement("INSERT INTO profile.notification_settings(environment,user_id,id) VALUES(?,?,?) ON CONFLICT(environment,user_id) DO NOTHING").use{it.setString(1,environment);it.setObject(2,owner);it.setObject(3,owner);it.executeUpdate()} }
    private fun row(c:Connection,owner:UUID):JsonObject=c.prepareStatement("SELECT * FROM profile.notification_settings WHERE environment=? AND user_id=? FOR UPDATE NOWAIT").use{s->
        s.setString(1,environment);s.setObject(2,owner);s.executeQuery().use{r->if(!r.next())fail(NotificationFailureCode.STORAGE_UNAVAILABLE);json(r)}
    }
    private fun json(r:ResultSet)=buildJsonObject {
        put("id",r.getObject("id",UUID::class.java).toString());put("version",r.getLong("version"))
        put("createdAt",r.getObject("created_at",OffsetDateTime::class.java).toInstant().toString());put("updatedAt",r.getObject("updated_at",OffsetDateTime::class.java).toInstant().toString())
        put("replies",r.getBoolean("replies"));put("reactions",r.getBoolean("reactions"));put("invitations",r.getBoolean("invitations"));put("remixes",r.getBoolean("remixes"));put("cookingReminders",r.getBoolean("cooking_reminders"));put("timeZone",r.getString("time_zone"))
        r.getString("quiet_start_local")?.let{put("quietStartLocal",it)};r.getString("quiet_end_local")?.let{put("quietEndLocal",it)}
    }
    private fun requirePatch(actual:JsonObject,patch:JsonObject) { for((key,value)in patch){if(key in setOf("quietStartLocal","quietEndLocal")&&value==JsonPrimitive("")){if(key in actual)fail(NotificationFailureCode.STORAGE_UNAVAILABLE)}else if(actual[key]!=value)fail(NotificationFailureCode.STORAGE_UNAVAILABLE)} }
    private fun reply(body:JsonObject,operation:String="getNotificationSettings")=StoredReply(200,body,"\"${body.getValue("version").jsonPrimitive.content}\"").also{validate(operation,it)}
    private fun validate(operation:String,reply:StoredReply){val bytes=reply.body?.toString()?.encodeToByteArray()?:fail(NotificationFailureCode.STORAGE_UNAVAILABLE)
        if(reply.status!=200||bytes.size>policy.maxResponseBytes||reply.etag!="\"${reply.body!!.jsonObject.getValue("version").jsonPrimitive.content}\""||validator.validateResponse(operation,200,bytes,"application/json")!=BodyValidationResult.Valid)fail(NotificationFailureCode.STORAGE_UNAVAILABLE)}
    private fun current(c:Connection,s:VerifiedSupabaseSubject,d:UUID,owner:UUID){if(accounts.lockAccountSafety(c,s,d)!=owner)fail(NotificationFailureCode.STORAGE_UNAVAILABLE)}
    private fun fail(code:NotificationFailureCode):Nothing=throw NotificationFailure(code)
    private inline fun<T> safe(block:()->T):T=try{block()}catch(e:NotificationFailure){throw e}catch(e:AccountFailure){throw e}catch(e:CommitOutcomeUnknown){throw e}catch(e:CancellationException){throw e}catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}catch(_:Exception){fail(NotificationFailureCode.STORAGE_UNAVAILABLE)}
    companion object {private val TIME=Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")}
}
