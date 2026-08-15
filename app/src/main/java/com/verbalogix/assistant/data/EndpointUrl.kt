package com.verbalogix.assistant.data

import java.net.URI

/**
 * What a typed endpoint URL is, decided BEFORE anything tries to connect to it.
 *
 * This exists because two of the three ways a hand-typed endpoint fails produce the
 * same symptom — a connection error — and only one of them is about the network:
 *
 *   a trailing slash    LlamaClient composes "$baseUrl/v1/models", so "http://h:8080/"
 *                       requests "//v1/models". Some servers 404, some redirect, and
 *                       the error says nothing about the slash.
 *   cleartext blocked   Android has refused cleartext HTTP by default since API 28.
 *                       The request never reaches the network; the app sees a failure
 *                       that looks exactly like a server being down. Diagnosing this
 *                       from the symptom means knowing the policy exists.
 *
 * Deciding it here rather than at the socket is the difference between "cannot reach
 * 192.168.1.5" — which sends someone to check their router — and "Android blocks
 * plain http to anything but this device."
 *
 * java.net.URI, not android.net.Uri, so this is testable on the JVM rung. android.net
 * is stubbed in unit tests and every method returns null, which would make the tests
 * pass while asserting nothing.
 */
sealed interface EndpointVerdict {

    /** Safe to store. [normalized] is what should be persisted, not what was typed. */
    data class Usable(val normalized: String) : EndpointVerdict

    /** Not a URL this app can use at all. [reason] is written to be shown as-is. */
    data class Malformed(val reason: String) : EndpointVerdict

    /**
     * Well-formed, and the platform will refuse it. Still carries [normalized] so the
     * caller can offer the https:// form rather than only saying no.
     */
    data class BlockedCleartext(val normalized: String, val host: String) : EndpointVerdict
}

object EndpointUrl {

    /**
     * The hosts network_security_config.xml permits cleartext to. Keep the two in
     * step: a name here that the XML does not list produces a dialog that says "fine"
     * and a request the platform kills, which is worse than either alone.
     */
    private val CLEARTEXT_OK = setOf("127.0.0.1", "localhost")

    fun inspect(raw: String): EndpointVerdict {
        val text = raw.trim()
        if (text.isEmpty()) return EndpointVerdict.Malformed("Enter an endpoint URL.")

        val scheme = text.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty()) {
            return EndpointVerdict.Malformed(
                "Include the scheme, e.g. http://192.168.1.5:8080",
            )
        }
        if (scheme != "http" && scheme != "https") {
            return EndpointVerdict.Malformed("Only http:// and https:// are supported.")
        }

        val uri = runCatching { URI(text) }.getOrNull()
            ?: return EndpointVerdict.Malformed("That is not a valid URL.")
        val host = uri.host
            ?: return EndpointVerdict.Malformed("No host in that URL.")

        // A path is not rejected -- a reverse proxy may well serve the API under one --
        // but a TRAILING SLASH is silently removed, because the client appends its own.
        val normalized = text.trimEnd('/')

        return if (scheme == "http" && host !in CLEARTEXT_OK) {
            EndpointVerdict.BlockedCleartext(normalized, host)
        } else {
            EndpointVerdict.Usable(normalized)
        }
    }

    /** The https:// form of a blocked URL, to offer as the fix. */
    fun asHttps(normalized: String): String = "https://" + normalized.removePrefix("http://")
}
