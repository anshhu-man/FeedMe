package com.feedme.server.cooking

import com.feedme.server.planning.CookingPlanSnapshot
import com.feedme.server.planning.CookingPlanUse
import java.sql.Connection
import java.util.UUID

/** Internal same-transaction domain bridge, never identity verification. The guest owner
 * binds its actual Plan verifier to the exact invocation/connection/actor/thread/transaction
 * and revalidates that source after final guest authority. No supplied snapshot or default
 * reader grants access. Existing public cooking construction still uses the real PlansStore.
 */
internal fun interface CookingPlanReader {
    fun lock(connection: Connection, actor: VerifiedCookingPrincipal, planId: UUID,
        use: CookingPlanUse, sessionId: UUID?): CookingPlanSnapshot
}
