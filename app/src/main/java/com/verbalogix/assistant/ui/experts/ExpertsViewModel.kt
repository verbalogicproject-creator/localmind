package com.verbalogix.assistant.ui.experts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verbalogix.assistant.data.capability.Capabilities
import com.verbalogix.assistant.data.capability.CapabilitySource
import com.verbalogix.assistant.data.LlamaClient
import com.verbalogix.assistant.data.ProviderRepository
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.data.harness.HarnessClient
import com.verbalogix.assistant.data.harness.HarnessErrorCodes
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessSessionRepository
import com.verbalogix.assistant.ui.evidence.GroundedDrafting
import com.verbalogix.assistant.ui.evidence.GroundedTurnController
import com.verbalogix.assistant.ui.evidence.GroundedTurnUiState
import com.verbalogix.assistant.ui.evidence.RetrievalController
import com.verbalogix.assistant.ui.evidence.RetrievalTarget
import com.verbalogix.assistant.ui.evidence.RetrievalUiState
import com.verbalogix.assistant.ui.nav.ARG_RELEASE_ID
import com.verbalogix.assistant.ui.nav.RouteArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val providers: ProviderRepository,
    private val llama: LlamaClient,
) : ViewModel() {

    /**
     * The lookup authority, and the only one.
     *
     * `release_id` is a digest: it cannot drift, be reused, or be ambiguous. Pack id and
     * version are display metadata carried by the RESPONSE, not inputs to finding it --
     * identifying a release by a pack plus a version string means resolving two softer
     * facts to reach an immutable thing that already has its own name.
     */
    private val releaseId: String? =
        RouteArgs.releaseIdOrNull(savedStateHandle.get<String>(ARG_RELEASE_ID))

    private val fetched = MutableStateFlow<ExpertDetailUiState?>(null)

    init {
        viewModelScope.launch {
            capabilities.capabilities().collect { caps ->
                val id = releaseId
                fetched.value = when {
                    caps.expertLibrary !is CapabilityState.Available -> null

                    // A MALFORMED ROUTE ARGUMENT NEVER BECOMES A REQUEST. The identity is
                    // interpolated into a request body, so an unvalidated one reaching the
                    // wire is exactly the failure RouteArgs exists to prevent.
                    id == null -> ExpertDetailUiState.NotFound(
                        savedStateHandle.get<String>(ARG_RELEASE_ID).orEmpty(),
                    )

                    else -> session.bearer()
                        ?.let { client.inspectRelease(it, id).toDetailState() }
                }
                // Discovery rides the same gate as the detail load, so it re-runs when a
                // session arrives rather than only once at construction -- the screen can
                // outlive a pairing, and a capability discovered against a dead session is
                // not a capability.
                discoverGroundedDrafting()
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

    // ── searching this expert ───────────────────────────────────────────────

    /**
     * The retrieval itself, behind a seam so its ordering rules are testable.
     *
     * The lambda is the whole production implementation: take the live bearer, send the
     * question, return the outcome. A null bearer -- no session -- returns null and becomes
     * [com.verbalogix.assistant.ui.evidence.RetrievalUiState.SessionExpired], because the
     * remedy is pairing rather than retrying.
     */
    private val retrieval = RetrievalController(
        scope = viewModelScope,
        source = { text, target ->
            session.bearer()?.let { bearer ->
                client.retrieveEvidence(bearer, text, target.packId, target.allowedSensitivities)
            }
        },
    )

    /**
     * The typed question. IN MEMORY, AND NOWHERE ELSE.
     *
     * NOT in the `SavedStateHandle` this class already holds, and the screen must not use
     * `rememberSaveable` for it either. Both write to saved instance state, which is a
     * Bundle the system may persist to disk and which turns up in bug reports -- so a
     * question typed against a private knowledge base would outlive the session that could
     * read it. A `ViewModel` field survives configuration changes, which is the part users
     * notice, and dies with the process, which is the part that matters.
     *
     * It is also never logged, never written to Room beside a message, and never appended
     * to any history: retrieval here produces evidence, not a conversation.
     */
    private val _queryText = MutableStateFlow("")
    val queryText: StateFlow<String> = _queryText.asStateFlow()

    /** Typing only records text. Nothing is sent until [submitQuery]. */
    fun onQueryChange(value: String) {
        _queryText.value = value
    }

    /**
     * Send the current question about the release on screen.
     *
     * The target is supplied by the caller rather than read from [state], so the question
     * is correlated against the release the USER was looking at when they submitted --
     * not against whatever the flow happens to hold when the reply lands.
     */
    fun submitQuery(target: RetrievalTarget) {
        // A NEW QUESTION CLEARS THE OLD ANSWER. Leaving it on screen beneath fresh evidence
        // would show an answer receipted against a packet that is no longer the one above
        // it -- which is the exact confusion the receipt exists to prevent.
        turn.reset()
        retrieval.submit(_queryText.value, target)
    }

    /**
     * What the retrieval surface shows, gated on `query.retrieve` being declared.
     *
     * The gate is applied here rather than inside the controller so that an unavailable
     * capability keeps saying so while a stale result would otherwise linger: the
     * capability is a property of the live session and can be withdrawn under a screen
     * that is already showing evidence.
     */
    val retrievalState: StateFlow<RetrievalUiState> =
        combine(capabilities.capabilities(), retrieval.state) { caps, current ->
            when (val gate = caps.evidenceQuery) {
                is CapabilityState.Unavailable ->
                    RetrievalUiState.Unavailable(gate.reason, gate.requiredCapability)

                CapabilityState.Available -> current
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RetrievalUiState.Idle)

    // ── drafting a grounded answer ──────────────────────────────────────────

    /**
     * The turn: Localmind calls the model, the Foundry decides whether it is grounded.
     *
     * PROVIDER OWNERSHIP IS UNCHANGED. Localmind calls llama-swap through the same
     * [LlamaClient] chat uses; the Foundry never calls a provider, and nothing here asks it
     * to. The two lambdas are the only places those two systems are touched, which is what
     * makes the ordering rules testable without either of them.
     */
    private val turn = GroundedTurnController(
        scope = viewModelScope,
        generate = { messages ->
            val provider = providers.active()
            llama.completeTurn(provider.baseUrl, provider.model, messages)
        },
        finalize = { request ->
            session.bearer()?.let { bearer ->
                client.finalizeTurn(bearer, request).also { outcome ->
                    // RE-DISCOVERY IS TRIGGERED WHERE THE OUTCOME IS SEEN, not on a timer
                    // and not on every token refresh. The Foundry's promise is about the
                    // current server instance or build, so the codes meaning "the other end
                    // is no longer the one you discovered against" are exactly the ones
                    // that invalidate what this screen holds.
                    if (outcome is HarnessOutcome.Unsuccessful &&
                        HarnessErrorCodes.requiresRenegotiation(outcome.errorCode)
                    ) {
                        discoverGroundedDrafting()
                    }
                }
            }
        },
    )

    val turnState: StateFlow<GroundedTurnUiState> = turn.state

    // ── is grounded drafting offered at all? ────────────────────────────────

    private val _drafting = MutableStateFlow<GroundedDrafting>(GroundedDrafting.Undiscovered)

    /**
     * Whether this server declares `assistant.turn.finalize`.
     *
     * ASKED, NEVER INFERRED. The previous gate offered the action whenever a retrieval had
     * succeeded, which reasons from a related capability to this one. The Foundry has stated
     * that the client must not: absence means hide or disable, and a withdrawn operation
     * must not first appear as a button that fails when pressed.
     *
     * Starts [GroundedDrafting.Undiscovered] rather than optimistic, so the window before an
     * answer arrives offers nothing.
     */
    val draftingState: StateFlow<GroundedDrafting> = _drafting.asStateFlow()

    private fun discoverGroundedDrafting() {
        viewModelScope.launch {
            val bearer = session.bearer()
            if (bearer == null) {
                // No session is not a refusal by the server. Reported as undiscovered so
                // pairing restores the action, rather than leaving a stated "not offered"
                // that never re-evaluates.
                _drafting.value = GroundedDrafting.Undiscovered
                return@launch
            }
            _drafting.value = when (val outcome = client.assistantCapabilities(bearer)) {
                is HarnessOutcome.Decoded -> GroundedDrafting.Offered

                // The server answered and declined. The code is carried so it can be
                // reported rather than guessed at.
                is HarnessOutcome.Unsuccessful -> GroundedDrafting.NotOffered(
                    "This Knowledge Foundry does not offer grounded answers" +
                        (outcome.errorCode?.let { " ($it)" } ?: "") + ".",
                )

                // The server answered something this build will not accept -- a contract
                // disagreement, not an absence. Withheld either way: offering an action
                // whose reply we could not read is the failure this slice removes.
                is HarnessOutcome.Refused -> GroundedDrafting.NotOffered(
                    "This Knowledge Foundry declared a grounded-answer contract this build " +
                        "does not accept, so the action is withheld.",
                )
            }
        }
    }

    /**
     * Draft an answer from the evidence currently on screen.
     *
     * Refuses unless a retrieval has SUCCEEDED and returned items. "Grounded" is defined as
     * derived from a specific evidence packet, so there is nothing to ground on before
     * that — and the screen does not offer the action either.
     */
    fun draftAnswer(target: RetrievalTarget) {
        val current = retrievalState.value
        // THE QUESTION THAT PRODUCED THE EVIDENCE, not whatever is in the field now. Those
        // differ as soon as the box is edited without pressing Retrieve again, and a turn
        // built from the edited text against the older evidence sends a `query_request` and
        // an `expected_evidence` that describe different questions -- which the Foundry
        // correctly reports as drift, blaming the mounted packs for a client-side mistake.
        val question = retrieval.acceptedQuestion ?: return
        // THE CAPABILITY IS CHECKED HERE TOO, not only where the button is drawn. The screen
        // already withholds the action, so this is the second lock on the same door -- and
        // it is the one that holds if a caller ever reaches this method another way.
        if (_drafting.value != GroundedDrafting.Offered) return
        if (current is RetrievalUiState.Ready && current.evidence.items.isNotEmpty()) {
            turn.submit(question, target, current.evidence)
        }
    }

    /** The malformed-argument case, distinguished from "valid but unknown". */
    val argumentsValid: Boolean = releaseId != null

    val unavailableFallback: CapabilityState.Unavailable =
        Capabilities.NONE.expertLibrary as CapabilityState.Unavailable
}
