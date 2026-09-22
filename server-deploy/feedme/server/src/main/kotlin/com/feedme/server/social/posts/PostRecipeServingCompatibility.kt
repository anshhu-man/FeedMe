package com.feedme.server.social.posts

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only optional-feature admission. Existing account/planning/catalog compatibility
 * remains mandatory; this installs no grants and never admits missing post authority. */
internal object PostRecipeServingCompatibility {
    fun check(c: Connection, makeMineEnabled: Boolean) {
        try {
            PostReadServingCompatibility.check(c)
            if (makeMineEnabled) {
                val resource = "/db/migration/V061__root_post_plan_storage.sql"
                val checksum = checkNotNull(javaClass.getResourceAsStream(resource)).use { input ->
                    MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                }
                c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=61").use { s ->
                    s.executeQuery().use { r -> check(r.next() && r.getString(1) == checksum && !r.next()) }
                }
                c.createStatement().use { s ->
                    s.executeQuery("SELECT evidence_text,evidence_hash,storage_format,request_text,request_hash FROM planning.plan_requests WHERE false FOR SHARE NOWAIT").close()
                    s.executeQuery("SELECT snapshot_text,snapshot_hash,proof_text,proof_hash,storage_format FROM planning.plans WHERE false FOR SHARE NOWAIT").close()
                }
            }
        } catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw PostReadFailure(PostReadFailureCode.NOT_CONFIGURED) }
    }
}
