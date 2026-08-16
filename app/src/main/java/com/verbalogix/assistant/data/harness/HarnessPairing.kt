package com.verbalogix.assistant.data.harness

import java.security.SecureRandom

/**
 * The one-use pairing credential, and the client identity that accompanies it.
 *
 * The operator runs the Harness in Termux, which writes a pairing line once to a file
 * descriptor with a **60-second** lifetime, and conveys it to the app. That is the whole
 * trust transfer: a short-lived, single-use, operator-mediated string exchanged
 * immediately for a rotating read-only session.
 *
 * PARSED STRICTLY, AND NOT STORED. The credential is exchanged the moment it is accepted
 * and is never held beyond that call -- it is one-use, so keeping it buys nothing and
 * risks everything. With a 60-second window there is no version of "save it for later"
 * that works.
 */
object HarnessPairing {

    /**
     * Both pairing and access tokens use the `kft2.<payload>.<signature>` form, per
     * `localmind-token-response/1.0`'s `access_token` pattern. Base64url alphabet only:
     * no padding, no `+`, no `/`.
     */
    private val TOKEN = Regex("""^kft2\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")

    /**
     * A pasted line is not necessarily a token.
     *
     * Operators paste from a terminal, which brings whitespace, a trailing newline, and
     * sometimes a shell prompt or a surrounding quote. Trimming those is helping; anything
     * beyond them is guessing, so a line that is not exactly one token after trimming is
     * refused rather than salvaged.
     */
    fun parsePairingLine(line: String?): String? {
        if (line == null) return null
        val trimmed = line.trim().trim('"', '\'')
        return if (TOKEN.matches(trimmed)) trimmed else null
    }

    fun isWellFormedToken(token: String): Boolean = TOKEN.matches(token)

    /**
     * A fresh client identity, 32 lowercase hex characters, per process.
     *
     * MEMORY-ONLY AND REGENERATED EVERY LAUNCH, matching the token's own lifetime. A
     * persisted client id would be a stable identifier for an app that deliberately keeps
     * nothing: it would outlive the credential it accompanies and become the one durable
     * thing linking sessions together, which is the opposite of what a session that
     * forgets itself on process death is for.
     *
     * `SecureRandom` rather than `Random`: this value is echoed back by the server and
     * bound into the session, so a predictable one weakens a binding that exists to make
     * a stolen token useless elsewhere.
     */
    fun newClientInstanceId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** `^[0-9a-f]{32}$`, as `localmind-token-response/1.0` requires. */
    fun isWellFormedClientInstanceId(value: String): Boolean =
        value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
