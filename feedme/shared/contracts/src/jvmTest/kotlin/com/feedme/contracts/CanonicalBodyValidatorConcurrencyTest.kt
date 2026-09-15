package com.feedme.contracts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CanonicalBodyValidatorConcurrencyTest {
    @Test fun concurrentDefaultReadersShareRulesButNotValidationBudgetsOrResults() {
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8); val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { worker -> pool.submit<CanonicalBodyValidator> {
                ready.countDown(); check(start.await(30, TimeUnit.SECONDS))
                val validator = CanonicalBodyValidator.bundled()
                val body = "{\"planId\":\"00000000-0000-4000-8000-${(worker + 1).toString().padStart(12, '0')}\"}".encodeToByteArray()
                repeat(50) {
                    assertSame(validator, CanonicalBodyValidator.bundled())
                    assertEquals(ContractValidationResult.Valid, validator.validateRequest("createCookSession", body, "application/json"))
                    assertEquals(ContractRejectionReason.RESOURCE_LIMIT,
                        assertIs<ContractValidationResult.Rejected>(validator.validateSchema("Health", "{\"n\":1e10001}".encodeToByteArray())).reason)
                    assertEquals(ContractRejectionReason.SCHEMA_VIOLATION,
                        assertIs<ContractValidationResult.Rejected>(validator.validateRequest("createCookSession", "{}".encodeToByteArray(), "application/json")).reason)
                    assertEquals(ContractValidationResult.Valid, validator.validateRequest("createCookSession", body, "application/json"))
                }
                validator
            } }
            assertTrue(ready.await(30, TimeUnit.SECONDS)); start.countDown()
            val validators = futures.map { it.get(60, TimeUnit.SECONDS) }
            validators.forEach { assertSame(validators.first(), it) }
        } finally {
            start.countDown(); pool.shutdownNow(); pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }
}
