package com.verbalogix.assistant.ui.experts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilitySource
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.data.harness.HarnessClient
import com.verbalogix.assistant.data.harness.HarnessSessionRepository
import com.verbalogix.assistant.ui.nav.ARG_PACK_ID
import com.verbalogix.assistant.ui.nav.ARG_VERSION
import com.verbalogix.assistant.ui.nav.RouteArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the expert library.
 *
 * IT NOW HAS A SOURCE. This class held no client at all until the Harness contract closed:
 * listing packs was a capability nothing could exercise, so the only truthful state was
 * unavailable -- DERIVED from the declared capability rather than hard-coded, precisely so
 * that the day a real client arrived the screen would start working without being
 * rewritten. That derivation is what made this a wiring change instead of a rebuild.
 *
 * DELIBERATELY NOT A PROVIDER. An expert is knowledge; a provider is an endpoint that runs
 * weights. An earlier design collapsed the two -- "an expert IS a model", routed through
 * `/v1/models` -- and that stays withdrawn: it would make selecting knowledge
 * indistinguishable from selecting an LLM, and would put pack identity into a field whose
 * meaning belongs to llama-swap.
 */
@HiltViewModel
class ExpertLibraryViewModel @Inject constructor(
    capabilities: CapabilitySource,
    private val session: HarnessSessionRepository,
    private val client: HarnessClient,
) : ViewModel() {

    private val fetched = MutableStateFlow<ExpertLibraryUiState?>(null)

    /**
     * Fetch the catalog whenever the capability opens, and drop it when it closes.
     *
     * Keyed on the GATE rather than on a screen event, so a session arriving while this
     * screen is already open populates it without the user leaving and coming back.
     *
     * Clearing on close is the half that matters: a list from a session that has ended is
     * stale in the one way that does damage, because it still looks current.
     */
    init {
        viewModelScope.launch {
            capabilities.capabilities().collect { caps ->
                fetched.value = if (caps.expertLibrary is CapabilityState.Available) {
                    session.bearer()?.let { client.expertCatalog(it).toLibraryState() }
                } else {
                    null
                }
            }
        }
    }

    val state: StateFlow<ExpertLibraryUiState> =
        combine(capabilities.capabilities(), fetched) { caps, loaded ->
            when (val gate = caps.expertLibrary) {
                is CapabilityState.Unavailable -> ExpertLibraryUiState.Unavailable(gate)
                // Available, fetch not landed. Loading is the honest state: an empty list
                // here would assert "nothing is mounted", which is a claim about the
                // Foundry rather than about this client's progress.
                CapabilityState.Available -> loaded ?: ExpertLibraryUiState.Loading
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ExpertLibraryUiState.Loading,
        )

    /**
     * Whether the shell should offer the Experts destination at all.
     *
     * Exposed separately so the navigation shell can gate the affordance without
     * constructing the whole screen state -- the bottom bar needs one boolean, not a
     * sealed hierarchy.
     */
    val discoveryAvailable: StateFlow<Boolean> = capabilities.capabilities()
        .map { it.expertLibrary is CapabilityState.Available }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/**
 * Backs one release's detail surface.
 *
 * The route arguments are re-validated here for the same reason
 * [com.verbalogix.assistant.ui.evidence.EvidenceViewModel] re-validates its id: a
 * `SavedStateHandle` restored across process death is not guaranteed to hold the value
 * that was checked on the way in.
 */
@HiltViewModel
class ExpertDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    capabilities: CapabilitySource,
    private val session: HarnessSessionRepository,
    private val client: HarnessClient,
) : ViewModel() {

    private val packId: String? =
        RouteArgs.identifierOrNull(savedStateHandle.get<String>(ARG_PACK_ID))
    private val version: String? =
        RouteArgs.identifierOrNull(savedStateHandle.get<String>(ARG_VERSION))

    private val fetched = MutableStateFlow<ExpertDetailUiState?>(null)

    init {
        viewModelScope.launch {
            capabilities.capabilities().collect { caps ->
                val id = packId
                val ver = version
                fetched.value = when {
                    caps.expertLibrary !is CapabilityState.Available -> null

                    // A MALFORMED ROUTE ARGUMENT NEVER BECOMES A REQUEST. The identity is
                    // interpolated into a request body, so an unvalidated one reaching the
                    // wire is exactly the failure RouteArgs exists to prevent.
                    id == null || ver == null -> ExpertDetailUiState.NotFound(
                        packId.orEmpty(), version.orEmpty(),
                    )

                    else -> session.bearer()
                        ?.let { client.inspectRelease(it, id, ver).toDetailState() }
                }
            }
        }
    }

    val state: StateFlow<ExpertDetailUiState> =
        combine(capabilities.capabilities(), fetched) { caps, loaded ->
            when (val gate = caps.expertLibrary) {
                is CapabilityState.Unavailable -> ExpertDetailUiState.Unavailable(gate)
                CapabilityState.Available -> loaded ?: ExpertDetailUiState.Loading
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ExpertDetailUiState.Loading,
        )

    /** The malformed-argument case, distinguished from "valid but unknown". */
    val argumentsValid: Boolean = packId != null && version != null

    val unavailableFallback: CapabilityState.Unavailable =
        Capabilities.NONE.expertLibrary as CapabilityState.Unavailable
}
