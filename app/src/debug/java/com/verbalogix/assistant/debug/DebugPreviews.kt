package com.verbalogix.assistant.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.verbalogix.assistant.ui.experts.ExpertDetailScreen
import com.verbalogix.assistant.ui.experts.ExpertDetailUiState
import com.verbalogix.assistant.ui.experts.ExpertLibraryScreen
import com.verbalogix.assistant.ui.experts.ExpertLibraryUiState
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import com.verbalogix.assistant.ui.tools.ToolApprovalSheet
import com.verbalogix.assistant.ui.tools.ToolApprovalState

/**
 * Previews of the branches a shipping build cannot reach.
 *
 * THEY LIVE HERE, IN `src/debug`, FOR THE SAME REASON THE FIXTURES DO. A release build
 * does not compile this source set, so none of this — and none of the fake data it
 * renders — is present in a shipping APK. Putting these next to the screens in `main`
 * with a `BuildConfig.DEBUG` guard would ship the fixtures and merely hide them.
 *
 * The runtime states are previewed beside their screens in `main`, deliberately: what a
 * build actually shows deserves the preview that a reader finds first.
 */

@Preview(name = "Experts · populated (debug fixture)", showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ExpertLibraryPopulatedPreview() {
    LocalmindTheme(darkTheme = true) {
        ExpertLibraryScreen(
            state = ExpertLibraryUiState.Ready(fakeExperts),
            onOpenExpert = { _, _ -> },
        )
    }
}

@Preview(name = "Experts · empty (debug fixture)", showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ExpertLibraryEmptyPreview() {
    LocalmindTheme(darkTheme = true) {
        ExpertLibraryScreen(
            state = ExpertLibraryUiState.Empty,
            onOpenExpert = { _, _ -> },
        )
    }
}

@Preview(name = "Expert detail · populated (debug fixture)", showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ExpertDetailPopulatedPreview() {
    LocalmindTheme(darkTheme = true) {
        ExpertDetailScreen(
            state = ExpertDetailUiState.Ready(fakeExpertDetail),
            onBack = {},
        )
    }
}

/**
 * The approval sheet with a proposal AND a decision sink — the only configuration in
 * which its buttons are enabled anywhere, and it exists solely so the layout can be
 * reviewed before the contract lands.
 */
@Preview(name = "Tool approval · awaiting (debug fixture)", showBackground = true, backgroundColor = 0xFF131312)
@Composable
private fun ToolApprovalAwaitingPreview() {
    val sink = RecordingDecisionSink()
    LocalmindTheme(darkTheme = true) {
        ToolApprovalSheet(
            state = ToolApprovalState.Awaiting(fakeToolProposal()),
            onDismiss = {},
            onDecision = sink::accept,
        )
    }
}
