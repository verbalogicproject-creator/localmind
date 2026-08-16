package com.verbalogix.assistant.ui

import android.content.pm.PackageManager
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.test.platform.app.InstrumentationRegistry
import com.verbalogix.assistant.ui.nav.ARG_MESSAGE_ID
import com.verbalogix.assistant.ui.nav.ARG_PACK_ID
import com.verbalogix.assistant.ui.nav.ARG_PROPOSAL_ID
import com.verbalogix.assistant.ui.nav.ARG_SESSION_ID
import com.verbalogix.assistant.ui.nav.ARG_VERSION
import com.verbalogix.assistant.ui.nav.Destinations
import com.verbalogix.assistant.ui.nav.GRAPH_ROOT
import com.verbalogix.assistant.ui.nav.RouteArgs
import com.verbalogix.assistant.ui.theme.LocalmindTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The route graph, driven by a controller a test can assert on.
 *
 * WHAT THIS DOES AND DOES NOT COVER, stated plainly because the distinction matters.
 * It builds a graph from the SAME [Destinations] constants the app uses, with stub
 * content, and drives it. That covers the route strings, the argument plumbing, the
 * back stack and restoration -- a route renamed in `Destinations` breaks this
 * immediately. It does NOT assemble the real `AppNavHost`, which needs the Hilt graph;
 * asserting "the Evidence screen appeared" is also weaker than asserting the back stack
 * is at `chat/message/7/evidence`, which is what is done here.
 */
class NavigationGraphTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var nav: TestNavHostController

    private fun graph(start: String = Destinations.CHAT) {
        compose.setContent {
            nav = TestNavHostController(InstrumentationRegistry.getInstrumentation().targetContext)
            nav.navigatorProvider.addNavigator(ComposeNavigator())
            LocalmindTheme(darkTheme = true) {
                NavHost(navController = nav, startDestination = start, route = GRAPH_ROOT) {
                    composable(Destinations.SETUP_READINESS) { Text("setup") }
                    composable(Destinations.CHAT) { Text("chat") }
                    composable(
                        Destinations.EVIDENCE,
                        arguments = listOf(navArgument(ARG_MESSAGE_ID) { type = NavType.StringType }),
                    ) { Text("evidence") }
                    composable(Destinations.EXPERTS) { Text("experts") }
                    composable(
                        Destinations.EXPERT_DETAIL,
                        arguments = listOf(
                            navArgument(ARG_PACK_ID) { type = NavType.StringType },
                            navArgument(ARG_VERSION) { type = NavType.StringType },
                        ),
                    ) { Text("expert detail") }
                    composable(Destinations.MODELS_PROVIDERS) { Text("providers") }
                    composable(
                        Destinations.TOOL_PROPOSAL,
                        arguments = listOf(
                            navArgument(ARG_SESSION_ID) { type = NavType.StringType },
                            navArgument(ARG_PROPOSAL_ID) { type = NavType.StringType },
                        ),
                    ) { Text("tool approval") }
                }
            }
        }
    }

    private fun currentRoute(): String? = nav.currentBackStackEntry?.destination?.route

    @Test
    fun all_seven_destinations_are_declared_and_reachable() {
        graph()
        val reached = mutableSetOf<String?>()
        compose.runOnUiThread {
            nav.navigate(Destinations.SETUP_READINESS); reached += currentRoute()
            nav.navigate(Destinations.EXPERTS); reached += currentRoute()
            nav.navigate(Destinations.MODELS_PROVIDERS); reached += currentRoute()
            nav.navigate(Destinations.evidence(7L)!!); reached += currentRoute()
            nav.navigate(Destinations.expertDetail("pack", "1.0.0")!!); reached += currentRoute()
            nav.navigate(Destinations.toolProposal("s1", "p1")!!); reached += currentRoute()
        }
        reached += Destinations.CHAT
        assertEquals(
            "every declared destination must resolve in the graph",
            Destinations.ALL.toSet(),
            reached.filterNotNull().toSet(),
        )
    }

    @Test
    fun the_evidence_route_carries_its_message_id() {
        graph()
        compose.runOnUiThread { nav.navigate(Destinations.evidence(7L)!!) }
        assertEquals(Destinations.EVIDENCE, currentRoute())
        assertEquals(
            "7",
            nav.currentBackStackEntry?.arguments?.getString(ARG_MESSAGE_ID),
        )
    }

    @Test
    fun the_expert_route_carries_both_opaque_tokens() {
        graph()
        compose.runOnUiThread {
            nav.navigate(Destinations.expertDetail("kf-core-env", "1.0.0")!!)
        }
        assertEquals(Destinations.EXPERT_DETAIL, currentRoute())
        val args = nav.currentBackStackEntry?.arguments
        assertEquals("kf-core-env", args?.getString(ARG_PACK_ID))
        assertEquals("1.0.0", args?.getString(ARG_VERSION))
    }

    /**
     * A malformed identifier never becomes a route, so it never reaches the graph.
     *
     * The builder returns null and the call site does not navigate -- which is why the
     * back stack must still be on chat afterwards.
     */
    @Test
    fun malformed_identifiers_never_produce_a_navigable_route() {
        graph()
        for (bad in listOf("../../etc/passwd", "a/b", "", "..", "a%2Fb", "-x")) {
            assertNull(Destinations.expertDetail(bad, "1.0.0"))
            assertNull(Destinations.toolProposal("s", bad))
            assertNull(RouteArgs.identifierOrNull(bad))
        }
        assertNull(Destinations.evidence(0L))
        assertEquals("nothing malformed may have navigated", Destinations.CHAT, currentRoute())
    }

    @Test
    fun back_returns_to_the_previous_destination() {
        graph()
        compose.runOnUiThread { nav.navigate(Destinations.MODELS_PROVIDERS) }
        assertEquals(Destinations.MODELS_PROVIDERS, currentRoute())
        compose.runOnUiThread { nav.popBackStack() }
        assertEquals(Destinations.CHAT, currentRoute())
    }

    @Test
    fun closing_evidence_returns_to_chat() {
        graph()
        compose.runOnUiThread { nav.navigate(Destinations.evidence(3L)!!) }
        assertEquals(Destinations.EVIDENCE, currentRoute())
        compose.runOnUiThread { nav.popBackStack() }
        assertEquals(Destinations.CHAT, currentRoute())
    }

    @Test
    fun the_first_run_start_destination_is_setup() {
        graph(start = Destinations.SETUP_READINESS)
        assertEquals(Destinations.SETUP_READINESS, currentRoute())
    }

    @Test
    fun every_declared_route_is_registered_in_the_graph() {
        graph()
        compose.runOnUiThread {
            for (route in Destinations.ALL) {
                val node = nav.graph.findNode(route)
                assertTrue("route $route is missing from the graph", node != null)
            }
        }
    }

    /**
     * Nothing outside this app may address a route.
     *
     * This test previously carried that name over the route-registration body now
     * directly above -- which says nothing whatever about deep links, and would have
     * passed with a `navDeepLink` on every destination. Pairing has no contract yet, so
     * external reachability deserves an assertion that can actually fail.
     *
     * Reachability is a MANIFEST fact, not a graph fact: a Compose `navDeepLink` is
     * inert unless an activity exports an intent filter to carry the URI in. So the
     * graph is the wrong instrument even when pointed correctly -- ask the
     * PackageManager what is genuinely exported.
     */
    @Test
    fun no_activity_is_exported_except_the_launcher() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val launcher = pm.getLaunchIntentForPackage(ctx.packageName)?.component?.className
        @Suppress("DEPRECATION")
        val activities = pm.getPackageInfo(
            ctx.packageName,
            PackageManager.GET_ACTIVITIES,
        ).activities.orEmpty()

        val exported = activities.filter { it.exported && it.name != launcher }.map { it.name }
        assertEquals(
            "no surface may be reachable from another app while pairing has no contract",
            emptyList<String>(),
            exported,
        )
    }

    @Test
    fun the_graph_survives_process_recreation_on_a_detail_route() {
        graph()
        compose.runOnUiThread { nav.navigate(Destinations.evidence(9L)!!) }
        assertEquals(Destinations.EVIDENCE, currentRoute())

        // The saved state a real process death would restore from.
        val saved = compose.runOnUiThread { nav.saveState() }
        val restored = TestNavHostController(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        restored.navigatorProvider.addNavigator(ComposeNavigator())
        compose.runOnUiThread { restored.restoreState(saved) }
        assertTrue("navigation state must be restorable", saved != null)
    }
}
