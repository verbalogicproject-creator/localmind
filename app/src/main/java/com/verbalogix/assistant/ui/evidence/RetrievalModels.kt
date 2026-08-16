package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.harness.PairAgainCause

/**
 * What a retrieval is allowed to be about.
 *
 * ASSEMBLED FROM ONE INSPECTED RELEASE, and carried through the request and back out
 * again as the thing every part of the reply is checked against. It is not a convenience
 * bundle: it is the correlation key.
 *
 * [allowedSensitivities] is copied from `expert-release-detail/3.0` verbatim. This client
 * has no basis to widen it and no reason to narrow it -- the Foundry stated what this
 * release may surface, and repeating that back is the whole of Localmind's part.
 *
 * [active] gates whether a question may be asked at all. An inactive release is installed
 * and not mounted, so retrieval against it would either fail at the Foundry or -- worse --
 * quietly answer from whatever IS mounted, attributing another release's material to the
 * one on screen.
 */
data class RetrievalTarget(
    val packId: String,
    val releaseId: String,
    val allowedSensitivities: List<String>,
    val active: Boolean,
)

/**
 * What a retrieval returned, as the screen shows it.
 *
 * THERE IS NO ANSWER FIELD HERE, and its absence is the design.
 *
 * A retrieval receipt certifies WHAT WAS RETRIEVED. It does not certify that anything
 * used it, because nothing generated an answer — `canonical-assistant-turn` is
 * planned-not-implemented, and until it exists no artifact could link a model's reply to
 * this evidence. So this surface shows the evidence and stops. A "grounded answer" badge
 * anywhere near it would be a claim the system cannot support, made about the one thing
 * users trust most.
 *
 * [answerability] IS THE HARNESS'S WORD, carried verbatim. This client never computes it,
 * never derives it from an item count, and never rewrites it into something friendlier —
 * "supported" and "conflicted" are verdicts with meanings the Foundry defines.
 */
data class RetrievalEvidence(
    val answerability: String,
    val disposition: String,
    val reasonCode: String?,
    val items: List<EvidenceEntry>,
    val contradictions: List<ContradictionView>,
    /** What the retrieval knowingly left out, in the Harness's own words. */
    val omissions: List<String>,
    /** Which limits were hit. A non-empty list means this is what FIT, not what exists. */
    val truncationBoundaries: List<String>,
    val receipt: RetrievalReceipt,
)

/**
 * One quoted piece of source material.
 *
 * `text` is a QUOTATION and nothing else. It is rendered as such, never as prose the app
 * wrote and never as an instruction — the contract states `content_treatment:
 * inert-untrusted-data` on every item, and the decoder refuses any that says otherwise.
 */
data class EvidenceEntry(
    val evidenceId: String,
    val packId: String,
    val releaseId: String,
    val kind: String,
    val text: String,
    val knowledgeStatus: String,
    val uncertainty: String,
    val sources: List<SourceRef>,
    /**
     * Graph paths as IDENTITIES, which is all the contract carries.
     *
     * There is no path narration anywhere in `evidence-packet/2.0` — only
     * `graph_path_ids`. Rendering "A → B → C" would require a lookup that does not exist,
     * so the ids are listed and labelled as ids. A linear list of real identities beats a
     * pretty diagram of invented ones.
     */
    val graphPathIds: List<String>,
    val contradictionIds: List<String>,
    val packFusedRank: Int,
    val globalFusedRank: Int,
    /** Null means this channel did not surface the item — different from rank zero. */
    val lexicalRank: Int?,
    val graphRank: Int?,
)

data class SourceRef(
    val sourceId: String,
    val logicalLocator: String,
    val sensitivity: String,
    val contentSha256: String,
)

/**
 * Two or more packs disagreeing, as far as the contract lets us describe it.
 *
 * [members] is empty when the Harness sent a bare identity rather than a group — the
 * `oneOf` in the schema. An empty member list therefore means "a contradiction exists and
 * its detail was not included", which is a different fact from "no members", and the
 * screen says so rather than rendering an empty section.
 */
data class ContradictionView(
    val groupId: String,
    val detectionMethod: String?,
    val disposition: String?,
    val members: List<ContradictionMemberView>,
)

data class ContradictionMemberView(
    val candidateId: String,
    val packId: String,
    val releaseId: String,
    val canonicalValueSha256: String,
)

/**
 * What this retrieval can be checked against later.
 *
 * Every value is a digest or an identity the Harness produced. Displayed in full and
 * copyable for the same reason the release digests are: this is what someone compares
 * against a Studio record or a log, and an abbreviation that is unambiguous on screen is
 * not unambiguous against the world.
 */
data class RetrievalReceipt(
    val packetId: String,
    val packetSha256: String,
    val traceId: String,
    val deterministicCoreSha256: String,
    val planId: String,
    val resultSha256: String,
    val mountRegistrySha256: String,
)

/**
 * What the retrieval surface can be showing.
 *
 * NINE STATES, AND THE SEPARATIONS ARE THE CONTENT. Every one of these could be collapsed
 * into "something went wrong", and each collapse would cost the user the one fact that
 * tells them what to do next:
 *
 *  - [Unavailable] — the Harness never offered retrieval. Waiting will not help.
 *  - [InactiveRelease] — this build is refusing to ask. Activating happens in Studio.
 *  - [SessionExpired] — pair again, and it works.
 *  - [Declined] — the Foundry answered and chose not to; its reason code is the account.
 *  - [Incompatible] — only a new release of software fixes it. Pairing loops forever here.
 *  - [Uncorrelated] — a reply arrived about something else. Never displayed as evidence.
 *  - [Refused] — everything else this client would not read.
 *
 * [Declined] versus [Refused] is the same split [com.verbalogix.assistant.data.harness.HarnessOutcome]
 * draws: the Harness declining is the Harness working correctly, and reporting it as a
 * client failure would blame the wrong component.
 */
sealed interface RetrievalUiState {

    /** Nothing asked yet. The resting state, not an error. */
    data object Idle : RetrievalUiState

    data object Querying : RetrievalUiState

    /** `query.retrieve` is not declared. */
    data class Unavailable(val reason: String, val requiredCapability: String) : RetrievalUiState

    /**
     * The inspected release is installed but not mounted.
     *
     * The screen does not offer the field in this case, so reaching this state means
     * something asked anyway. Kept as a real state rather than an assertion because the
     * cost of the alternative is a question answered from a DIFFERENT release than the one
     * on screen, which would be indistinguishable from working.
     */
    data object InactiveRelease : RetrievalUiState

    /**
     * There is no live session, or the Harness said the token is finished.
     *
     * Separate from [Refused] because this one has a remedy the user can perform.
     * [cause] is null when the session was simply absent rather than rejected.
     */
    data class SessionExpired(val cause: PairAgainCause?) : RetrievalUiState

    /** The Harness answered and declined — `abstained`, `refused` or `failed`. */
    data class Declined(val disposition: String, val reasonCode: String?) : RetrievalUiState

    /**
     * A payload version or runtime contract this build does not share.
     *
     * Split out from [Refused] for the same reason `ExpertLibraryUiState` splits it: both
     * mean "the reply was not read", only one is fixed by updating software, and a user
     * told "something went wrong" cannot tell which.
     */
    data class Incompatible(val detail: String) : RetrievalUiState

    /**
     * The reply did not describe the pack and release that were asked about.
     *
     * A mismatch is never rendered as evidence, however well-formed it is. A stale
     * response, a misrouted one, or a scope the Foundry widened would all produce a
     * perfectly valid document about the WRONG expert -- and the failure mode of showing
     * it is that nothing looks wrong.
     */
    data class Uncorrelated(val detail: String) : RetrievalUiState

    /** This client would not read the reply. */
    data class Refused(val detail: String) : RetrievalUiState

    data class Ready(val evidence: RetrievalEvidence) : RetrievalUiState
}
