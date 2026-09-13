package com.feedme.sync

import com.feedme.core.ports.StorageScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-lifetime coordination only, NOT durable state or a server lock. Prevents overlapping queue
 * instances from recovering an active local send. Entries live only from claim until finally; a
 * process restart clears this registry so durable IN_FLIGHT records can be recovered. The native
 * database factory independently prevents competing writer processes from opening the same file.
 */
internal object ProcessCommandClaims {
    private data class Key(val scope: StorageScope, val commandId: String)
    private val mutex = Mutex()
    private val active = mutableSetOf<Key>()
    suspend fun acquire(scope: StorageScope, commandId: String): Boolean = mutex.withLock { active.add(Key(scope, commandId)) }
    suspend fun isActive(scope: StorageScope, commandId: String): Boolean = mutex.withLock { Key(scope, commandId) in active }
    suspend fun release(scope: StorageScope, commandId: String) = withContext(NonCancellable) {
        mutex.withLock { active.remove(Key(scope, commandId)); Unit }
    }
}
