package com.feedme.server.social.posts

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Non-mutating feature admission, not a grant installer or full schema attestation.
 * PlatformMigrations owns exact schema/history inspection. These probes read no domain rows
 * and prove the actual serving connection can execute the reader's statements without RLS
 * silently producing an empty feed. Current canonical feedme_api grants intentionally fail;
 * this does not authorize broadening them or using an owner credential in production. */
internal object PostReadServingCompatibility {
    fun check(connection: Connection) {
        try {
            require(!connection.autoCommit &&
                connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for ((table, lock) in dependencies) {
                    statement.executeQuery("SELECT * FROM $table WHERE false" + if (lock) " FOR SHARE NOWAIT" else "").use {
                        check(!it.next())
                    }
                }
            }
            // Required even when the current feed is empty. Missing moderation authority
            // is a configuration failure, not successful admission followed by empty data.
            PostgresPostReadContentAuthority.lockModeration(connection)
            com.feedme.server.media.processing.SupabaseMediaReadiness.checkCompatibility(connection)
        } catch (failure: PostReadFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw PostReadFailure(PostReadFailureCode.NOT_CONFIGURED) }
    }

    private val dependencies = listOf(
        "identity.users" to true, "identity.principals" to true, "profile.profiles" to true,
        "social.circles" to true, "social.circle_members" to false,
        "social.blocks" to false, "social.block_pairs" to false,
        "social.posts" to true, "social.post_publications" to true,
        "social.post_audiences" to true, "social.post_media" to true,
        "social.post_attachments" to true, "social.recipe_save_policies" to true,
        "safety.moderation_cases" to false,
        "platform.media_assets" to true, "platform.media_processing_jobs" to true, "platform.media_derivative_intents" to true,
        "platform.media_safety_records" to true, "platform.media_safety_revocations" to false,
        "platform.media_private_materializations" to false, "platform.media_digest_readiness" to true,
    )
}
