package com.feedme.server

import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.ContractCatalog
import com.feedme.server.http.feedMeLocalService
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

fun main() {
    val config = LocalServerConfig.fromEnvironment(System.getenv())
    val catalog = ContractCatalog.bundled() // Validate before opening a listening socket.
    println("FeedMe local-only scaffold: health is degraded; 200 product operations are unavailable.")
    embeddedServer(CIO, port = config.port, host = config.host) {
        feedMeLocalService(config, catalog)
    }.start(wait = true)
}
