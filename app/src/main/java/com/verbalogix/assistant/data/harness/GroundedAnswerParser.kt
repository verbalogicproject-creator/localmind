package com.verbalogix.assistant.data.harness

/**
 * Turns what a model wrote into segments that cite evidence — or refuses to.
 *
 * THE MODEL NEVER HANDLES AN EVIDENCE ID. It is shown a numbered list and writes `[1]`,
 * `[2]`; this maps those indices back to identities the client already holds. That is a
 * stronger guarantee than checking ids after the fact: a hallucinated
 * `kf:evidence:0000…` is a 64-character string that looks exactly like a real one and
 * would have to be rejected by lookup, whereas a hallucinated `[9]` against three items is
 * out of range by construction. The instruction says model-invented ids must be rejected;
 * this makes them unrepresentable.
 *
 * WHAT COUNTS AS A CLAIM. A paragraph carrying at least one citation marker is a `claim`
 * and must cite; a paragraph carrying none is an `uncertainty` and need not. The Foundry
 * enforces the same rule and will refuse the turn if this gets it wrong — so this is the
 * client saying the same thing first, in a message a person can act on.
 *
 * MARKERS ARE STRIPPED FROM THE TEXT. The answer that is displayed is exactly the answer
 * that is digested and receipted, so the citation lives in `evidence_ids` rather than in
 * prose. Nothing else is edited: no summarising, no reordering, no repair of a sentence
 * that reads badly.
 */
object GroundedAnswerParser {

    /** What a parse produced, or why there is nothing to send. */
    sealed interface Result {
        data class Parsed(val segments: List<AssistantTurnRequest.Segment>) : Result

        /**
         * The model's output cannot become a grounded answer.
         *
         * NEVER SILENTLY REPAIRED. Every one of these is a case where a lenient parser
         * could produce something plausible — drop the bad citation, merge the empty
         * paragraph, truncate at sixty-four — and every such repair would attach a receipt
         * to an answer the model did not actually give.
         */
        data class Unusable(val reason: String) : Result
    }

    /**
     * @param output the model's text, verbatim.
     * @param evidenceIds the selected evidence, in the order the prompt numbered it.
     *   `[1]` is the first element; the list is the entire vocabulary of citable things.
     */
    fun parse(output: String, evidenceIds: List<String>): Result {
        // \r\n is normalised because a model trained on Windows-flavoured text emits it and
        // the contract forbids a bare \r. A lone \r that survives is refused rather than
        // stripped: it means something else is producing this text.
        val text = output.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return Result.Unusable("the model returned no text")
        if ('\r' in text || '\u0000' in text) {
            return Result.Unusable("the model's text contains characters the contract forbids")
        }

        val segments = mutableListOf<AssistantTurnRequest.Segment>()
        for (paragraph in text.split(PARAGRAPH)) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue

            val indices = MARKER.findAll(trimmed)
                .flatMap { match -> match.groupValues[1].split(',') }
                .mapNotNull { it.trim().toIntOrNull() }
                .toList()

            for (index in indices) {
                if (index < 1 || index > evidenceIds.size) {
                    // The hallucination case, and the whole point of numbering. Refused
                    // rather than dropped: an answer that cited something imaginary is not
                    // improved by removing the citation and keeping the sentence.
                    return Result.Unusable(
                        "the model cited item $index, and only ${evidenceIds.size} were given",
                    )
                }
            }

            val body = trimmed.replace(MARKER, "").replace(SPACES, " ").trim()
            if (body.isEmpty()) continue
            if (body.length > MAX_SEGMENT_CHARS) {
                return Result.Unusable("a segment is longer than the contract allows")
            }

            val cited = indices.map { evidenceIds[it - 1] }.distinct().sorted()
            segments += AssistantTurnRequest.Segment(
                // A paragraph with no marker is not a failed claim; it is the model
                // hedging, which the contract has a kind for.
                kind = if (cited.isEmpty()) SchemaIds.SEGMENT_UNCERTAINTY else SchemaIds.SEGMENT_CLAIM,
                text = body,
                evidenceIds = cited,
            )
        }

        if (segments.isEmpty()) return Result.Unusable("the model produced no usable text")
        if (segments.size > MAX_SEGMENTS) {
            // Truncating would send an answer that stops mid-argument while claiming to be
            // the whole of what the model said.
            return Result.Unusable("the model produced more segments than the contract allows")
        }
        return Result.Parsed(segments)
    }

    /** A blank line. The contract's own separator, so segments and paragraphs agree. */
    private val PARAGRAPH = Regex("\n[ \t]*\n")

    /** `[1]`, `[2,3]`, `[1, 2]` — one marker, one or more indices. */
    private val MARKER = Regex("""\[\s*(\d+(?:\s*,\s*\d+)*)\s*]""")

    private val SPACES = Regex("[ \t]+")

    private const val MAX_SEGMENTS = 64
    private const val MAX_SEGMENT_CHARS = 8192
}
