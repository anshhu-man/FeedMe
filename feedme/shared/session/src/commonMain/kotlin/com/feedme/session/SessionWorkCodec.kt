package com.feedme.session

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.StorageScope
import kotlinx.serialization.json.*

/** Bounded private effect index, never executable configuration, credentials, or domain deadlines. */
internal object SessionWorkCodec {
    const val MAX_BYTES = 32_768
    const val MAX_ENTRIES = 64
    const val MAX_LOGICAL_ID_BYTES = 200

    fun encode(state: SessionWorkState): PrivateBytes = checked {
        // Bound caller-supplied collections and strings before constructing a JSON document.
        if (state is SessionWorkState.Origin) validateOrigin(state)
        val root = buildJsonObject {
            put("version", 1)
            when (state) {
                SessionWorkState.Idle -> put("state", "idle")
                is SessionWorkState.Origin -> {
                    put("state", if (state.retiring) "retiring" else "active")
                    put("scope", buildJsonObject {
                        put("environment", state.scope.environment)
                        put("actorKind", state.scope.actorKind.name)
                        put("actorId", state.scope.actorId)
                    })
                    put("origin", state.origin)
                    put("entries", JsonArray(state.entries.map { entry ->
                        buildJsonObject {
                            put("id", entry.id)
                            put("kind", entry.kind.name)
                            put("logicalId", entry.logicalId)
                            put("phase", entry.phase.name)
                        }
                    }))
                }
            }
        }
        val encoded = root.toString().encodeToByteArray(throwOnInvalidSequence = true)
        try {
            if (encoded.size > MAX_BYTES) invalid()
            PrivateBytes(encoded).also { decode(it) }
        } finally { encoded.fill(0) }
    }

    fun decode(bytes: PrivateBytes): SessionWorkState = checked {
        val root = document(bytes)
        val version = root["version"] as? JsonPrimitive ?: invalid()
        if (version.isString || version.content != "1") invalid()
        when (string(root["state"])) {
            "idle" -> {
                exact(root, setOf("version", "state"))
                SessionWorkState.Idle
            }
            "active", "retiring" -> {
                exact(root, setOf("version", "state", "scope", "origin", "entries"))
                val scope = decodeScope(root["scope"])
                val origin = uuid(string(root["origin"]))
                val array = root["entries"] as? JsonArray ?: invalid()
                if (array.size > MAX_ENTRIES) invalid()
                val entries = array.map { value ->
                    val entry = value as? JsonObject ?: invalid()
                    exact(entry, setOf("id", "kind", "logicalId", "phase"))
                    SessionWorkEntry(
                        uuid(string(entry["id"])),
                        NativeWorkKind.entries.firstOrNull { it.name == string(entry["kind"]) } ?: invalid(),
                        logicalId(string(entry["logicalId"])),
                        NativeWorkPhase.entries.firstOrNull { it.name == string(entry["phase"]) } ?: invalid(),
                    )
                }
                SessionWorkState.Origin(scope, origin, string(root["state"]) == "retiring", entries).also(::validateOrigin)
            }
            else -> invalid()
        }
    }

    private fun document(bytes: PrivateBytes): JsonObject {
        val copy = bytes.copyForCodec()
        val document = try { WireDocument.decode(copy, WireLimits(maxBytes = MAX_BYTES, maxDepth = 8)) }
        finally { copy.fill(0) }
        val encoded = document.encodeUtf8()
        return try {
            Json.parseToJsonElement(encoded.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: invalid()
        } finally { encoded.fill(0) }
    }

    private fun decodeScope(value: JsonElement?): StorageScope {
        val scope = value as? JsonObject ?: invalid()
        exact(scope, setOf("environment", "actorKind", "actorId"))
        val kind = ActorKind.entries.firstOrNull { it.name == string(scope["actorKind"]) && it != ActorKind.DEMO } ?: invalid()
        return StorageScope(string(scope["environment"]), kind, string(scope["actorId"]))
    }

    private fun validateOrigin(state: SessionWorkState.Origin) {
        if (state.scope.actorKind == ActorKind.DEMO || state.entries.size > MAX_ENTRIES) invalid()
        uuid(state.origin)
        val ids = mutableSetOf<String>()
        val logicalIds = mutableSetOf<Pair<NativeWorkKind, String>>()
        for (entry in state.entries) {
            uuid(entry.id)
            logicalId(entry.logicalId)
            if (!ids.add(entry.id) || !logicalIds.add(entry.kind to entry.logicalId)) invalid()
        }
    }

    private fun uuid(value: String): String {
        if (value.length != 36) invalid()
        return requireCredentialUuid(value)
    }

    private fun logicalId(value: String): String {
        if (value.length !in 1..MAX_LOGICAL_ID_BYTES || value.isBlank() || value.any(Char::isISOControl)) invalid()
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        try { if (bytes.size > MAX_LOGICAL_ID_BYTES) invalid() }
        finally { bytes.fill(0) }
        return value
    }

    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun exact(value: JsonObject, keys: Set<String>) { if (value.keys != keys) invalid() }
    private inline fun <T> checked(action: () -> T): T = try { action() }
    catch (_: Exception) { invalid() }
    private fun invalid(): Nothing = throw SessionWorkFormatException()
}
