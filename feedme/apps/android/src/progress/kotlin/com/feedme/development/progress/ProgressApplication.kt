package com.feedme.development.progress

import android.app.Application
import com.feedme.core.ports.PortResult
import kotlinx.coroutines.*

/** Real application lifetime, isolated to the progress UID. No instrumentation global or I/O
 * in construction. Android does not promise onTerminate; correctness never depends on it. */
class ProgressApplication : Application() {
    internal lateinit var owner: ProgressSessionOwner
        private set
    private val operations = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        check(packageName == ProgressNativeInventory.PACKAGE)
        owner = ProgressSessionOwner(this)
    }

    /** Activity recreation does not cancel an admitted application-lifetime native open/close. */
    internal fun run(action: suspend () -> PortResult<Unit>, completed: (PortResult<Unit>) -> Unit = {}) {
        operations.launch { completed(action()) }
    }
}
