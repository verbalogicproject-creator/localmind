package com.verbalogix.assistant.data.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The negotiation header, and the storage directive that travels with it.
 *
 * Absence is not neutral here: the Harness falls back to `/2.0` when no header is sent,
 * so failing to send one silently selects a version this client cannot read. Every
 * ambiguous value fails closed, because the expert routes exist only under `/3.0` and
 * proceeding under an unclear agreement is how a client ends up reading a document the
 * server did not think it was sending.
 */
class HarnessNegotiationTest {

    @Test
    fun the_header_is_exactly_what_the_contract_specifies() {
        assertEquals("Knowledge-Foundry-Accept-Schema", HarnessNegotiation.HEADER)
        assertEquals("/3.0", HarnessNegotiation.ACCEPT_VALUE)
    }

    @Test
    fun the_exact_value_selects_the_three_point_zero_path() {
        assertTrue(HarnessNegotiation.selectsThreePointZero("/3.0"))
        // HTTP tolerates surrounding whitespace, so this client does too -- and nothing more.
        assertTrue(HarnessNegotiation.selectsThreePointZero(" /3.0 "))
    }

    @Test
    fun absence_does_not_select_it() {
        // The consequential case: no header means the server answers /2.0, so a missing
        // header is a bug rather than a default.
        assertFalse(HarnessNegotiation.selectsThreePointZero(null))
        assertFalse(HarnessNegotiation.selectsThreePointZero(emptyList()))
    }

    @Test
    fun a_comma_combined_value_fails_closed() {
        // Content-negotiation habits invite reading this as "3.0 preferred". It is not a
        // preference list, and a client that treated it as one would proceed under an
        // agreement the server may not have made.
        assertFalse(HarnessNegotiation.selectsThreePointZero("/3.0, /2.0"))
        assertFalse(HarnessNegotiation.selectsThreePointZero("/2.0,/3.0"))
    }

    @Test
    fun duplicate_headers_fail_closed() {
        // Two headers are two claims with no rule for choosing between them.
        assertFalse(HarnessNegotiation.selectsThreePointZero(listOf("/3.0", "/3.0")))
        assertFalse(HarnessNegotiation.selectsThreePointZero(listOf("/3.0", "/2.0")))
    }

    @Test
    fun legacy_and_unknown_values_fail_closed() {
        assertFalse(HarnessNegotiation.selectsThreePointZero("/2.0"))
        assertFalse(HarnessNegotiation.selectsThreePointZero("3.0"))     // no leading slash
        assertFalse(HarnessNegotiation.selectsThreePointZero("/4.0"))
        assertFalse(HarnessNegotiation.selectsThreePointZero(""))
    }

    @Test
    fun no_store_is_required_not_merely_preferred() {
        // `no-cache` permits writing the response and revalidating later; only `no-store`
        // forbids writing it at all. A cached capability document would let the client
        // act on authority the Harness has since withdrawn.
        assertTrue(HarnessNegotiation.forbidsStorage("no-store"))
        assertTrue(HarnessNegotiation.forbidsStorage("no-store, max-age=0"))
        assertTrue(HarnessNegotiation.forbidsStorage("private, No-Store"))

        assertFalse(HarnessNegotiation.forbidsStorage(null))
        assertFalse(HarnessNegotiation.forbidsStorage("no-cache"))
        assertFalse(HarnessNegotiation.forbidsStorage("max-age=0"))
        assertFalse(HarnessNegotiation.forbidsStorage("private"))
    }
}
