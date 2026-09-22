package com.feedme.server.config

import com.feedme.server.social.drafts.PostDraftCursors
import com.feedme.server.social.drafts.PostDraftServicePolicy
import com.feedme.server.social.posts.AccountPostAdmissionPolicy
import com.feedme.server.social.posts.PostPublicationPolicy

/** Explicit operational composition, not content permission or deployment approval.
 * Draft/upload/publication use the same actual account and MediaStore lifetime. */
internal class AccountPostAuthoringRuntimePolicy(
    val admission: AccountPostAdmissionPolicy,
    val drafts: PostDraftServicePolicy,
    val publication: PostPublicationPolicy,
    val cursors: PostDraftCursors,
) {
    override fun toString() = "AccountPostAuthoringRuntimePolicy(<redacted>)"
}
