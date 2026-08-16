package com.verbalogix.assistant.data.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client refuses what it cannot read, rather than reading part of it.
 *
 * The dangerous payload is not the one this client rejects; it is the one it
 * half-understands. A reader that takes the fields it recognises from a document written
 * to a version it has never seen produces a confident screen from a document whose
 * meaning it does not share -- and trust state and compatibility are precisely the
 * fields where a silent partial read yields a safe-looking answer that is wrong.
 *
 * Every test here asserts a REFUSAL, which is why they can exist before the Foundry's
 * golden responses do. Acceptance cannot be tested honestly yet, and the one test that
 * touches it asserts only that nothing is accepted today.
 */
class SchemaNegotiationTest {

    @Test
    fun nothing_is_accepted_until_a_decoder_and_a_golden_payload_exist() {
        // Deliberate, and the most load-bearing assertion in this file. Foundry 0.3.2 is
        // introducing explicit /3.0 negotiation; adding an entry to ACCEPTED before the
        // decoder behind it exists would claim this client can read documents it has
        // never seen an example of. This test fails the moment someone widens the set
        // without revisiting it, which is the point.
        assertTrue(
            "widening ACCEPTED requires a decoder and a golden payload in the same change",
            SchemaNegotiation.ACCEPTED.isEmpty(),
        )
    }

    @Test
    fun a_missing_schema_field_is_undeclared_not_assumed_old() {
        // Every Foundry schema makes `schema` required, so its absence does not mean an
        // older version -- it means this is not a Foundry payload, or not one that
        // survived whatever produced it.
        assertEquals(SchemaVerdict.Undeclared, SchemaNegotiation.negotiate(null))
        assertEquals(SchemaVerdict.Undeclared, SchemaNegotiation.negotiate(""))
        assertEquals(SchemaVerdict.Undeclared, SchemaNegotiation.negotiate("   "))
    }

    @Test
    fun a_value_that_is_not_a_version_identifier_is_malformed() {
        for (bogus in listOf(
            "knowledge-foundry-mount-registry",   // no version
            "2.0",                                // no name
            "knowledge-foundry-mount-registry/2", // not major.minor
            "Knowledge-Foundry/2.0",              // upper case is not the wire form
            "../../etc/passwd",
        )) {
            val verdict = SchemaNegotiation.negotiate(bogus)
            assertTrue("$bogus should be Malformed, was $verdict", verdict is SchemaVerdict.Malformed)
            assertFalse(verdict.isAccepted)
        }
    }

    @Test
    fun a_well_formed_but_unknown_version_is_unsupported_and_says_so() {
        // The real 2.0 documents this client has read the SCHEMAS for but has no decoder
        // and no golden payload for. Well-formed, refused, and named in the refusal.
        val verdict = SchemaNegotiation.negotiate("knowledge-foundry-mount-registry/2.0")
        assertTrue(verdict is SchemaVerdict.Unsupported)
        assertFalse(verdict.isAccepted)
        assertTrue(
            "the refusal must name the version it saw",
            verdict.reason.contains("knowledge-foundry-mount-registry/2.0"),
        )
    }

    @Test
    fun a_version_mismatch_never_reads_as_something_pairing_would_fix() {
        // The two failure families must stay apart. Presenting a version mismatch as a
        // session problem puts the user in a loop -- re-pair, fail, re-pair -- against a
        // fault only a software update can clear.
        for (verdict in listOf(
            SchemaNegotiation.negotiate("knowledge-foundry-mount-registry/2.0"),
            SchemaNegotiation.negotiate("nonsense"),
            SchemaNegotiation.negotiate(null),
        )) {
            val text = verdict.reason.lowercase()
            assertFalse("must not suggest pairing: $text", text.contains("pair"))
            assertFalse("must not suggest a session problem: $text", text.contains("session"))
        }
    }

    @Test
    fun every_refusal_says_that_nothing_was_read() {
        // A user who is told only "unsupported" cannot tell whether the screen behind
        // the message is empty or stale.
        for (verdict in listOf(
            SchemaNegotiation.negotiate(null),
            SchemaNegotiation.negotiate("not-a-version"),
        )) {
            assertTrue(
                "must state that nothing was read: ${verdict.reason}",
                verdict.reason.contains("Nothing was read"),
            )
        }
    }
}
