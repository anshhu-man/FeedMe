package com.feedme.transport

import com.feedme.contracts.ContractCatalog
import com.feedme.contracts.PrincipalClass
import com.feedme.contracts.SchemaDefinition
import com.feedme.core.ports.ApiCall
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.SecretText
import kotlin.test.*

class MobileRequestValidatorReuseTest {
    private fun cooking(body: String = "{\"planId\":\"123e4567-e89b-12d3-a456-426614174000\"}") =
        ApiCall("createCookSession", body = PrivateBytes(body.encodeToByteArray()),
            idempotencyKey = SecretText("123e4567-e89b-12d3-a456-426614174001"))

    @Test fun repeatedDefaultConstructorsKeepFullBodyPrincipalAndParameterChecks() {
        val validators = List(32) { MobileRequestValidator() }
        for (validator in validators) {
            assertTrue(validator.accepts(cooking(), PrincipalClass.ACCOUNT))
            assertFalse(validator.accepts(cooking(), PrincipalClass.PUBLIC))
            assertFalse(validator.accepts(cooking("{\"planId\":true}"), PrincipalClass.ACCOUNT))
            assertFalse(validator.accepts(ApiCall("createCookSession", body = cooking().body), PrincipalClass.ACCOUNT))
            assertTrue(validator.accepts(ApiCall("listRecipes", queryParameters = mapOf("limit" to listOf("50"))), PrincipalClass.GUEST))
            assertFalse(validator.accepts(ApiCall("listRecipes", queryParameters = mapOf("limit" to listOf("51"))), PrincipalClass.GUEST))
        }
    }

    @Test fun explicitCatalogConstructorDoesNotSilentlyUseBundledBodyRules() {
        val defaults = MobileRequestValidator()
        val catalog = ContractCatalog.bundled()
        val content = checkNotNull(catalog.operation("createCookSession")?.requestBody).content as MutableMap<String, SchemaDefinition>
        content["application/json"] = checkNotNull(catalog.schema("Health"))
        val explicit = MobileRequestValidator(catalog = catalog)
        assertFalse(explicit.accepts(cooking(), PrincipalClass.ACCOUNT))
        assertTrue(defaults.accepts(cooking(), PrincipalClass.ACCOUNT))
        assertTrue(MobileRequestValidator().accepts(cooking(), PrincipalClass.ACCOUNT))
    }

    @Test fun callerChangesAndRejectedResourceInputCannotPoisonSharedPreparation() {
        val first = MobileRequestValidator(); val second = MobileRequestValidator()
        val parameters = linkedMapOf("limit" to mutableListOf("50"))
        val call = ApiCall("listRecipes", queryParameters = parameters)
        assertTrue(first.accepts(call, PrincipalClass.GUEST))
        parameters.getValue("limit")[0] = "51"
        assertTrue(second.accepts(call, PrincipalClass.GUEST)) // ApiCall owns its original detached snapshot.
        assertFalse(second.accepts(ApiCall("listRecipes", queryParameters = parameters), PrincipalClass.GUEST))
        parameters.getValue("limit")[0] = "1"
        assertTrue(first.accepts(ApiCall("listRecipes", queryParameters = parameters), PrincipalClass.GUEST))
        assertEquals(listOf("50"), call.queryParameters.getValue("limit"))
        assertFalse(second.accepts(cooking("{\"planId\":1e10001}"), PrincipalClass.ACCOUNT))
        assertTrue(second.accepts(cooking(), PrincipalClass.ACCOUNT))
        assertFalse(first.accepts(ApiCall("getPost", pathParameters = mapOf("postId" to "not-a-uuid")), PrincipalClass.ACCOUNT))
        assertTrue(second.accepts(ApiCall("getPost", pathParameters = mapOf("postId" to "123e4567-e89b-12d3-a456-426614174000")), PrincipalClass.ACCOUNT))
    }
}
