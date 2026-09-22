package com.feedme.server.identity

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Continuation only; each page independently checks the real current account. */
internal class AccountSessionCursors(private val currentKeyId: String, keys: Map<String, ByteArray>) {
    private val keys = keys.mapValues { it.value.copyOf() }
    init { require(currentKeyId in this.keys && this.keys.size in 1..8 && this.keys.all { (id,key) -> id.matches(Regex("[a-z0-9_-]{1,32}")) && key.size==32 }) }
    fun encode(environment: String, account: UUID, device: UUID, limit: Int, position: Position): String {
        val data="$currentKeyId.$limit.${position.after}.${position.cutoff.epochSecond}.${position.cutoff.nano}.${position.expires.epochSecond}.${position.expires.nano}"
        return "$data.${mac(currentKeyId,environment,account,device,data)}"
    }
    fun decode(environment: String, account: UUID, device: UUID, limit: Int, cursor: String?, now: Instant): Position? {
        if(cursor==null)return null
        if(cursor.length !in 1..2048)invalid()
        val p=cursor.split('.'); if(p.size!=8 || p[0] !in keys || !p[7].matches(Regex("[A-Za-z0-9_-]{43}")))invalid()
        fun number(i:Int):Long { if(!p[i].matches(Regex("0|[1-9][0-9]{0,18}")))invalid();return p[i].toLongOrNull()?:invalid() }
        if(number(1)!=limit.toLong())invalid()
        val after=try{UUID.fromString(p[2]).also{if(it.toString()!=p[2])invalid()}}catch(_:IllegalArgumentException){invalid()}
        if(number(4)>999999999 || number(6)>999999999)invalid()
        val expected=mac(p[0],environment,account,device,p.take(7).joinToString("."))
        if(!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII),p[7].toByteArray(Charsets.US_ASCII)))invalid()
        val cutoff=try{Instant.ofEpochSecond(number(3),number(4))}catch(_:Exception){invalid()}
        val expires=try{Instant.ofEpochSecond(number(5),number(6))}catch(_:Exception){invalid()}
        if(!now.isBefore(expires))throw SessionFailure(SessionFailureCode.CURSOR_EXPIRED)
        if(cutoff.isAfter(now) || !cutoff.isBefore(expires))invalid()
        return Position(after,cutoff,expires)
    }
    private fun mac(key:String,env:String,account:UUID,device:UUID,data:String):String=Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(keys.getValue(key),"HmacSHA256"));Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal("feedme.account.sessions.v1\u0000$env\u0000$account\u0000$device\u0000$data".toByteArray(Charsets.UTF_8)))
    }
    class Position(val after:UUID,val cutoff:Instant,val expires:Instant) { override fun toString()="AccountSessionPosition(<redacted>)" }
    private fun invalid():Nothing=throw SessionFailure(SessionFailureCode.CURSOR_INVALID)
    override fun toString()="AccountSessionCursors(<redacted>)"
}
