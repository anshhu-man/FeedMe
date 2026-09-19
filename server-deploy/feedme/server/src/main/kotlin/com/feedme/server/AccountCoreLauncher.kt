package com.feedme.server

import com.feedme.server.config.AccountCoreRuntimeConfig
import com.feedme.server.config.DependencyHoldConfig
import com.feedme.server.runtime.AccountCoreRuntime
import com.feedme.server.runtime.DependencyHoldStartupStage
import kotlinx.coroutines.CancellationException
import kotlin.system.exitProcess

/** Called by the packaged Main only when the explicit FEEDME_ACCOUNT_* settings are
 * present. Incomplete/conflicting configuration fails; never fall back to local liveness.
 * The environment is supplied once by Main, not queried by individual routes/stores. */
internal fun runAccountCore(environment: Map<String, String>) {
    val held = DependencyHoldConfig.CONFIG in environment
    var heldStage = DependencyHoldStartupStage.CONFIGURATION
    try {
        val runtime = if (held) AccountCoreRuntime.startHeld(DependencyHoldConfig.fromEnvironment(environment),
            onStartupStage = { heldStage = it })
            else AccountCoreRuntime.start(AccountCoreRuntimeConfig.fromEnvironment(environment))
        val shutdown = Thread({
            try { runtime.close() }
            catch (_: Exception) { System.err.println("FeedMe account core shutdown incomplete; retain original command identities.") }
        }, "feedme-account-core-shutdown")
        try {
            Runtime.getRuntime().addShutdownHook(shutdown)
            println(if (held) "FeedMe dependency hold started; dependencies verified, all public operations remain closed."
                else "FeedMe account core listener started; dependency health is not whole-app release readiness.")
            runtime.awaitTermination()
        } finally {
            try { runtime.close() }
            finally { runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) } }
        }
    } catch (failure: CancellationException) { throw failure }
      catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
      catch (_: Exception) {
        if (held) System.err.println("FeedMe dependency hold startup/shutdown failed; stage=${heldStage.diagnostic}.")
        else System.err.println("FeedMe account core startup/shutdown failed; explicit valid configuration and current dependencies are required.")
        exitProcess(1)
    }
}
