package com.feedme.server.http

import com.feedme.server.social.posts.PostPublicationStore
import kotlinx.coroutines.CoroutineDispatcher

/** Explicit publication composition. No provider, public-invite exception or default activation. */
class PostPublicationHttpConfiguration(
    val environment: String,
    val store: PostPublicationStore,
    val verifier: SocialHttpVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(store.environment == environment)
    }
    override fun toString() = "PostPublicationHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 65_536 }
}
