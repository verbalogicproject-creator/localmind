package com.verbalogix.assistant.ui

import com.verbalogix.assistant.ui.tools.ToolProposal

/**
 * A proposal to hand the approval sheet, owned by the tests that use it.
 *
 * IT USED TO LIVE IN `src/debug`, and that made the instrumented suite compile for exactly
 * one build type. `src/debug` is not compiled for the release variant, so
 * `connectedReleaseAndroidTest` failed with `Unresolved reference 'debug'` before a single
 * test ran — which is worth naming, because the build file recorded that release
 * instrumentation "hangs with no output", and this is a compile error, not a hang. The
 * hypothesis it was abandoned on could not have been tested through this.
 *
 * A fixture belongs to whoever asserts on it. `src/debug` is the right home for PREVIEW
 * fixtures — `DebugFixtures.kt` keeps its own copy for exactly that, and the isolation
 * argument in its header still holds — but a test reaching across into another variant's
 * sources couples the suite to a build type for no benefit.
 *
 * Reachable because [ToolProposal]'s constructor is `internal` and AGP compiles androidTest
 * as a friend of the main source set.
 *
 * Deliberately, obviously fake, for the same reason the preview fixture is: it targets
 * `EXAMPLE-system` rather than anything real, so a screenshot of a test failure can never be
 * mistaken for a working integration.
 */
internal fun exampleToolProposal(): ToolProposal = ToolProposal(
    proposalId = "EXAMPLE-proposal",
    sessionId = "EXAMPLE-session",
    action = "EXAMPLE action (test fixture)",
    target = "EXAMPLE-system",
    preview = "EXAMPLE effect — nothing is executed by this build.",
    requiredPermission = "EXAMPLE-permission",
    // Fixed rather than `now() + n`: a value that changes per run makes a failure message
    // differ between runs for reasons that have nothing to do with the failure.
    expiresAtEpochMillis = 0L,
)
