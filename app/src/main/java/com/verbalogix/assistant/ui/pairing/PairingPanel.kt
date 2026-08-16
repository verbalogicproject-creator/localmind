package com.verbalogix.assistant.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import com.verbalogix.assistant.data.harness.HarnessPairing
import com.verbalogix.assistant.data.harness.HarnessSessionState
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.components.MARK_ERROR
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.MARK_OK
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.theme.AmberTokens
import com.verbalogix.assistant.ui.theme.LocalmindTheme

const val TAG_PAIRING_PANEL = "pairing-panel"
const val TAG_PAIRING_INPUT = "pairing-input"
const val TAG_PAIRING_SUBMIT = "pairing-submit"

/**
 * The Localmind ↔ Knowledge Foundry session, as the user sees it.
 *
 * FIVE STATES, and each says something different about what to do next:
 *
 *   NotPaired   nothing has been exchanged; here is how
 *   Pairing     a credential is being exchanged
 *   Connected   a live session, with when it renews
 *   Refreshing  a rotation is in flight; NOT an error and NOT a disconnection
 *   PairAgain   the session ended, recoverably, and the cause is named
 *
 * `Refreshing` earns its own state rather than being folded into `Connected`. Rotation
 * happens on a timer while the session is still healthy -- the rotating token is what
 * authorises its own replacement, so renewal is only possible BEFORE expiry -- and a UI
 * that showed "connected" throughout would leave a user watching a request stall with no
 * idea why. Showing it also makes the proactive design visible instead of mysterious.
 *
 * PAIR AGAIN IS NOT AN ERROR DIALOG. Every route into it is recoverable by one action the
 * user can take, so it reads as an instruction with a cause attached, not as a failure.
 */
@Composable
fun PairingPanel(
    state: HarnessSessionState,
    onPair: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AmberPanel(modifier = modifier.fillMaxWidth().testTag(TAG_PAIRING_PANEL)) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            Text(
                "Knowledge Foundry",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            when (state) {
                HarnessSessionState.NotPaired -> NotPairedContent(onPair)

                HarnessSessionState.Pairing -> StatusLine(
                    mark = MARK_INFO,
                    label = "Pairing…",
                )

                is HarnessSessionState.Connected -> {
                    StatusLine(
                        mark = MARK_OK,
                        label = "Connected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        // What it can do, in the user's terms. The four scopes are all
                        // read-only, and saying so is the honest summary of the grant.
                        "Read-only access to the expert catalog and evidence. This app " +
                            "cannot install, activate or change a pack.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is HarnessSessionState.Refreshing -> {
                    StatusLine(mark = MARK_INFO, label = "Renewing the session…")
                    Text(
                        "Sessions are short-lived and renew themselves before they expire.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is HarnessSessionState.PairAgain -> {
                    StatusLine(mark = MARK_ERROR, label = "Pair again")
                    // The CAUSE, verbatim. "Your session ended" and "the Foundry
                    // restarted, which ends every session it issued" have the same
                    // remedy and are different events, and only one of them will happen
                    // again the moment the Harness restarts.
                    Text(
                        state.cause.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    NotPairedContent(onPair)
                }
            }
        }
    }
}

/**
 * How to get a credential, and where to put it.
 *
 * The instruction names the command because the credential is operator-mediated: the
 * Harness writes it once, to a file descriptor, and it lives for SIXTY SECONDS. A user who
 * has to go and read documentation first will lose the window, so the step is on screen.
 *
 * The field is validated on submit rather than per keystroke: a paste arrives complete,
 * and marking a half-typed value invalid teaches the user to ignore the marking.
 */
@Composable
private fun NotPairedContent(onPair: (String) -> Unit) {
    var line by rememberSaveable { mutableStateOf("") }
    var rejected by rememberSaveable { mutableStateOf(false) }

    Text(
        "Run the Foundry's pairing command in Termux and paste the line it prints. " +
            "It is single-use and expires after about a minute.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = line,
        onValueChange = {
            line = it
            rejected = false
        },
        label = { Text("Pairing line") },
        singleLine = true,
        isError = rejected,
        supportingText = if (rejected) {
            {
                Text(
                    // Says what was wrong with the INPUT, not that the user failed. A
                    // truncated paste is the likeliest cause by far.
                    "That does not look like a pairing line. It should begin with " +
                        "\"kft2.\" and have three parts.",
                )
            }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_PAIRING_INPUT),
    )
    Button(
        onClick = {
            val parsed = HarnessPairing.parsePairingLine(line)
            if (parsed == null) {
                rejected = true
            } else {
                // Exchanged immediately and never stored: it is one-use, so holding it
                // buys nothing, and with a 60-second life there is no "later".
                onPair(parsed)
                line = ""
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .minimumTouchTarget()
            .testTag(TAG_PAIRING_SUBMIT),
    ) { Text("Pair") }
}

@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun PairingNotPairedPreview() {
    LocalmindTheme(darkTheme = true) {
        PairingPanel(state = HarnessSessionState.NotPaired, onPair = {})
    }
}
