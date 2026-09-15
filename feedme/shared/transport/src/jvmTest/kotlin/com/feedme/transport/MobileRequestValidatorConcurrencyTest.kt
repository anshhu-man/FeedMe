package com.feedme.transport

import com.feedme.contracts.PrincipalClass
import com.feedme.core.ports.ApiCall
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.SecretText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MobileRequestValidatorConcurrencyTest {
    @Test fun concurrentDefaultValidatorsKeepIndependentHeaderPathBodyAndQueryChecks() {
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8); val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { worker -> pool.submit<Unit> {
                ready.countDown(); check(start.await(30, TimeUnit.SECONDS))
                val validator = MobileRequestValidator()
                val id = "00000000-0000-4000-8000-${(worker + 1).toString().padStart(12, '0')}"
                repeat(50) {
                    val call = ApiCall("updatePreferences", body = PrivateBytes("{}".encodeToByteArray()),
                        idempotencyKey = SecretText(id), ifMatch = "\"${worker + 1}\"")
                    assertTrue(validator.accepts(call, PrincipalClass.GUEST))
                    assertFalse(validator.accepts(ApiCall("updatePreferences", body = call.body,
                        idempotencyKey = call.idempotencyKey, ifMatch = "unquoted"), PrincipalClass.GUEST))
                    assertFalse(validator.accepts(ApiCall("updatePreferences", body = PrivateBytes("null".encodeToByteArray()),
                        idempotencyKey = call.idempotencyKey, ifMatch = call.ifMatch), PrincipalClass.GUEST))
                    assertFalse(validator.accepts(call, PrincipalClass.PUBLIC))
                    assertTrue(validator.accepts(ApiCall("getPost", pathParameters = mapOf("postId" to id)), PrincipalClass.ACCOUNT))
                    assertFalse(validator.accepts(ApiCall("getPost", pathParameters = mapOf("postId" to "../$id")), PrincipalClass.ACCOUNT))
                    assertTrue(validator.accepts(ApiCall("listRecipes", queryParameters = mapOf("limit" to listOf("1"))), PrincipalClass.GUEST))
                    assertFalse(validator.accepts(ApiCall("listRecipes", queryParameters = mapOf("limit" to listOf("1", "1"))), PrincipalClass.GUEST))
                    assertTrue(validator.accepts(call, PrincipalClass.GUEST))
                }
            } }
            assertTrue(ready.await(30, TimeUnit.SECONDS)); start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            start.countDown(); pool.shutdownNow(); pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }
}
