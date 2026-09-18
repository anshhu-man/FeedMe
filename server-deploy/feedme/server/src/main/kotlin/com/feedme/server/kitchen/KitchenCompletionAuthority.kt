package com.feedme.server.kitchen

import java.sql.Connection

/** Time-sensitive authority may expire while a kernel operation waits on an owned row.
 * Recheck it in the SAME transaction after the operation, before commit or disclosure.
 * Implementations only revalidate already locked authority roots in their original order;
 * they must not authorize new selections, initialize records, commit or perform network I/O.
 * This is internal composition, never an authority capability available to an HTTP caller.
 */
internal interface KitchenCompletionAuthority {
    fun revalidatePrincipal(connection: Connection, principal: VerifiedKitchenPrincipal)
}
