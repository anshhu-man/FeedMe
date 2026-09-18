package com.feedme.server.config

/** No production switch exists in this scaffold. Provider approval needs real adapters and gates. */
class LocalServerConfig private constructor(val port: Int, val minimumAppVersion: String,
    val maximumInFlightRequests: Int, val hasExplicitRequestLimit: Boolean) {
    val host: String = "127.0.0.1"

    companion object {
        private val supported = setOf(
            "FEEDME_SERVER_MODE", "FEEDME_SERVER_HOST", "FEEDME_SERVER_PORT", "FEEDME_MINIMUM_APP_VERSION",
            "FEEDME_SERVER_MAX_IN_FLIGHT_REQUESTS",
        )
        private val version = Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}(?:-[A-Za-z0-9][A-Za-z0-9.-]{0,31})?")

        fun fromEnvironment(environment: Map<String, String>): LocalServerConfig {
            // A typo must not silently configure an unintended service. Never echo values/secrets.
            require(environment.keys.none { it.startsWith("FEEDME_SERVER_") && it !in supported }) {
                "Unsupported FeedMe server configuration key"
            }
            require((environment["FEEDME_SERVER_MODE"] ?: "local") == "local") {
                "Only local development mode is supported"
            }
            require((environment["FEEDME_SERVER_HOST"] ?: "127.0.0.1") == "127.0.0.1") {
                "FeedMe development server must bind to IPv4 loopback"
            }
            val portText = environment["FEEDME_SERVER_PORT"] ?: "8780"
            require(portText.matches(Regex("[0-9]{4,5}"))) { "Invalid FeedMe server port" }
            val port = portText.toInt()
            require(port in 1024..65535) { "FeedMe server port must be between 1024 and 65535" }
            val minimum = environment["FEEDME_MINIMUM_APP_VERSION"] ?: "0.1.0-dev"
            require(version.matches(minimum)) { "Invalid minimum app version" }
            val configuredLimit = environment["FEEDME_SERVER_MAX_IN_FLIGHT_REQUESTS"]
            // Compatibility for the unconfigured loopback scaffold/test embeddings only.
            // Container startup and the configured pantry runtime require an explicit limit.
            val limit = configuredLimit?.let {
                require(it.matches(Regex("[1-9][0-9]{0,3}"))) { "Invalid request admission limit" }
                it.toInt().also { value -> require(value in 1..4096) { "Invalid request admission limit" } }
            } ?: 32
            return LocalServerConfig(port, minimum, limit, configuredLimit != null)
        }
    }
}
