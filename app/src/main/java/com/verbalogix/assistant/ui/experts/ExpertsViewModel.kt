package com.verbalogix.assistant.ui.experts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilitySource
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.nav.ARG_PACK_ID
import com.verbalogix.assistant.ui.nav.ARG_VERSION
import com.verbalogix.assistant.ui.nav.RouteArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the expert library.
 *
 * It has no expert source, and that is not an omission to be filled in later by this
 * class. Listing mounted packs is `mount.list` on the Foundry Harness -- a capability
 * this client does not have -- so the only truthful state is unavailable, derived from
 * the declared capability rather than hard-coded, so that the day a real client
 * declares `mount.list` this screen starts working without being rewritten.
 *
 * DELIBERATELY NOT A PROVIDER. An expert is knowledge; a provider is an endpoint that
 * runs weights. An earlier design in this project collapsed the two -- "an expert IS a
 * model", routed through `/v1/models` -- and that is explicitly withdrawn here: it
 * would make selecting knowledge indistinguishable from selecting an LLM, and would put
 * pack identity into a field whose meaning belongs to llama-swap.
 */
@HiltViewModel
class ExpertLibraryViewModel @Inject constructor(
    capabilities: CapabilitySource,
) : ViewModel() {

    val state: StateFlow<ExpertLibraryUiState> = capabilities.capabilities()
        .map { caps ->
            when (val gate = caps.expertLibrary) {
                is CapabilityState.Unavailable -> ExpertLibraryUiState.Unavailable(gate)
                // Reachable only once a real client declares the capability. There is
                // no discovery call to make yet, so an available capability with no
                // source is reported as empty rather than as a fabricated list.
                CapabilityState.Available -> ExpertLibraryUiState.Empty
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpertLibraryUiState.Loading)

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
 * Backs one expert's detail surface.
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
) : ViewModel() {

    private val packId: String? =
        RouteArgs.identifierOrNull(savedStateHandle.get<String>(ARG_PACK_ID))
    private val version: String? =
        RouteArgs.identifierOrNull(savedStateHandle.get<String>(ARG_VERSION))

    val state: StateFlow<ExpertDetailUiState> = capabilities.capabilities()
        .map { caps ->
            when (val gate = caps.expertLibrary) {
                is CapabilityState.Unavailable -> ExpertDetailUiState.Unavailable(gate)
                CapabilityState.Available ->
                    // Nothing can look a pack up, so a well-formed route for a pack
                    // that cannot be fetched is reported as not-found rather than as a
                    // blank screen that looks like a loading failure.
                    ExpertDetailUiState.NotFound(packId.orEmpty(), version.orEmpty())
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ExpertDetailUiState.Loading,
        )

    /** The malformed-argument case, distinguished from "valid but unknown". */
    val argumentsValid: Boolean = packId != null && version != null

    val unavailableFallback: CapabilityState.Unavailable =
        Capabilities.NONE.expertLibrary as CapabilityState.Unavailable
}
