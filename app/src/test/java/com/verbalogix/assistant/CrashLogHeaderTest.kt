package com.verbalogix.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash header is a machine-read contract, not a log format.
 *
 * `pull-crash.sh` and the device stage parse `gitSha=` out of it to refuse
 * diagnosing a stale APK -- a real mistake from this lineage, where a green run
 * attached to the wrong commit ended an investigation instead of starting one.
 * Renaming or reordering these keys would break that check silently, so it is
 * pinned here.
 */
class CrashLogHeaderTest {

    private fun header() = CrashLog.header(
        versionName = "0.0.1",
        versionCode = 1,
        gitSha = "abc1234",
        timestamp = "2026-08-15 07:00:00",
        device = "TestCo Model (API 34)",
        threadName = "main",
    )

    @Test
    fun `first three lines are versionName versionCode and gitSha in that order`() {
        val lines = header().trimEnd().lines()
        assertEquals("versionName=0.0.1", lines[0])
        assertEquals("versionCode=1", lines[1])
        assertEquals("gitSha=abc1234", lines[2])
    }

    @Test
    fun `every line is a single key equals value pair`() {
        header().trimEnd().lines().forEach { line ->
            assertTrue("not a key=value pair: '$line'", line.matches(Regex("^[a-zA-Z]+=.*$")))
        }
    }

    @Test
    fun `header ends with a newline so the stack trace starts on its own line`() {
        assertTrue(header().endsWith("\n"))
    }
}
