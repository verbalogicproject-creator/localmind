package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRequest
import com.verbalogix.assistant.data.harness.wire.QueryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a retrieval actually goes.
 *
 * A `fun interface` rather than the client itself, for one reason: the ordering and gating
 * rules in [RetrievalController] are the part that can be wrong in ways a person would not
 * notice, and they must be testable without a socket. The production implementation is one
 * lambda in [com.verbalogix.assistant.ui.experts.ExpertDetailViewModel] that calls
 * `HarnessClient.retrieveEvidence` with the live bearer.
 *
 * Returning null means THERE IS NO SESSION — distinct from a session the Harness rejected,
 * which comes back as an outcome carrying an adapter code.
 */
internal fun interface RetrievalSource {
    suspend fun retrieve(text: String, target: RetrievalTarget): HarnessOutcome<QueryResult>?
}

/**
 * Turns explicit submissions into at most one displayed answer, in order.
 *
 * NOT A VIEW MODEL, deliberately. Everything here is plain state and a coroutine scope, so
 * the rules below can be exercised on the JVM against a fake source rather than only on a
 * device — and these are rules whose failures are invisible: a stale answer looks exactly
 * like a fresh one.
 *
 * THE QUESTION IS NEVER PERSISTED. It is held in memory only — see `accepted` — for as long
 * as the evidence it produced is on screen. Nothing writes it to Room, to a
 * `SavedStateHandle`, to a crash record or to a log, and it dies with the process. The
 * screen's copy lives in a ViewModel field for the same reason — see
 * [com.verbalogix.assistant.ui.experts.ExpertDetailViewModel].
 *
 * This paragraph used to say the question was never held AT ALL, which stopped being true
 * when `accepted` was added so a turn could bind the question that produced its evidence.
 * Corrected rather than left standing: a comment claiming a stronger property than the code
 * provides is worse than no comment, because it is what a reviewer checks instead of the
 * code.
 *
 * NO REQUEST PER KEYSTROKE. There is no text-change entry point at all; [submit] is the
 * only way in, and it is wired to a button and to the keyboard's own submit action. Typing
 * a question into a field is not the same act as sending it to a knowledge base, and a
 * search-as-you-type retrieval would send a dozen partial questions — each one a real
 * request against a real expert — on the way to the one the user meant.
 */
internal class RetrievalController(
    private val scope: CoroutineScope,
    private val source: RetrievalSource,
) {

    private val _state = MutableStateFlow<RetrievalUiState>(RetrievalUiState.Idle)
    val state: StateFlow<RetrievalUiState> = _state.asStateFlow()

    /**
     * Which submission the displayed answer is allowed to come from.
     *
     * ORDERING IS ENFORCED HERE AND NOWHERE ELSE, and the in-flight coroutine is
     * deliberately NOT cancelled when a newer question is asked.
     *
     * Cancelling would feel tidier and would not be the guarantee. The bytes are already
     * on the wire; cancelling a coroutine does not recall a request, and the property that
     * matters is not "the old work stops" but "the old ANSWER can never replace a newer
     * one". Cancellation also cannot promise that: a coroutine already past its last
     * suspension point runs to completion regardless, which is precisely the window where
     * an overtaken response would be published.
     *
     * So there is one mechanism, it is checked on the assignment itself, and it is
     * exercised by every request rather than only by the racy ones. Atomic because the
     * ticket is written on the caller's thread and read on the scope's.
     */
    private val issued = AtomicLong(0)

    /**
     * The question that produced the evidence currently on screen.
     *
     * NOT the text in the field. Those differ the moment a user edits the box without
     * pressing the button again, and an assistant turn built from the edited text against
     * the older evidence is a request whose `query_request` and `expected_evidence`
     * describe different questions. The Foundry re-runs the query, gets a different packet,
     * and reports `evidence-drift` -- blaming the mounted packs for a client-side
     * inconsistency, which is exactly the confusion the drift check exists to avoid.
     *
     * Written only when a state is actually published, so it cannot describe an answer the
     * screen is not showing.
     */
    @Volatile
    private var accepted: String? = null

    /** The question behind the displayed evidence, or null when none is displayed. */
    val acceptedQuestion: String? get() = accepted

    /**
     * Ask, having been asked to.
     *
     * The gates run in order of how much they cost to be wrong about:
     *
     *  1. An empty question does nothing at all — not an error, just no act.
     *  2. An inactive release is refused BEFORE the wire. The screen does not offer the
     *     field in that case, so this is the second lock on the same door; the failure it
     *     prevents is a question answered from whatever is mounted instead, which reads as
     *     a working feature.
     *  3. Text the contract forbids is refused locally, so the user is told what is wrong
     *     with their question rather than shown an opaque `request-invalid`.
     */
    fun submit(text: String, target: RetrievalTarget) {
        val question = text.trim()
        if (question.isEmpty()) return

        if (!target.active) {
            publish(issued.incrementAndGet(), RetrievalUiState.InactiveRelease)
            return
        }
        if (!HarnessRequest.isSendableQueryText(question)) {
            publish(
                issued.incrementAndGet(),
                RetrievalUiState.Refused(
                    "A question must be 1 to ${HarnessRequest.MAX_QUERY_CHARS} characters " +
                        "and cannot contain line breaks or control characters.",
                ),
            )
            return
        }

        val ticket = issued.incrementAndGet()
        _state.value = RetrievalUiState.Querying
        scope.launch {
            // A null source result means no bearer was held. Reported as an expired
            // session rather than a refusal: there is a remedy, and it is pairing.
            val outcome = source.retrieve(question, target)
            val state = outcome?.toRetrievalState(target) ?: RetrievalUiState.SessionExpired(null)
            publish(ticket, state, question.takeIf { state is RetrievalUiState.Ready })
        }
    }

    /**
     * Publish only if nothing newer has been asked since.
     *
     * [question] is recorded alongside the state so the two cannot drift apart: a gate
     * failure clears it, and only a Ready state carries one.
     */
    private fun publish(ticket: Long, state: RetrievalUiState, question: String? = null) {
        if (ticket == issued.get()) {
            _state.value = state
            accepted = question
        }
    }
}
