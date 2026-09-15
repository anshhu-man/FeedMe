package com.feedme.server.social.posts

import com.feedme.server.social.VerifiedSocialAccount
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** REQUIRED real account/device, community terms, circle/block, source and moderation integration.
 * No accepting default. All callbacks use only the supplied transaction, never commit, perform
 * network I/O, log private material or mutate input. Every revocation/lifecycle writer shares:
 * principal -> command -> owner draft head -> client root -> publication/draft -> content/circles
 * -> UUID-ordered media. Statement/lock deadlines and interruption are actual adapter obligations.
 *
 * lockDraftLifecycle returns the same positive stable incarnation used by Media/PostDraft; absent
 * text-only roots may be explicitly reserved under the actual owner lifecycle. It is not a PostDraft.
 * forPublish=true additionally requires the current root to admit a NEW publication. false is
 * owner historical-lineage observation for replay, not renewed write/upload eligibility. Both
 * purposes lock the same actual root and return its current incarnation; never invent a generation.
 * authorizeNew validates eligible current author/profile; exact source/review/redistribution rights,
 * confirmed changes and current saveDisclosureVersion; terms and requested save permission;
 * current reciprocal blocks/membership generations. A private saved/cooking pin grants no sharing.
 * It must reject changed provenance instead of relabelling the reviewed attachment. No implicit
 * preview consent: this endpoint consumes only the client's explicit original Publish request.
 * authorizeReadyMedia rechecks current safe-to-publish/recall policy for exact retained derivatives;
 * a READY string, expired scan, test marker or provider outage alone is never a safety grant.
 * Its returned deadline bounds the actual retained safety/recall grant; the store intersects all
 * media deadlines and checks them again after later locks and before publication/replay commit.
 * authorizeReplay checks current owner/device/privacy/recall for disclosure of the ORIGINAL Post;
 * it grants no new publication, audience, media access or client action authority.
 */
interface PostPublicationAuthority {
    fun lockPrincipal(connection: Connection, actor: VerifiedSocialAccount)
    fun requirePublishEnabled(connection: Connection, actor: VerifiedSocialAccount)
    fun lockDraftLifecycle(connection: Connection, actor: VerifiedSocialAccount, clientDraftId: UUID, forPublish: Boolean): Long
    fun authorizeNew(connection: Connection, actor: VerifiedSocialAccount, selection: JsonObject): PostPublicationEvidence
    fun authorizeReadyMedia(connection: Connection, actor: VerifiedSocialAccount, mediaId: UUID, version: Long, derivatives: JsonObject): Instant
    fun authorizeReplay(connection: Connection, actor: VerifiedSocialAccount, originalPost: JsonObject): Instant
}
