package com.feedme.server

import com.feedme.server.config.LocalServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LocalServerConfigTest {
    @Test fun defaultsAreLoopbackAndDevelopmentOnly() {
        val config = LocalServerConfig.fromEnvironment(emptyMap())
        assertEquals("127.0.0.1", config.host)
        assertEquals(8780, config.port)
        assertEquals("0.1.0-dev", config.minimumAppVersion)
    }

    @Test fun acceptsExplicitLocalValuesAndUnrelatedEnvironment() {
        val config = LocalServerConfig.fromEnvironment(mapOf("FEEDME_SERVER_MODE" to "local", "FEEDME_SERVER_HOST" to "127.0.0.1",
            "FEEDME_SERVER_PORT" to "18780", "FEEDME_MINIMUM_APP_VERSION" to "1.2.3-test.1", "PATH" to "/unused"))
        assertEquals(18780, config.port)
        assertEquals("1.2.3-test.1", config.minimumAppVersion)
    }

    @Test fun cannotEnableDeploymentOrExternalInterfaces() {
        listOf("production", "staging", "test", "LOCAL", "").forEach { value ->
            assertFailsWith<IllegalArgumentException> { LocalServerConfig.fromEnvironment(mapOf("FEEDME_SERVER_MODE" to value)) }
        }
        listOf("0.0.0.0", "::", "::1", "localhost", "127.0.0.2", "example.com", "").forEach { value ->
            assertFailsWith<IllegalArgumentException> { LocalServerConfig.fromEnvironment(mapOf("FEEDME_SERVER_HOST" to value)) }
        }
    }

    @Test fun rejectsInvalidPortsRatherThanFallingBack() {
        listOf("0", "80", "1023", "65536", "999999", " 8780", "+8780", "1e4", "8780.0", "").forEach { value ->
            assertFailsWith<IllegalArgumentException> { LocalServerConfig.fromEnvironment(mapOf("FEEDME_SERVER_PORT" to value)) }
        }
        listOf("1024", "65535").forEach { value ->
            assertEquals(value.toInt(), LocalServerConfig.fromEnvironment(mapOf("FEEDME_SERVER_PORT" to value)).port)
        }
    }

    @Test fun rejectsInvalidVersionsAndUnknownServerOptionsWithoutLeakingValues() {
        listOf("", "1.0", "v1.2.3", "1.2.3\r\nsecret", "secret-token").forEach { value ->
            val failure = assertFailsWith<IllegalArgumentException> {
                LocalServerConfig.fromEnvironment(mapOf("FEEDME_MINIMUM_APP_VERSION" to value))
            }
            assertFalse(failure.message.orEmpty().contains("secret"))
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            LocalServerConfig.fromEnvironment(mapOf("FEEDME_SERVER_SECRET_TOKEN" to "very-sensitive"))
        }
        assertFalse(failure.message.orEmpty().contains("very-sensitive"))
    }
}
