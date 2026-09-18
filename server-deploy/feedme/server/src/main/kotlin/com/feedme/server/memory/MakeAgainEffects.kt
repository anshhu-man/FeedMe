package com.feedme.server.memory

import com.feedme.server.cooking.VerifiedCookingPrincipal
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/** Mandatory explicit composition, not copy/feedback/identity authority. The actual guest
 * owner supplies a per-attempt implementation bound to its SAME connection, actor, thread,
 * transaction and original command. It must persist/reconcile its real child effects in
 * that transaction and retain their rejecting final checks until the outer commit.
 * Implementations must not start/commit transactions, replace the original input, infer a
 * signal from an ordinary Save, or publish social activity. There is no accepting default.
 * Only canonical markMakeAgain=true reaches these methods. `created` means this Save made
 * the exact new saved-copy generation; an existing authorized copy is not newly owned. */
internal interface SavedRecipeMakeAgainEffect {
    fun applied(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
        input: JsonObject, saved: JsonObject, generation: Long, created: Boolean)
    fun replayed(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
        input: JsonObject, saved: JsonObject, generation: Long)
}

/** Called only for explicit canonical makeAgain=true, after the real completed session and
 * its private completion event exist (or the exact successful replay has been verified).
 * The actual owner must compose durable saving and explicit feedback using their original
 * child identities and retain all final checks; this interface itself grants none of them.
 * No effect is invoked for ordinary completion. Canonical response shape is unchanged. */
internal interface CookingMakeAgainEffect {
    fun applied(connection: Connection, actor: VerifiedCookingPrincipal, key: UUID,
        input: JsonObject, session: JsonObject)
    fun replayed(connection: Connection, actor: VerifiedCookingPrincipal, key: UUID,
        input: JsonObject, session: JsonObject)
}
