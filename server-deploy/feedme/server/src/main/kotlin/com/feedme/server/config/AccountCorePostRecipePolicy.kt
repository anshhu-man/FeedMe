package com.feedme.server.config

/** Explicit optional composition only; no storage rights or provider deployment claim. */
internal class AccountCorePostRecipePolicy(val makeMineEnabled: Boolean, val saveEnabled: Boolean,
    val disclosureVersion: String) {
    init {
        require(makeMineEnabled || saveEnabled)
        require(disclosureVersion.isNotBlank() && disclosureVersion.length <= 128 && disclosureVersion.none(Char::isISOControl))
    }
    override fun toString() = "AccountCorePostRecipePolicy(<redacted>)"
}
