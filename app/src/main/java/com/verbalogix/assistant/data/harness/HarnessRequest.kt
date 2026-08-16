package com.verbalogix.assistant.data.harness

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
    const val PATH_QUERY_RETRIEVE = "/v1/queries"

    // ── the fixed shape of every retrieval this client will ever send ───────────
    //
    // CONSTANTS, NOT PARAMETERS, and that is the design. Each one closes off a request
    // Localmind must not be able to make:
    //
    //   role         `project-expert` is the only role in the enum this client holds.
    //   query_mode   `auto` lets the Foundry pick its channels; `lexical-graph` would be
    //                this client instructing a retrieval engine it does not own.
    //   answer_mode  `evidence-only`. The other values ask the Foundry to GENERATE, and
    //                Localmind has no contract that could attest such an answer.
    //   provider_id  null, always. Naming a provider would point Foundry retrieval at a
    //                model endpoint, collapsing the pack/provider boundary this app keeps.
    //
    // A caller cannot vary any of them, so no screen, argument or future edit can widen
    // the request without deleting a line here and explaining why.
    const val QUERY_REQUEST_SCHEMA = "knowledge-foundry-query-request/2.0"
    const val QUERY_ROLE = "project-expert"
    const val QUERY_MODE = "auto"
    const val QUERY_ANSWER_MODE = "evidence-only"

    /** `text` is `maxLength: 4096` and forbids control characters outright. */
    const val MAX_QUERY_CHARS = 4096

    /** `access.allowed_sensitivities` is a closed two-value enum. */
    val SENSITIVITIES = setOf("public", "internal")

    /**
     * The characters the schema's `text` pattern excludes: `^[^\u0000-\u001f\u007f]+$`.
     *
     * REJECTED RATHER THAN ESCAPED. A newline is representable in JSON as `\n`, so an
     * escaping client would send a document the Foundry then refuses -- the pattern
     * forbids the CHARACTER, not its encoding. Catching it here turns an opaque
     * `request-invalid` into a local failure that names the reason.
     */
    private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f\\u007f]")

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
     * The body for `expert.release.inspect`.
     *
     * EXACTLY ONE FIELD. The first version sent an `operation-request/3.0` envelope
     * carrying `pack_id` and `version`, inferred from the route rather than read from a
     * schema; the live adapter takes the raw route form and keys on the RELEASE. Kept
     * here rather than inline in the client so the exact bytes can be asserted without a
     * socket -- which is the only way a mistake like the first one gets caught early.
     */
    fun inspectReleaseBody(releaseId: String): String {
        require(HarnessDecoder.isWellFormedIdentity(releaseId)) {
            "release_id must be a kf:<kind>:<sha256> identity"
        }
        return """{"release_id":"$releaseId"}"""
    }

    /**
     * Whether a typed question can be sent as-is.
     *
     * Length is measured in UTF-16 units against a schema limit expressed in characters,
     * so a question full of astral-plane characters is refused slightly early. Strict in
     * the safe direction, and the alternative -- counting code points to send a longer
     * body -- optimises a case nobody has.
     */
    fun isSendableQueryText(text: String): Boolean =
        text.isNotEmpty() &&
            text.length <= MAX_QUERY_CHARS &&
            !CONTROL_CHARACTERS.containsMatchIn(text)

    /**
     * The body for `query.retrieve`.
     *
     * THE ARGUMENT IS WRAPPED IN `request`, and that is not a stylistic choice. The
     * adapter passes a raw `/3.0` body straight through as the operation's ARGUMENTS, and
     * the facade allows exactly `{"trust_store", "request"}` for this operation --
     * anything else is `unknown-field`. So a bare `query-request/2.0` at the top level,
     * which is what the golden's `plan.request` makes it look like, would be refused on
     * arrival. `trust_store` is deliberately absent: it is a SERVER startup input, and the
     * adapter refuses a body that carries one.
     *
     * BUILT THROUGH A JSON ENCODER RATHER THAN INTERPOLATED. Every other body in this file
     * is a string template, because every other body is made of hex identities and enum
     * values that cannot contain a quote. This one carries a sentence a person typed. A
     * hand-rolled escape is exactly the kind of code that works until someone asks a
     * question containing a backslash, so the encoder does it.
     *
     * `limits` is `{}` -- no ceilings named. The schema's `required` list for it is empty
     * and the Foundry applies its own budgets; a client-side ceiling here would silently
     * shrink what an expert is allowed to answer with, and this app has no basis to pick
     * one.
     *
     * @param allowedSensitivities exactly what `expert-release-detail/3.0` returned for
     *   the release being inspected. Not widened, not defaulted: asking for `internal` on
     *   a release that permits only `public` is asking the Foundry for material this
     *   client was not told it may see.
     */
    fun retrieveBody(
        text: String,
        packId: String,
        allowedSensitivities: List<String>,
    ): String {
        require(isSendableQueryText(text)) {
            "query text must be 1..$MAX_QUERY_CHARS characters and free of control characters"
        }
        require(HarnessDecoder.isWellFormedIdentity(packId)) {
            "pack_id must be a kf:<kind>:<sha256> identity"
        }
        require(allowedSensitivities.isNotEmpty()) {
            "a release with no allowed sensitivities cannot be queried"
        }
        require(allowedSensitivities.size == allowedSensitivities.distinct().size) {
            "allowed_sensitivities is uniqueItems"
        }
        require(allowedSensitivities.all { it in SENSITIVITIES }) {
            "allowed_sensitivities is a closed enum: $SENSITIVITIES"
        }

        val request = buildJsonObject {
            // Keys alphabetical, matching the Foundry's own canonical form. The adapter
            // compares a key SET and does not care, but a stable order is what lets a test
            // assert the exact bytes -- and byte-exactness is the only way a request this
            // sensitive stays reviewable.
            putJsonObject("access") {
                putJsonArray("allowed_sensitivities") {
                    for (sensitivity in allowedSensitivities) add(sensitivity)
                }
            }
            put("answer_mode", QUERY_ANSWER_MODE)
            put("limits", JsonObject(emptyMap()))
            putJsonObject("pack_scope") {
                // `include` with exactly one pack. `all` is representable in the schema and
                // is never sent: a question asked from an expert's own screen is a question
                // about that expert, and quietly widening it to every mounted pack would
                // attribute another pack's material to the one on screen.
                put("mode", "include")
                putJsonArray("pack_ids") { add(packId) }
            }
            put("provider_id", JsonNull)
            put("query_mode", QUERY_MODE)
            put("role", QUERY_ROLE)
            put("schema", QUERY_REQUEST_SCHEMA)
            put("text", text)
        }

        return HarnessDecoder.STRICT.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("request", request) },
        )
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
