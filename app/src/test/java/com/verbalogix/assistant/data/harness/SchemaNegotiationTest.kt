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
 * Almost every test here asserts a REFUSAL, which is why this file predates the Foundry's
 * golden responses. Acceptance is now testable for three ids and is proved where it
 * belongs -- against the server's own bytes, in `HarnessDecoderGoldenTest`. What stays
 * here is the membership rule: nothing enters `ACCEPTED` without a decoder and a golden.
 */
class SchemaNegotiationTest {

    /**
     * Nothing is accepted without a decoder AND a server-emitted golden behind it.
     *
     * This assertion previously read `ACCEPTED.isEmpty()`, and it fired the moment
     * Foundry 0.3.2 landed -- which is exactly what it was for. The precondition is now
     * genuinely satisfied for three ids, so the tripwire moves from "nothing" to "only
     * these", and the rule it protects is unchanged: no id enters this set without bytes
     * from the server to verify it against.
     */
    @Test
    fun only_versions_with_a_decoder_and_a_golden_are_accepted() {
        assertEquals(
            setOf(
                "knowledge-foundry-operation-response/3.0",
                "knowledge-foundry-capabilities/3.0",
                "knowledge-foundry-expert-catalog/3.0",
                "knowledge-foundry-expert-release-detail/3.0",
            ),
            SchemaNegotiation.ACCEPTED,
        )
    }

    @Test
    fun token_responses_are_never_admitted_to_the_negotiated_set() {
        // Not "not yet" -- never. This set governs documents carried inside
        // operation-response/3.0 and selected by the Accept-Schema header. A token
        // response has its own fixed schema and its routes are exempt from negotiation,
        // so admitting it would make a future /4.0 appear to govern a document it has no
        // relationship with.
        assertFalse(HarnessTokenDecoder.SCHEMA in SchemaNegotiation.ACCEPTED)
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
