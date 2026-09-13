package com.feedme.transport

/** Trusted composition-root configuration, never constructed from recipe/post/user content. */
class ApiEndpoint private constructor(val environment: String, internal val origin: String) {
    init {
        require(environment.isNotBlank() && environment.length <= 200 && environment.none(Char::isISOControl))
    }

    override fun toString() = "ApiEndpoint(environment=$environment)"

    companion object {
        /** An origin only: no credentials, path, query, fragment or redirect-derived host. */
        fun https(environment: String, origin: String): ApiEndpoint {
            val match = Regex("https://([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::([0-9]{1,5}))?").matchEntire(origin)
            requireNotNull(match) { "Expected a trusted HTTPS origin" }
            require(match.groupValues[1].length <= 253 && match.groupValues[1].split('.').all {
                it.length in 1..63 && !it.startsWith('-') && !it.endsWith('-')
            }) { "Invalid endpoint host" }
            val port = match.groupValues[2]
            require(port.isEmpty() || port.toInt() in 1..65535) { "Invalid endpoint port" }
            return ApiEndpoint(environment, origin)
        }

        /** Explicit test/development escape hatch; HTTP is limited to literal loopback. */
        fun loopback(environment: String, port: Int): ApiEndpoint {
            require(port in 1..65535)
            return ApiEndpoint(environment, "http://127.0.0.1:$port")
        }
    }
}
