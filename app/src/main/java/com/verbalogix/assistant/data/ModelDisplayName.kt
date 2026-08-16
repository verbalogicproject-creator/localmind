package com.verbalogix.assistant.data

import java.text.Normalizer

/**
 * The server's own name for a model, made safe to render — or refused.
 *
 * EPHEMERAL, AND THAT IS THE WHOLE POINT. llama-swap reports a display name per model
 * ("LFM2.5 8B", "Bonsai 8B (1-bit)"), and showing it means a rename in the server's
 * config appears in the app without an app change. It is never written to Room, never
 * overwrites the seeded provider row, and does not survive the process. Configuration
 * belongs to the provider record; this is presentation, and the two must not merge --
 * persisting it would let a server rename silently rewrite the user's own configuration.
 *
 * IDENTITY IS NEVER THIS STRING. Matching is by the stable server id (`lfm-8b`,
 * `qwen-4b`, `bonsai-8b`) in every case. A display name is a label; if it were also the
 * key, a server-side rename would silently repoint the user's selected endpoint at a
 * different model, which is the failure this separation exists to prevent.
 *
 * TREATED AS UNTRUSTED INPUT, because it is: it arrives over the network and is rendered
 * directly. The specific hazards are not hypothetical for a label drawn into a
 * single-line row --
 *
 *   - bidirectional overrides (U+202A..U+202E, U+2066..U+2069) can visually reorder text
 *     so the rendered name differs from its bytes;
 *   - line separators turn a one-line row into a multi-line one and break the layout;
 *   - zero-width and other format characters allow two distinct names to look identical;
 *   - an unbounded string pushes every sibling out of the row.
 *
 * Anything failing these checks is not sanitised into shape -- it is REFUSED, and the
 * caller falls back to the name the user already configured. Silently repairing hostile
 * input produces a name that is neither what the server sent nor what the user chose.
 */
object ModelDisplayName {

    /**
     * Generous for real names, far below anything that could disrupt a row.
     * "Bonsai 8B (1-bit)" is 17 characters; the longest plausible real name is well
     * under this.
     */
    const val MAX_LENGTH = 64

    /**
     * Sanitise a server-reported name, or return null to fall back.
     *
     * @return a safe single-line name, or null when the server sent nothing usable.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null

        // NFC first, so equivalent sequences compare and measure consistently, and so a
        // decomposed form cannot smuggle a combining character past a later check.
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty()) return null

        // Length AFTER normalisation and trimming: a name that is only long because of
        // padding is not too long, and normalisation can change length either way.
        if (normalized.length > MAX_LENGTH) return null

        for (ch in normalized) {
            if (isUnsafe(ch)) return null
        }
        return normalized
    }

    /**
     * Characters refused outright.
     *
     * Checked by Unicode CATEGORY rather than by an enumerated blocklist. A list of known
     * bad code points is the kind of nearly-right defence that passes review and misses
     * the next one added to the standard; the categories are stable and cover the class.
     */
    private fun isUnsafe(ch: Char): Boolean = when (Character.getType(ch).toByte()) {
        // Cc control, Cf format (includes bidi overrides and zero-width joiners),
        // Cs surrogate, Co private use, Cn unassigned.
        Character.CONTROL,
        Character.FORMAT,
        Character.SURROGATE,
        Character.PRIVATE_USE,
        Character.UNASSIGNED,
        // Zl and Zp are line and paragraph separators: a single line must stay one line.
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        -> true

        else -> false
    }

    /**
     * What to show for a model: the server's name when it is safe, else the configured one.
     *
     * @param serverName the untrusted `name` from `/v1/models`.
     * @param configuredName the seeded or user-set provider name, always shown when the
     *   server offers nothing usable. Never null, so there is always something to render.
     */
    fun resolve(serverName: String?, configuredName: String): String =
        sanitize(serverName) ?: configuredName
}
