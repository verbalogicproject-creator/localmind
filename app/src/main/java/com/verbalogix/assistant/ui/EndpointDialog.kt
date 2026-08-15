package com.verbalogix.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.EndpointUrl
import com.verbalogix.assistant.data.EndpointVerdict
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.ui.theme.LocalmindTheme

/**
 * Add or edit an endpoint.
 *
 * The app previously had no way to reach this at all: three providers were seeded at
 * 127.0.0.1:8090 and the picker could only choose between them. On the build author's
 * phone that is correct and invisible; on anyone else's it is an app where every
 * message fails and nothing on screen suggests a remedy.
 *
 * The verdict is computed as the user types, and it is shown as a HINT until they try
 * to save. Turning the field red while someone is still typing "h" is noise; saying
 * nothing until they press Save and then refusing is worse.
 */
@Composable
fun EndpointDialog(
    editing: Provider?,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, baseUrl: String, model: String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var url by remember { mutableStateOf(editing?.baseUrl ?: "https://") }
    var model by remember { mutableStateOf(editing?.model ?: "") }
    var attempted by remember { mutableStateOf(false) }

    val verdict = EndpointUrl.inspect(url)
    val usable = verdict is EndpointVerdict.Usable

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "Add endpoint" else "Edit endpoint") },
        text = {
            // Scrollable because three text fields plus their supporting lines plus a
            // raised keyboard do not fit a short screen, and AlertDialog clips rather
            // than scrolls its content slot -- so the Save button would be reachable
            // while the URL field was not.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Any OpenAI-compatible server: llama.cpp, llama-swap, vLLM, Ollama.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    singleLine = true,
                    isError = attempted && !usable,
                    // KeyboardType.Uri, not Text: it suppresses auto-capitalisation and
                    // autocorrect, which mangle hostnames, and puts / and . on the main
                    // key plane. One parameter instead of three, and it is the stable one.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    supportingText = { VerdictHint(verdict, onUseHttps = { url = it }) },
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    supportingText = { Text("Defaults to the host.") },
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    // The empty case is not a lesser version of the filled one. Empty
                    // means "whatever this server has loaded", which is how a plain
                    // llama-server works and what /props reports. A name here switches
                    // the client to the llama-swap protocol, where the field selects
                    // which model to start.
                    supportingText = {
                        Text("Leave blank for a single-model server. A name asks a router for that model.")
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    attempted = true
                    val v = verdict
                    if (v is EndpointVerdict.Usable) {
                        onSave(editing?.id, name, v.normalized, model)
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Delete is offered ONLY for endpoints the user added. A seeded one is
                // reinserted by ensureDefaults on next launch, so the button would
                // report a success that reverses itself overnight.
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * The supporting line under the URL field.
 *
 * The cleartext case gets a tappable fix rather than only an explanation. It is the
 * single most likely thing to be typed — a LAN address for a server on a laptop — and
 * the platform refuses it in a way that looks exactly like the server being down.
 */
@Composable
private fun VerdictHint(verdict: EndpointVerdict, onUseHttps: (String) -> Unit) {
    when (verdict) {
        is EndpointVerdict.Usable -> Text("")

        is EndpointVerdict.Malformed -> Text(
            verdict.reason,
            color = MaterialTheme.colorScheme.error,
        )

        is EndpointVerdict.BlockedCleartext -> Column {
            Text(
                "Android blocks plain http to ${verdict.host}. Only this device's own " +
                    "127.0.0.1 is allowed in the clear.",
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onUseHttps(EndpointUrl.asHttps(verdict.normalized)) }) {
                Text("Use https instead")
            }
        }
    }
}

@Preview
@Composable
private fun EndpointDialogPreview() {
    LocalmindTheme {
        EndpointDialog(
            editing = null,
            canDelete = false,
            onDismiss = {},
            onSave = { _, _, _, _ -> },
            onDelete = {},
        )
    }
}
