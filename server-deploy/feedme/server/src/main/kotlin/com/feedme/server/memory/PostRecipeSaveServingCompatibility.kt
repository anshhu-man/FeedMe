package com.feedme.server.memory

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only optional-feature admission. No role creation, GRANT or historical installer
 * changes. Existing identity/source/catalog/receipt/outbox checks remain mandatory. */
internal object PostRecipeSaveServingCompatibility {
    fun check(c: Connection) {
        try {
            check(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            for ((version, resource) in listOf(62 to "post_recipe_saves", 63 to "post_recipe_save_erasure_inventory")) {
                val path = "/db/migration/V${version.toString().padStart(3, '0')}__$resource.sql"
                val hash = checkNotNull(javaClass.getResourceAsStream(path)).use {
                    MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
                }
                c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use {
                    it.setInt(1, version); it.executeQuery().use { r -> check(r.next() && r.getString(1) == hash && !r.next()) }
                }
            }
            c.createStatement().use { s ->
                for (table in listOf("memory.saved_recipes", "memory.save_commands", "memory.library_heads",
                    "memory.collections", "memory.collection_items", "social.recipe_save_policies", "social.post_attachments"))
                    s.executeQuery("SELECT * FROM $table WHERE false FOR SHARE NOWAIT").use { check(!it.next()) }
                s.executeQuery("SELECT count(*)=3 FROM pg_constraint WHERE conrelid='memory.saved_recipes'::regclass " +
                    "AND contype='c' AND convalidated AND conname IN ('saved_recipes_source_type_check'," +
                    "'saved_recipes_exact_source','saved_recipes_post_grant_shape')").use { check(it.next() && it.getBoolean(1) && !it.next()) }
            }
            c.prepareStatement("SELECT bool_and(has_column_privilege(current_user,'memory.saved_recipes',name,'INSERT')) " +
                "FROM unnest(string_to_array(?,',')) t(name)").use {
                it.setString(1, "environment,actor_kind,principal_id,id,generation,version,recipe_version_id,recipe_hash,source_type," +
                    "source_id,origin_plan_id,content_license,snapshot,copy_evidence,created_at,updated_at")
                it.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
            }
        } catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (_: Exception) { throw SavedRecipeFailure(SavedRecipeFailureCode.NOT_CONFIGURED) }
    }
}
