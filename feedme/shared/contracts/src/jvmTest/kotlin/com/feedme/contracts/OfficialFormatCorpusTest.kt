package com.feedme.contracts

import kotlinx.serialization.json.*
import kotlin.test.*

/** Golden expectations are pinned upstream/RFC/IERS data, not produced by either validator. */
class OfficialFormatCorpusTest {
    @Test fun pinnedOfficialAndStandardsCasesPassThroughTheSchemaEvaluator() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/format-corpus.json")).use { it.readBytes() }
        val corpus = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("f6fd52a0a95472e079cbfc6ef7f089702b80e045",
            corpus.getValue("upstream").jsonObject.getValue("revision").jsonPrimitive.content)
        val cases = corpus.getValue("cases").jsonArray
        assertEquals(167, cases.size)
        assertEquals(167, cases.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet().size)
        val compiler = CanonicalSchemaProgram { error("Golden format schema has no reference") }
        val rules = listOf("uuid", "uri", "date-time").associateWith { format ->
            compiler.compile(buildJsonObject { put("format", format) })
        }
        for (entry in cases) {
            val case = entry.jsonObject
            val format = case.getValue("format").jsonPrimitive.content
            val value = case.getValue("data")
            assertEquals(case.getValue("valid").jsonPrimitive.boolean,
                rules.getValue(format).accepts(value, ValidationBudget()), case.getValue("id").jsonPrimitive.content)
        }
    }
}
