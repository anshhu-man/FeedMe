package com.feedme.server.config

/** Listener configuration only. Container binding does not configure any product service
 * or establish readiness; the existing service remains explicitly unconfigured. */
class ServerStartupConfig private constructor(
    val host: String,
    val port: Int,
    val service: LocalServerConfig,
    val isContainer: Boolean,
) {
    override fun toString() = "ServerStartupConfig(<redacted>)"

    companion object {
        fun fromEnvironment(environment: Map<String, String>): ServerStartupConfig =
            when (environment["FEEDME_SERVER_MODE"] ?: "local") {
                "local" -> {
                    // Preserve local validation and defaults. PORT alone never enables a
                    // different listener or changes the loopback development port.
                    val service = LocalServerConfig.fromEnvironment(environment)
                    ServerStartupConfig(service.host, service.port, service, false)
                }
                "container" -> {
                    // Reject conflicts before constructing the deliberately small settings
                    // map. Unknown keys must not disappear during delegation.
                    require(environment.keys.none {
                        it.startsWith("FEEDME_SERVER_") && it !in setOf("FEEDME_SERVER_MODE", "FEEDME_SERVER_MAX_IN_FLIGHT_REQUESTS")
                    }) { "Unsupported or conflicting container server configuration key" }
                    val portText = requireNotNull(environment["PORT"]) {
                        "Container server port is required"
                    }
                    require(portText.matches(Regex("[1-9][0-9]{3,4}"))) {
                        "Invalid container server port"
                    }
                    val port = portText.toInt()
                    require(port in 1024..65535) {
                        "Container server port must be between 1024 and 65535"
                    }
                    val minimum = requireNotNull(environment["FEEDME_MINIMUM_APP_VERSION"]) {
                        "Container minimum app version is required"
                    }
                    val admissionLimit = requireNotNull(environment["FEEDME_SERVER_MAX_IN_FLIGHT_REQUESTS"]) {
                        "Container request admission limit is required"
                    }
                    // Reuse the existing application-setting validator without treating
                    // local listener defaults as the container listener configuration.
                    val service = LocalServerConfig.fromEnvironment(mapOf(
                        "FEEDME_MINIMUM_APP_VERSION" to minimum,
                        "FEEDME_SERVER_MAX_IN_FLIGHT_REQUESTS" to admissionLimit,
                    ))
                    ServerStartupConfig("0.0.0.0", port, service, true)
                }
                else -> throw IllegalArgumentException("Unsupported FeedMe server mode")
            }
    }
}
