package com.verbalogix.assistant.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.data.ServerStatus
import com.verbalogix.assistant.ui.EndpointDialog
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.components.MARK_ERROR
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.MARK_OK
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.theme.AmberTokens
import com.verbalogix.assistant.ui.theme.LocalmindTheme

const val TAG_MODELS_PROVIDERS = "models-providers"
const val TAG_ADD_ENDPOINT = "add-endpoint"

/**
 * Models and providers, moved out of the chat status strip.
 *
 * WHAT MOVED AND WHY. The picker, the endpoint editor and the delete affordance used to
 * be private internals of `ChatScreen`, reachable only through a dropdown inside a
 * status line. That was the only route to an endpoint the build did not seed -- which,
 * on any device but the author's, is every endpoint that would actually answer -- and
 * it was three taps deep inside a component whose job is to report status. Chat keeps a
 * compact, tappable status for the ACTIVE provider and nothing more.
 *
 * WHAT DID NOT CHANGE. Selection, persistence, seeding, delete protection and URL
 * validation all still live in `ProviderRepository` and `EndpointUrl`. This screen is a
 * new surface onto the same behaviour, not a reimplementation of it -- which is why
 * `EndpointDialog` is reused verbatim rather than rebuilt in an Amber style.
 *
 * NO CLOUD PROVIDERS AND NO CREDENTIAL STORAGE. The approved screenshot shows an
 * "Optional Cloud Providers" card with an add button. A runtime API key inside an APK
 * is public the moment it ships, so there is no such affordance here, and the section
 * is absent rather than present-and-disabled.
 */
@Composable
fun ModelsProvidersScreen(
    providers: List<Provider>,
    provider: Provider?,
    status: ServerStatus,
    onSelectProvider: (Long) -> Unit,
    onSaveEndpoint: (Long?, String, String, String) -> Unit,
    onDeleteEndpoint: (Provider) -> Unit,
    isDefaultProvider: (Provider) -> Boolean,
    onRetryStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two flags rather than one nullable: "add" is `open with no target`, and collapsing
    // the two would make it indistinguishable from closed. Carried over unchanged from
    // ChatScreen, where the same distinction was already load-bearing.
    var editorOpen by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<Provider?>(null) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_MODELS_PROVIDERS),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AmberTokens.mobileMargin),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
        ) {
            Text(
                "Models & providers",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            ActiveStatusPanel(provider = provider, status = status, onRetry = onRetryStatus)

            Text(
                "Endpoints",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            // Not a LazyColumn: the whole screen already scrolls, and nesting a lazy
            // list inside a scrollable column is an unbounded-height crash rather than
            // a layout preference. The provider list is a handful of rows by nature.
            Column(verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit)) {
                providers.forEach { candidate ->
                    ProviderRow(
                        provider = candidate,
                        selected = candidate.id == provider?.id,
                        seeded = isDefaultProvider(candidate),
                        onSelect = { onSelectProvider(candidate.id) },
                        onEdit = {
                            editorTarget = candidate
                            editorOpen = true
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    editorTarget = null
                    editorOpen = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumTouchTarget()
                    .testTag(TAG_ADD_ENDPOINT),
            ) { Text("Add endpoint…") }

            Text(
                "Any OpenAI-compatible server: llama.cpp, llama-swap, vLLM, Ollama. " +
                    "Localmind is a client and does not bundle one. Android blocks plain " +
                    "http to anything but this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editorOpen) {
        val target = editorTarget
        EndpointDialog(
            editing = target,
            canDelete = target != null && !isDefaultProvider(target),
            onDismiss = { editorOpen = false },
            onSave = { id, name, url, model ->
                editorOpen = false
                onSaveEndpoint(id, name, url, model)
            },
            onDelete = {
                editorOpen = false
                target?.let(onDeleteEndpoint)
            },
        )
    }
}

/**
 * What the ACTIVE endpoint is currently reporting.
 *
 * Provider and model stay two distinct facts, exactly as they are in the chat strip.
 * The provider is the endpoint you chose; the model is what that server says it has
 * loaded. They disagree precisely when something is wrong, and collapsing them into one
 * label would hide the only symptom.
 *
 * Nothing here is hard-coded. There is no "device optimized", no RAM figure, no
 * accelerator name and no context size unless the server reported one -- the approved
 * screenshot shows all four, and all four were invented.
 */
@Composable
private fun ActiveStatusPanel(
    provider: Provider?,
    status: ServerStatus,
    onRetry: () -> Unit,
) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Text(
                provider?.name ?: "No provider selected",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            provider?.let {
                Text(
                    it.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // `direct` vs `harness` vs `embedded` is shown, because when retrieval
                // silently stops happening the only other symptom is answers slowly
                // getting worse.
                //
                // MODE AND ROUTING ARE ORTHOGONAL, and showing only the first read as a
                // contradiction on a real device: the seeded llama-swap endpoints are
                // `mode direct` AND swap-routed, so the panel said "mode direct" under
                // a `:8090` proxy and looked simply wrong.
                //
                //   mode     WHO answers      direct | harness | embedded
                //   isSwap   HOW the model is chosen -- a non-empty `model` field means
                //            the request names a model and a proxy starts that one
                //
                // A plain llama-server is direct with no routing; llama-swap is direct
                // WITH routing. One label cannot carry both without lying about one.
                StatusLine(
                    mark = MARK_INFO,
                    label = if (it.isSwap) {
                        "mode ${it.mode} · routed by model name"
                    } else {
                        "mode ${it.mode}"
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (status.reachable) {
                StatusLine(
                    mark = MARK_OK,
                    label = "Reachable",
                    tint = MaterialTheme.colorScheme.primary,
                )
                status.model?.let { Stat("model", it) }
                status.contextSize?.let { Stat("context", it.toString()) }
                status.modelLoaded?.let { Stat("state", if (it) "resident" else "idle") }
                status.tokensPerSecond?.let { Stat("tok/s", "%.1f".format(it)) }
            } else {
                StatusLine(
                    mark = MARK_ERROR,
                    label = status.error ?: "Not reachable",
                    tint = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.minimumTouchTarget(),
                ) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun Stat(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProviderRow(
    provider: Provider,
    selected: Boolean,
    seeded: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    AmberPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
                .clickable(onClick = onSelect)
                // `selected` rather than a colour: TalkBack announces "selected", and a
                // check glyph alone would leave a screen-reader user guessing.
                .semantics { this.selected = selected }
                .padding(AmberTokens.mobileMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            // Text mark, so selection is never carried by colour alone.
            Text(
                if (selected) MARK_OK else MARK_UNSELECTED,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // THE MODEL ID IS THE DIFFERENTIATOR, NOT THE URL.
                //
                // OBSERVED ON A DEVICE: three seeded endpoints -- LFM2.5 8B, Qwen3.5 4B
                // and Bonsai 8B -- all print `127.0.0.1:8090`, because llama-swap serves
                // all three on one port and routes by MODEL NAME. So the line intended
                // to tell rows apart was identical on all of them, and the one value
                // that actually differs was not on screen anywhere.
                //
                // The URL stays: two providers misconfigured to different ports is the
                // case it was added for, and that case is still real.
                Text(
                    buildString {
                        append(provider.baseUrl.removePrefix("http://"))
                        if (provider.model.isNotEmpty()) append(" · ${provider.model}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // One meta line instead of two full-width sentences. At 412dp the old
                // "seeded by this build · cannot be deleted" wrapped mid-phrase onto a
                // second line on every seeded row, which was most of the list -- density
                // spent on a fact that never changes.
                val tags = buildList {
                    if (provider.mode == Provider.MODE_HARNESS) add(MOCK_TAG)
                    if (seeded) add("seeded · cannot be deleted")
                }
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = onEdit,
                modifier = Modifier
                    .minimumTouchTarget()
                    .semantics { contentDescription = "Edit ${provider.name}" },
            ) { Text("Edit") }
        }
    }
}

private const val MARK_UNSELECTED = "○"

/**
 * Says what a harness-mode row IS, rather than leaving it to a name.
 *
 * The only such row today is seeded `Handbook (mock)`, and it is seeded in DEBUG builds
 * only -- so no release build can show this. That is correct and it is not sufficient:
 * on a debug build the row rendered as an ordinary endpoint whose sole hint was three
 * characters inside a display name a user could edit. A parenthetical is not a label.
 *
 * Written as a claim about the backend, because "mock" alone reads like a nickname.
 */
private const val MOCK_TAG = "mock harness · not a real backend"

// Sample content lives here and nowhere else.
private val previewProviders = listOf(
    Provider(1, "LFM2.5 8B", "http://127.0.0.1:8090", model = "lfm-8b", isActive = true),
    Provider(2, "Qwen3.5 4B", "http://127.0.0.1:8090", model = "qwen-4b"),
)

@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ModelsProvidersPreview() {
    LocalmindTheme(darkTheme = true) {
        ModelsProvidersScreen(
            providers = previewProviders,
            provider = previewProviders.first(),
            status = ServerStatus(reachable = true, model = "lfm-8b", contextSize = 8192),
            onSelectProvider = {},
            onSaveEndpoint = { _, _, _, _ -> },
            onDeleteEndpoint = {},
            isDefaultProvider = { true },
            onRetryStatus = {},
        )
    }
}
