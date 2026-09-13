package com.feedme.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling

/**
 * Native runtime verification remains gated; see KTOR_TRANSPORT_ENGINE_NOTES.md.
 * Stock Darwin queues response chunks without a hard bound and exposes no blanket retry switch.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun platformHttpClient(): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        // Darwin supports request/socket deadlines, not a separate connect deadline.
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    engine {
        configureSession {
            setHTTPCookieStorage(null)
            setHTTPShouldSetCookies(false)
            setURLCache(null)
            setURLCredentialStorage(null)
            setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            setConnectionProxyDictionary(emptyMap<Any?, Any?>())
            setTimeoutIntervalForRequest(30.0)
            setTimeoutIntervalForResource(30.0)
        }
        configureRequest {
            setHTTPShouldHandleCookies(false)
            setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        }
        handleChallenge { _, _, challenge, completion ->
            if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
            } else {
                completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }
}
