package com.verbalogix.assistant.ui.evidence

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verbalogix.assistant.data.Citation
import com.verbalogix.assistant.data.CitationCodec
import com.verbalogix.assistant.data.MessageDao
import com.verbalogix.assistant.data.capability.CapabilitySource
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.nav.ARG_MESSAGE_ID
import com.verbalogix.assistant.ui.nav.RouteArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the evidence surface can be showing.
 *
 * These map ONE-TO-ONE onto states that can actually be observed from a persisted row,
 * and no further. `state-catalog.json` also lists `retrieving`, `generating`, `refused`
 * and `failed` as grounding states; none of them is recoverable from what is stored
 * today, so none of them appears here. Adding a branch this app cannot reach would be a
 * state that renders in a screenshot and never in life.
 */
sealed interface EvidenceUiState {

    data object Loading : EvidenceUiState

    /** The route addressed a row that is not there -- cleared, or never existed. */
    data object MessageNotFound : EvidenceUiState

    /**
     * Retrieval never ran, so there is no opinion to report.
     *
     * THE IMPORTANT CASE, and the reason `Message.grounded` is a nullable Boolean rather
     * than a plain one. A direct llama.cpp server has no retrieval; rendering that as
     * "not grounded" would make every ordinary direct answer look suspect, and a user
     * who learns to ignore an ungrounded warning is a user for whom the warning has
     * stopped working.
     */
    data object NoRetrieval : EvidenceUiState

    /** Grounded, with the citations that were stored alongside the answer. */
    data class Grounded(val citations: List<Citation>) : EvidenceUiState

    /**
     * Grounded was claimed and nothing was cited.
     *
     * A CONTRACT VIOLATION SURFACED, not a display state. `state-catalog.json` requires
     * `grounded-success-requires-receipt-and-citations` and
     * `missing-receipt-is-visible-error`, so this renders as an error rather than as an
     * empty list -- an empty list looks like "nothing to see", which is exactly the
     * wrong reading.
     */
    data object ReceiptMissing : EvidenceUiState

    /** Retrieval ran and found no supporting passage. */
    data object Abstained : EvidenceUiState
}

/**
 * Backs the evidence drawer for one message.
 *
 * READS ONLY WHAT WAS ALREADY PERSISTED. It performs no retrieval and asks no Harness
 * anything -- `query.retrieve` is a capability this client does not have. What it shows
 * is the evidence that was stored with the answer when the answer arrived, which is the
 * difference between a claim you can check tomorrow and one you had to trust as it
 * scrolled past.
 */
@HiltViewModel
class EvidenceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    dao: MessageDao,
    capabilities: CapabilitySource,
) : ViewModel() {

    /**
     * Re-validated here even though the graph validated it on the way in.
     *
     * `SavedStateHandle` is restored across process death from a bundle the app does
     * not fully control, so the value arriving here is not necessarily the value that
     * was checked when the route was built. Validating at the point of USE rather than
     * only at the point of construction is what makes that irrelevant.
     */
    private val messageId: Long? =
        RouteArgs.rowIdOrNull(savedStateHandle.get<String>(ARG_MESSAGE_ID))

    /**
     * Whether deeper inspection is offered at all.
     *
     * Unavailable in every shipping build today. Surfaced rather than hidden so a
     * paired Foundry that silently lapses is visible, instead of looking identical to
     * a feature that was never built.
     */
    val reQueryCapability: StateFlow<CapabilityState> = capabilities.capabilities()
        .map { it.evidenceQuery }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.verbalogix.assistant.data.capability.Capabilities.NONE.evidenceQuery,
        )

    val state: StateFlow<EvidenceUiState> =
        if (messageId == null) {
            flowOf(EvidenceUiState.MessageNotFound)
        } else {
            dao.observeById(messageId).map { message ->
                when {
                    message == null -> EvidenceUiState.MessageNotFound
                    // Null is "never asked", and it is checked FIRST so it can never
                    // fall through into the ungrounded branch.
                    message.grounded == null -> EvidenceUiState.NoRetrieval
                    message.grounded == false -> EvidenceUiState.Abstained
                    else -> {
                        val citations = CitationCodec.decode(message.citations)
                        if (citations.isEmpty()) {
                            EvidenceUiState.ReceiptMissing
                        } else {
                            EvidenceUiState.Grounded(citations)
                        }
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EvidenceUiState.Loading)
}
