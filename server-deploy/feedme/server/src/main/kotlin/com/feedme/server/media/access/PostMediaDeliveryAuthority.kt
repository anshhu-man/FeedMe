package com.feedme.server.media.access

import com.feedme.server.social.posts.PostgresPostReadContentAuthority
import java.sql.Connection
import java.util.UUID

/** Rechecks revocation when a short-lived byte capability is opened, not just when
 * issued. Does not broaden the original viewer grant or read any private content.
 * A delivery admitted before removal can be in flight; already delivered bytes
 * cannot be recalled. The lock order matches ordinary post reads. */
internal object PostMediaDeliveryAuthority {
    fun requireCurrent(c: Connection, environment: String, ownerId: UUID, postId: UUID, postVersion: Long) {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        c.prepareStatement("SELECT version,status FROM social.posts WHERE environment=? AND owner_user_id=? AND id=? FOR SHARE NOWAIT").use { s ->
            s.setString(1, environment); s.setObject(2, ownerId); s.setObject(3, postId)
            s.executeQuery().use { r ->
                if (!r.next() || r.getLong(1) != postVersion || r.getString(2) != "published" || r.next()) unavailable()
            }
        }
        PostgresPostReadContentAuthority.lockModeration(c)
        c.prepareStatement("SELECT EXISTS(SELECT 1 FROM safety.moderation_cases WHERE environment=? AND action IN ('hide','remove','suspend') " +
            "AND ((target_type='post' AND target_id=?) OR (target_type='user' AND target_id=?)))").use { s ->
            s.setString(1, environment); s.setObject(2, postId); s.setObject(3, ownerId)
            s.executeQuery().use { r -> if (!r.next() || r.getBoolean(1) || r.next()) unavailable() }
        }
    }
    private fun unavailable(): Nothing = throw MediaAccessFailure(MediaAccessFailureCode.MEDIA_UNAVAILABLE)
}
