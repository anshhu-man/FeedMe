package com.feedme.contracts

enum class ResponseRejectionReason { BODY_VALIDATION, STATUS_MISMATCH, TRACE_MISMATCH, INVALID_METADATA }

sealed interface ResponseBindingResult {
    class Accepted internal constructor(val response: BoundContractResponse) : ResponseBindingResult
    data class Rejected(val reason: ResponseRejectionReason, val contractReason: ContractRejectionReason? = null) : ResponseBindingResult
}

/** A bound document, not an authorized domain object. Only the binder mints this result. */
class BoundContractResponse internal constructor(val operationId: String, val status: Int, val body: WireBody) {
    override fun toString() = "BoundContractResponse(operationId=$operationId, status=$status, body=<redacted>)"
}

/** Checks the schema and the relationships that an operation-independent Problem schema cannot. */
class CanonicalResponseBinder(private val validator: CanonicalBodyValidator = CanonicalBodyValidator.bundled()) {
    fun bind(operationId: String, status: Int, bytes: ByteArray?, mediaType: String?, traceId: String? = null): ResponseBindingResult {
        val snapshot = bytes?.copyOf()
        if (traceId != null && (traceId.isBlank() || traceId.length > 256 || traceId.any(Char::isISOControl)))
            return ResponseBindingResult.Rejected(ResponseRejectionReason.INVALID_METADATA)
        val result = validator.validateResponse(operationId, status, snapshot, mediaType)
        if (result is ContractValidationResult.Rejected)
            return ResponseBindingResult.Rejected(ResponseRejectionReason.BODY_VALIDATION, result.reason)
        val body = WireBody.decode(snapshot)
        if (mediaType?.substringBefore(';')?.trim()?.lowercase() == "application/problem+json" && body is WireBody.Present) {
            val document = body.document
            val numeric = (document.field("status") as WireField.Value).value.numberTokenOrNull()!!
            if (ExactDecimal.parse(numeric)!!.compareTo(ExactDecimal.parse(status.toString())!!) != 0)
                return ResponseBindingResult.Rejected(ResponseRejectionReason.STATUS_MISMATCH)
            val bodyTrace = (document.field("traceId") as WireField.Value).value.stringOrNull()!!
            if (traceId != null && traceId != bodyTrace)
                return ResponseBindingResult.Rejected(ResponseRejectionReason.TRACE_MISMATCH)
        }
        return ResponseBindingResult.Accepted(BoundContractResponse(operationId, status, body))
    }
}
