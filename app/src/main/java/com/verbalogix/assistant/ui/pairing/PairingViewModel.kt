package com.verbalogix.assistant.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verbalogix.assistant.data.harness.HarnessSessionRepository
import com.verbalogix.assistant.data.harness.HarnessSessionState
import com.verbalogix.assistant.data.harness.ManualPairingCredentialSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Exposes the session, and offers a credential when the user supplies one.
 *
 * A THIN SEAM ON PURPOSE. Everything interesting -- rotation timing, terminal causes,
 * scope admission -- lives in [HarnessSessionRepository] and its policy, which are
 * testable without Android. This class does the two things a view model is for: it hands
 * the UI a `StateFlow`, and it forwards one user action.
 *
 * The repository is a `@Singleton`, so the session survives navigating away from whatever
 * screen showed the panel. That matters: a session bound to a composable's lifetime would
 * end when the user opened Chat, which is the screen they paired in order to use.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: HarnessSessionRepository,
    private val credentials: ManualPairingCredentialSource,
) : ViewModel() {

    val session: StateFlow<HarnessSessionState> = repository.session

    // NO init BLOCK, deliberately. This used to call `repository.start(viewModelScope)`,
    // which was wrong in two ways at once: this view model is created by BOTH the Setup
    // and the Experts destinations, so the session started twice with two rotation loops
    // -- and a nav entry's scope dies when the user navigates away, so leaving Experts
    // stopped the timer and the session expired behind their back. The repository owns
    // its own lifetime now; a screen must not own an app-wide session.

    /**
     * Offer a pasted credential.
     *
     * The value travels through the injectable source rather than being handed to the
     * repository directly, so the Foundry's forthcoming Termux bridge can supply
     * credentials by the same route with nothing downstream changing.
     */
    fun pair(line: String) {
        // OFFER AND NOTHING ELSE. An earlier version also called `refreshCapabilities()`
        // here, which raced: `offer` returns when the rendezvous receiver TAKES the
        // value, not when the exchange completes, so the read usually ran with no token
        // held and reported Capabilities.NONE over a session that was about to succeed.
        // Reading capabilities is now part of adopting a token, inside the repository,
        // where there is no moment at which it can be asked too early.
        viewModelScope.launch { credentials.offer(line) }
    }
}
