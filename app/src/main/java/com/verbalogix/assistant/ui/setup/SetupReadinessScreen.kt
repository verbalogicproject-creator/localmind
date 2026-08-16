package com.verbalogix.assistant.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.verbalogix.assistant.data.Provider
import com.verbalogix.assistant.data.ServerStatus
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.data.harness.HarnessSessionState
import com.verbalogix.assistant.ui.components.AmberPanel
import com.verbalogix.assistant.ui.components.MARK_ERROR
import com.verbalogix.assistant.ui.components.MARK_INFO
import com.verbalogix.assistant.ui.components.MARK_OK
import com.verbalogix.assistant.ui.components.StatusLine
import com.verbalogix.assistant.ui.components.minimumTouchTarget
import com.verbalogix.assistant.ui.pairing.PairingPanel
import com.verbalogix.assistant.ui.theme.AmberTokens
import com.verbalogix.assistant.ui.theme.LocalmindTheme

const val TAG_SETUP = "setup-readiness"
const val TAG_CONTINUE_DIRECT = "continue-direct-chat"

/**
 * What is actually true on this device, before the user is asked to do anything.
 *
 * EVERY VALUE ON THIS SCREEN IS AN OBSERVATION. There is no "Ready offline", no
 * "System integrity verified", no detected model name, no context size, no accelerator
 * and no RAM figure unless a server has reported one this session. The approved
 * screenshot states all of those as facts; none of them was measured, and a readiness
 * screen that asserts readiness it did not check is worse than no readiness screen at
 * all -- it teaches the user to believe it.
 *
 * IT LAUNCHES NOTHING. No Termux intent, no model load, no service start. The app is a
 * client and starts nothing, which is also what keeps it inside Android's UID sandbox.
 * The screen reports; the user acts.
 *
 * THE WAY OUT IS ALWAYS OPEN. `Continue to chat` is never gated on readiness. Direct
 * llama.cpp is the proven path and must keep working with no Foundry, no Harness and no
 * packs present -- so a user with none of those is not trapped here, and the button
 * does not change its meaning depending on what was found.
 */
@Composable
fun SetupReadinessScreen(
    provider: Provider?,
    status: ServerStatus,
    foundry: CapabilityState,
    buildLabel: String,
    onContinue: () -> Unit,
    onOpenProviders: () -> Unit,
    /**
     * The Harness session, offered here as well as on Experts.
     *
     * Null means the panel is not shown at all -- used by previews and by any caller that
     * has no session to report. It is never a substitute for `NotPaired`, which is a real
     * state with a real instruction attached.
     */
    session: HarnessSessionState? = null,
    onPair: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_SETUP),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(AmberTokens.mobileMargin),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.mobileMargin),
        ) {
            Text(
                "Localmind",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Everything you type stays on this device. Localmind is a client: it " +
                    "talks to a model server you run, and it starts nothing by itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Optional, and placed after the observations rather than before them: the
            // screen's job is to report what is true, and pairing is an action the user
            // may take once they have read it. Continue to chat is never gated on it.
            if (session != null) {
                PairingPanel(state = session, onPair = onPair)
            }

            // ── What was observed ───────────────────────────────────────────────
            AmberPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(AmberTokens.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
                ) {
                    Text(
                        "Model server",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )

                    if (provider == null) {
                        StatusLine(mark = MARK_INFO, label = "No endpoint selected yet")
                    } else {
                        Text(
                            "${provider.name} · ${provider.baseUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (status.reachable) {
                            StatusLine(
                                mark = MARK_OK,
                                label = "Answered when asked",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            // Shown ONLY where the server reported them. A missing
                            // field renders nothing rather than a plausible default.
                            status.model?.let {
                                StatusLine(mark = MARK_INFO, label = "reports model $it")
                            }
                            status.contextSize?.let {
                                StatusLine(mark = MARK_INFO, label = "reports context $it")
                            }
                        } else {
                            StatusLine(
                                mark = MARK_ERROR,
                                label = status.error ?: "Did not answer",
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "That is normal before you have started one. Run a server " +
                                    "and come back, or point Localmind somewhere else — " +
                                    "either way you can carry on now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── Knowledge Foundry ───────────────────────────────────────────────
            AmberPanel(modifier = Modifier.fillMaxWidth()) {
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
                    when (foundry) {
                        is CapabilityState.Unavailable -> {
                            StatusLine(mark = MARK_INFO, label = "Not connected")
                            Text(
                                foundry.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Requires: ${foundry.requiredCapability}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CapabilityState.Available -> StatusLine(
                            mark = MARK_OK,
                            label = "Connected",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Text(
                "A model is the reasoning engine. An expert is grounded knowledge. " +
                    "Localmind works with just the first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The primary action is never disabled and never depends on what was found.
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumTouchTarget()
                    .testTag(TAG_CONTINUE_DIRECT),
            ) { Text("Continue to chat") }

            // THE ACTION DESCRIBES WHAT IS TRUE NOW.
            //
            // OBSERVED ON A DEVICE: with LFM2.5 8B selected, answering, and reporting
            // `lfm-8b` in the panel directly above, this still read "Choose an endpoint
            // first" -- instructing the user to do a thing the same screen had just
            // shown was done. It also read as a preconditon for the button above it,
            // which was never gated on anything.
            //
            // The label is derived from the same `provider` the panel renders, so the
            // two cannot disagree again.
            val hasEndpoint = provider != null
            OutlinedButton(
                onClick = onOpenProviders,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumTouchTarget(),
            ) {
                Text(if (hasEndpoint) "Change endpoint" else "Choose an endpoint")
            }

            // Build provenance stays on screen for the same reason it does in chat: it
            // is what makes a screenshot evidence rather than an impression.
            Text(
                buildLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun SetupUnreachablePreview() {
    LocalmindTheme(darkTheme = true) {
        SetupReadinessScreen(
            provider = Provider(1, "LFM2.5 8B", "http://127.0.0.1:8090", model = "lfm-8b"),
            status = ServerStatus(reachable = false, error = "no server on 127.0.0.1:8090"),
            foundry = Capabilities.NONE.expertLibrary,
            buildLabel = "v0.0.1-dev · local",
            onContinue = {},
            onOpenProviders = {},
        )
    }
}
