package com.verbalogix.assistant

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rung that answers "does it launch".
 *
 * Everything cheaper — preflight, compile, unit tests, lint, R8 — only ever proves
 * the app COMPILES. The three worst failures in this pipeline's history compiled
 * perfectly and died at launch: a missing @HiltAndroidApp, a fabricated font
 * certificate, and R8 stripping a serializer. Nine consecutive green builds once
 * shipped an app that crashed before rendering a pixel.
 */
@RunWith(AndroidJUnit4::class)
class LaunchTest {

    @Test
    fun activityLaunchesAndReachesResumed() {
        // Fails if Hilt's SingletonComponent does not exist — the @HiltAndroidApp
        // check no static analysis can perform.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertNotNull("activity was not created", activity) }
        }
    }

    @Test
    fun buildConfigCarriesProvenance() {
        // A build that cannot say which commit produced it makes every later crash
        // report unattributable.
        assertTrue("GIT_SHA is blank", BuildConfig.GIT_SHA.isNotBlank())
        assertTrue("VERSION_NAME is blank", BuildConfig.VERSION_NAME.isNotBlank())
    }

    @Test
    fun applicationIdIsTheExpectedOne() {
        // The applicationId can never change after the first install. Pin it, so a
        // rename becomes a failing test rather than an orphaned user cohort.
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertTrue("unexpected applicationId: $pkg", pkg.startsWith("com.verbalogix.assistant"))
    }
}
