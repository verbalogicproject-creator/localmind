package com.verbalogix.assistant.data.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Canonical JSON, byte-identical to the Foundry's.
 *
 * THIS FILE IS A REIMPLEMENTATION OF SOMEONE ELSE'S BYTES, and that is the whole risk in
 * it. Every digest Localmind computes — the answer, the provider observation, its identity,
 * the assistant-turn request — is checked by the Foundry against a digest it computes from
 * the same object. There is no negotiation and no tolerance: one differing byte anywhere in
 * the tree produces a different SHA-256 and the turn is refused as drift, with an error that
 * says nothing about which byte.
 *
 * So the rules are transcribed from `knowledge_foundry/contracts/canonical.py` rather than
 * from a description of it, and they are:
 *
 *   NFC          every string value and every object KEY is Unicode-normalised
 *   sorted keys  by code point, ascending
 *   compact      `,` and `:` separators, no whitespace anywhere
 *   float-free   floats raise rather than serialise
 *   UTF-8        `ensure_ascii=False`, so non-ASCII travels raw, not as \uXXXX
 *
 * A trailing line feed is added when a canonical value is STORED OR TRANSPORTED, and is
 * NOT part of what gets hashed — `digest()` hashes `canonical_bytes`, which has no
 * terminator. Getting that backwards would break every digest while looking correct.
 *
 * Verified the only way this can be verified: [CanonicalJsonTest] recomputes the four
 * client-side digests in the Stage 3D request golden and requires them to equal the values
 * the Foundry itself wrote there.
 */
object CanonicalJson {

    /**
     * The canonical text of a value, with no terminator.
     *
     * This is what gets hashed. [line] is what gets sent.
     */
    fun text(value: JsonElement): String = buildString { write(value, this) }

    /** Canonical text plus the single terminating line feed, for storage and transport. */
    fun line(value: JsonElement): String = text(value) + "\n"

    fun sha256(value: JsonElement): String = sha256(text(value).toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            // Lowercase hex, and byte-to-int masking that a Kotlin `toString(16)` on a
            // signed byte would get wrong for anything above 0x7f.
            val v = it.toInt() and 0xff
            HEX[v ushr 4].toString() + HEX[v and 0x0f]
        }

    /**
     * The digest of an object with one field removed — the Foundry's `_self_digest`.
     *
     * Used for every self-describing digest in the contract: an object states its own
     * SHA-256, computed over itself WITHOUT that statement. The field must be absent, not
     * empty-string: a `"answer_sha256":""` placeholder left in the basis changes the bytes.
     */
    fun selfDigest(value: JsonObject, field: String): String =
        sha256(JsonObject(value.filterKeys { it != field }))

    /** Set `field` to the digest of everything else. The Foundry's `seal`. */
    fun seal(value: JsonObject, field: String): JsonObject =
        JsonObject(value + (field to JsonPrimitive(selfDigest(value, field))))

    /** `kf:<prefix>:<digest of basis>`. The Foundry's `identity`. */
    fun identity(prefix: String, basis: JsonElement): String = "kf:$prefix:${sha256(basis)}"

    // ── the serialiser ──────────────────────────────────────────────────────

    private fun write(value: JsonElement, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")

            is JsonPrimitive -> when {
                value.isString -> writeString(value.content, out)
                // `true`, `false` and integers travel as-is. A float would be ambiguous
                // across languages -- 1.0 versus 1, exponent form, precision -- which is
                // why the contract forbids them outright rather than picking a format.
                value.content == "true" || value.content == "false" -> out.append(value.content)
                else -> {
                    require(FLOAT !in value.content) {
                        "floats are forbidden in canonical JSON: ${value.content}"
                    }
                    out.append(value.content)
                }
            }

            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }

            is JsonObject -> {
                out.append('{')
                // KEYS ARE NORMALISED BEFORE THEY ARE SORTED, because normalisation can
                // change a key's code points and therefore its position. Sorting first
                // would order the pre-normalised forms and emit them in the wrong places.
                //
                // Sorted by CODE POINT, which is what Python's `sort_keys=True` does.
                // Kotlin's natural String ordering is by UTF-16 code unit and differs for
                // anything above the BMP -- no key in this contract goes near that, and
                // relying on the coincidence is how a contract breaks years later.
                val normalised = value.entries
                    .map { normalize(it.key) to it.value }
                    .sortedWith(compareBy(CODE_POINT_ORDER) { it.first })
                normalised.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
        }
    }

    /**
     * A JSON string, escaped exactly as Python's `json.dumps(ensure_ascii=False)` does.
     *
     * Only the five short escapes and the two mandatory ones. Everything else below 0x20
     * becomes `\u00xx` in LOWERCASE hex; everything at or above 0x20 — including every
     * non-ASCII character — is written raw as UTF-8. An encoder that helpfully escaped
     * non-ASCII would produce valid JSON with a different SHA-256.
     */
    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (character in normalize(value)) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                // Kotlin has no \f escape, so the code point is named rather than pasted
                // as a literal control character into source.
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (character < ' ') {
                        out.append("\\u00")
                        out.append(HEX[(character.code shr 4) and 0x0f])
                        out.append(HEX[character.code and 0x0f])
                    } else {
                        out.append(character)
                    }
            }
        }
        out.append('"')
    }

    private fun normalize(value: String): String =
        if (Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            value
        } else {
            Normalizer.normalize(value, Normalizer.Form.NFC)
        }

    /** Compares by Unicode code point, matching Python's string ordering. */
    private val CODE_POINT_ORDER = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return@Comparator ca - cb
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        (a.length - i) - (b.length - j)
    }

    private val FLOAT = Regex("[.eE]")
    private const val HEX = "0123456789abcdef"
}
