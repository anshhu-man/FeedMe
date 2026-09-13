package com.feedme.transport

import com.feedme.contracts.CanonicalBodyValidator
import com.feedme.contracts.ContractCatalog
import com.feedme.contracts.ContractValidationResult
import com.feedme.contracts.PrincipalClass
import com.feedme.core.ports.ApiCall

/**
 * Pure validation of caller-controlled mobile intent, suitable before persistence or secure access.
 * Covers bundled operation/principal/parameters and the complete pinned request-body schema.
 * Only the transport-supplied device-session header is deferred; transport must still bind and
 * validate its real value at execution. Success grants no ownership, entitlement, authorization,
 * offline eligibility, or permission to retry. No private request values appear in the result.
 */
class MobileRequestValidator internal constructor(
    private val preparation: RequestPreparation,
    private val bodies: CanonicalBodyValidator,
) {
    constructor(catalog: ContractCatalog = ContractCatalog.bundled()) :
        this(RequestPreparation(catalog), CanonicalBodyValidator.bundled(catalog))

    fun accepts(call: ApiCall, principal: PrincipalClass): Boolean =
        preparation.acceptsIntent(call, principal) && bodies.validateRequest(call.operationId,
            call.body?.copyForCodec(), if (call.body == null) null else "application/json") == ContractValidationResult.Valid
}
