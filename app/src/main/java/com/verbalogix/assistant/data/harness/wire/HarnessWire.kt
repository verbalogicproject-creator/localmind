package com.verbalogix.assistant.data.harness.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The closed `/3.0` payload shapes, transcribed from the Foundry's own schemas.
 *
 * TWO KOTLIN CONVENTIONS ARE LOAD-BEARING HERE, and neither is stylistic:
 *
 * 1. NO DEFAULT VALUES. Every schema field listed in `required` is declared without a
 *    Kotlin default, so kotlinx throws `MissingFieldException` when the key is absent.
 *    Giving `error: AdapterError? = null` a default would silently accept a response
 *    that omitted the key entirely -- which is a different document from one that sent
 *    `"error": null`, and the schema requires the latter.
 *
 * 2. NO `ignoreUnknownKeys`. Every schema here is `additionalProperties: false`, and the
 *    decoder honours that by refusing unknown keys rather than dropping them. A field
 *    this build does not recognise means the document was written to an agreement this
 *    build does not share, and reading the parts it recognises would produce a confident
 *    screen from a document it half-understands.
 *
 * `result` stays a [JsonElement] on purpose. The schema declares it as a `oneOf` across
 * five alternatives, and rather than trusting a discriminator we decode the envelope
 * first, read the INNER `schema`, and dispatch on it explicitly -- see `HarnessDecoder`.
 * The check is then a comparison this code performs, not one it delegates.
 */
@Serializable
internal data class OperationResponseEnvelope(
    val schema: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("operation_id") val operationId: String,
    val disposition: String,
    val result: JsonElement?,
    val error: AdapterError?,
    val receipt: JsonElement?,
    @SerialName("proof_limit") val proofLimit: String,
    @SerialName("response_sha256") val responseSha256: String,
)

/** The failure envelope. Carried verbatim; never widened with a locally invented code. */
@Serializable
internal data class AdapterError(
    val schema: String,
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String?,
    @SerialName("proof_limit") val proofLimit: String,
)

/**
 * `knowledge-foundry-capabilities/3.0`.
 *
 * `operations` is what the HARNESS declares it can do. It is emphatically not what
 * Localmind may do: the golden lists all fifteen operations including `mount.activate`
 * and `pack.install`, and this client holds none of that authority. Translation from
 * this list to enabled UI happens in one place and drops everything outside the
 * read-only set -- see `HarnessCapabilityMapper`.
 */
@Serializable
internal data class CapabilitiesResult(
    val schema: String,
    @SerialName("distribution_version") val distributionVersion: String,
    @SerialName("runtime_contract") val runtimeContract: String,
    @SerialName("kpack_runtime_contract") val kpackRuntimeContract: String,
    val operations: List<String>,
    val adapters: List<String>,
    @SerialName("supported_response_schemas") val supportedResponseSchemas: List<String>,
    @SerialName("default_response_schema") val defaultResponseSchema: String,
    @SerialName("explicit_response_schemas") val explicitResponseSchemas: List<String>,
    val limits: JsonElement,
    @SerialName("proof_limit") val proofLimit: String,
    @SerialName("capabilities_sha256") val capabilitiesSha256: String,
)

/** `knowledge-foundry-expert-catalog/3.0`. */
@Serializable
internal data class ExpertCatalogResult(
    val schema: String,
    @SerialName("state_format") val stateFormat: String,
    val generation: Long,
    @SerialName("mount_registry_sha256") val mountRegistrySha256: String,
    val releases: List<ExpertReleaseSummary>,
    @SerialName("proof_limit") val proofLimit: String,
    @SerialName("result_sha256") val resultSha256: String,
)

/**
 * `knowledge-foundry-expert-release-summary/3.0`.
 *
 * VERIFIED AGAINST SERVER BYTES. Transcribed from the schema first and unverified for a
 * time; the populated-catalog and release-detail goldens now decode through this type
 * unchanged, which is what promoted it from "structurally correct" to "observed". One
 * type serves both the catalog list and the detail view, so a field cannot drift between
 * the two representations.
 *
 * `trust_state` is `{"const": "trusted"}` in the schema, which is a stronger statement
 * than it first appears: the catalog NEVER lists an untrusted, revoked or unsigned
 * release. So the client does not need to render a trust badge to keep the user safe --
 * it needs to refuse any value other than "trusted", because such a value means the
 * document is not the one this contract describes.
 */
@Serializable
internal data class ExpertReleaseSummary(
    val schema: String,
    @SerialName("pack_id") val packId: String,
    @SerialName("release_id") val releaseId: String,
    val namespace: String,
    val slug: String,
    val name: String,
    val description: String,
    val profile: String,
    val version: String,
    val capabilities: List<String>,
    @SerialName("risk_class") val riskClass: String,
    @SerialName("publication_channel") val publicationChannel: String,
    @SerialName("content_sha256") val contentSha256: String,
    @SerialName("archive_sha256") val archiveSha256: String,
    @SerialName("install_record_sha256") val installRecordSha256: String,
    @SerialName("trust_state") val trustState: String,
    @SerialName("mount_state") val mountState: String,
    val role: String?,
    @SerialName("allowed_sensitivities") val allowedSensitivities: List<String>,
    @SerialName("proof_limit") val proofLimit: String,
)

/**
 * `knowledge-foundry-expert-release-detail/3.0`.
 *
 * Three closed sub-objects, and the split is meaningful rather than organisational.
 * [release] is the same summary the catalog carries, so one type serves both and a field
 * cannot drift between the list and the detail view. [install] is what verification
 * established — signer, compatibility, dependencies — and [lifecycle] is where this
 * release sits relative to its neighbours.
 *
 * NOTHING HERE IS AN EVALUATION OR A SOURCE-STANDING FIGURE. Those were asked for early
 * and exist in no schema; the contract carries provenance and verification facts only.
 */
@Serializable
internal data class ExpertReleaseDetailResult(
    val schema: String,
    val release: ExpertReleaseSummary,
    val install: ExpertInstallFacts,
    val lifecycle: ExpertLifecycleFacts,
    @SerialName("proof_limit") val proofLimit: String,
    @SerialName("result_sha256") val resultSha256: String,
)

/** What verification established about the installed release. */
@Serializable
internal data class ExpertInstallFacts(
    @SerialName("install_id") val installId: String,
    @SerialName("signer_key_id") val signerKeyId: String,
    val compatibility: String,
    @SerialName("dependency_release_ids") val dependencyReleaseIds: List<String>,
    @SerialName("verification_sha256") val verificationSha256: String,
)

/**
 * Where this release sits in its own history.
 *
 * ALL THREE FIELDS ARE REQUIRED AND NULLABLE, which is the distinction that matters: the
 * key must be present, and its value may be null. A null predecessor means "the Harness
 * says there is none", not "the Harness did not say" — so an update badge can finally be
 * driven by a fact rather than inferred from two versions existing.
 */
@Serializable
internal data class ExpertLifecycleFacts(
    @SerialName("predecessor_release_id") val predecessorReleaseId: String?,
    @SerialName("rollback_release_id") val rollbackReleaseId: String?,
    @SerialName("superseded_content_sha256") val supersededContentSha256: String?,
)
