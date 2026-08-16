package com.verbalogix.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.Citation
import com.verbalogix.assistant.data.CitationCodec
import com.verbalogix.assistant.data.Message
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.data.ServerStatus
import com.verbalogix.assistant.ui.theme.LocalmindTheme

/**
 * Transcribed from the approved prototype rather than interpreted from a picture.
 *
 * The prototype was authored under Compose constraints on purpose -- flex row/column
 * only, no absolute positioning, tokens named as Material 3 ColorScheme roles, a 4dp
 * grid -- so each element here has a direct counterpart rather than an equivalent.
 * That is the difference between transcription and translation: the usual failure
 * when porting HTML is nested-container garbage, because HTML positions absolutely
 * while Compose is constraint-based.
 *
 * Every value is a PARAMETER. Sample content lives in @Preview and nowhere else.
 *
 * WHAT LEFT THIS FILE. The provider picker, the endpoint editor and the delete
 * affordance moved to `ModelsProvidersScreen`. They were private internals here,
 * reachable only through a dropdown inside the status strip -- a component whose job is
 * to REPORT, not to configure. What stays is a compact, tappable status for the active
 * provider, which is the one provider fact a conversation needs on screen.
 *
 * The chat path itself is untouched: same transcript, same composer, same send path,
 * same grounding rendering.
 */
@Composable
fun ChatScreen(
    messages: List<Message>,
    status: ServerStatus,
    sending: Boolean,
    onSend: (String) -> Unit,
    onRetryStatus: () -> Unit,
    buildLabel: String,
    provider: Provider?,
    elapsed: Int?,
    think: Boolean,
    onToggleThink: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenEvidence: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip(
                status = status,
                onRetry = onRetryStatus,
                buildLabel = buildLabel,
                provider = provider,
                onOpenProviders = onOpenProviders,
                elapsed = elapsed,
                think = think,
                onToggleThink = onToggleThink,
            )

            val listState = rememberLazyListState()
            // Follow the conversation as it grows, without stealing scroll from a
            // user who has deliberately gone back to read something.
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }

            if (messages.isEmpty()) {
                EmptyTranscript(
                    status = status,
                    provider = provider,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onAddEndpoint = onOpenProviders,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageRow(message, onOpenEvidence)
                    }
                }
            }

            Composer(sending = sending, onSend = onSend)
        }
    }
}

/**
 * What the app says before it has been used — and, on a device that is not the build
 * author's, what it says forever unless this screen explains the way out.
 *
 * Every seeded endpoint points at 127.0.0.1:8090, which is llama-swap running in
 * Termux on THIS phone. That is right for the machine this was built on and wrong for
 * every other one, where the status strip reads "unreachable" and nothing suggests
 * that a remedy exists. An empty transcript is the one moment where there is room to
 * say so, so it says the specific thing — which address is silent — rather than a
 * generic welcome.
 */
@Composable
private fun EmptyTranscript(
    status: ServerStatus,
    provider: Provider?,
    onAddEndpoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        if (status.reachable) {
            Text(
                "Ready.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Everything you type stays on this device. Ask it something.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            "No server at ${provider?.baseUrl?.removePrefix("http://") ?: "the configured endpoint"}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            "Localmind is a client. It needs an OpenAI-compatible server — llama.cpp, " +
                "llama-swap, vLLM or Ollama — and it does not bundle one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            "On this device: run llama-server in Termux on port 8090, then tap retry " +
                "in the strip above. Nothing leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            "Somewhere else: add its address. It must be https — Android blocks plain " +
                "http to anything but this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Text(
            "Add endpoint",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onAddEndpoint),
        )
    }
}

/**
 * The design thesis, made concrete: a cloud assistant hides its machinery, this one
 * shows it. Each value answers a question you would otherwise leave the app to ask.
 */
@Composable
private fun StatusStrip(
    status: ServerStatus,
    onRetry: () -> Unit,
    buildLabel: String,
    provider: Provider?,
    onOpenProviders: () -> Unit,
    elapsed: Int?,
    think: Boolean,
    onToggleThink: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Tappable when the server is down. The server lives in Termux with a
                // lifecycle independent of this app, so "reachable" observed a minute
                // ago is not evidence about now -- the user needs a way to re-ask.
                .then(if (status.reachable) Modifier else Modifier.clickable { onRetry() })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (elapsed != null) {
                    // A request is in flight. On a swap endpoint the first one starts a
                    // model process, which measures ~35s before a single token exists.
                    // A spinner alone reads as hung, and the natural response to a hung
                    // app is to kill it -- throwing away a load that had nearly
                    // finished. The count is the whole difference.
                    if (provider?.isSwap != true) {
                        Stat(
                            "model",
                            status.model ?: "…",
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Stat(
                        if (status.modelLoaded == false) "loading" else "waiting",
                        "${elapsed}s",
                        live = true,
                    )
                } else if (status.reachable) {
                    // For a SWAP provider the model name is the id we asked for, which
                    // the picker already shows one line below -- so printing it here
                    // said the same thing twice and, worse, did it badly: squeezed
                    // between "state" and "tok/s" it truncated to "b…" and "q…", which
                    // is a label conveying nothing at all.
                    //
                    // A DIRECT provider is the opposite case: its name comes from the
                    // server's /props, so it is the only place the actually-loaded
                    // weights are named, and it is worth the width.
                    if (provider?.isSwap != true) {
                        // The only unbounded value here, so the one that must yield.
                        // Without weight(fill = false) it steals width from the stats
                        // after it and "24.4" wraps to two lines.
                        Stat(
                            "model",
                            status.model ?: "loaded",
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    status.contextSize?.let { Stat("ctx", it.toString()) }
                    // Only a swap endpoint can report this; a direct server holds its
                    // model for as long as the process lives, so the field is null and
                    // nothing is shown rather than a meaningless "loaded".
                    status.modelLoaded?.let { Stat("state", if (it) "resident" else "idle") }
                    Spacer(Modifier.weight(1f))
                    status.tokensPerSecond?.let { Stat("tok/s", "%.1f".format(it), live = true) }
                } else {
                    // The most likely state on first run, so it gets a real
                    // instruction rather than a red dot.
                    //
                    // weight(fill = false) IS LOAD-BEARING, and its absence was visible
                    // on a real device: the error text is a whole URL ("no server on
                    // http://127.0.0.1:8090"), it is the only unbounded value in this
                    // row, and without a weight it takes every pixel it wants. That
                    // left "tap to retry" with about one character of width, so it
                    // wrapped to eleven lines down the right-hand edge and dragged the
                    // strip's height with it.
                    //
                    // The same failure, with the same fix, is documented twenty lines
                    // above for the `model` stat. It was applied there and not here --
                    // which is the ordinary way a known bug survives: fixed where it
                    // was noticed, left everywhere else.
                    Stat(
                        "server",
                        status.error ?: "not reachable",
                        error = true,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "tap to retry",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        // Never wrap. This is an instruction; broken across lines it
                        // stops reading as one.
                        maxLines = 1,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // THE PROVIDER NAME IS THE ONLY VARIABLE-LENGTH ITEM HERE, so it is the
                // one that must yield. Observed on a device: "Bonsai 8B · 1-bit" is
                // half again as long as "LFM2.5 8B", which pushed the build label past
                // the right edge -- it wrapped to two lines AND lost its leading space,
                // rendering as "think offv0.0.1-dev-debug ·/local". The two shorter
                // provider names fit, so the strip looked correct on two of three
                // endpoints, which is exactly how this survives a review.
                // NESTED ROW, AND NOT A WEIGHTED SPACER. Compose measures a Row's
                // UNWEIGHTED children first and lets the weighted ones divide what is
                // left -- so two children each carrying weight(1f) split the remainder
                // equally regardless of what either actually needs.
                //
                // That was the first attempt, and it traded one bug for another: the
                // provider link and a weight(1f) spacer took half the free space each,
                // so "LFM2.5 8B" ellipsised to "LFM2.5 …" while a wide gap sat empty
                // beside it. Correct on Bonsai, wrong on the other two -- the same
                // shape of mistake as the bug it replaced.
                //
                // Here `buildLabel` is unweighted, so it is measured first and gets its
                // full intrinsic width. The nested Row takes everything remaining, and
                // inside it "think off" is unweighted while the provider link carries
                // weight(1f, fill = false) -- meaning it takes its natural width when
                // that fits, and ellipsises only when it genuinely cannot.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActiveProviderLink(
                        provider = provider,
                        onOpenProviders = onOpenProviders,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(12.dp))
                    // Sits next to the provider because it is the same kind of
                    // decision: which machine answers, and how hard it works first.
                    Text(
                        text = if (think) "think ON" else "think off",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (think) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = Modifier
                            .clickable { onToggleThink() }
                            .padding(vertical = 2.dp),
                    )
                }
                // A fixed minimum gap. The previous weight(1f) spacer collapsed to zero
                // under pressure, which is what ran "think off" and the version string
                // together into "think offv0.0.1-dev-debug".
                Spacer(Modifier.width(12.dp))
                // Build provenance stays on screen. It belongs to the same thesis as
                // the rest of this strip -- the build IS part of the machinery -- and
                // it is what makes a screenshot evidence rather than an impression.
                Text(
                    buildLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Never wrap. A version string broken across lines stops being
                    // scannable, which is the only reason it is on screen.
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Which endpoint is answering, and the way to somewhere that can change it.
 *
 * `provider` and `status.model` remain two different facts and both are still shown:
 * the provider is the endpoint you chose, the model is what that server reports it
 * actually loaded. They disagree exactly when something is wrong -- you switched to the
 * NPU port and it is still serving the old weights -- and collapsing them into one
 * label would hide the only symptom.
 *
 * What changed is that this is now a LINK rather than a menu. Choosing, adding, editing
 * and deleting live on `ModelsProvidersScreen`; a status strip that also mutated
 * configuration was doing two jobs, and the second one was buried three taps deep
 * inside the first.
 */
@Composable
private fun ActiveProviderLink(
    provider: Provider?,
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            // 48dp is the accessibility floor from the design contract, applied as a
            // MINIMUM so the row is still free to grow when the font scale does.
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onOpenProviders)
            .semantics {
                contentDescription =
                    "Provider ${provider?.name ?: "none selected"}. Opens models and providers."
            }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            provider?.name ?: "no provider",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // A chevron pointing right, not down: this opens a screen now, it does not
        // drop a menu. The glyph is the only thing telling the user which, so it is
        // worth getting right.
        Text(
            "\u203a",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Stat(
    key: String,
    value: String,
    live: Boolean = false,
    error: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = when {
                error -> MaterialTheme.colorScheme.error
                live -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** Row(rail, Column) -- the prototype's transcript shape, not chat bubbles. */
@Composable
private fun MessageRow(message: Message, onOpenEvidence: (Long) -> Unit) {
    // Reasoning collapses; everything else does not.
    //
    // The thesis of this UI is that the machinery is the product, and that still
    // holds -- but showing reasoning expanded by default inverted it. Measured on
    // device: Qwen3.5-4B spent roughly two thousand tokens deliberating the word
    // "hi", producing THREE SCREENS of thinking above a one-line answer. The
    // machinery was not being revealed, it was burying the thing the user asked for.
    //
    // Collapsed-with-a-count keeps both: the reasoning is visibly present, its size
    // is stated as a fact, and reading it is one tap. Nothing is hidden -- it is
    // folded, which is a different thing.
    var expanded by remember(message.id) { mutableStateOf(false) }
    val isThinking = message.role == "thinking"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = when (message.role) {
                "user" -> "YOU"
                "error" -> "ERROR"
                "thinking" -> "THINKING"
                else -> "LOCALMIND"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when (message.role) {
                "user" -> MaterialTheme.colorScheme.primary
                "error" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(72.dp),
        )

        if (isThinking && !expanded) {
            // Words, not characters or tokens: the app cannot count the model's
            // tokens without its tokeniser, and stating a number it did not measure
            // would be the same class of mistake this project keeps finding.
            val words = message.content.split(Regex("\\s+")).count { it.isNotEmpty() }
            Text(
                text = "thought for $words words · tap to read",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .clickable { expanded = true },
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (isThinking) Modifier.clickable { expanded = false } else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (message.role) {
                        "error" -> MaterialTheme.colorScheme.error
                        // Dimmed: present and readable, but visibly not the answer.
                        "thinking" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onBackground
                    },
                )
                // Grounding is rendered only where it was actually reported. A direct
                // server has no retrieval and therefore no opinion, so it shows
                // nothing -- rather than an absence that would read as "not grounded".
                message.grounded?.let {
                    Grounding(
                        grounded = it,
                        citations = CitationCodec.decode(message.citations),
                        // The evidence affordance is offered ONLY where a verdict was
                        // actually reported, which is the same condition as rendering
                        // grounding at all. An "open evidence" link on a direct answer
                        // would promise a drawer that can only say "no retrieval ran".
                        onOpenEvidence = { onOpenEvidence(message.id) },
                    )
                }
            }
        }
    }
}

/**
 * Where an answer came from, or the fact that it came from nowhere.
 *
 * THE UNGROUNDED CASE IS THE REASON THIS EXISTS. An answer with no evidence behind it
 * must not look like one with evidence: rendered identically, a confident guess becomes
 * indistinguishable from a cited fact, which is the most damaging thing this interface
 * could do. So ungrounded gets the error colour and says so in words, and it is the
 * branch written first rather than the fallback.
 */
@Composable
private fun Grounding(
    grounded: Boolean,
    citations: List<Citation>,
    onOpenEvidence: () -> Unit,
) {
    if (!grounded) {
        // Still tappable. "Why does it say that?" is the first question an ungrounded
        // answer provokes, and the drawer is where it is answered -- so the state that
        // most needs explaining is not the one state without a way in.
        Text(
            "not grounded \u2014 no supporting passage was found",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clickable(onClick = onOpenEvidence)
                .semantics { contentDescription = "Not grounded. Opens evidence." },
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        citations.forEach { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "[${c.n}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column {
                    // Source first, quote second. The question a reader has is "can I
                    // check this?", and the answer is the document and page.
                    Text(
                        buildString {
                            append(c.document)
                            c.page?.let { append("  p$it") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (c.quote.isNotBlank()) {
                        Text(
                            "\u201c${c.quote}\u201d",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (citations.isEmpty()) {
            // Grounded but citing nothing is a contract violation, not a display
            // state. Saying so is more useful than rendering a confident bare answer.
            Text(
                "grounded, but the Harness returned no citations",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // One entry point for the whole evidence set, rather than a link per citation.
        // The question is about the ANSWER -- what stands behind it -- and a link on
        // each row would answer a narrower question nobody asked.
        Text(
            "open evidence ›",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clickable(onClick = onOpenEvidence)
                .semantics { contentDescription = "Open evidence for this answer" },
        )
    }
}

@Composable
private fun Composer(sending: Boolean, onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about anything on this device…") },
                enabled = !sending,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (draft.isNotBlank()) { onSend(draft); draft = "" }
                }),
            )
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(
                    onClick = { if (draft.isNotBlank()) { onSend(draft); draft = "" } },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = if (draft.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

// Sample content lives here and nowhere else.
private val previewProviders = listOf(
    Provider(1, "LFM2.5 8B", "http://127.0.0.1:8090", model = "lfm-8b", isActive = true),
    Provider(2, "Qwen3.5 4B", "http://127.0.0.1:8090", model = "qwen-4b"),
    Provider(3, "Bonsai 8B \u00b7 1-bit", "http://127.0.0.1:8090", model = "bonsai-8b"),
)

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ChatScreenPreview() {
    LocalmindTheme(darkTheme = true) {
        ChatScreen(
            messages = listOf(
                Message(1, "user", "What did I write down about the thermal governor?", 0),
                Message(2, "assistant",
                    "Your notes say to pause and checkpoint at THERMAL_STATUS_SEVERE and resume " +
                        "at LIGHT, and never to run training on Dispatchers.Default.", 0),
            ),
            status = ServerStatus(
                reachable = true,
                model = "LFM2.5-1.2B-Instruct-Q4_K_M",
                contextSize = 8192,
                tokensPerSecond = 22.4,
            ),
            sending = false,
            onSend = {},
            onRetryStatus = {},
            buildLabel = "v0.0.2 · abc1234",
            provider = previewProviders.first(),
            elapsed = null,
            think = false,
            onToggleThink = {},
            onOpenProviders = {},
            onOpenEvidence = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ChatScreenOfflinePreview() {
    LocalmindTheme(darkTheme = true) {
        ChatScreen(
            messages = emptyList(),
            status = ServerStatus(reachable = false, error = "no server on http://127.0.0.1:8080"),
            sending = false,
            onSend = {},
            onRetryStatus = {},
            buildLabel = "v0.0.2 · abc1234",
            provider = previewProviders.first(),
            elapsed = null,
            think = false,
            onToggleThink = {},
            onOpenProviders = {},
            onOpenEvidence = {},
        )
    }
}
