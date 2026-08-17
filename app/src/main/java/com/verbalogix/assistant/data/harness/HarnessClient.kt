package com.verbalogix.assistant.data.harness

import com.verbalogix.assistant.data.harness.wire.CapabilitiesResult
import com.verbalogix.assistant.data.harness.wire.ExpertCatalogResult
import com.verbalogix.assistant.data.harness.wire.ExpertReleaseDetailResult
import com.verbalogix.assistant.data.harness.wire.QueryResult
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
     * Inspect one release, keyed by its immutable release identity.
     *
     * THE BODY IS EXACTLY `{"release_id": "kf:pack-release:…"}`.
     *
     * The first version of this sent an `operation-request/3.0` envelope carrying
     * `pack_id` and `version`, inferred from the route rather than read from a schema.
     * Both halves were wrong against the live adapter: the raw route form is what it
     * accepts, and the lookup key is the RELEASE, not a pack plus a version string.
     *
     * That distinction is the substantive one. `pack_id` names a pack across all of its
     * releases and `version` is a label attached to one; identifying a release by the
     * pair means resolving two mutable-ish facts to reach an immutable thing that already
     * has its own name. `release_id` is a digest -- it cannot drift, be reused, or be
     * ambiguous -- so it is the only honest lookup authority. Pack id and version stay as
     * things to SHOW.
     */
    internal suspend fun inspectRelease(
        accessToken: String,
        releaseId: String,
        bind: String = DEFAULT_BIND,
    ): HarnessOutcome<ExpertReleaseDetailResult> = runCatching {
        // Validated before it is interpolated. The identity reaches a request body, and
        // an unchecked one is precisely what RouteArgs and this regex exist to stop.
        if (!HarnessDecoder.isWellFormedIdentity(releaseId)) {
            return HarnessOutcome.Refused(HarnessRefusal.MalformedIdentity(releaseId))
        }
        val body = HarnessRequest.inspectReleaseBody(releaseId)
        val response = http.post("http://$bind${HarnessRequest.PATH_EXPERT_RELEASE_INSPECT}") {
            HarnessRequest.operationHeaders(bind, accessToken, post = true)
                .forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        decodeOperation(response) { HarnessDecoder.decodeExpertReleaseDetail(it) }
    }.getOrElse { transportRefusal(it) }

    /**
     * Ask one expert a question and receive EVIDENCE.
     *
     * `answer_mode: evidence-only` is fixed inside [HarnessRequest.retrieveBody] and
     * cannot be varied from here, so there is no call shape in this client that asks the
     * Foundry to generate prose. What comes back is quotation, provenance and the
     * Harness's own `answerability` verdict.
     *
     * The pack scope is ONE PACK: the release being inspected. Localmind never sends
     * `mode: all` from an expert's own screen, because a question asked there is a
     * question about that expert.
     *
     * @param allowedSensitivities exactly the list `expert.release.inspect` returned. This
     *   client neither widens nor defaults it.
     */
    internal suspend fun retrieveEvidence(
        accessToken: String,
        text: String,
        packId: String,
        allowedSensitivities: List<String>,
        bind: String = DEFAULT_BIND,
    ): HarnessOutcome<QueryResult> = runCatching {
        val body = HarnessRequest.retrieveBody(text, packId, allowedSensitivities)
        val response = http.post("http://$bind${HarnessRequest.PATH_QUERY_RETRIEVE}") {
            HarnessRequest.operationHeaders(bind, accessToken, post = true)
                .forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        decodeOperation(response) { HarnessDecoder.decodeQueryResult(it) }
    }.getOrElse { transportRefusal(it) }

    /**
     * Finalise one assistant turn: `POST /v1/assistant/turns`, negotiated at `/4.0`.
     *
     * THE ONLY ROUTE THAT SENDS `/4.0`. Stage 3D is additive -- `/1.0`, `/2.0` and `/3.0`
     * stay byte-compatible -- so negotiating it anywhere else would request a version of a
     * document the Foundry does not describe under that number.
     *
     * The body is the caller's already-sealed request. This method deliberately does NOT
     * build it: `request_sha256` covers the exact object, and a transport that reassembled
     * it would be a second chance to differ from what was hashed.
     *
     * The existing `query:read` token authorises this. No new scope, no new authority: the
     * operation is pure finalisation, and the Foundry calls no provider, no network and no
     * tool to serve it.
     */
    internal suspend fun finalizeTurn(
        accessToken: String,
        turnRequest: kotlinx.serialization.json.JsonObject,
        bind: String = DEFAULT_BIND,
    ): HarnessOutcome<com.verbalogix.assistant.data.harness.wire.AssistantTurnResult> = runCatching {
        val response = http.post("http://$bind${AssistantTurnRequest.PATH_ASSISTANT_TURN}") {
            HarnessRequest.turnHeaders(bind, accessToken).forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(AssistantTurnRequest.body(turnRequest))
        }
        decodeOperation(response) { HarnessDecoder.decodeAssistantTurn(it) }
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
