package com.feedme.server.social.drafts

import com.feedme.server.social.VerifiedSocialAccount
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/**
 * REQUIRED actual account/device, terms and social-content integration. No accepting default.
 * Callbacks use only this connection, never commit, perform network I/O or emit side effects.
 * lockPrincipal takes the exclusive current account/device lifecycle lock shared with media,
 * circles and account deletion. Order: principal, command receipt, owner list head, client-draft
 * lifecycle, PostDraft, content/circle authority, media rows in UUID order. Every lifecycle writer
 * must honor the same order; do not call public stores which open a nested transaction.
 *
 * lockDraftLifecycle locks the SAME stable root as MediaAuthority and processing authority.
 * Its positive generation identifies one owner/clientDraftId incarnation, not a caption version.
 * The root may exist for uploads without a PostDraft. Media upload/worker adapters MUST call
 * PostDraftLifecycle.requireEditable under that root lock before admitting effects. The false
 * forEdit path permits owner observation/cleanup of terminal drafts, not renewed upload authority.
 *
 * requireMutationEnabled checks current community terms/account eligibility and the draft-save
 * kill switch. Observation and deletion do not depend on new posting/upload being enabled.
 * authorizeContent must verify current circle membership/block policy and exact attachment/source
 * read/redistribution eligibility. newSelection=true applies new-write rights; false retains only
 * established permissible draft viewing. It must not accept client Audience.bindings, private-copy
 * or cooking grants as redistribution authority. This is draft eligibility, NEVER publish consent,
 * image readiness, a stamped audience generation, or a publication grant.
 */
interface PostDraftAuthority {
    fun lockPrincipal(connection: Connection, actor: VerifiedSocialAccount)
    fun requireMutationEnabled(connection: Connection, actor: VerifiedSocialAccount)
    fun lockDraftLifecycle(connection: Connection, actor: VerifiedSocialAccount, clientDraftId: UUID, forEdit: Boolean): Long
    fun authorizeContent(connection: Connection, actor: VerifiedSocialAccount, content: JsonObject, newSelection: Boolean)
}

/** Mandatory adapter integration under the shared root lock; absence means no implicit server draft. */
object PostDraftLifecycle {
    fun requireEditable(connection: Connection, environment: String, ownerId: UUID, clientDraftId: UUID) {
        check(!connection.autoCommit)
        connection.prepareStatement("SELECT status,expires_at>clock_timestamp() FROM platform.post_drafts WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR SHARE").use {
            it.setString(1, environment); it.setObject(2, ownerId); it.setObject(3, clientDraftId)
            it.executeQuery().use { row ->
                if (row.next() && (row.getString(1) != "draft" || !row.getBoolean(2)))
                    throw PostDraftFailure(PostDraftFailureCode.DRAFT_CONFLICT)
            }
        }
    }
}
