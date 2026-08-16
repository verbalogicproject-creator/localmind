package com.verbalogix.assistant.data.harness.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * `knowledge-foundry-query-result/2.0`, carried inside an `operation-response/3.0`.
 *
 * A 2.0 PAYLOAD IN A 3.0 ENVELOPE, which is not a mistake: the envelope's `oneOf` names
 * `query-result-2.0` explicitly. Retrieval was specified before the expert surfaces and
 * did not need re-versioning, so the negotiated envelope carries an unversioned-by-3.0
 * body. Worth stating because "everything inside /3.0 is /3.0" is the natural assumption
 * and it is wrong here.
 *
 * MODELLED FROM THE SCHEMA, NOT FROM THE GOLDEN. The one server response available has
 * two evidence items, no contradictions, no omissions and a null `reason_code` -- so a
 * type shaped around it would compile, decode that file, and then fail the first time a
 * pack disagreed with another. Every optional shape below is therefore present and
 * unexercised: contradiction groups, their members, omission strings, non-null reason
 * codes, and the nullable per-channel ranks.
 */
@Serializable
internal data class QueryResult(
    val schema: String,
    val plan: QueryPlan,
    val trace: RetrievalTrace,
    @SerialName("evidence_packet") val evidencePacket: EvidencePacket,
    @SerialName("result_sha256") val resultSha256: String,
    @SerialName("proof_limit") val proofLimit: String,
)

// ── the packet ──────────────────────────────────────────────────────────────

/**
 * `knowledge-foundry-evidence-packet/2.0`.
 *
 * TWO CONSTS HERE ARE INSTRUCTIONS TO THIS CLIENT, not decoration:
 *
 *   content_treatment   "inert-untrusted-data"
 *   authority_boundary  "context-does-not-grant-effect-authority"
 *
 * The Foundry is stating that retrieved text is DATA. It is not a prompt, not an
 * instruction, and it confers no authority to act. Anything that later feeds
 * [EvidenceItem.selectedText] to a model has to honour that: a pack -- or a document
 * inside one -- can contain text shaped like a system instruction, and it arrives over a
 * signed, trusted-looking path. Both values are decoded and checked rather than ignored,
 * so a response that omits or weakens them is refused.
 *
 * [answerability] IS THE HARNESS'S VERDICT. This client never computes whether evidence
 * is sufficient, and never infers it from an item count.
 */
@Serializable
internal data class EvidencePacket(
    val schema: String,
    @SerialName("packet_id") val packetId: String,
    val trace: PacketTraceRef,
    @SerialName("mount_snapshot") val mountSnapshot: MountSnapshot,
    val disposition: String,
    @SerialName("reason_code") val reasonCode: String?,
    val answerability: String,
    val items: List<EvidenceItem>,
    /** Bare identities OR full groups -- see [ContradictionSerializer]. */
    val contradictions: List<Contradiction>,
    val omissions: List<String>,
    @SerialName("content_treatment") val contentTreatment: String,
    @SerialName("authority_boundary") val authorityBoundary: String,
    @SerialName("packet_sha256") val packetSha256: String,
    @SerialName("proof_limit") val proofLimit: String,
)

@Serializable
internal data class PacketTraceRef(
    @SerialName("trace_id") val traceId: String,
    @SerialName("deterministic_core_sha256") val deterministicCoreSha256: String,
)

@Serializable
internal data class MountSnapshot(
    val mounts: List<MountRef>,
    @SerialName("registry_sha256") val registrySha256: String,
)

@Serializable
internal data class MountRef(
    @SerialName("pack_id") val packId: String,
    @SerialName("release_id") val releaseId: String,
)

/**
 * One piece of retrieved evidence.
 *
 * `selected_text` is the only human-readable field, and the only one that must never be
 * treated as anything but a quotation.
 */
@Serializable
internal data class EvidenceItem(
    @SerialName("evidence_id") val evidenceId: String,
    @SerialName("candidate_id") val candidateId: String,
    @SerialName("pack_id") val packId: String,
    @SerialName("release_id") val releaseId: String,
    @SerialName("revision_id") val revisionId: String,
    @SerialName("logical_id") val logicalId: String,
    val kind: String,
    @SerialName("selected_text") val selectedText: String,
    @SerialName("content_sha256") val contentSha256: String,
    val provenance: List<Provenance>,
    @SerialName("transformation_ids") val transformationIds: List<String>,
    @SerialName("graph_path_ids") val graphPathIds: List<String>,
    val ranks: Ranks,
    @SerialName("knowledge_status") val knowledgeStatus: String,
    val uncertainty: String,
    @SerialName("contradiction_ids") val contradictionIds: List<String>,
    @SerialName("content_treatment") val contentTreatment: String,
    @SerialName("proof_limit") val proofLimit: String,
)

@Serializable
internal data class Provenance(
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_revision_id") val sourceRevisionId: String,
    @SerialName("source_artifact_sha256") val sourceArtifactSha256: String,
    @SerialName("source_content_sha256") val sourceContentSha256: String,
    @SerialName("logical_locator") val logicalLocator: String,
    val sensitivity: String,
)

/**
 * Where an item placed, per channel.
 *
 * `pack_lexical` and `pack_graph_local` are NULLABLE by schema: an item found only by one
 * channel has no rank in the other. Null here means "this channel did not surface it",
 * which is a different fact from rank zero -- and the golden's two items would not have
 * exercised the distinction on their own.
 */
@Serializable
internal data class Ranks(
    @SerialName("pack_lexical") val packLexical: Int?,
    @SerialName("pack_graph_local") val packGraphLocal: Int?,
    @SerialName("pack_fused") val packFused: Int,
    @SerialName("global_fused") val globalFused: Int,
)

/**
 * A contradiction: either a bare identity, or a resolved group.
 *
 * THE POLYMORPHISM IS IN THE SCHEMA and would never have been discovered from the golden,
 * which contains none. `contradictions` is an array whose items are `oneOf` a `kf:` string
 * or an object carrying the group and its members. A client modelling only one form
 * decodes the sample perfectly and throws the first time two packs disagree -- which is
 * precisely the moment the user most needs the screen to work.
 */
@Serializable(with = ContradictionSerializer::class)
internal sealed interface Contradiction {

    /** Referenced by identity only; the group is elsewhere or withheld. */
    data class Reference(val groupId: String) : Contradiction

    data class Group(
        val groupId: String,
        val detectionMethod: String,
        val disposition: String,
        val members: List<ContradictionMember>,
    ) : Contradiction
}

@Serializable
internal data class ContradictionMember(
    @SerialName("candidate_id") val candidateId: String,
    @SerialName("pack_id") val packId: String,
    @SerialName("release_id") val releaseId: String,
    @SerialName("revision_id") val revisionId: String,
    @SerialName("canonical_value_sha256") val canonicalValueSha256: String,
)

/**
 * Decodes the `oneOf` by SHAPE, which is the only discriminator available.
 *
 * A string is a reference; an object is a group. There is no type tag, so nothing else
 * would work -- and anything that is neither is refused rather than defaulted, keeping
 * the strictness the rest of this file relies on.
 */
internal object ContradictionSerializer : KSerializer<Contradiction> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("knowledge-foundry.Contradiction")

    override fun deserialize(decoder: Decoder): Contradiction {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("contradictions require a JSON decoder")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) throw IllegalArgumentException("contradiction is not an identity")
                Contradiction.Reference(element.content)
            }
            is JsonObject -> {
                val group = input.json.decodeFromJsonElement(RawGroup.serializer(), element)
                Contradiction.Group(
                    groupId = group.groupId,
                    detectionMethod = group.detectionMethod,
                    disposition = group.disposition,
                    members = group.members,
                )
            }
            else -> throw IllegalArgumentException("contradiction must be an identity or a group")
        }
    }

    override fun serialize(encoder: Encoder, value: Contradiction): Nothing =
        throw UnsupportedOperationException("this client never sends a contradiction")

    /** The object form, with `additionalProperties: false` honoured by strict decoding. */
    @Serializable
    private data class RawGroup(
        @SerialName("group_id") val groupId: String,
        @SerialName("detection_method") val detectionMethod: String,
        val disposition: String,
        val members: List<ContradictionMember>,
    )
}

// ── plan and trace ──────────────────────────────────────────────────────────
//
// Modelled in full rather than left as opaque JSON. They are large and the UI reads
// little of them, which is exactly the argument for typing them: an untyped subtree is a
// hole in `additionalProperties: false`, and these two are where a future contract change
// is most likely to land first.

@Serializable
internal data class QueryPlan(
    val schema: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("plan_sha256") val planSha256: String,
    @SerialName("planner_revision") val plannerRevision: String,
    val request: PlanRequest,
    val inputs: PackInputs,
    val access: PlanAccess,
    val budgets: PlanBudgets,
    val federation: PlanFederation,
    val routes: List<PlanRoute>,
    val safety: PlanSafety,
    @SerialName("proof_limit") val proofLimit: String,
)

@Serializable
internal data class PlanRequest(
    val schema: String,
    val text: String,
    val role: String,
    @SerialName("query_mode") val queryMode: String,
    @SerialName("answer_mode") val answerMode: String,
    @SerialName("provider_id") val providerId: String?,
    @SerialName("pack_scope") val packScope: PackScope,
    val limits: RequestLimits,
    val access: RequestAccess,
)

@Serializable
internal data class PackScope(val mode: String, @SerialName("pack_ids") val packIds: List<String>)

/**
 * Caller-supplied ceilings, ALL OPTIONAL.
 *
 * The only node in this contract whose `required` list is empty, and the golden sends
 * `{}` -- a request that named no limits at all. So every field carries a Kotlin default,
 * which is the same rule applied the other way round: a default exists here precisely
 * BECAUSE the schema permits absence.
 *
 * Getting this backwards is what refused the real retrieval on the first attempt. The
 * fields were read out of `properties` and assumed required; the empty `required` list
 * two lines above them was not.
 */
@Serializable
internal data class RequestLimits(
    @SerialName("evidence_items") val evidenceItems: Int? = null,
    @SerialName("global_candidates") val globalCandidates: Int? = null,
    @SerialName("graph_candidates") val graphCandidates: Int? = null,
    @SerialName("graph_depth") val graphDepth: Int? = null,
    @SerialName("graph_edges") val graphEdges: Int? = null,
    @SerialName("graph_seeds") val graphSeeds: Int? = null,
    @SerialName("item_bytes") val itemBytes: Int? = null,
    @SerialName("lexical_candidates") val lexicalCandidates: Int? = null,
    @SerialName("per_pack_fused") val perPackFused: Int? = null,
    @SerialName("total_bytes") val totalBytes: Int? = null,
)

/**
 * The PLAN's access policy. Both fields required.
 *
 * `policy_kind` reads `local-filter-not-authentication`, and the name is the point:
 * sensitivity filtering is a local convenience, not an access-control decision. Carried
 * verbatim so the wording can be shown rather than paraphrased into something stronger.
 */
@Serializable
internal data class PlanAccess(
    @SerialName("allowed_sensitivities") val allowedSensitivities: List<String>,
    @SerialName("policy_kind") val policyKind: String,
)

/**
 * The REQUEST's access, which is a different shape and not the same type.
 *
 * It carries only `allowed_sensitivities` -- no `policy_kind` -- and both objects are
 * `additionalProperties: false`. Sharing one class with an optional field would have made
 * a plan missing its required `policy_kind` decode cleanly, which is exactly the silent
 * weakening this decoder exists to prevent.
 */
@Serializable
internal data class RequestAccess(
    @SerialName("allowed_sensitivities") val allowedSensitivities: List<String>,
)

@Serializable
internal data class PlanBudgets(
    @SerialName("context_budget_bytes") val contextBudgetBytes: Int,
    @SerialName("maximum_depth") val maximumDepth: Int,
    @SerialName("maximum_edges_per_pack") val maximumEdgesPerPack: Int,
    @SerialName("maximum_evidence_items") val maximumEvidenceItems: Int,
    @SerialName("maximum_fused_candidates_per_pack") val maximumFusedCandidatesPerPack: Int,
    @SerialName("maximum_global_candidates") val maximumGlobalCandidates: Int,
    @SerialName("maximum_graph_candidates_per_pack") val maximumGraphCandidatesPerPack: Int,
    @SerialName("maximum_lexical_candidates_per_pack") val maximumLexicalCandidatesPerPack: Int,
    @SerialName("query_utf8_bytes") val queryUtf8Bytes: Int,
)

@Serializable
internal data class PlanFederation(
    val method: String,
    val deduplication: String,
    @SerialName("pack_order") val packOrder: String,
    @SerialName("tie_break") val tieBreak: String,
    @SerialName("candidate_ceiling") val candidateCeiling: Int,
    @SerialName("partial_results") val partialResults: Boolean?,
)

@Serializable
internal data class PlanRoute(val channel: String, val status: String)

/**
 * The planner's own safety verdict.
 *
 * `reason` is REQUIRED and nullable, and its enum is worth reading:
 * `instruction-injection` and `protected-effect`. The Foundry can refuse a plan because
 * the QUERY looked like an injection attempt -- which is the server half of the same
 * concern that makes `content_treatment: inert-untrusted-data` non-negotiable on the way
 * back. Both directions are guarded, and neither is this client's invention.
 */
@Serializable
internal data class PlanSafety(val disposition: String, val reason: String?)

@Serializable
internal data class PackInputs(
    @SerialName("mount_registry_sha256") val mountRegistrySha256: String,
    val packs: List<PackRef>,
)

@Serializable
internal data class PackRef(
    @SerialName("pack_id") val packId: String,
    @SerialName("release_id") val releaseId: String,
    @SerialName("content_sha256") val contentSha256: String,
    @SerialName("install_record_sha256") val installRecordSha256: String,
)

@Serializable
internal data class RetrievalTrace(
    val schema: String,
    @SerialName("trace_id") val traceId: String,
    val plan: TracePlanRef,
    val inputs: PackInputs,
    val disposition: String,
    @SerialName("reason_code") val reasonCode: String?,
    val access: TraceAccess,
    @SerialName("per_pack_channels") val perPackChannels: List<PackChannels>,
    @SerialName("per_pack_fusion") val perPackFusion: List<PackFusion>,
    @SerialName("global_fusion") val globalFusion: GlobalFusion,
    val truncation: Truncation,
    @SerialName("evidence_packet_id") val evidencePacketId: String?,
    @SerialName("deterministic_core_sha256") val deterministicCoreSha256: String,
    @SerialName("proof_limit") val proofLimit: String,
)

@Serializable
internal data class TracePlanRef(
    @SerialName("plan_id") val planId: String,
    @SerialName("plan_sha256") val planSha256: String,
)

@Serializable
internal data class TraceAccess(
    @SerialName("eligible_count") val eligibleCount: Int,
    @SerialName("filtered_count") val filteredCount: Int,
    @SerialName("filtered_set_sha256") val filteredSetSha256: String,
)

@Serializable
internal data class PackChannels(
    @SerialName("pack_id") val packId: String,
    @SerialName("release_id") val releaseId: String,
    val channels: List<Channel>,
)

@Serializable
internal data class Channel(
    val channel: String,
    val status: String,
    @SerialName("candidate_count") val candidateCount: Int,
)

@Serializable
internal data class PackFusion(
    @SerialName("pack_id") val packId: String,
    val algorithm: String,
    @SerialName("candidate_count") val candidateCount: Int,
)

@Serializable
internal data class GlobalFusion(
    val algorithm: String,
    @SerialName("pack_order") val packOrder: String,
    @SerialName("candidate_count") val candidateCount: Int,
    @SerialName("selected_candidate_ids") val selectedCandidateIds: List<String>,
)

/**
 * What the retrieval had to leave out.
 *
 * `boundaries` names which limit was hit -- lexical, graph seeds, graph depth, graph
 * edges, and so on. A non-empty list means the answer is working from a truncated view,
 * which is a fact worth surfacing rather than a detail: it is the difference between
 * "this is what there is" and "this is what fit".
 */
@Serializable
internal data class Truncation(
    val boundaries: List<String>,
    @SerialName("omission_count") val omissionCount: Int,
)
