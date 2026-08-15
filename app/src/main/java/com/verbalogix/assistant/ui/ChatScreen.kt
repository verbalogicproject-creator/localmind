package com.verbalogix.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun ChatScreen(
    messages: List<Message>,
    status: ServerStatus,
    sending: Boolean,
    onSend: (String) -> Unit,
    onRetryStatus: () -> Unit,
    buildLabel: String,
    providers: List<Provider>,
    provider: Provider?,
    onSelectProvider: (Long) -> Unit,
    elapsed: Int?,
    think: Boolean,
    onToggleThink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip(
                status, onRetryStatus, buildLabel, providers, provider, onSelectProvider,
                elapsed, think, onToggleThink,
            )

            val listState = rememberLazyListState()
            // Follow the conversation as it grows, without stealing scroll from a
            // user who has deliberately gone back to read something.
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(messages, key = { it.id }) { message -> MessageRow(message) }
            }

            Composer(sending = sending, onSend = onSend)
        }
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
    providers: List<Provider>,
    provider: Provider?,
    onSelectProvider: (Long) -> Unit,
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
                    Stat(
                        "model",
                        status.model ?: "…",
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    Stat(
                        if (status.modelLoaded == false) "loading" else "waiting",
                        "${elapsed}s",
                        live = true,
                    )
                } else if (status.reachable) {
                    // The model name is the only unbounded value here, so it is the
                    // one that must yield. Without weight(fill = false) it steals
                    // width from the stats after it and "24.4" wraps to two lines.
                    Stat(
                        "model",
                        status.model ?: "loaded",
                        modifier = Modifier.weight(1f, fill = false),
                    )
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
                    Stat("server", status.error ?: "not reachable", error = true)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "tap to retry",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderPicker(providers, provider, onSelectProvider)
                Spacer(Modifier.width(12.dp))
                // Sits next to the provider because it is the same kind of decision:
                // which machine answers, and how hard it works before it does.
                Text(
                    text = if (think) "think ON" else "think off",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (think) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clickable { onToggleThink() }
                        .padding(vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                // Build provenance stays on screen. It belongs to the same thesis as
                // the rest of this strip -- the build IS part of the machinery -- and
                // it is what makes a screenshot evidence rather than an impression.
                Text(
                    buildLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Which endpoint is answering, and how to change it.
 *
 * `provider` and `status.model` are two different facts and both are shown: the
 * provider is the endpoint you chose, the model is what that server reports it
 * actually loaded. They disagree exactly when something is wrong -- you switched to
 * the NPU port and it is still serving the old weights -- and collapsing them into one
 * label would hide the only symptom.
 */
@Composable
private fun ProviderPicker(
    providers: List<Provider>,
    current: Provider?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // One configured endpoint is not a choice, so it renders as a plain label. A menu
    // that opens to a single item is a control that does nothing.
    val switchable = providers.size > 1

    Box {
        Row(
            modifier = Modifier
                .then(if (switchable) Modifier.clickable { expanded = true } else Modifier)
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current?.name ?: "no provider",
                style = MaterialTheme.typography.labelSmall,
                color = if (switchable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (switchable) {
                Text(
                    "▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { p ->
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onSelect(p.id)
                    },
                    text = {
                        Column {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                            // The URL is shown, not hidden behind the name. When two
                            // providers are misconfigured to the same port this is the
                            // only place that difference is visible.
                            Text(
                                p.baseUrl.removePrefix("http://"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = if (p.id == current?.id) {
                        {
                            Text(
                                "✓",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
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
private fun MessageRow(message: Message) {
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
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = when (message.role) {
                    "error" -> MaterialTheme.colorScheme.error
                    // Dimmed: present and readable, but visibly not the answer.
                    "thinking" -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onBackground
                },
                modifier = Modifier
                    .weight(1f)
                    .then(if (isThinking) Modifier.clickable { expanded = false } else Modifier),
            )
        }
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
    Provider(1, "LFM2.5 8B", "http://127.0.0.1:8080", isActive = true),
    Provider(2, "Qwen3.5 4B", "http://127.0.0.1:8081"),
    Provider(3, "LFM2.5 8B \u21c4", "http://127.0.0.1:8090", model = "lfm-8b"),
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
            providers = previewProviders,
            provider = previewProviders.first(),
            onSelectProvider = {},
            elapsed = null,
            think = false,
            onToggleThink = {},
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
            providers = previewProviders,
            provider = previewProviders.first(),
            onSelectProvider = {},
            elapsed = null,
            think = false,
            onToggleThink = {},
        )
    }
}
