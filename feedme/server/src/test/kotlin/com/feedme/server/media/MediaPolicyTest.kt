package com.feedme.server.media

import java.time.Instant
import java.util.UUID
import org.junit.Test
import kotlin.test.*

class MediaPolicyTest {
    @Test fun verifiedAccountIsExplicitAndHasRedactedIdentity() {
        val actor=VerifiedMediaAccount("test",UUID.randomUUID(),UUID.randomUUID())
        assertFalse(actor.toString().contains(actor.accountId.toString()))
        assertFailsWith<IllegalArgumentException>{VerifiedMediaAccount("UPPER",actor.accountId,actor.deviceSessionId)}
    }
    @Test fun sourceByteCeilingIsDecimalTenMillionAndNeverAnImplicitDefault() {
        assertEquals(10_000_000L,policy(bytes=10_000_000).maxSourceBytes)
        for(size in listOf(0L,10_000_001L,Long.MAX_VALUE))assertFailsWith<IllegalArgumentException>{policy(bytes=size)}
    }
    @Test fun supportedFormatsMustBeExplicitCanonicalPhotos() {
        for(types in listOf(emptySet(),setOf("video/mp4"),setOf("image/gif")))assertFailsWith<IllegalArgumentException>{policy(types=types)}
        assertEquals(setOf("image/heic"),policy(types=setOf("image/heic")).supportedContentTypes)
    }
    @Test fun expiryPolicyCannotExtendCapabilityBeyondReservation() {
        for(pair in listOf(0 to 1,86401 to 1,10 to 0,10 to 11))assertFailsWith<IllegalArgumentException>{policy(ttl=pair.first,capTtl=pair.second)}
        assertEquals(1,policy(ttl=1,capTtl=1).capabilityLifetimeSeconds)
    }
    @Test fun capabilityResponseAndObjectVersionBudgetsAreExplicitAndBounded() {
        for(cap in listOf(0,65537))assertFailsWith<IllegalArgumentException>{policy(cap=cap)}
        for(response in listOf(0,262145))assertFailsWith<IllegalArgumentException>{policy(response=response)}
        for(version in listOf(0,4097))assertFailsWith<IllegalArgumentException>{policy(version=version)}
    }
    @Test fun uploadOriginsRequireExactHttpsAuthorityWithoutPathQueryOrCredentials() {
        for(origin in listOf("http://upload.invalid","https://user:password@upload.invalid","https://upload.invalid/path","https://upload.invalid?x=1","https://upload.invalid#x"))
            assertFailsWith<IllegalArgumentException>{policy(origins=setOf(origin))}
        assertFailsWith<IllegalArgumentException>{policy(origins=emptySet())}
        assertEquals(setOf("https://upload.invalid:8443"),policy(origins=setOf("https://upload.invalid:8443")).uploadOrigins)
    }
    @Test fun configurationSetsAreDefensivelyPinned() {
        val formats=mutableSetOf("image/png");val origins=mutableSetOf("https://upload.invalid")
        val p=policy(types=formats,origins=origins);formats.clear();origins.clear()
        assertEquals(setOf("image/png"),p.supportedContentTypes);assertEquals(setOf("https://upload.invalid"),p.uploadOrigins)
    }
    @Test fun capabilityFieldsAreDetachedAndDiagnosticsNeverRevealBearer() {
        val fields=mutableMapOf("token" to "sensitive-upload-value")
        val cap=MediaUploadCapability("https://upload.invalid",fields,Instant.now());fields["token"]="changed"
        assertEquals("sensitive-upload-value",cap.fields["token"]);assertFalse(cap.toString().contains("sensitive"))
    }
    @Test fun objectEvidenceAndSigningConstraintsHaveRedactedDiagnostics() {
        val id=UUID.randomUUID();val secret="secret-quarantine-key"
        val values=listOf(MediaUploadAuthorization("test",id,id,id,1,secret,"image/png",10,"a".repeat(64),Instant.now()),
            MediaObjectVerificationRequest("test",id,id,secret,"version-secret",10,"image/png","a".repeat(64)),
            VerifiedMediaObject(secret,"version-secret",10,"image/png","a".repeat(64)))
        values.forEach{assertFalse(it.toString().contains(secret));assertFalse(it.toString().contains(id.toString()))}
    }
    @Test fun failuresHaveOnlyTypedSafeCodesAndNeverAdapterMessages() {
        MediaFailureCode.entries.forEach{assertEquals("Media operation unavailable: ${it.name}",MediaFailure(it).message)}
    }
    private fun policy(bytes:Long=1000,types:Set<String> = setOf("image/png"),ttl:Int=60,capTtl:Int=30,
        cap:Int=4096,response:Int=65536,version:Int=512,origins:Set<String> = setOf("https://upload.invalid"))=
        MediaServicePolicy(bytes,types,ttl,capTtl,cap,response,version,origins)
}
