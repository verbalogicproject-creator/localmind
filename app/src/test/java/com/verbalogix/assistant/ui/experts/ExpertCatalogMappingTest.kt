package com.verbalogix.assistant.ui.experts

import com.verbalogix.assistant.data.harness.HarnessDecoder
import com.verbalogix.assistant.data.harness.HarnessOutcome
import com.verbalogix.assistant.data.harness.HarnessRefusal
import com.verbalogix.assistant.data.harness.SchemaVerdict
import com.verbalogix.assistant.data.harness.wire.ExpertCatalogResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Expert Library's empty state, backed by the Foundry's own response.
 *
 * The property worth protecting is that "nothing is mounted" and "we could not ask" stay
 * distinguishable. Rendering both as an empty list would tell a user with a broken
 * connection that their library is empty -- a claim the UI has no way to withdraw once
 * made, and one the user has no reason to doubt.
 */
class ExpertCatalogMappingTest {

    private fun emptyCatalogGolden(): String =
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "goldens/stage3c-v1/client-empty-catalog-response.json",
            ),
        ) { "missing golden" }.readBytes().decodeToString()

    @Test
    fun the_real_empty_catalog_renders_as_empty_not_unavailable() {
        val state = HarnessDecoder.decodeExpertCatalog(emptyCatalogGolden()).toLibraryState()
        assertEquals(ExpertLibraryUiState.Empty, state)
    }

    @Test
    fun a_refusal_becomes_unavailable_and_keeps_its_own_reason() {
        // Not a generic message: the refusal explains itself, and replacing that text
        // with "something went wrong" discards the only thing that made it actionable.
        val outcome: HarnessOutcome<ExpertCatalogResult> = HarnessOutcome.Refused(
            HarnessRefusal.Schema(SchemaVerdict.Unsupported("knowledge-foundry-expert-catalog/9.9")),
        )
        val state = outcome.toLibraryState()
        assertTrue(state is ExpertLibraryUiState.Unavailable)
        val capability = (state as ExpertLibraryUiState.Unavailable).capability
        assertTrue(
            "must carry the refusal's own words: ${capability.reason}",
            capability.reason.contains("knowledge-foundry-expert-catalog/9.9"),
        )
        assertEquals("expert.catalog.list", capability.requiredCapability)
    }

    @Test
    fun a_declined_request_says_the_harness_declined_rather_than_showing_an_empty_shelf() {
        val outcome: HarnessOutcome<ExpertCatalogResult> =
            HarnessOutcome.Unsuccessful("refused", "scope-denied")
        val state = outcome.toLibraryState()
        assertTrue(state is ExpertLibraryUiState.Unavailable)
        val reason = (state as ExpertLibraryUiState.Unavailable).capability.reason
        assertTrue("must name the disposition: $reason", reason.contains("refused"))
        assertTrue("must name the error code: $reason", reason.contains("scope-denied"))
    }
}
