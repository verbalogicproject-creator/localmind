package com.verbalogix.assistant.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilitySource
import com.verbalogix.assistant.data.settings.SetupPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * State the shell itself needs, as opposed to state a screen needs.
 *
 * Two things only: where to start, and which destinations may be offered. Keeping this
 * separate from [com.verbalogix.assistant.ui.ChatViewModel] matters because the shell
 * outlives every screen in it -- folding these into the chat view model would mean the
 * navigation bar's contents depended on a chat session having been constructed.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val setupPreferences: SetupPreferences,
    capabilities: CapabilitySource,
) : ViewModel() {

    /**
     * Read SYNCHRONOUSLY, once, before the first frame.
     *
     * A start destination that arrives asynchronously means the NavHost is built with a
     * guess and corrected a frame later, which the user sees as the setup screen
     * flashing past on every launch -- and which puts a spurious entry in the back
     * stack. SharedPreferences' first read is a disk hit, but it is one the framework
     * has already made by the time a composable runs.
     */
    val startDestination: String =
        if (setupPreferences.isSetupCompleted()) Destinations.CHAT else Destinations.SETUP_READINESS

    val capabilities: StateFlow<Capabilities> = capabilities.capabilities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Capabilities.NONE)

    /**
     * Recorded when the user chooses to continue, however they continue.
     *
     * See [SetupPreferences.markSetupCompleted]: this records a CHOICE, not a state the
     * system reached, which is what keeps a user with no server from being trapped.
     */
    fun completeSetup() = setupPreferences.markSetupCompleted()
}
