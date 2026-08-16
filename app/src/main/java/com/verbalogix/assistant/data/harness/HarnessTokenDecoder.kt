package com.verbalogix.assistant.data.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reads `knowledge-foundry-localmind-token-response/1.0`.
 *
 * DELIBERATELY OUTSIDE [SchemaNegotiation], permanently, and not as an oversight to be
 * corrected when a golden arrives. That set governs the NEGOTIATED surface -- documents
 * carried inside `operation-response/3.0`, selected by the `Knowledge-Foundry-Accept-Schema`
 * header. A token response is none of those: it has its own fixed schema, its routes are
 * exempt from negotiation, and no header selects it. Admitting it there would blur two
 * different agreements, and a future `/4.0` negotiation would then appear to govern a
 * document it has no relationship with.
 *
 * FIXTURE-GATED. [ENABLED] is false because no server-emitted token response exists yet.
 * The decoder is complete and strict; what it lacks is bytes from the server to prove it
 * agrees with them. Turning it on is one constant plus the golden test that justifies it.
 */
object HarnessTokenDecoder {

    const val SCHEMA = "knowledge-foundry-localmind-token-response/1.0"

    /**
     * Whether token exchange may run.
     *
     * FALSE UNTIL A GOLDEN EXISTS. This is the same rule that keeps
     * `expert-release-detail/3.0` out of the accepted set, applied to the one path where
     * being wrong is worst: a token decoder that mis-reads a response could hold a
     * session it should have rejected. Every field below is transcribed from a closed
     * schema and has never met a real reply.
     */
    const val ENABLED = false

    @Serializable
    private data class TokenResponse(
        val schema: String,
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String,
        @SerialName("expires_in") val expiresIn: Int,
        val scopes: List<String>,
        @SerialName("client_instance_id") val clientInstanceId: String,
    )

    /**
     * Decode a token response, checking every constraint the schema states.
     *
     * @param expectedClientInstanceId the id this client sent. Compared, not trusted: a
     *   response echoing a different id is not this client's session, and adopting it
     *   would bind us to a token issued for someone else's request.
     */
    fun decode(
        raw: String,
        expectedClientInstanceId: String,
        nowEpochSeconds: Long,
    ): TokenDecodeResult {
        if (!ENABLED) return TokenDecodeResult.Disabled

        val response = try {
            // The same strict Json as every Harness payload: unknown keys throw, because
            // this schema is additionalProperties: false like the rest.
            HarnessDecoder.STRICT.decodeFromString(TokenResponse.serializer(), raw)
        } catch (e: Exception) {
            return TokenDecodeResult.Refused("unreadable token response: ${e.message}")
        }

        if (response.schema != SCHEMA) {
            return TokenDecodeResult.Refused("unexpected token schema ${response.schema}")
        }
        if (response.tokenType != "Bearer") {
            return TokenDecodeResult.Refused("unexpected token type ${response.tokenType}")
        }
        if (!HarnessPairing.isWellFormedToken(response.accessToken)) {
            // Never echo the value itself -- see HarnessCredentials.AccessToken.
            return TokenDecodeResult.Refused("access token is not in the expected form")
        }
        if (response.expiresIn !in 1..900) {
            return TokenDecodeResult.Refused("expires_in outside 1..900")
        }
        if (response.clientInstanceId != expectedClientInstanceId) {
            return TokenDecodeResult.Refused("token was issued for a different client instance")
        }

        val granted = response.scopes.mapNotNull(HarnessScope::fromWire).toSet()
        // Any unrecognised scope means the whole grant is refused, rather than kept minus
        // the unknown one. A session carrying authority this build cannot name is not a
        // reduced session, it is an unknown one.
        if (granted.size != response.scopes.size || !HarnessSessionPolicy.admit(granted)) {
            return TokenDecodeResult.Refused("granted scopes are not a subset of what was requested")
        }

        return TokenDecodeResult.Granted(
            expiresAtEpochSeconds = nowEpochSeconds + response.expiresIn,
            scopes = granted,
            token = HarnessCredentials.AccessToken(
                value = response.accessToken,
                expiresAtEpochSeconds = nowEpochSeconds + response.expiresIn,
                scopes = granted,
            ),
        )
    }
}

/** The outcome of reading a token response. */
sealed interface TokenDecodeResult {

    /** No golden exists yet, so the path is closed. Not an error; a stated gap. */
    data object Disabled : TokenDecodeResult

    data class Granted(
        val expiresAtEpochSeconds: Long,
        val scopes: Set<HarnessScope>,
        val token: HarnessCredentials.AccessToken,
    ) : TokenDecodeResult

    /**
     * The reason never contains the token.
     *
     * A refusal is the most likely thing to be logged or shown, which makes it the most
     * likely place for a credential to escape. Every message here describes the shape of
     * the failure and never its value.
     */
    data class Refused(val reason: String) : TokenDecodeResult
}
