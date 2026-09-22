package com.feedme.server.identity

internal class AccountSessionPolicy(val maxResponseBytes: Int = 65536, val cursorLifetimeSeconds: Int = 300) {
    init { require(maxResponseBytes in 4096..262144 && cursorLifetimeSeconds in 1..600) }
    override fun toString() = "AccountSessionPolicy(<redacted>)"
}

internal enum class SessionFailureCode(val status: Int) {
    INPUT_INVALID(422), SESSION_UNAVAILABLE(404), CURRENT_SESSION_UNSUPPORTED(409),
    VERSION_CONFLICT(412), CURSOR_INVALID(422), CURSOR_EXPIRED(410), NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503)
}
internal class SessionFailure(val code: SessionFailureCode) : RuntimeException("Account sessions unavailable: ${code.name}")
