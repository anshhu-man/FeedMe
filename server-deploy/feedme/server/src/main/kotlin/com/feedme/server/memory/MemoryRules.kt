package com.feedme.server.memory

import com.feedme.contracts.CanonicalFormats
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** A pure, versioned explicit-opinion projection, not inferred hard constraints or authority.
 * The actual source owner resolves context FIRST. Whole-meal feedback is never expanded to
 * its ingredients/tastes; this function retains precisely the context it receives. */
internal object MemoryRules {
    const val VERSION = "explicit-target-v1"
    private val kinds = setOf("taste", "effort", "repeat")
    private val values = setOf("prefer", "neutral", "show_less")
    private val tasteTags = setOf("crunch", "fresh", "creamy", "heat")
    private val effortAspects = setOf("chopping", "activeCooking", "cleanup")

    fun normalizeContext(context: JsonObject): JsonObject = valid {
        require(context.keys.all { it in setOf("recipeVersionId", "ingredientId", "tasteTag", "effortAspect") })
        buildJsonObject {
            for ((key, value) in context.toSortedMap()) {
                val text = value.jsonPrimitive.let { require(it.isString); it.content }
                when (key) {
                    "recipeVersionId", "ingredientId" -> {
                        require(CanonicalFormats.accepts("uuid", text)); put(key, UUID.fromString(text).toString())
                    }
                    "tasteTag" -> { require(text in tasteTags); put(key, text) }
                    "effortAspect" -> { require(text in effortAspects); put(key, text) }
                }
            }
        }
    }

    fun validateValue(value: String): String = valid { require(value in values); value }

    /** Only changes to these exact explicit fields advance their persisted source epochs.
     * Notes, feedback revision, timestamps and unrelated actions are intentionally absent. */
    fun derive(snapshot: JsonObject, context: JsonObject, epochs: Map<String, Long>): List<MemoryContribution> = valid {
        val normalized = normalizeContext(context)
        require(epochs.keys.all { it in setOf("taste", "effort", "makeAgain") })
        require(epochs.values.all { it >= 0 })
        buildList {
            for ((signal, kind) in listOf("taste" to "taste", "effort" to "effort", "makeAgain" to "repeat")) {
                val raw = snapshot[signal] ?: continue
                val value = when (signal) {
                    "taste" -> when (raw.jsonPrimitive.let { require(it.isString); it.content }) {
                        "loved" -> "prefer"; "okay" -> "neutral"; "notForMe" -> "show_less"; else -> error("Unsupported signal")
                    }
                    "effort" -> when (raw.jsonPrimitive.let { require(it.isString); it.content }) {
                        "easy" -> "prefer"; "manageable" -> "neutral"; "tooMuch" -> "show_less"; else -> error("Unsupported signal")
                    }
                    else -> { require(!raw.jsonPrimitive.isString)
                        when (raw.jsonPrimitive.booleanOrNull) { true -> "prefer"; false -> "show_less"; null -> error("Unsupported signal") } }
                }
                val epoch = epochs[signal] ?: error("Missing source epoch")
                require(epoch > 0)
                add(MemoryContribution(kind, semanticKey(kind, normalized), normalized, signal, epoch, value,
                    label(kind, value, normalized)))
            }
        }
    }

    /** Stable across rule versions, note edits, memory reincarnations and feedback versions. */
    fun semanticKey(kind: String, context: JsonObject): String = valid {
        require(kind in kinds)
        feedbackSha("feedme.memory.semantic.v1\u0000$kind\u0000${feedbackCanonical(normalizeContext(context))}")
    }
    fun fingerprint(feedbackId: UUID, contribution: MemoryContribution): String =
        feedbackSha("feedme.memory.contribution.v1\u0000${contribution.semanticKey}\u0000$feedbackId\u0000" +
            "${contribution.signalKey}\u0000${contribution.epoch}")

    /** Human-readable effect only. Context is carried separately; never interpolate a note,
     * ingredient title, medical inference or alleged current source availability. */
    fun label(kind: String, value: String, context: JsonObject): String = valid {
        require(kind in kinds); validateValue(value); normalizeContext(context)
        when (kind) {
            "taste" -> when (value) { "prefer" -> "You liked this taste"; "neutral" -> "This taste was okay"; else -> "You preferred this taste less" }
            "effort" -> when (value) { "prefer" -> "This felt easy"; "neutral" -> "This effort was manageable"; else -> "This felt like too much work" }
            else -> when (value) { "prefer" -> "You would make this again"; "neutral" -> "You have no repeat preference"; else -> "You would rather not repeat this" }
        }
    }

    private fun <T> valid(action: () -> T): T = try { action() }
        catch (failure: MemoryFailure) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { throw MemoryFailure(MemoryFailureCode.INPUT_INVALID) }
}

internal class MemoryContribution internal constructor(val kind: String, val semanticKey: String,
    val context: JsonObject, val signalKey: String, val epoch: Long, val value: String, val label: String) {
    override fun toString() = "MemoryContribution(<redacted>)"
}
