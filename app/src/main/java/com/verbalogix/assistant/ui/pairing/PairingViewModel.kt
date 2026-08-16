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

    init {
        // Starting here rather than in the repository's own constructor keeps the
        // rotation loop tied to a scope something owns. `viewModelScope` on a singleton
        // repository's first consumer is the earliest honest owner available.
        repository.start(viewModelScope)
    }

    /**
     * Offer a pasted credential.
     *
     * The value travels through the injectable source rather than being handed to the
     * repository directly, so the Foundry's forthcoming Termux bridge can supply
     * credentials by the same route with nothing downstream changing.
     */
    fun pair(line: String) {
        viewModelScope.launch {
            if (credentials.offer(line)) {
                // Capabilities are read after the session lands, not before: an unpaired
                // client asking what it may do gets a refusal, which would be reported as
                // a failure rather than as the ordinary state it is.
                repository.refreshCapabilities()
            }
        }
    }
}
