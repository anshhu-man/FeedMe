package com.feedme.contracts

import kotlin.test.*

class CanonicalResponseBinderTest {
    private val binder = CanonicalResponseBinder()
    private fun problem(status: String = "503", trace: String = "trace", extra: String = "") =
        """{"type":"about:blank","title":"Unavailable","status":$status,"code":"UNAVAILABLE","traceId":"$trace"$extra}""".encodeToByteArray()

    @Test fun errorsBindToActualHttpStatusWithoutNumericSpellingLoss() {
        val original = problem("503.000e0")
        val bound = assertIs<ResponseBindingResult.Accepted>(binder.bind("getServiceHealth", 503, original,
            "application/problem+json", "trace")).response
        assertEquals("getServiceHealth", bound.operationId)
        assertEquals(503, bound.status)
        val document = assertIs<WireBody.Present>(bound.body).document
        assertContentEquals(original, document.encodeUtf8())
        assertFalse(bound.toString().contains("Unavailable"))
    }

    @Test fun aSchemaValidProblemWithWrongStatusDoesNotBecomeAReceipt() {
        for (status in listOf("500", "9007199254740993", "1e400")) {
            assertEquals(ResponseRejectionReason.STATUS_MISMATCH,
                assertIs<ResponseBindingResult.Rejected>(binder.bind("getServiceHealth", 503, problem(status), "application/problem+json")).reason)
        }
    }

    @Test fun providedHttpTraceMustMatchProblemButAbsentHeaderIsNotInvented() {
        assertIs<ResponseBindingResult.Accepted>(binder.bind("getServiceHealth", 503, problem(), "application/problem+json"))
        assertEquals(ResponseRejectionReason.TRACE_MISMATCH,
            assertIs<ResponseBindingResult.Rejected>(binder.bind("getServiceHealth", 503, problem(), "application/problem+json", "other")).reason)
        for (trace in listOf("", "bad\ntrace", "x".repeat(257)))
            assertEquals(ResponseRejectionReason.INVALID_METADATA,
                assertIs<ResponseBindingResult.Rejected>(binder.bind("getServiceHealth", 503, problem(), "application/problem+json", trace)).reason)
    }

    @Test fun allRequiredFieldsEnumsAndUnknownPropertiesMustValidateBeforeBinding() {
        assertEquals(ContractRejectionReason.SCHEMA_VIOLATION,
            assertIs<ResponseBindingResult.Rejected>(binder.bind("getServiceHealth", 503, problem(extra = ",\"future\":null"), "application/problem+json")).contractReason)
        assertEquals(ContractRejectionReason.SCHEMA_VIOLATION,
            assertIs<ResponseBindingResult.Rejected>(binder.bind("getServiceHealth", 200, "{}".encodeToByteArray(), "application/json")).contractReason)
        assertIs<ResponseBindingResult.Rejected>(binder.bind("getServiceHealth", 503, problem("503.000000000000000001"), "application/problem+json"))
    }

    @Test fun acceptedDocumentsAreDetachedAndBodylessIsNotNullJson() {
        val original = problem()
        val expected = original.copyOf()
        val bound = assertIs<ResponseBindingResult.Accepted>(binder.bind("getServiceHealth", 503, original, "application/problem+json")).response
        original.fill(0)
        assertContentEquals(expected, assertIs<WireBody.Present>(bound.body).document.encodeUtf8())
        val bodyless = assertIs<ResponseBindingResult.Accepted>(binder.bind("deletePost", 204, null, null)).response
        assertIs<WireBody.Absent>(bodyless.body)
        assertEquals(ContractRejectionReason.UNEXPECTED_BODY,
            assertIs<ResponseBindingResult.Rejected>(binder.bind("deletePost", 204, "null".encodeToByteArray(), null)).contractReason)
    }
}
