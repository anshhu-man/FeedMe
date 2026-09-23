package com.feedme.app

/** Optional host chrome only, not a canonical screen action or account authority. Opening a
 * menu constructs an inert ticket. Exact rendering/callback comparison and one-use claiming
 * reject stale, replaced and reentrant clicks; the native host still checks its own route. */
internal class AccountSettingsHostAction(private val rendering: Any, private val callback: () -> Unit) {
    private var consumed = false
    fun claim(currentRendering: Any, currentCallback: (() -> Unit)?, admitted: Boolean): (() -> Unit)? {
        if (consumed || !admitted || currentRendering !== rendering || currentCallback !== callback) return null
        consumed = true
        return callback
    }
}
