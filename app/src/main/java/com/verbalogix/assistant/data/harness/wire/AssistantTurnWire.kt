package com.verbalogix.assistant.data.harness.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Stage 3D response shapes: what a finalised assistant turn looks like coming back.
 *
 * REQUEST TYPES ARE DELIBERATELY ABSENT FROM THIS FILE. Everything Localmind SENDS in this
 * slice carries a SHA-256 of itself, computed over its own canonical JSON, and the Foundry
 * recomputes each one and refuses on any difference. Building those objects as
 * `@Serializable` classes and then re-encoding them for the digest would put two
 * serialisers in the path where the contract allows one — so requests are assembled as
 * `JsonObject` and hashed directly. See `AssistantTurnRequest`.
 *
 * The same two conventions as `HarnessWire` apply and for the same reasons: no Kotlin
 * defaults on required fields, and no `ignoreUnknownKeys`.
 *
 * WHAT IS NULLABLE HERE IS A DISPOSITION, NOT AN OPTION. `model`, `prompt_template` and
 * `answer` are null exactly when the turn was `abstained` or `refused` — the Foundry
 * forbids a provider observation in those cases, so there is no model to name and no
 * answer to carry. A client that treated null as "missing" rather than "no provider ran"
 * would report a contract violation where the contract was working.
 */

/** `knowledge-foundry-assistant-turn/1.0`, the `result` of `assistant.turn.finalize`. */
@Serializable
internal data class AssistantTurnResult(
    val schema: String,
    @SerialName("turn_id") val turnId: String,
    val disposition: String,
    @SerialName("query_result_sha256") val queryResultSha256: String,
    val evidence: TurnEvidence,
    val model: ModelIdentity?,
    @SerialName("prompt_template") val promptTemplate: PromptTemplateRef?,
    val answer: GroundedAnswer?,
    val receipt: AssistantTurnReceipt,
    @SerialName("proof_limit") val proofLimit: String,
    @SerialName("turn_sha256") val turnSha256: String,
)

/**
 * Which packet the turn was bound to, and how the Foundry rated it.
 *
 * `answerability` is repeated here from the evidence packet rather than inferred from the
 * disposition. They are not the same fact: `conflicted` evidence still produces a
 * `grounded` turn, and collapsing the two would hide a disagreement between sources behind
 * a reassuring word.
 */
@Serializable
internal data class TurnEvidence(
    @SerialName("packet_id") val packetId: String,
    @SerialName("packet_sha256") val packetSha256: String,
    @SerialName("mount_registry_sha256") val mountRegistrySha256: String,
    val answerability: String,
)

@Serializable
internal data class ModelIdentity(
    @SerialName("model_id") val modelId: String,
    /** Null when the runtime cannot name the weights it loaded. Not an error. */
    @SerialName("artifact_sha256") val artifactSha256: String?,
    @SerialName("runtime_id") val runtimeId: String,
    @SerialName("runtime_version") val runtimeVersion: String?,
    @SerialName("endpoint_kind") val endpointKind: String,
)

@Serializable
internal data class PromptTemplateRef(
    @SerialName("template_id") val templateId: String,
    @SerialName("template_sha256") val templateSha256: String,
)

/**
 * `knowledge-foundry-grounded-answer/1.0`.
 *
 * [text] IS NOT FREE PROSE. The contract requires it to equal the ordered segment texts
 * joined by two line feeds, and the Foundry checks that before it will call a turn
 * grounded. It is carried rather than recomputed so a mismatch is visible instead of
 * papered over.
 */
@Serializable
internal data class GroundedAnswer(
    val schema: String,
    val text: String,
    val segments: List<AnswerSegment>,
    @SerialName("answer_sha256") val answerSha256: String,
)

/**
 * One segment: a claim that must cite, or an uncertainty that need not.
 *
 * ONE CLASS FOR BOTH ARMS OF THE `oneOf`, which is safe here and would not be elsewhere.
 * The two arms have identical field SETS — `kind`, `text`, `evidence_ids` — and differ
 * only in the `kind` const and in whether `evidence_ids` may be empty. Contrast
 * `PlanAccess` and `RequestAccess`, which were kept apart precisely because their field
 * sets differed and sharing a class would have let a document missing a required field
 * decode cleanly. Nothing is weakened by sharing this one; the claim-must-cite rule is
 * enforced where it belongs, in the parser and again on the server.
 */
@Serializable
internal data class AnswerSegment(
    val kind: String,
    val text: String,
    @SerialName("evidence_ids") val evidenceIds: List<String>,
)

/**
 * `knowledge-foundry-assistant-turn-receipt/1.0` — what makes "grounded" checkable.
 *
 * Every field is a digest or an identity binding one link of the chain: the request, the
 * retrieval result, the evidence packet, the mount registry, the provider observation, the
 * model, the prompt template, the answer, and the citations that closed over the packet.
 * Together they let someone else re-derive the claim later without trusting this app, the
 * model, or the person showing them the screen.
 *
 * `proof_limit` states what it does NOT establish, and is displayed rather than dropped:
 * structural derivation only, never source truth, factuality, provider honesty or model
 * quality.
 */
@Serializable
internal data class AssistantTurnReceipt(
    val schema: String,
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("request_sha256") val requestSha256: String,
    @SerialName("query_result_sha256") val queryResultSha256: String,
    @SerialName("packet_id") val packetId: String,
    @SerialName("packet_sha256") val packetSha256: String,
    @SerialName("mount_registry_sha256") val mountRegistrySha256: String,
    /** Null on abstention or refusal: no provider ran, so there is nothing to bind. */
    @SerialName("provider_observation_sha256") val providerObservationSha256: String?,
    @SerialName("model_identity_sha256") val modelIdentitySha256: String?,
    @SerialName("prompt_template_sha256") val promptTemplateSha256: String?,
    @SerialName("answer_sha256") val answerSha256: String?,
    @SerialName("cited_evidence_ids") val citedEvidenceIds: List<String>,
    val disposition: String,
    @SerialName("proof_limit") val proofLimit: String,
    @SerialName("receipt_sha256") val receiptSha256: String,
)

/**
 * `knowledge-foundry-capabilities/4.0` — whether this server offers the assistant turn.
 *
 * FLAT, WITH NO ENVELOPE, and singular. `capabilities/3.0` arrives inside an
 * `operation-response/3.0` and lists every operation as a bare string in `operations`;
 * this document has no envelope and describes exactly one operation. The difference was
 * read from the server's own bytes, not inferred from the version number, and it is the
 * reason `/4.0` needed a separate decoder rather than a widened one.
 *
 * EVERY FIELD IS REQUIRED and none is nullable. The three effect flags in particular are
 * not conveniences: `provider_execution`, `tool_execution` and `persistence` are the
 * server stating that discovering this capability grants no authority to run a provider,
 * call a tool, or write anything. A client that decoded them leniently — or defaulted a
 * missing one to `false` — would be inventing the assurance rather than reading it.
 *
 * `capabilities_sha256` seals the rest of the document under the same canonical rules as
 * Stage 3D, so it is verified rather than displayed. See [HarnessDecoder].
 */
@Serializable
internal data class AssistantCapabilities(
    val schema: String,
    val operation: String,
    @SerialName("response_schema") val responseSchema: String,
    @SerialName("runtime_contract") val runtimeContract: String,
    @SerialName("kpack_runtime_contract") val kpackRuntimeContract: String,
    @SerialName("distribution_version") val distributionVersion: String,
    @SerialName("provider_execution") val providerExecution: Boolean,
    @SerialName("tool_execution") val toolExecution: Boolean,
    val persistence: Boolean,
    @SerialName("proof_limit") val proofLimit: String,
    @SerialName("capabilities_sha256") val capabilitiesSha256: String,
)
