package com.feedme.server.http

import com.feedme.server.social.drafts.PostDraftStore
import kotlinx.coroutines.CoroutineDispatcher

/** Five owner-only canonical draft operations. Shares the mandatory ACCOUNT/device verifier,
 * not the circle/public-invitation exception. No default activation, provider or publication. */
class PostDraftHttpConfiguration(
    val environment: String,
    val store: PostDraftStore,
    val verifier: SocialHttpVerifier,
    val databaseDispatcher: CoroutineDispatcher,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(store.environment == environment)
    }
    override fun toString() = "PostDraftHttpConfiguration(<redacted>)"
    companion object { const val MAX_REQUEST_BYTES = 65_536 }
}
