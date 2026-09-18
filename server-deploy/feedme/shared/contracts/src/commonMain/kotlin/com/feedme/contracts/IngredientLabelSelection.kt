package com.feedme.contracts

/** Exact catalog-label selection only, never an ingredient availability or access grant.
 * Input spelling is validated before canonicalization; no trimming or silent deduplication. */
object IngredientLabelSelection {
    const val MAX_IDS = 50
    const val MAX_TEXT_LENGTH = 1849

    fun parse(value: String): List<String>? {
        if (value.length !in 36..MAX_TEXT_LENGTH) return null
        val parts = value.split(',')
        if (parts.size !in 1..MAX_IDS || parts.any { !CanonicalFormats.accepts("uuid", it) }) return null
        val ids = parts.map { it.lowercase() }
        if (ids.distinct().size != ids.size) return null
        return ids
    }
}
