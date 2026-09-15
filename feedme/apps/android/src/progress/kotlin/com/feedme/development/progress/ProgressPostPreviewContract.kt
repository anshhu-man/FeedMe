package com.feedme.development.progress

/** Data for the explicitly labelled, self-only synthetic preview. Never an identity credential. */
internal object ProgressPostPreviewContract {
    const val authorId = "00000000-0000-4000-8000-000000000201"
    const val avatarId = "00000000-0000-4000-8000-000000000202"
    const val displayName = "Synthetic preview cook"
    const val handle = "preview_cook"
    const val disclosureVersion = "progress-self-only-v1"
    const val disclosureText = "This is a self-only synthetic preview stored on this device. Nothing is shared with other people. Recipe saves are unavailable."
    const val maxRecordBytes = 1_000_000
    const val maxRoots = 8
    const val maxCommands = 16
    const val maxRequestBytes = 16_384
    const val maxResponseBytes = 8_192
    const val maxPageBytes = 65_536
    const val lifetimeMillis = 86_400_000L
    const val cursorLifetimeMillis = 300_000L
    val operations = setOf("createPostDraft", "getPostDraft", "listPostDrafts", "updatePostDraft", "deletePostDraft", "publishPost")
    val commands = operations - setOf("getPostDraft", "listPostDrafts")
}
