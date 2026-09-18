package com.feedme.server

import com.feedme.server.config.ServerStartupConfig
import com.feedme.server.contract.ContractCatalog
import com.feedme.server.http.feedMeLocalService
import com.feedme.server.http.createRuntimeHttpDiagnostics
import com.feedme.server.runtime.ServiceHealthMode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.ApplicationStopped

fun main() {
    val environment = System.getenv()
    if (environment.keys.any { it.startsWith("FEEDME_ACCOUNT_") }) {
        runAccountCore(environment)
        return
    }
    val startup = ServerStartupConfig.fromEnvironment(environment)
    val catalog = ContractCatalog.bundled() // Validate before opening a listening socket.
    val listener = if (startup.isContainer) "container" else "local"
    println("FeedMe unconfigured $listener listener: production readiness is unavailable; product operations are unavailable.")
    // One process-owned writer, not one per module invocation. Startup println above is
    // still synchronous; this owner removes backpressure only from request observations.
    createRuntimeHttpDiagnostics().use { diagnostics ->
        embeddedServer(CIO, port = startup.port, host = startup.host) {
            feedMeLocalService(startup.service, catalog,
                healthMode = if (startup.isContainer) ServiceHealthMode.UNCONFIGURED_READINESS else ServiceHealthMode.LOCAL_LIVENESS,
                observationSink = diagnostics)
            monitor.subscribe(ApplicationStopped) { diagnostics.close() }
        }.start(wait = true)
    } // Also retires the writer when construction/bind/start fails before ApplicationStopped.
}
