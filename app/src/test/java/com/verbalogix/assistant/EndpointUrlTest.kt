package com.verbalogix.assistant

import com.verbalogix.assistant.data.EndpointUrl
import com.verbalogix.assistant.data.EndpointVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These run on the JVM rung, which is the whole reason [EndpointUrl] uses java.net.URI
 * rather than android.net.Uri: the android.net stub returns null from every method, so
 * the same tests against it would pass while asserting nothing.
 */
class EndpointUrlTest {

    @Test
    fun `loopback over http is usable because the network config permits it`() {
        val v = EndpointUrl.inspect("http://127.0.0.1:8090")
        assertEquals(EndpointVerdict.Usable("http://127.0.0.1:8090"), v)
    }

    @Test
    fun `localhost is the same address by another name`() {
        assertTrue(EndpointUrl.inspect("http://localhost:8090") is EndpointVerdict.Usable)
    }

    @Test
    fun `https anywhere is usable`() {
        assertTrue(EndpointUrl.inspect("https://llm.example.com") is EndpointVerdict.Usable)
    }

    /**
     * The case this class exists for. A LAN address is the most natural thing to type
     * and the platform kills the request before it leaves the app, with an error
     * indistinguishable from the server being down.
     */
    @Test
    fun `plain http to a LAN address is reported as blocked, not as unreachable`() {
        val v = EndpointUrl.inspect("http://192.168.1.5:8080")
        assertEquals(
            EndpointVerdict.BlockedCleartext("http://192.168.1.5:8080", "192.168.1.5"),
            v,
        )
    }

    /** LlamaClient composes "$baseUrl/v1/models", so a trailing slash requests "//v1". */
    @Test
    fun `a trailing slash is removed rather than passed on`() {
        val v = EndpointUrl.inspect("https://llm.example.com/")
        assertEquals(EndpointVerdict.Usable("https://llm.example.com"), v)
    }

    @Test
    fun `a path is kept, because a reverse proxy may serve the API under one`() {
        val v = EndpointUrl.inspect("https://example.com/llama")
        assertEquals(EndpointVerdict.Usable("https://example.com/llama"), v)
    }

    @Test
    fun `surrounding whitespace is trimmed, since it is what a paste brings`() {
        assertEquals(
            EndpointVerdict.Usable("http://127.0.0.1:8090"),
            EndpointUrl.inspect("  http://127.0.0.1:8090\n"),
        )
    }

    @Test
    fun `a bare host names the missing scheme rather than failing to parse`() {
        val v = EndpointUrl.inspect("192.168.1.5:8080")
        assertTrue(v is EndpointVerdict.Malformed)
        assertTrue((v as EndpointVerdict.Malformed).reason.contains("scheme"))
    }

    @Test
    fun `an unsupported scheme is rejected by name`() {
        assertTrue(EndpointUrl.inspect("ftp://example.com") is EndpointVerdict.Malformed)
    }

    @Test
    fun `empty input asks for a URL instead of reporting a parse failure`() {
        assertTrue(EndpointUrl.inspect("   ") is EndpointVerdict.Malformed)
    }

    @Test
    fun `the offered fix for a blocked URL is the same URL over https`() {
        assertEquals(
            "https://192.168.1.5:8080",
            EndpointUrl.asHttps("http://192.168.1.5:8080"),
        )
    }
}
