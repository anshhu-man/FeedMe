package com.feedme.server.social.posts

import java.sql.Connection
import java.util.UUID

/** Same-connection terminal-root/attachment fences, not a public permission or provider adapter.
 * Caller already holds the shared principal and exact lifecycle root. Never infer absence from
 * SQL errors. Publication identities remain after receipt compaction or post deletion. */
object PostPublicationLifecycle {
    fun isPublished(connection: Connection, environment: String, ownerId: UUID, clientDraftId: UUID): Boolean {
        check(!connection.autoCommit)
        return connection.prepareStatement("SELECT 1 FROM social.post_publications WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR SHARE").use {
            it.setString(1,environment);it.setObject(2,ownerId);it.setObject(3,clientDraftId)
            it.executeQuery().use { r -> r.next() }
        }
    }
    fun isAttached(connection: Connection, environment: String, ownerId: UUID, mediaId: UUID): Boolean {
        check(!connection.autoCommit)
        return connection.prepareStatement("SELECT 1 FROM social.post_media WHERE environment=? AND owner_user_id=? AND media_id=? FOR SHARE").use {
            it.setString(1,environment);it.setObject(2,ownerId);it.setObject(3,mediaId)
            it.executeQuery().use { r -> r.next() }
        }
    }
}
