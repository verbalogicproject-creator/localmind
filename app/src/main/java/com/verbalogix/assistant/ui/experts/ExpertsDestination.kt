package com.verbalogix.assistant.ui.experts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verbalogix.assistant.data.harness.HarnessSessionState
import com.verbalogix.assistant.ui.pairing.PairingPanel

/**
 * The Experts destination, exactly as the navigation graph composes it.
 *
 * A NAMED COMPOSABLE RATHER THAN A LAMBDA IN THE NAV HOST, because of a real crash.
 *
 * The destination previously assembled itself inline: `Column(verticalScroll(...))`
 * wrapping the pairing panel and [ExpertLibraryScreen]. That is two vertical scroll
 * owners with a lazy list inside a scrollable parent -- an infinite-height measure, and
 * a hard crash. It survived every rung because the branch that renders it was
 * unreachable: only a NON-EMPTY catalog builds the lazy list, and nothing could produce
 * one until pairing worked. The first successful pairing on a physical phone was also
 * the first time that code had ever run.
 *
 * The tests missed it for a structural reason worth keeping in mind: they exercised
 * `ExpertLibraryScreen` in ISOLATION while the destination wrapped it differently, so
 * the composition under test was never the composition that shipped. Extracting the
 * destination is what closes that gap -- there is now one definition, used by the nav
 * host and by the regression test, and a wrapper added in either place would be visible
 * in the other.
 *
 * ONE SCROLLING OWNER. The panel is passed as [ExpertLibraryScreen]'s `header` slot, so
 * it scrolls with the list instead of beside it. No fixed height is imposed anywhere:
 * capping the list would have hidden the crash while introducing a second bug, where a
 * long catalog is silently unreachable below an arbitrary cut-off.
 */
@Composable
fun ExpertsDestination(
    state: ExpertLibraryUiState,
    session: HarnessSessionState,
    onPair: (String) -> Unit,
    onOpenExpert: (releaseId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpertLibraryScreen(
        state = state,
        onOpenExpert = onOpenExpert,
        modifier = modifier,
        // ABOVE THE LIBRARY, not behind a menu. When the library is unavailable the
        // session is almost always why, so the remedy belongs next to the explanation --
        // and it stays put once connected, because a user whose session has just ended
        // needs to find it in the same place.
        header = { PairingPanel(state = session, onPair = onPair) },
    )
}
