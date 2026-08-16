package com.verbalogix.assistant.data.harness

/**
 * How a request to the Harness must be shaped.
 *
 * TRANSCRIBED FROM THE SERVER'S OWN ADAPTER, not inferred from convention. Each rule
 * below has a matching rejection in `harness/http.py`, so a request this object refuses
 * to build is one the Harness would have refused anyway -- the difference being a typed
 * failure here instead of an opaque one over the wire.
 *
 * The unobvious ones, and why they are unobvious:
 *
 *  - **Origin must be ABSENT.** Not "matching" -- absent. The adapter raises
 *    `token-origin-denied` if any Origin accompanies a Localmind or token route. Browser
 *    habits say send it; here it is a refusal.
 *  - **Host must equal the bound address exactly**, or `token-bind-denied`. An HTTP
 *    client that helpfully normalises `127.0.0.1:8765` differs from one that does not.
 *  - **No query string, no fragment**, or `adapter-route-unsupported`. Arguments travel
 *    in the body, including for reads.
 *  - **The negotiation header is not sent on token routes.** The adapter reads it only
 *    for operations, and sending it there implies an agreement that does not apply.
 *  - **Scopes must be sorted and unique**, or `request-invalid`. Sorted by the WIRE
 *    string, which is not the enum's declaration order.
 */
object HarnessRequest {

    /** The adapter refuses a body over 64 KiB with `http-body-too-large`. */
    const val MAX_BODY_BYTES = 65_536

    const val CONTENT_TYPE = "application/json"

    /**
     * A fixed 300-second session.
     *
     * `expires_in` is ECHOED FROM THIS VALUE -- the server does not choose it, it returns
     * what the client asked for, bounded to 1..900 by the schema. So the client is
     * choosing how long a stolen token stays useful, and asking for the 900-second
     * maximum would be choosing the worst answer for no benefit. 300s with a 60s refresh
     * lead gives four minutes of working session per rotation, which is far more than a
     * loopback round trip needs.
     */
    const val ACCESS_TTL_SECONDS = 300

    const val PATH_TOKEN_EXCHANGE = "/v1/tokens/exchange"
    const val PATH_TOKEN_REFRESH = "/v1/tokens/refresh"
    const val PATH_CAPABILITIES = "/v1/capabilities"
    const val PATH_EXPERT_CATALOG = "/v1/experts"
    const val PATH_EXPERT_RELEASE_INSPECT = "/v1/experts/releases/inspect"

    /**
     * The scopes to request, sorted by wire string as the adapter requires.
     *
     * Derived from [HarnessScope.REQUESTED] rather than written out, so the four-value
     * enum stays the single authority. A literal list here could drift into asking for
     * something the enum cannot represent.
     */
    fun requestedScopes(): List<String> =
        HarnessScope.REQUESTED.map { it.wire }.sorted()

    /**
     * The body for `/v1/tokens/exchange` and `/v1/tokens/refresh`.
     *
     * Exactly three keys. The adapter compares the key SET and answers `unknown-field`
     * for anything extra, so this is not a place to add a client version or a nonce.
     */
    fun tokenRequestBody(clientInstanceId: String, ttlSeconds: Int = ACCESS_TTL_SECONDS): String {
        require(HarnessPairing.isWellFormedClientInstanceId(clientInstanceId)) {
            "client_instance_id must be 32 lowercase hex characters"
        }
        require(ttlSeconds in 1..900) { "ttl must be within 1..900" }
        val scopes = requestedScopes().joinToString(",") { "\"$it\"" }
        // Key order is irrelevant to the adapter, which compares a set; alphabetical is
        // chosen so the emitted body is stable and diffable in a test.
        return """{"client_instance_id":"$clientInstanceId","scopes":[$scopes],"ttl":$ttlSeconds}"""
    }

    /**
     * Headers for a token exchange or refresh.
     *
     * @param bearer the pairing credential on exchange, the current access token on
     *   refresh. Both are `Bearer`; the route decides which is meant.
     */
    fun tokenHeaders(host: String, bearer: String): Map<String, String> = mapOf(
        "Host" to host,
        "Authorization" to "Bearer $bearer",
        "Content-Type" to CONTENT_TYPE,
        // No Knowledge-Foundry-Accept-Schema: token routes are outside negotiation.
        // No Origin: forbidden outright.
    )

    /** Headers for an operation on the `/3.0` path. */
    fun operationHeaders(host: String, bearer: String, post: Boolean): Map<String, String> =
        buildMap {
            put("Host", host)
            put("Authorization", "Bearer $bearer")
            put(HarnessNegotiation.HEADER, HarnessNegotiation.ACCEPT_VALUE)
            if (post) put("Content-Type", CONTENT_TYPE)
        }

    /**
     * Whether a target is acceptable to send.
     *
     * Rejects the query string and fragment the adapter refuses, so a caller that
     * appends `?foo=1` fails here rather than receiving `adapter-route-unsupported`.
     */
    fun isSendableTarget(target: String): Boolean =
        target.startsWith("/") && '?' !in target && '#' !in target
}
