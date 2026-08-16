package com.verbalogix.assistant.data.harness

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the access token, in memory, for exactly as long as the process lives.
 *
 * NEVER PERSISTED, and that is enforced by construction rather than by discipline:
 *
 *  - not a Room entity, so no DAO can write it;
 *  - not `Parcelable`, `Serializable` or `@Serializable`, so it cannot ride a saved
 *    instance state bundle or be encoded by kotlinx.serialization;
 *  - [toString] is overridden, so it cannot reach Logcat, a crash report, or a string
 *    template by accident. That single override is the difference between a secret and
 *    a secret in a bug report.
 *
 * Process death therefore looks exactly like never having paired, which is correct: the
 * pairing credential is one-use, the session is short-lived and rotating, and a token
 * that survived a restart would be a token outliving the guarantees it was issued under.
 *
 * Not a cache. There is no read-through, no reissue-on-miss, no fallback. Absent means
 * absent, and the caller shows "Pair again".
 */
class HarnessCredentials {

    private val current = AtomicReference<AccessToken?>(null)

    /**
     * Replace the held token, atomically, returning the value now in force.
     *
     * Rotation is a swap and never a two-step, because a window in which both the
     * outgoing and incoming tokens are held is a window in which a request can be sent
     * with the one the server has already retired.
     */
    fun replace(token: AccessToken?): AccessToken? {
        current.set(token)
        return token
    }

    fun peek(): AccessToken? = current.get()

    /** Drop the token. Called on every path into `PairAgain`, including expiry. */
    fun clear() {
        current.set(null)
    }

    /**
     * An opaque bearer credential.
     *
     * The value is `internal` so no UI module can render it, and [toString] deliberately
     * does not include it. The prior brief required that no plaintext bearer token reach
     * logs, Room, screenshots or error messages; a data class would have defeated all
     * four at once, since its generated `toString` prints every property and Kotlin
     * string templates call it silently.
     */
    class AccessToken internal constructor(
        internal val value: String,
        val expiresAtEpochSeconds: Long,
        val scopes: Set<HarnessScope>,
    ) {
        /** Enough to debug a session, with nothing that could authorise a request. */
        override fun toString(): String =
            "AccessToken(expiresAt=$expiresAtEpochSeconds, scopes=${scopes.map { it.wire }})"
    }
}
