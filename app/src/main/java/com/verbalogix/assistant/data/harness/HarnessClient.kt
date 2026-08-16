package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.harness.wire.CapabilitiesResult
import com.verbalogix.assistant.data.harness.wire.ExpertCatalogResult
import com.verbalogix.assistant.data.harness.wire.ExpertReleaseDetailResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The loopback Harness client.
 *
 * NO CONTENT NEGOTIATION PLUGIN, deliberately. Ktor's `ContentNegotiation` would decode
 * the body for us with whatever `Json` it was configured with, and that is exactly the
 * decision this project keeps separate: the LLM path is lenient, the Harness path is
 * strict, and the difference has to be visible rather than buried in a plugin's config.
 * So the body arrives as text and goes through [HarnessDecoder], which is the same code
 * the golden tests exercise. The transport moves bytes; it does not interpret them.
 *
 * LOOPBACK ONLY. The base URL is fixed to `127.0.0.1` at a configurable port and there is
 * no code path that accepts a host from anywhere else -- not from a response, not from a
 * provider row, not from a route argument. The Harness refuses a non-loopback peer
 * anyway; this is the client-side half of the same rule, so a mistake fails here rather
 * than becoming a request that leaves the device.
 */
@Singleton
class HarnessClient @Inject constructor() {

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            // Loopback to a local process. Generous enough for a cold Harness, and far
            // short of anything a user would sit through: an unreachable Foundry should
            // report itself quickly, because "not paired" is a normal state here.
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 2_000
        }
        expectSuccess = false
    }

    /**
     * Exchange a one-use pairing credential for a rotating session.
     *
     * The credential is a PARAMETER and is never held by this class. It arrives, it is
     * spent, and it is gone -- see [com.verbalogix.assistant.data.harness.PairingCredentialSource].
     */
    suspend fun exchange(
        pairingCredential: String,
        clientInstanceId: String,
        bind: String = DEFAULT_BIND,
    ): TokenExchange = token(HarnessRequest.PATH_TOKEN_EXCHANGE, pairingCredential, clientInstanceId, bind)

    /** Rotate a live session. Authorised by the token being replaced. */
    suspend fun refresh(
        accessToken: String,
        clientInstanceId: String,
        bind: String = DEFAULT_BIND,
    ): TokenExchange = token(HarnessRequest.PATH_TOKEN_REFRESH, accessToken, clientInstanceId, bind)

    private suspend fun token(
        path: String,
        bearer: String,
        clientInstanceId: String,
        bind: String,
    ): TokenExchange = runCatching {
        val response = http.post("http://$bind$path") {
            HarnessRequest.tokenHeaders(bind, bearer).forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(HarnessRequest.tokenRequestBody(clientInstanceId))
        }
        val body = response.bodyAsText()
        if (!response.isSuccess()) {
            return TokenExchange.Rejected(errorCodeOf(body))
        }
        // `Cache-Control: no-store` is REQUIRED on a token response, not preferred. A
        // cached credential is one written to disk by something other than this app.
        if (!HarnessNegotiation.forbidsStorage(response.headers["Cache-Control"])) {
            return TokenExchange.Rejected("token-response-storable")
        }
        TokenExchange.Decoded(HarnessTokenDecoder.decode(body, clientInstanceId, nowSeconds()))
    }.getOrElse { TokenExchange.Unreachable(it.message ?: it::class.simpleName ?: "unreachable") }

    internal suspend fun capabilities(accessToken: String, bind: String = DEFAULT_BIND) =
        operationGet(HarnessRequest.PATH_CAPABILITIES, accessToken, bind, HarnessDecoder::decodeCapabilities)

    internal suspend fun expertCatalog(accessToken: String, bind: String = DEFAULT_BIND) =
        operationGet(HarnessRequest.PATH_EXPERT_CATALOG, accessToken, bind, HarnessDecoder::decodeExpertCatalog)

    /**
     * Inspect one release.
     *
     * A POST with an `operation-request/3.0` envelope, because the adapter requires the
     * request to name its own operation and expected response schema -- a route match
     * alone is not enough.
     */
    internal suspend fun inspectRelease(
        accessToken: String,
        packId: String,
        version: String,
        bind: String = DEFAULT_BIND,
    ): HarnessOutcome<ExpertReleaseDetailResult> = runCatching {
        val body = """{"arguments":{"pack_id":"$packId","version":"$version"},""" +
            """"mode":"http","operation_id":"expert.release.inspect",""" +
            """"response_schema":"knowledge-foundry-operation-response/3.0",""" +
            """"schema":"knowledge-foundry-operation-request/3.0"}"""
        val response = http.post("http://$bind${HarnessRequest.PATH_EXPERT_RELEASE_INSPECT}") {
            HarnessRequest.operationHeaders(bind, accessToken, post = true)
                .forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        decodeOperation(response) { HarnessDecoder.decodeExpertReleaseDetail(it) }
    }.getOrElse { transportRefusal(it) }

    private suspend fun <T> operationGet(
        path: String,
        accessToken: String,
        bind: String,
        decode: (String) -> HarnessOutcome<T>,
    ): HarnessOutcome<T> = runCatching {
        val response = http.get("http://$bind$path") {
            HarnessRequest.operationHeaders(bind, accessToken, post = false)
                .forEach { (k, v) -> header(k, v) }
        }
        decodeOperation(response, decode)
    }.getOrElse { transportRefusal(it) }

    private suspend fun <T> decodeOperation(
        response: HttpResponse,
        decode: (String) -> HarnessOutcome<T>,
    ): HarnessOutcome<T> {
        val body = response.bodyAsText()
        if (!response.isSuccess()) {
            // An adapter error is a real answer from the Harness, not a decode failure.
            // The code decides whether the session ended -- see HarnessErrorCodes.
            return HarnessOutcome.Unsuccessful("failed", errorCodeOf(body))
        }
        if (!HarnessNegotiation.forbidsStorage(response.headers["Cache-Control"])) {
            return HarnessOutcome.Refused(
                HarnessRefusal.Undecodable("a /3.0 response must be Cache-Control: no-store"),
            )
        }
        return decode(body)
    }

    private fun <T> transportRefusal(cause: Throwable): HarnessOutcome<T> =
        HarnessOutcome.Refused(
            HarnessRefusal.Undecodable(
                // Sanitised: the message names the failure, never a credential, and the
                // Authorization header is never echoed into it.
                cause.message?.takeIf { "kft2." !in it } ?: "the Knowledge Foundry did not answer",
            ),
        )

    /**
     * Pull the adapter's error code out of a failure body without trusting its shape.
     *
     * A best-effort read on purpose: an error body is the least reliable thing a server
     * sends, and refusing to classify one because it did not parse strictly would turn
     * every unexpected failure into an unexplained one.
     */
    private fun errorCodeOf(body: String): String? =
        Regex(""""code"\s*:\s*"([a-z0-9-]+)"""").find(body)?.groupValues?.get(1)

    private fun HttpResponse.isSuccess(): Boolean = status.value in 200..299

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        /**
         * The Foundry's loopback bind.
         *
         * 8091, and NOT 8090 -- the model server is a different service on a different
         * port, and the token goldens carry `"bind":"127.0.0.1:8091"` confirming it. The
         * two must stay visibly separate: a Harness reachable on the model port would
         * mean one of them is not what the app thinks it is.
         */
        const val DEFAULT_BIND = "127.0.0.1:8091"
    }
}

/** What a token exchange or refresh produced. */
sealed interface TokenExchange {
    data class Decoded(val result: TokenDecodeResult) : TokenExchange

    /** The Harness answered and declined. Carries the adapter code, when it gave one. */
    data class Rejected(val errorCode: String?) : TokenExchange

    /** Nothing answered. Distinct from a rejection: no session ended, none was created. */
    data class Unreachable(val detail: String) : TokenExchange
}
