package com.feedme.development.progress

import kotlin.test.*

class ProgressInventoryNamesTest {
    @Test fun onlyBoundedPlannedCredentialCandidatesReachNativeAuthentication() {
        val target = "a".repeat(64)
        val incarnation = "00000000-0000-4000-8000-000000000001"
        for (name in listOf("$target.1.bin", "$target.9.bin.pending", "manifest.create.$incarnation.pending", "manifest.abort.$incarnation.pending"))
            assertTrue(progressCredentialCandidate(name), name)
        for (name in listOf("manifest.bin.pending", "unknown", "../$target.1.bin", "$target.0.bin", "$target.01.bin",
            "$target.12345678901234567890.bin", "$target.1.bin.pending.pending", "manifest.create.$incarnation.pending/child",
            "manifest.create.${incarnation.uppercase()}x.pending", "state.sqlite-journal"))
            assertFalse(progressCredentialCandidate(name), name)
    }
}
