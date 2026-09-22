package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Account safety operation: never requires onboarding/age/Terms completion. Target
 * revocation and the canonical receipt share one database transaction, including the
 * real provider session deletion. No provider HTTP or unbounded background retry. */
internal class AccountSessionStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, val policy: AccountSessionPolicy, private val cursors: AccountSessionCursors) {
    private val commands=DurableCommands(transactions)
    private val outbox=OutboxStore(transactions)
    init { require(environment==accounts.environment) }

    fun listSessions(subject:VerifiedSupabaseSubject,device:UUID,cursor:String?,limit:Int):StoredReply=safe {
        if(limit !in 1..50)fail(SessionFailureCode.INPUT_INVALID)
        transactions.run { c ->
            SessionServingCompatibility.check(c)
            val account=accounts.lockAccountSafety(c,subject,device)
            val at=now(c);val position=cursors.decode(environment,account,device,limit,cursor,at)
            val cutoff=position?.cutoff?:at; val expires=position?.expires?:at.plusSeconds(policy.cursorLifetimeSeconds.toLong())
            val rows=c.prepareStatement("""SELECT id,version,created_at,updated_at,platform,device_label,last_seen_at,revoked_at
                FROM identity.device_sessions WHERE environment=? AND user_id=? AND created_at<=?
                AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?""").use { s ->
                s.setString(1,environment);s.setObject(2,account);s.setObject(3,OffsetDateTime.ofInstant(cutoff,java.time.ZoneOffset.UTC))
                s.setObject(4,position?.after);s.setObject(5,position?.after);s.setInt(6,limit+1)
                s.executeQuery().use { r -> buildList { while(r.next())add(deviceJson(r,device)) } }
            }
            current(c,subject,device,account)
            val responseAt=now(c);if(!responseAt.isBefore(expires))fail(SessionFailureCode.CURSOR_EXPIRED)
            val items=rows.take(limit)
            val next=if(rows.size>limit)cursors.encode(environment,account,device,limit,
                AccountSessionCursors.Position(UUID.fromString(items.last().getValue("id").jsonPrimitive.content),cutoff,expires)) else null
            val body=buildJsonObject{put("items",JsonArray(items));put("nextCursor",next?.let(::JsonPrimitive)?:JsonNull);put("serverTime",responseAt.toString())}
            val reply=StoredReply(200,body);validate("listSessions",reply);reply
        }
    }

    fun revokeSession(subject:VerifiedSupabaseSubject,device:UUID,key:UUID,target:UUID,ifMatch:String):CommandResult=safe {
        val expected=version(ifMatch)
        if(device==target)fail(SessionFailureCode.CURRENT_SESSION_UNSUPPORTED)
        transactions.run { c ->
            SessionServingCompatibility.check(c)
            val account=accounts.lockAccountSafety(c,subject,device)
            val command=CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,account),"revokeSession",key,
                pathParameters=mapOf("sessionId" to target.toString()),ifMatch=ifMatch)
            val result=commands.executeInTransaction(c,command,{current(it,subject,device,account)}, {},
                { _,reply ->
                    // Exact original fingerprint binds target and reviewed version.
                    // Do not reread or require an active target after committed revoke.
                    validate("revokeSession",reply)
                }) { db ->
                val row=db.prepareStatement("SELECT provider_session_id,version,revoked_at FROM identity.device_sessions WHERE environment=? AND user_id=? AND id=? FOR UPDATE NOWAIT").use { s ->
                    s.setString(1,environment);s.setObject(2,account);s.setObject(3,target)
                    s.executeQuery().use { r ->
                        if(!r.next())fail(SessionFailureCode.SESSION_UNAVAILABLE)
                        Triple(r.getObject(1,UUID::class.java),r.getLong(2),r.getTimestamp(3)!=null)
                    }
                }
                if(row.first==subject.providerSessionId)fail(SessionFailureCode.CURRENT_SESSION_UNSUPPORTED)
                if(row.second!=expected || row.third || expected==Long.MAX_VALUE)fail(SessionFailureCode.VERSION_CONFLICT)
                val next=db.prepareStatement("SELECT identity.revoke_account_device_session(?,?,?,?,?,?)").use { s ->
                    s.setString(1,environment);s.setString(2,subject.issuer);s.setObject(3,subject.subject)
                    s.setObject(4,device);s.setObject(5,target);s.setLong(6,expected)
                    s.executeQuery().use { r -> if(!r.next())fail(SessionFailureCode.STORAGE_UNAVAILABLE);val v=r.getLong(1);if(r.wasNull()||r.next()||v!=expected+1)fail(SessionFailureCode.STORAGE_UNAVAILABLE);v }
                }
                outbox.append(db,EventDraft(UUID.randomUUID(),"identity.session.revoked.v1",1,"device_session",target,next,
                    "identity",key.toString(),key,buildJsonObject{put("userId",account.toString());put("sessionId",target.toString())},
                    owner=EventOwner.account(environment,account)))
                current(db,subject,device,account)
                StoredReply(204).also{validate("revokeSession",it)}
            }
            current(c,subject,device,account)
            result
        }
    }
    private fun deviceJson(r:ResultSet,current:UUID):JsonObject=buildJsonObject {
        val id=r.getObject("id",UUID::class.java)
        put("id",id.toString());put("version",r.getLong("version"));put("createdAt",instant(r,"created_at").toString())
        put("updatedAt",instant(r,"updated_at").toString());put("platform",r.getString("platform"));put("deviceLabel",r.getString("device_label"))
        put("lastSeenAt",instant(r,"last_seen_at").toString());put("current",id==current)
        r.getObject("revoked_at",OffsetDateTime::class.java)?.let{put("revokedAt",it.toInstant().toString())}
    }
    private fun current(c:Connection,s:VerifiedSupabaseSubject,d:UUID,a:UUID) { if(accounts.lockAccountSafety(c,s,d)!=a)fail(SessionFailureCode.SESSION_UNAVAILABLE) }
    private fun validate(operation:String,reply:StoredReply) {
        val bytes=reply.body?.toString()?.encodeToByteArray()
        if((bytes?.size?:0)>policy.maxResponseBytes || reply.etag!=null ||
            ContractBodyValidator.bundled().validateResponse(operation,reply.status,bytes,if(bytes==null)null else "application/json")!=BodyValidationResult.Valid ||
            (operation=="revokeSession" && (reply.status!=204 || reply.body!=null)) || (operation=="listSessions" && reply.status!=200))fail(SessionFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun version(value:String):Long {
        if(!value.matches(Regex("\"[1-9][0-9]{0,18}\"")))fail(SessionFailureCode.INPUT_INVALID)
        return value.drop(1).dropLast(1).toLongOrNull()?.takeIf{it>0}?:fail(SessionFailureCode.INPUT_INVALID)
    }
    private fun now(c:Connection):Instant=c.createStatement().use{s->s.executeQuery("SELECT clock_timestamp()").use{it.next();it.getObject(1,OffsetDateTime::class.java).toInstant()}}
    private fun instant(r:ResultSet,name:String)=r.getObject(name,OffsetDateTime::class.java).toInstant()
    private fun fail(code:SessionFailureCode):Nothing=throw SessionFailure(code)
    private inline fun <T> safe(block:()->T):T=try{block()}catch(e:SessionFailure){throw e}catch(e:AccountFailure){throw e}
        catch(e:CommitOutcomeUnknown){throw e}catch(e:CancellationException){throw e}catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}
        catch(_:Exception){fail(SessionFailureCode.STORAGE_UNAVAILABLE)}
    override fun toString()="AccountSessionStore(<redacted>)"
}
