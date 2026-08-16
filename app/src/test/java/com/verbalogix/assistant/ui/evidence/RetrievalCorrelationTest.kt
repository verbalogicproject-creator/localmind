package com.verbalogix.assistant.ui.evidence

import com.verbalogix.assistant.data.harness.HarnessDecoder
import com.verbalogix.assistant.data.harness.HarnessOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does the reply describe the expert that was asked about?
 *
 * THIS IS THE CHECK AGAINST THE FAILURE THAT LOOKS LIKE SUCCESS. Everything else on this
 * surface guards against documents that are malformed, and a malformed document announces
 * itself. A well-formed retrieval about a DIFFERENT pack does not: it renders perfectly,
 * cites real sources, carries a valid receipt, and attributes another expert's material to
 * the one on screen.
 *
 * Every mutation below is applied to the real server-emitted golden, so each one asks the
 * decoder and the mapper about a document that is genuine in every respect except the one
 * under test.
 */
class RetrievalCorrelationTest {

    private val packId =
        "kf:pack:c597b1bfbc5ff099921cfc338451b34c5d8e10e82c2ee6a290d4c53a2e7e5efe"
    private val releaseId =
        "kf:pack-release:7b8d2db313b98cb708b65b34e0143faf19b126daa9eda77e6407ca3ed452dac5"

    private val target = RetrievalTarget(
        packId = packId,
        releaseId = releaseId,
        allowedSensitivities = listOf("internal"),
        active = true,
    )

    private fun golden(): String =
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "goldens/stage3c-v1/client-query-response.json",
            ),
        ) { "missing retrieval golden" }.readBytes().decodeToString()

    /**
     * Replace a substring, failing loudly when it is absent.
     *
     * A plain `replace` that matches nothing yields the ORIGINAL document, so a mutation
     * test whose target has moved passes while testing nothing. That has already happened
     * once in this repository; this helper is the fix.
     */
    private fun mutate(vararg edits: Pair<String, String>): String {
        var raw = golden()
        for ((from, to) in edits) {
            assertTrue("mutation target absent from the golden: $from", from in raw)
            raw = raw.replace(from, to)
        }
        return raw
    }

    private fun state(raw: String, against: RetrievalTarget = target): RetrievalUiState =
        HarnessDecoder.decodeQueryResult(raw).toRetrievalState(against)

    @Test
    fun the_real_reply_correlates_with_the_release_it_came_from() {
        val outcome = HarnessDecoder.decodeQueryResult(golden())
        check(outcome is HarnessOutcome.Decoded)
        assertNull(outcome.value.uncorrelatedWith(target))
        assertTrue(outcome.toRetrievalState(target) is RetrievalUiState.Ready)
    }

    @Test
    fun a_reply_about_another_pack_is_never_displayed_as_evidence() {
        val other = target.copy(packId = "kf:pack:${"ab".repeat(32)}")
        val outcome = HarnessDecoder.decodeQueryResult(golden())
        check(outcome is HarnessOutcome.Decoded)
        assertNotNull(outcome.value.uncorrelatedWith(other))
        assertTrue(outcome.toRetrievalState(other) is RetrievalUiState.Uncorrelated)
    }

    /**
     * The same pack at a different release.
     *
     * The subtlest of the mismatches, and the one that most deserves its own test: the pack
     * identity matches, the name on screen matches, and the evidence is from a release the
     * user is not looking at. Nothing about the rendering would look wrong.
     */
    @Test
    fun a_reply_from_another_release_of_the_same_pack_is_refused() {
        val other = target.copy(releaseId = "kf:pack-release:${"cd".repeat(32)}")
        assertTrue(state(golden(), other) is RetrievalUiState.Uncorrelated)
    }

    @Test
    fun a_single_foreign_evidence_item_refuses_the_whole_reply() {
        // ONE item of the two, chosen by its revision so the second is untouched. A packet
        // whose plan and mounts are correct but which slipped in one foreign item is the
        // shape a partial failure takes -- and showing "most of it" would be showing
        // material this expert did not provide.
        val raw = mutate(
            """"$releaseId","revision_id":"kf:rev:f0bbbb""" to
                """"kf:pack-release:${"ef".repeat(32)}","revision_id":"kf:rev:f0bbbb""",
        )
        assertTrue(state(raw) is RetrievalUiState.Uncorrelated)
    }

    @Test
    fun a_mount_snapshot_naming_an_unrequested_pack_refuses_the_reply() {
        // The Foundry scopes the snapshot to the packs it selected, so a foreign mount
        // means the retrieval considered material this request excluded.
        val raw = mutate(
            """"mount_snapshot":{"mounts":[{"pack_id":"$packId"""" to
                """"mount_snapshot":{"mounts":[{"pack_id":"kf:pack:${"12".repeat(32)}"""",
        )
        assertTrue(state(raw) is RetrievalUiState.Uncorrelated)
    }

    @Test
    fun a_plan_scoped_to_every_pack_refuses_the_reply() {
        // `mode: all` is representable in the schema and is never sent from an expert's own
        // screen. A plan echoing it means the retrieval ran wider than the question.
        val raw = mutate(
            """"pack_scope":{"mode":"include","pack_ids":["$packId"]}""" to
                """"pack_scope":{"mode":"all","pack_ids":[]}""",
        )
        assertTrue(state(raw) is RetrievalUiState.Uncorrelated)
    }

    /**
     * A plan that came back naming a generative mode.
     *
     * Localmind cannot send one — `answer_mode` is a constant in `HarnessRequest`. So a
     * plan echoing anything else means the reply is not answering this client's request,
     * and the document is not the one this surface was built to render.
     */
    @Test
    fun a_plan_that_is_not_evidence_only_refuses_the_reply() {
        val raw = mutate(""""answer_mode":"evidence-only"""" to """"answer_mode":"grounded"""")
        assertTrue(state(raw) is RetrievalUiState.Uncorrelated)
    }

    /**
     * An abstention, envelope and packet together, exactly as the Foundry emits one.
     *
     * TWO PROPERTIES AT ONCE. The reply is a decline, so correlation is not demanded of it
     * — an abstention carries no items and often no mounts, and refusing it over a missing
     * snapshot would turn "the expert had nothing" into an error. And the packet survives
     * the decline, so the reason code survives with it: the facade derives the envelope's
     * disposition from the packet's and seals the result either way, leaving `error` null,
     * so a client that stopped at the envelope would report "abstained" with no account of
     * why.
     */
    @Test
    fun an_abstention_keeps_its_reason_and_is_not_asked_to_correlate() {
        val raw = mutate(
            """{"disposition":"succeeded","error":null,""" to
                """{"disposition":"abstained","error":null,""",
            """"contradictions":[],"disposition":"succeeded","items":[""" to
                """"contradictions":[],"disposition":"abstained","items":[""",
            """"reason_code":null,"schema":"knowledge-foundry-evidence-packet/2.0"""" to
                """"reason_code":"no-evidence","schema":"knowledge-foundry-evidence-packet/2.0"""",
        )
        val declined = state(raw)
        assertTrue("got $declined", declined is RetrievalUiState.Declined)
        assertEquals("abstained", (declined as RetrievalUiState.Declined).disposition)
        assertEquals("no-evidence", declined.reasonCode)
    }
}
