package com.feedme.server.media.processing

import java.time.Instant
import java.util.UUID
import org.junit.Test
import kotlin.test.*

class MediaProcessingModelsTest {
    @Test fun policiesHaveNoPermissiveDefaultsAndRetainExplicitBounds() {
        assertEquals(10_000_000,policy(source=10_000_000).maxSourceBytes)
        for(n in listOf(0,10_000_001,Int.MAX_VALUE))assertFailsWith<IllegalArgumentException>{policy(source=n)}
        for(n in listOf(0,301))assertFailsWith<IllegalArgumentException>{policy(lease=n)}
        for(n in listOf(0,21))assertFailsWith<IllegalArgumentException>{policy(attempts=n)}
    }
    @Test fun leaseWriteAndManifestBoundsCannotHideUnboundedProviderWork() {
        for(n in listOf(0,301))assertFailsWith<IllegalArgumentException>{policy(acceptance=n)}
        for(n in listOf(0,65537))assertFailsWith<IllegalArgumentException>{policy(manifest=n)}
        assertFailsWith<IllegalArgumentException>{policy(revision="unsafe\npolicy")}
    }
    @Test fun encodedBytesAndDecoderVariantListAreDefensivelyDetached() {
        val input=byteArrayOf(1,2);val output=EncodedPhotoVariant(PhotoVariant.DISPLAY,"image/png",1,1,input)
        input[0]=9;output.copyBytes()[0]=8;assertContentEquals(byteArrayOf(1,2),output.copyBytes());assertEquals(2,output.byteCount)
        val list=mutableListOf(output);val decoded=PhotoDecodeResult.Decoded(list);list.clear();assertEquals(1,decoded.variants.size)
        assertFalse(output.toString().contains("image/png"))
    }
    @Test fun unavailableCodecReasonsAreDistinctFromContentRejection() {
        for(reason in PhotoCodecUnavailable.entries)assertIs<PhotoDecodeResult.Unavailable>(PhotoDecodeResult.Unavailable(reason))
        assertEquals(setOf("MALFORMED_IMAGE","UNSUPPORTED_FORMAT","IMAGE_LIMIT_EXCEEDED"),PhotoRejection.entries.map{it.name}.toSet())
    }
    @Test fun safetyEvidencePinsBothVariantsAndExactBoundedTimeAndHashes() {
        val now=Instant.now();val hashes=mutableMapOf(PhotoVariant.DISPLAY to "b".repeat(64),PhotoVariant.THUMBNAIL to "c".repeat(64))
        val proof=MediaSafetyEvidence("receipt","revision","a".repeat(64),hashes,now,now.plusSeconds(1));hashes.clear()
        assertEquals(2,proof.derivativeSha256.size)
        assertFailsWith<IllegalArgumentException>{MediaSafetyEvidence("receipt","revision","a".repeat(64),emptyMap(),now,now.plusSeconds(1))}
        assertFailsWith<IllegalArgumentException>{MediaSafetyEvidence("receipt","revision","a".repeat(64),proof.derivativeSha256,now,now)}
    }
    @Test fun diagnosticsRedactPrivateOwnerObjectsReceiptsAndSafetyIdentity() {
        val id=UUID.randomUUID();val key="private-object-key";val now=Instant.now()
        val values=listOf(MediaProcessingOwner("test",id),MediaDerivativeReceipt(key,"private-version","a".repeat(64),1,"image/png"),
            MediaSafetyEvidence("private_receipt","revision","a".repeat(64),PhotoVariant.entries.associateWith{"b".repeat(64)},now,now.plusSeconds(1)),
            SettledMediaVersions(key,listOf("private-version")))
        for(v in values)for(secret in listOf(id.toString(),key,"private-version","private_receipt"))assertFalse(v.toString().contains(secret))
    }
    @Test fun internalFailuresExposeOnlyFiniteSafeCodes() {
        for(code in MediaProcessingFailureCode.entries)assertEquals("Media processing unavailable: ${code.name}",MediaProcessingFailure(code).message)
    }
    @Test fun settledVersionInventoryAndAssessmentHashesDetachCallerCollections() {
        val versions=mutableListOf("v1");val settled=SettledMediaVersions("private-key",versions);versions.clear();assertEquals(listOf("v1"),settled.versionIds)
        assertNotEquals<MediaSafetyResult>(MediaSafetyResult.Pending,MediaSafetyResult.Unavailable)
    }
    private fun policy(source:Int=1000,lease:Int=30,attempts:Int=5,acceptance:Int=20,manifest:Int=65536,revision:String="p1")=
        MediaProcessingPolicy(revision,"codec1",lease,attempts,1,acceptance,source,1000,2000,100,512,20,manifest)
}
