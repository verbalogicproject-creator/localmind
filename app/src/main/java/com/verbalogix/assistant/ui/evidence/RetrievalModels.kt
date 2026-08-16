package com.verbalogix.assistant.ui.evidence

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
 * [Refused] and [Unavailable] stay apart for the reason they do everywhere else in this
 * app: one is the client declining to read a reply, the other is the Harness never having
 * offered the operation, and only one of them is worth waiting on.
 */
sealed interface RetrievalUiState {

    /** Nothing asked yet. The resting state, not an error. */
    data object Idle : RetrievalUiState

    data object Querying : RetrievalUiState

    /** `query.retrieve` is not declared, or there is no session. */
    data class Unavailable(val reason: String, val requiredCapability: String) : RetrievalUiState

    /** The Harness answered and declined — `abstained`, `refused` or `failed`. */
    data class Declined(val disposition: String, val reasonCode: String?) : RetrievalUiState

    /** This client would not read the reply. */
    data class Refused(val detail: String) : RetrievalUiState

    data class Ready(val evidence: RetrievalEvidence) : RetrievalUiState
}
