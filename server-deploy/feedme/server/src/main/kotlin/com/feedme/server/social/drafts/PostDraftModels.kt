package com.feedme.server.social.drafts

/** Explicit service bounds, not implicit publication consent or an account/draft quota. */
class PostDraftServicePolicy(val maxResponseBytes: Int, val draftLifetimeSeconds: Int, val cursorLifetimeSeconds: Int) {
    init {
        require(maxResponseBytes in 1..262144)
        require(draftLifetimeSeconds in 1..2_592_000 && cursorLifetimeSeconds in 1..86400)
    }
    override fun toString() = "PostDraftServicePolicy(<redacted>)"
}

enum class PostDraftFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), FORBIDDEN(403), DRAFT_UNAVAILABLE(404),
    DRAFT_CONFLICT(409), DRAFT_EXPIRED(410), VERSION_CONFLICT(412), MEDIA_UNAVAILABLE(409),
    CURSOR_INVALID(409), CURSOR_EXPIRED(410), RESPONSE_TOO_LARGE(422),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503),
}

/** Finite public failure. Never retain SQL, caption, source content, credentials or provider errors. */
class PostDraftFailure(val code: PostDraftFailureCode) : RuntimeException("Post draft operation unavailable: ${code.name}")
