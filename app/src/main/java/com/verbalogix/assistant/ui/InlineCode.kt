package com.verbalogix.assistant.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Renders `backtick spans` as monospace, and nothing else.
 *
 * DELIBERATELY NOT A MARKDOWN RENDERER, and the restraint is the feature. A model emitting
 * `**bold**`, a `# heading` or a `| table |` is not a reason to grow one: every construct
 * added is a new way for model output to control what the screen looks like, and a
 * renderer that follows instructions embedded in generated text is a renderer that can be
 * talked into misrepresenting its own UI. One inline construct, no block constructs, no
 * links, no images.
 *
 * The gain is real and narrow. These models are used for code -- Qwen3.5 is seeded here as
 * "Code and structured output" -- and identifiers set in the body font run together with
 * the prose around them: `activateExpert` and "activateExpert" read very differently in a
 * sentence about which method to call.
 *
 * PARSED, NOT REGEXED, because the interesting cases are the malformed ones and a regex
 * over pairs quietly does the wrong thing with an odd count.
 */
object InlineCode {

    private const val TICK = '`'

    /**
     * Split text into runs, marking the ones that were fenced.
     *
     * AN UNPAIRED BACKTICK IS LITERAL TEXT. A model that writes "use the ` character" has
     * not opened a span, and swallowing the rest of the message into monospace because of
     * one stray tick would be a rendering bug indistinguishable from a broken answer. So
     * an opening tick with no partner is emitted as the character it is.
     *
     * AN EMPTY SPAN IS ALSO LITERAL. "``" is two characters, not a zero-width code run.
     */
    fun parse(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                runs += Run(plain.toString(), code = false)
                plain.setLength(0)
            }
        }

        // Only a LONE backtick delimits. A run of two or more is literal text.
        //
        // Found by testing rather than reasoned out: with a naive scan, "```" parsed as an
        // empty pair followed by an opening tick, so a fenced block swallowed its content
        // into one span and SILENTLY DROPPED two backticks from what the model wrote. No
        // block formatting was applied -- the failure was quieter than that -- but text
        // vanishing from an answer is the worst kind of rendering bug, because the reader
        // has no way to know anything is missing.
        //
        // Treating any multi-tick run as literal makes fenced blocks pass through whole
        // and keeps this an inline-only renderer, which is the intent either way.
        fun runLengthAt(index: Int): Int {
            var n = 0
            while (index + n < text.length && text[index + n] == TICK) n++
            return n
        }

        while (i < text.length) {
            val ch = text[i]
            if (ch != TICK) {
                plain.append(ch)
                i++
                continue
            }
            val opening = runLengthAt(i)
            if (opening != 1) {
                repeat(opening) { plain.append(TICK) }
                i += opening
                continue
            }
            // Find the next LONE tick to close on, skipping over multi-tick runs.
            var j = i + 1
            var close = -1
            while (j < text.length) {
                if (text[j] == TICK) {
                    val n = runLengthAt(j)
                    if (n == 1) { close = j; break }
                    j += n
                } else {
                    j++
                }
            }
            if (close == -1 || close == i + 1) {
                // No partner, or an empty pair. Either way it is a character, not a span.
                plain.append(ch)
                i++
                continue
            }
            flushPlain()
            runs += Run(text.substring(i + 1, close), code = true)
            i = close + 1
        }
        flushPlain()
        return runs
    }

    /**
     * Build the annotated string a `Text` renders.
     *
     * Only the FONT FAMILY changes. No background, no border, no colour: a code span that
     * repaints its own background is a span that can be made to look like a system
     * notice, and the whole point of the restriction above is that generated text must not
     * be able to imitate application chrome.
     */
    fun annotate(text: String): AnnotatedString = buildAnnotatedString(parse(text))

    private fun buildAnnotatedString(runs: List<Run>): AnnotatedString {
        val builder = AnnotatedString.Builder()
        for (run in runs) {
            if (run.code) {
                builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                builder.append(run.text)
                builder.pop()
            } else {
                builder.append(run.text)
            }
        }
        return builder.toAnnotatedString()
    }

    /** One stretch of text, and whether it was fenced. */
    data class Run(val text: String, val code: Boolean)
}
