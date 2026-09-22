package com.feedme.server.export

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.identity.*
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Real owned jobs only. All callbacks inside transactions are DB/RAM-only. Provider
 * storage, encryption and binary delivery belong to the explicit worker/download owner. */
internal class AccountExportStore(private val environment:String,private val transactions:PgTransactions,
    private val accounts:AccountProfileStore,private val provider:SupabasePostgresAuthority,
    val policy:AccountExportPolicy,private val downloads:(()->AccountExportDownloadIssuer)?=null) {
    private val commands=DurableCommands(transactions)
    private val validator=ContractBodyValidator.bundled()
    init { require(environment==accounts.environment) }

    fun requestAccountExport(subject:VerifiedSupabaseSubject,device:UUID,key:UUID,body:JsonObject):CommandResult=safe {
        if(body.toString().encodeToByteArray().size>4096 || validator.validateRequest("requestAccountExport",body.toString().encodeToByteArray(),"application/json")!=BodyValidationResult.Valid)fail(ExportFailureCode.INPUT_INVALID)
        if(body["includeMedia"]!=JsonPrimitive(false))fail(ExportFailureCode.MEDIA_EXPORT_UNSUPPORTED)
        transactions.run { c ->
            AccountExportServingCompatibility.check(c)
            val owner=accounts.lockAccountSafety(c,subject,device)
            val principal=principal(c,owner)
            val command=CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,owner),"requestAccountExport",key,body=body)
            var recentUntil:Instant?=null
            val result=commands.executeInTransaction(c,command,{ current(it,subject,device,owner) },{
                if(!policy.enabled)fail(ExportFailureCode.NOT_CONFIGURED)
                recentUntil=try { provider.lockRecentExportValidUntil(it,subject,policy.recentAuthSeconds) }
                    catch(e:AccountFailure){if(e.code==AccountFailureCode.UNAUTHENTICATED)fail(ExportFailureCode.REAUTHENTICATION_REQUIRED);throw e}
            },{ db,reply ->
                validate("requestAccountExport",reply)
                val id=reply.body?.jsonObject?.get("jobId")?.jsonPrimitive?.content?.let(UUID::fromString)?:fail(ExportFailureCode.STORAGE_UNAVAILABLE)
                val row=job(db,id,owner)
                if(row.getValue("command_key").jsonPrimitive.content!=key.toString() || row.getValue("request_sha256").jsonPrimitive.content!=command.requestHash ||
                    reply.body?.jsonObject?.get("status")!=JsonPrimitive("pending"))fail(ExportFailureCode.STORAGE_UNAVAILABLE)
            }) { db ->
                expireJobs(db,owner)
                val active=db.prepareStatement("SELECT EXISTS(SELECT 1 FROM platform.account_export_jobs WHERE environment=? AND account_id=? AND state IN('pending','running','complete'))").use{s->s.setString(1,environment);s.setObject(2,owner);s.executeQuery().use{it.next();it.getBoolean(1)}}
                if(active)fail(ExportFailureCode.ACTIVE_EXPORT_EXISTS)
                val id=UUID.randomUUID();val at=now(db)
                db.prepareStatement("INSERT INTO platform.account_export_jobs(environment,id,account_id,principal_id,issuer,provider_subject,device_session_id,provider_session_id,command_key,request_sha256,policy_revision,format,include_media,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'json',false,?)").use{s->
                    s.setString(1,environment);s.setObject(2,id);s.setObject(3,owner);s.setObject(4,principal);s.setString(5,subject.issuer);s.setObject(6,subject.subject)
                    s.setObject(7,device);s.setObject(8,subject.providerSessionId);s.setObject(9,key);s.setString(10,command.requestHash);s.setString(11,policy.policyRevision);s.setObject(12,time(at.plusSeconds(policy.jobLifetimeSeconds.toLong())));check(s.executeUpdate()==1)
                }
                OutboxStore(transactions).append(db,EventDraft(UUID.randomUUID(),"identity.account.export_requested.v1",1,"account_export",id,1,"identity",key.toString(),key,
                    buildJsonObject{put("userId",owner.toString());put("jobId",id.toString());put("format","json");put("includeMedia",false)},EventOwner.account(environment,owner)))
                current(db,subject,device,owner)
                if(now(db)>=checkNotNull(recentUntil))fail(ExportFailureCode.REAUTHENTICATION_REQUIRED)
                StoredReply(202,buildJsonObject{put("jobId",id.toString());put("status","pending");put("pollAfterSeconds",policy.pollAfterSeconds)}).also{validate("requestAccountExport",it)}
            }
            current(c,subject,device,owner)
            recentUntil?.let{if(now(c)>=it)fail(ExportFailureCode.REAUTHENTICATION_REQUIRED)}
            result
        }
    }

    fun getJob(subject:VerifiedSupabaseSubject,device:UUID,id:UUID):StoredReply=safe { transactions.run { c ->
        val owner=accounts.lockAccountSafety(c,subject,device);val row=job(c,id,owner)
        val at=now(c);if(at>=instant(row,"expires_at")||row["state"]==JsonPrimitive("expired"))fail(ExportFailureCode.EXPORT_EXPIRED)
        val state=row.getValue("state").jsonPrimitive.content
        val artifact=if(state=="complete")readArtifact(c,id)?.also{try{usable(it,at)}catch(t:Throwable){it.close();throw t}}?:fail(ExportFailureCode.STORAGE_UNAVAILABLE) else null
        try {
        val providerUntil=provider.lockCurrentValidUntil(c,subject)
        val artifactUntil=artifact?.expiresAt
        val capability=artifact?.use{(downloads?:fail(ExportFailureCode.NOT_CONFIGURED)).invoke().issue(subject,device,it,minOf(it.expiresAt,providerUntil,at.plusSeconds(policy.downloadSeconds.toLong())))}
        current(c,subject,device,owner)
        val finalAt=now(c)
        if(finalAt>=instant(row,"expires_at") || finalAt>=providerUntil)fail(ExportFailureCode.EXPORT_EXPIRED)
        capability?.let {
            val latest = minOf(checkNotNull(artifactUntil), providerUntil, at.plusSeconds(policy.downloadSeconds.toLong()))
            if (it.expiresAt <= finalAt || it.expiresAt > latest) fail(ExportFailureCode.STORAGE_UNAVAILABLE)
        }
        val body=buildJsonObject{
            put("id",id.toString());put("version",row.getValue("version"));put("createdAt",instant(row,"created_at").toString());put("updatedAt",instant(row,"updated_at").toString())
            put("status",state);put("serverTime",finalAt.toString())
            if(state=="failed")put("error",buildJsonObject{put("type","urn:feedme:problem:account-export");put("title","Export unavailable");put("code",row.getValue("failure_code"));put("status",503);put("traceId",id.toString())})
            capability?.let{put("downloadUrl",it.url);put("downloadExpiresAt",it.expiresAt.toString())}
        }
        StoredReply(200,body,"\"${row.getValue("version").jsonPrimitive.content}\"").also{validate("getJob",it)}
        }finally{artifact?.close()}
    } }

    fun loadDownload(subject:VerifiedSupabaseSubject,device:UUID,id:UUID):ExportArtifact=safe { transactions.run { c ->
        val owner=accounts.lockAccountSafety(c,subject,device);val row=job(c,id,owner)
        if(row["state"]!=JsonPrimitive("complete")||now(c)>=instant(row,"expires_at"))fail(ExportFailureCode.EXPORT_EXPIRED)
        val result=readArtifact(c,id)?:fail(ExportFailureCode.JOB_UNAVAILABLE)
        try { if(result.accountId!=owner)fail(ExportFailureCode.JOB_UNAVAILABLE)
            current(c,subject,device,owner);usable(result,now(c));result
        }catch(t:Throwable){result.close();throw t}
    } }

    fun claim(token:UUID,leaseSeconds:Int):ExportLease?=safe {
        require(leaseSeconds in 1..300)
        if(!policy.enabled)return@safe null
        transactions.run { c ->
            AccountExportServingCompatibility.check(c,worker=true)
            val id=c.prepareStatement("SELECT j.id FROM platform.account_export_jobs j JOIN identity.users u ON u.environment=j.environment AND u.id=j.account_id JOIN identity.principals p ON p.environment=j.environment AND p.id=j.principal_id AND p.user_id=j.account_id WHERE j.environment=? AND j.policy_revision=? AND j.state IN('pending','running') AND j.expires_at>clock_timestamp() AND j.retry_after<=clock_timestamp() AND (j.lease_until IS NULL OR j.lease_until<=clock_timestamp()) AND u.status IN('active','suspended') AND p.status IN('active','suspended') AND NOT EXISTS(SELECT 1 FROM identity.account_deletion_jobs d WHERE d.environment=j.environment AND d.user_id=j.account_id) ORDER BY j.created_at,j.id LIMIT 1").use{s->s.setString(1,environment);s.setString(2,policy.policyRevision);s.executeQuery().use{if(it.next())it.getObject(1,UUID::class.java)else null}}?:return@run null
            val before=job(c,id);lockWorkerOwner(c,before)
            c.prepareStatement("UPDATE platform.account_export_jobs SET state='running',version=version+1,updated_at=clock_timestamp(),lease_token=?,lease_generation=lease_generation+1,lease_until=least(expires_at,clock_timestamp()+make_interval(secs=>?)) WHERE environment=? AND id=? AND state IN('pending','running') AND expires_at>clock_timestamp() AND retry_after<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp()) AND lease_generation<9223372036854775807 AND lease_token IS DISTINCT FROM ? RETURNING to_jsonb(account_export_jobs)::text").use{s->
                s.setObject(1,token);s.setInt(2,leaseSeconds);s.setString(3,environment);s.setObject(4,id);s.setObject(5,token)
                s.executeQuery().use{if(!it.next())null else lease(parse(it.getString(1)))}
            }
        }
    }

    fun snapshot(lease:ExportLease,read:(Connection,ExportLease)->ByteArray):ByteArray=safe {
        var retained:ByteArray?=null
        try { transactions.run { c ->
            retained?.fill(0);retained=null
            lockLease(c,lease);readArtifact(c,lease.jobId)?.use{fail(ExportFailureCode.STORAGE_UNAVAILABLE)}
            val bytes=read(c,lease);retained=bytes
            if(bytes.size !in 1..4194304)fail(ExportFailureCode.STORAGE_UNAVAILABLE)
            lockLease(c,lease);bytes
        }.also{retained=null} }finally{retained?.fill(0)}
    }
    fun artifact(lease:ExportLease):ExportArtifact?=safe { transactions.run{c->lockLease(c,lease);readArtifact(c,lease.jobId)} }

    fun prepareArtifact(lease:ExportLease,value:ExportPreparedArtifact):Boolean=safe { transactions.run{c->
        lockLease(c,lease)
        if(value.objectKey!="exports/$environment/${lease.jobId}/${value.artifactId}"||value.expiresAt>lease.jobExpiresAt||value.expiresAt<=now(c))fail(ExportFailureCode.INPUT_INVALID)
        val existing=readArtifact(c,lease.jobId)
        if(existing!=null)return@run existing.use{it.artifactId==value.artifactId&&it.cipherSha256==value.cipherSha256&&it.objectKey==value.objectKey&&it.bucket==value.bucket&&it.keyId==value.keyId&&it.plaintextSha256==value.plaintextSha256&&it.plaintextBytes==value.plaintextBytes&&it.expiresAt==value.expiresAt}
        val bytes=value.ciphertext;val key=value.wrappedKey;val nonce=value.nonce
        try {
            if(bytes.size!=value.plaintextBytes+16||value.plaintextBytes !in 1..4194304||key.size!=60||nonce.size!=12||sha(bytes)!=value.cipherSha256)fail(ExportFailureCode.INPUT_INVALID)
            c.prepareStatement("INSERT INTO platform.account_export_artifacts(environment,job_id,account_id,principal_id,artifact_id,bucket,object_key,key_id,wrapped_key,nonce,ciphertext,plaintext_sha256,plaintext_bytes,cipher_sha256,cipher_bytes,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use{s->
                s.setString(1,environment);s.setObject(2,lease.jobId);s.setObject(3,lease.accountId);s.setObject(4,lease.principalId);s.setObject(5,value.artifactId);s.setString(6,value.bucket);s.setString(7,value.objectKey);s.setString(8,value.keyId)
                s.setBytes(9,key);s.setBytes(10,nonce);s.setBytes(11,bytes);s.setString(12,value.plaintextSha256);s.setInt(13,value.plaintextBytes);s.setString(14,value.cipherSha256);s.setInt(15,bytes.size);s.setObject(16,time(value.expiresAt));check(s.executeUpdate()==1)
            }
            lockLease(c,lease);true
        }finally{bytes.fill(0);key.fill(0);nonce.fill(0)}
    } }
    fun markDispatched(lease:ExportLease):Boolean=safe { transactions.run{c->
        lockLease(c,lease)
        val changed=c.prepareStatement("UPDATE platform.account_export_artifacts SET write_attempted_at=clock_timestamp(),dispatch_token=?,dispatch_generation=? WHERE environment=? AND job_id=? AND write_attempted_at IS NULL AND expired_at IS NULL AND expires_at>clock_timestamp()").use{s->s.setObject(1,lease.token);s.setLong(2,lease.generation);s.setString(3,environment);s.setObject(4,lease.jobId);s.executeUpdate()==1}
        lockLease(c,lease);changed
    } }
    fun recordObject(lease:ExportLease,verifiedCipherSha:String):Boolean=safe { transactions.run{c->
        lockLease(c,lease)
        val valid=readArtifact(c,lease.jobId)?.use{it.writeAttempted&&it.cipherSha256==verifiedCipherSha&&it.expiresAt>now(c)&&it.hasWrappedKey}?:false
        if(!valid)return@run false
        c.prepareStatement("UPDATE platform.account_export_artifacts SET verified_at=coalesce(verified_at,clock_timestamp()) WHERE environment=? AND job_id=? AND expired_at IS NULL").use{s->s.setString(1,environment);s.setObject(2,lease.jobId);check(s.executeUpdate()==1)}
        lockLease(c,lease)
        c.prepareStatement("UPDATE platform.account_export_jobs SET state='complete',version=version+1,updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE environment=? AND id=?").use{s->s.setString(1,environment);s.setObject(2,lease.jobId);check(s.executeUpdate()==1)}
        true
    } }
    fun defer(lease:ExportLease,delaySeconds:Int):Boolean=safe {require(delaySeconds in 1..3600);transactions.run{c->
        lockLease(c,lease)
        c.prepareStatement("UPDATE platform.account_export_jobs SET version=version+1,updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,retry_after=clock_timestamp()+make_interval(secs=>?) WHERE environment=? AND id=?").use{s->s.setInt(1,delaySeconds);s.setString(2,environment);s.setObject(3,lease.jobId);s.executeUpdate()==1}
    } }
    fun failJob(lease:ExportLease,code:String):Boolean=safe {
        require(code in setOf("EXPORT_TOO_LARGE","EXPORT_SOURCE_UNAVAILABLE","EXPORT_OBJECT_MISMATCH"))
        transactions.run{c->lockLease(c,lease)
            c.prepareStatement("UPDATE platform.account_export_jobs SET state='failed',failure_code=?,version=version+1,updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE environment=? AND id=?").use{s->s.setString(1,code);s.setString(2,environment);s.setObject(3,lease.jobId);s.executeUpdate()==1}
        }
    }
    fun expireArtifacts(limit:Int=20):List<ExportArtifact> = safe { require(limit in 1..100);transactions.run{c->
        AccountExportServingCompatibility.check(c,worker=true)
        val ids=c.prepareStatement("SELECT job_id FROM platform.account_export_artifacts WHERE environment=? AND expires_at<=clock_timestamp() ORDER BY (expired_at IS NULL) DESC,cleanup_attempted_at NULLS FIRST,expires_at,job_id LIMIT ?").use{s->s.setString(1,environment);s.setInt(2,limit);s.executeQuery().use{r->buildList{while(r.next())add(r.getObject(1,UUID::class.java))}}}
        ids.mapNotNull{id->
            job(c,id,lock=true)
            c.prepareStatement("UPDATE platform.account_export_artifacts SET wrapped_key=NULL,ciphertext=NULL,expired_at=clock_timestamp() WHERE environment=? AND job_id=? AND expired_at IS NULL AND expires_at<=clock_timestamp()").use{s->s.setString(1,environment);s.setObject(2,id);s.executeUpdate()}
            c.prepareStatement("UPDATE platform.account_export_artifacts SET cleanup_attempted_at=clock_timestamp() WHERE environment=? AND job_id=? AND expired_at IS NOT NULL").use{s->s.setString(1,environment);s.setObject(2,id);s.executeUpdate()}
            c.prepareStatement("UPDATE platform.account_export_jobs SET state='expired',version=version+1,updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE environment=? AND id=? AND state<>'expired' AND expires_at<=clock_timestamp()").use{s->s.setString(1,environment);s.setObject(2,id);s.executeUpdate()}
            readArtifact(c,id)
        }
    } }

    private fun lockLease(c:Connection,value:ExportLease):JsonObject {
        if(value.environment!=environment||value.policyRevision!=policy.policyRevision)fail(ExportFailureCode.LEASE_LOST)
        val before=job(c,value.jobId);lockWorkerOwner(c,before);val actual=job(c,value.jobId,lock=true)
        if(actual["state"]!=JsonPrimitive("running")||actual["lease_token"]!=JsonPrimitive(value.token.toString())||actual["lease_generation"]!=JsonPrimitive(value.generation)||
            actual["account_id"]!=JsonPrimitive(value.accountId.toString())||actual["principal_id"]!=JsonPrimitive(value.principalId.toString())||actual["issuer"]!=JsonPrimitive(value.issuer)||
            actual["provider_subject"]!=JsonPrimitive(value.providerSubject.toString())||actual["device_session_id"]!=JsonPrimitive(value.deviceSessionId.toString())||actual["provider_session_id"]!=JsonPrimitive(value.providerSessionId.toString())||
            actual["policy_revision"]!=JsonPrimitive(value.policyRevision)||instant(actual,"expires_at")!=value.jobExpiresAt||instant(actual,"lease_until")!=value.expiresAt||now(c)>=value.expiresAt||now(c)>=value.jobExpiresAt)fail(ExportFailureCode.LEASE_LOST)
        return actual
    }
    private fun lockWorkerOwner(c:Connection,j:JsonObject) {
        c.prepareStatement("SELECT u.id FROM identity.users u JOIN identity.principals p ON p.environment=u.environment AND p.user_id=u.id WHERE u.environment=? AND u.id=? AND p.id=? AND p.kind='user' AND u.provider_issuer=? AND u.provider_subject=? AND u.status IN('active','suspended') AND p.status IN('active','suspended') AND NOT EXISTS(SELECT 1 FROM identity.account_deletion_jobs d WHERE d.environment=u.environment AND d.user_id=u.id) FOR SHARE OF u,p NOWAIT").use{s->
            s.setString(1,environment);s.setObject(2,uuid(j,"account_id"));s.setObject(3,uuid(j,"principal_id"));s.setString(4,j.getValue("issuer").jsonPrimitive.content);s.setObject(5,uuid(j,"provider_subject"));s.executeQuery().use{if(!it.next()||it.next())fail(ExportFailureCode.LEASE_LOST)}
        }
    }
    private fun principal(c:Connection,owner:UUID):UUID=c.prepareStatement("SELECT id FROM identity.principals WHERE environment=? AND user_id=? AND kind='user' AND status IN('active','suspended') FOR SHARE NOWAIT").use{s->s.setString(1,environment);s.setObject(2,owner);s.executeQuery().use{if(!it.next())fail(ExportFailureCode.JOB_UNAVAILABLE);it.getObject(1,UUID::class.java).also{_->if(it.next())fail(ExportFailureCode.STORAGE_UNAVAILABLE)}}}
    private fun current(c:Connection,s:VerifiedSupabaseSubject,d:UUID,owner:UUID){if(accounts.lockAccountSafety(c,s,d)!=owner)fail(ExportFailureCode.JOB_UNAVAILABLE)}
    private fun job(c:Connection,id:UUID,owner:UUID?=null,lock:Boolean=false):JsonObject=c.prepareStatement("SELECT to_jsonb(j)::text FROM platform.account_export_jobs j WHERE environment=? AND id=? AND (?::uuid IS NULL OR account_id=?)"+if(lock)" FOR UPDATE NOWAIT" else "").use{s->s.setString(1,environment);s.setObject(2,id);s.setObject(3,owner);s.setObject(4,owner);s.executeQuery().use{if(!it.next())fail(ExportFailureCode.JOB_UNAVAILABLE);parse(it.getString(1))}}
    private fun readArtifact(c:Connection,id:UUID):ExportArtifact?=c.prepareStatement("SELECT * FROM platform.account_export_artifacts WHERE environment=? AND job_id=? FOR SHARE NOWAIT").use{s->s.setString(1,environment);s.setObject(2,id);s.executeQuery().use{if(!it.next())null else artifactRow(it)}}
    private fun artifactRow(r:ResultSet):ExportArtifact {
        val key=r.getBytes("wrapped_key");val nonce=r.getBytes("nonce");val bytes=r.getBytes("ciphertext")
        return try { ExportArtifact(environment,r.getObject("job_id",UUID::class.java),r.getObject("account_id",UUID::class.java),r.getObject("principal_id",UUID::class.java),r.getObject("artifact_id",UUID::class.java),r.getString("bucket"),r.getString("object_key"),r.getString("key_id"),key,nonce,bytes,r.getString("plaintext_sha256"),r.getInt("plaintext_bytes"),r.getString("cipher_sha256"),r.getInt("cipher_bytes"),r.getObject("expires_at",OffsetDateTime::class.java).toInstant(),r.getObject("write_attempted_at")!=null,r.getObject("verified_at")!=null&&r.getObject("expired_at")==null) }
        finally {key?.fill(0);nonce.fill(0);bytes?.fill(0)}
    }
    private fun lease(j:JsonObject)=ExportLease(environment,uuid(j,"id"),uuid(j,"account_id"),uuid(j,"principal_id"),j.getValue("issuer").jsonPrimitive.content,uuid(j,"provider_subject"),uuid(j,"device_session_id"),uuid(j,"provider_session_id"),uuid(j,"lease_token"),j.getValue("lease_generation").jsonPrimitive.long,instant(j,"lease_until"),instant(j,"expires_at"),j.getValue("policy_revision").jsonPrimitive.content)
    private fun usable(a:ExportArtifact,at:Instant){if(!a.ready||!a.hasWrappedKey||at>=a.expiresAt)fail(ExportFailureCode.EXPORT_EXPIRED)}
    private fun expireJobs(c:Connection,owner:UUID){c.prepareStatement("UPDATE platform.account_export_jobs SET state='expired',version=version+1,updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE environment=? AND account_id=? AND state<>'expired' AND expires_at<=clock_timestamp()").use{s->s.setString(1,environment);s.setObject(2,owner);s.executeUpdate()}}
    private fun validate(op:String,r:StoredReply){val b=r.body?.toString()?.encodeToByteArray()?:fail(ExportFailureCode.STORAGE_UNAVAILABLE);if(b.size>policy.maxResponseBytes||validator.validateResponse(op,r.status,b,"application/json")!=BodyValidationResult.Valid)fail(ExportFailureCode.STORAGE_UNAVAILABLE)}
    private fun fail(code:ExportFailureCode):Nothing=throw ExportFailure(code)
    private inline fun<T> safe(block:()->T):T=try{block()}catch(e:ExportFailure){throw e}catch(e:ExportWorkFailure){throw e}catch(e:AccountFailure){throw e}catch(e:CommitOutcomeUnknown){throw e}catch(e:CancellationException){throw e}catch(e:InterruptedException){Thread.currentThread().interrupt();throw e}catch(_:Exception){fail(ExportFailureCode.STORAGE_UNAVAILABLE)}
    override fun toString()="AccountExportStore(<redacted>)"
    private companion object {
        fun parse(value:String)=Json.parseToJsonElement(value).jsonObject
        fun uuid(j:JsonObject,k:String)=UUID.fromString(j.getValue(k).jsonPrimitive.content)
        fun instant(j:JsonObject,k:String)=OffsetDateTime.parse(j.getValue(k).jsonPrimitive.content).toInstant()
        fun time(value:Instant)=OffsetDateTime.ofInstant(value,ZoneOffset.UTC)
        fun now(c:Connection):Instant=c.createStatement().use{s->s.executeQuery("SELECT clock_timestamp()").use{check(it.next());it.getObject(1,OffsetDateTime::class.java).toInstant()}}
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
