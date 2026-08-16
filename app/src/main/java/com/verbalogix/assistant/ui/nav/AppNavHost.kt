package com.verbalogix.assistant.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.verbalogix.assistant.BuildConfig
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.ChatScreen
import com.verbalogix.assistant.ui.ChatViewModel
import com.verbalogix.assistant.ui.evidence.EvidenceDrawer
import com.verbalogix.assistant.ui.evidence.EvidenceViewModel
import com.verbalogix.assistant.ui.experts.ExpertDetailScreen
import com.verbalogix.assistant.ui.experts.ExpertDetailViewModel
import com.verbalogix.assistant.ui.experts.ExpertLibraryScreen
import com.verbalogix.assistant.ui.experts.ExpertLibraryViewModel
import com.verbalogix.assistant.ui.providers.ModelsProvidersScreen
import com.verbalogix.assistant.ui.pairing.PairingPanel
import com.verbalogix.assistant.ui.pairing.PairingViewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.verbalogix.assistant.ui.setup.SetupReadinessScreen
import com.verbalogix.assistant.ui.tools.NoToolProposalSource
import com.verbalogix.assistant.ui.tools.ToolApprovalSheet
import kotlinx.coroutines.launch

/**
 * The route the whole graph is nested under.
 *
 * It exists so a view model can be scoped to the GRAPH rather than to one destination.
 * `ChatViewModel` holds the active provider, and Chat and Models&Providers both act on
 * it -- if each destination got its own instance, changing the endpoint on one screen
 * would leave the other pointed at the previous one until something happened to
 * re-read it. That drift is invisible: the strip would name one server while another
 * answered. One instance, one truth.
 */
const val GRAPH_ROOT = "localmind"

const val TAG_NAV_EXPERTS = "nav-experts"
const val TAG_NAV_CHAT = "nav-chat"
const val TAG_NAV_SETTINGS = "nav-settings"

/**
 * What TalkBack says for the Experts tab.
 *
 * A FUNCTION, so it can be tested. The nav bar lives inside [AppNavHost], which needs
 * the Hilt graph to compose at all -- so an assertion about this string would otherwise
 * have to go through an instrumented test that builds the whole shell, and in practice
 * would not have been written. This is the part worth pinning: that an unavailable tab
 * announces WHY and NAMES the operation, rather than announcing only that it is off.
 *
 * The capability id is interpolated rather than spelled out, so this string and the
 * screen behind it cannot drift into naming different operations.
 */
internal fun expertsNavLabel(state: CapabilityState): String = when (state) {
    is CapabilityState.Available -> "Experts"
    is CapabilityState.Unavailable ->
        "Experts, unavailable. ${state.reason} Requires ${state.requiredCapability}."
}

/**
 * The application shell: everything between the theme and a screen.
 *
 * DEEP LINKS ARE DELIBERATELY NOT DECLARED. No `navDeepLink`, no intent filter, no
 * `android:scheme`. Pairing is a Foundry concern and its contract does not exist, so
 * publishing an externally-reachable route now would expose argument handling to other
 * apps before there is anything on the other end of it. Every argument is nevertheless
 * validated as though it came from outside -- see [RouteArgs] -- because the cheap time
 * to get that right is before the door opens, not after.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    shellViewModel: ShellViewModel = hiltViewModel(),
) {
    val capabilities by shellViewModel.capabilities.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // The bar is offered only on the top-level destinations. On setup it would
            // let the user step around the screen that explains the app, and on a
            // detail route it competes with Back.
            if (currentRoute in TOP_LEVEL) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = currentRoute == Destinations.CHAT,
                        onClick = { navController.navigateTopLevel(Destinations.CHAT) },
                        // ICONS ALONGSIDE LABELS, never instead of them. The label is what
                        // makes a tab unambiguous; the icon is what makes the bar scannable
                        // and gives the selected tab a second, non-colour cue.
                        //
                        // Drawn from material-icons-CORE, which has no speech bubble. The
                        // extended artifact would supply one and is a large dependency to
                        // add for a single glyph, so Chat takes the compose/write pencil --
                        // the place you write to the model -- rather than an envelope,
                        // which would read as email.
                        icon = { Icon(Icons.Filled.Create, contentDescription = null) },
                        label = { Text("Chat") },
                        modifier = Modifier.testTag(TAG_NAV_CHAT),
                    )
                    // NEITHER HIDDEN NOR DISABLED. Hiding it makes a lapsed pairing
                    // indistinguishable from a feature that was never built. Disabling
                    // it is barely better and was what shipped: the comment here claimed
                    // the tap "says why" while `enabled = expertsAvailable` guaranteed
                    // the tap did nothing at all. A control that silently absorbs a
                    // press teaches the user the app is broken, not that a capability is
                    // missing.
                    //
                    // So it stays live and navigates. The destination renders the
                    // unavailable state and names the operation it is waiting for, which
                    // is the only honest thing on screen -- `mount.list` is declared by
                    // the Harness or it is not.
                    val expertsGate = capabilities.expertLibrary
                    NavigationBarItem(
                        selected = currentRoute == Destinations.EXPERTS,
                        onClick = { navController.navigateTopLevel(Destinations.EXPERTS) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("Experts") },
                        modifier = Modifier
                            .testTag(TAG_NAV_EXPERTS)
                            .semantics {
                                contentDescription = expertsNavLabel(expertsGate)
                            },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Destinations.MODELS_PROVIDERS,
                        onClick = { navController.navigateTopLevel(Destinations.MODELS_PROVIDERS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Providers") },
                        modifier = Modifier.testTag(TAG_NAV_SETTINGS),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = shellViewModel.startDestination,
            route = GRAPH_ROOT,
            modifier = Modifier.padding(padding),
        ) {

            // ── setup/readiness ─────────────────────────────────────────────────
            composable(Destinations.SETUP_READINESS) {
                val chat = navController.sharedChatViewModel()
                val provider by chat.provider.collectAsStateWithLifecycle()
                val status by chat.status.collectAsStateWithLifecycle()

                val pairing: PairingViewModel = hiltViewModel()
                val session by pairing.session.collectAsStateWithLifecycle()

                SetupReadinessScreen(
                    provider = provider,
                    status = status,
                    foundry = capabilities.expertLibrary,
                    // Offered on first run as well as on Experts. Setup is where the app
                    // explains what a Foundry is FOR, which is the one moment a user has
                    // the context to decide whether to pair at all -- and it is optional
                    // here, exactly like the readiness it reports: Continue is never
                    // gated on it.
                    session = session,
                    onPair = pairing::pair,
                    buildLabel = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                    onContinue = {
                        shellViewModel.completeSetup()
                        navController.navigate(Destinations.CHAT) {
                            // Setup is not somewhere to come back to. Popping it means
                            // Back from chat leaves the app, which is what a user
                            // expects from a start destination.
                            popUpTo(Destinations.SETUP_READINESS) { inclusive = true }
                        }
                    },
                    onOpenProviders = {
                        shellViewModel.completeSetup()
                        navController.navigate(Destinations.MODELS_PROVIDERS) {
                            popUpTo(Destinations.SETUP_READINESS) { inclusive = true }
                        }
                    },
                )
            }

            // ── chat ────────────────────────────────────────────────────────────
            composable(Destinations.CHAT) {
                val chat = navController.sharedChatViewModel()
                val messages by chat.messages.collectAsStateWithLifecycle()
                val status by chat.status.collectAsStateWithLifecycle()
                val sending by chat.sending.collectAsStateWithLifecycle()
                val provider by chat.provider.collectAsStateWithLifecycle()
                val elapsed by chat.elapsed.collectAsStateWithLifecycle()
                val think by chat.think.collectAsStateWithLifecycle()

                ChatScreen(
                    messages = messages,
                    status = status,
                    sending = sending,
                    onSend = chat::send,
                    onRetryStatus = chat::refreshStatus,
                    buildLabel = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
                    provider = provider,
                    elapsed = elapsed,
                    think = think,
                    onToggleThink = chat::toggleThink,
                    onOpenProviders = {
                        navController.navigate(Destinations.MODELS_PROVIDERS)
                    },
                    onOpenEvidence = { messageId ->
                        // A route that cannot be built is not navigated to. The
                        // affordance is only rendered for messages that carry a
                        // grounded verdict, so this is a second line rather than the
                        // first.
                        Destinations.evidence(messageId)?.let(navController::navigate)
                    },
                )
            }

            // ── chat/message/{messageId}/evidence ───────────────────────────────
            //
            // StringType, not LongType, and that is the whole argument-safety design.
            // LongType makes the framework parse the value, and a malformed one throws
            // inside navigation where this app cannot render anything useful about it.
            // Taking a string and validating it in [RouteArgs] means a bad id becomes a
            // state the screen can show.
            composable(
                route = Destinations.EVIDENCE,
                arguments = listOf(navArgument(ARG_MESSAGE_ID) { type = NavType.StringType }),
            ) {
                val vm: EvidenceViewModel = hiltViewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val reQuery by vm.reQueryCapability.collectAsStateWithLifecycle()

                EvidenceDrawer(
                    state = state,
                    reQueryCapability = reQuery,
                    // popBackStack, so focus returns to the transcript entry that
                    // opened the drawer rather than to a freshly built chat screen.
                    onClose = { navController.popBackStack() },
                )
            }

            // ── experts ─────────────────────────────────────────────────────────
            composable(Destinations.EXPERTS) {
                val vm: ExpertLibraryViewModel = hiltViewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val pairing: PairingViewModel = hiltViewModel()
                val session by pairing.session.collectAsStateWithLifecycle()

                // THE PANEL SITS ABOVE THE LIBRARY, not behind a menu. When the library
                // is unavailable, the session is almost always why -- so the remedy
                // belongs on the screen that is failing, next to the explanation of what
                // is missing. Hiding it once connected would be worse: a user whose
                // session has just ended needs to find it in the same place.
                // No padding here: the NavHost already applies the Scaffold's insets to
                // every destination, and re-applying them would double the bottom gap
                // above the navigation bar.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PairingPanel(state = session, onPair = pairing::pair)
                    ExpertLibraryScreen(
                        state = state,
                        onOpenExpert = { releaseId ->
                            Destinations.expertDetail(releaseId)?.let(navController::navigate)
                        },
                    )
                }
            }

            // ── experts/{releaseId} ─────────────────────────────────────────────
            composable(
                route = Destinations.EXPERT_DETAIL,
                arguments = listOf(
                    navArgument(ARG_RELEASE_ID) { type = NavType.StringType },
                ),
            ) {
                val vm: ExpertDetailViewModel = hiltViewModel()
                val state by vm.state.collectAsStateWithLifecycle()

                ExpertDetailScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                )
            }

            // ── models-providers ────────────────────────────────────────────────
            composable(Destinations.MODELS_PROVIDERS) {
                val chat = navController.sharedChatViewModel()
                val providers by chat.providerList.collectAsStateWithLifecycle()
                val provider by chat.provider.collectAsStateWithLifecycle()
                val status by chat.status.collectAsStateWithLifecycle()

                ModelsProvidersScreen(
                    providers = providers,
                    provider = provider,
                    status = status,
                    onSelectProvider = chat::selectProvider,
                    onSaveEndpoint = chat::saveEndpoint,
                    onDeleteEndpoint = { candidate ->
                        // Delete protection lives in ProviderRepository and THROWS on a
                        // seeded row. The UI already hides the affordance, so reaching
                        // here means the two disagreed -- report it rather than crash
                        // the screen the user is standing on.
                        runCatching { chat.deleteEndpoint(candidate) }
                            .onFailure {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        it.message ?: "That endpoint cannot be deleted.",
                                    )
                                }
                            }
                    },
                    isDefaultProvider = chat::isDefault,
                    onRetryStatus = chat::refreshStatus,
                )
            }

            // ── sessions/{sessionId}/tool-proposals/{proposalId} ────────────────
            //
            // The route resolves through NoToolProposalSource, which returns
            // Unavailable for every input. Nothing here constructs a proposal, and
            // there is no branch in which it could.
            composable(
                route = Destinations.TOOL_PROPOSAL,
                arguments = listOf(
                    navArgument(ARG_SESSION_ID) { type = NavType.StringType },
                    navArgument(ARG_PROPOSAL_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                val source = remember { NoToolProposalSource() }
                val state = remember(entry) {
                    source.stateFor(
                        sessionId = entry.arguments?.getString(ARG_SESSION_ID).orEmpty(),
                        proposalId = entry.arguments?.getString(ARG_PROPOSAL_ID).orEmpty(),
                    )
                }
                ToolApprovalSheet(
                    state = state,
                    onDismiss = { navController.popBackStack() },
                    // No decision sink. Approve and Deny cannot fire.
                    onDecision = null,
                )
            }
        }
    }
}

private val TOP_LEVEL = setOf(
    Destinations.CHAT,
    Destinations.EXPERTS,
    Destinations.MODELS_PROVIDERS,
)

/**
 * Top-level switching that does not grow the back stack without bound.
 *
 * `launchSingleTop` plus `popUpTo(start) { saveState }` is the standard bar behaviour:
 * tapping Chat, Providers, Chat leaves one entry rather than three, and Back from any
 * tab leaves the app instead of walking a history of tab presses. `restoreState` is
 * what makes a tab remember its scroll position across a switch.
 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The one [ChatViewModel], scoped to the graph rather than to a destination.
 *
 * Every destination that asks receives the same instance, so a provider change on one
 * surface is immediately true on the other.
 *
 * KEYED ON THE CURRENT BACK STACK ENTRY, NOT ON THE CONTROLLER, and the difference is
 * not cosmetic. The controller is one stable object for the whole composition, so
 * `remember(this)` resolves the graph entry exactly once and caches it forever --
 * including across a pop that destroys and recreates that entry, which leaves this
 * scoped to a dead `ViewModelStoreOwner`. Re-keying on the current entry re-resolves
 * it whenever the back stack moves.
 *
 * Found by `lint`, not by reading: the first version keyed on `this`, compiled
 * perfectly, and `UnrememberedGetBackStackEntry` flagged it. That is the rung doing
 * its job — the failure it prevents is a stale or destroyed scope, which shows up as
 * an intermittent crash long after the change that caused it.
 */
@Composable
private fun NavHostController.sharedChatViewModel(): ChatViewModel {
    val current by currentBackStackEntryAsState()
    val parentEntry = remember(current) { getBackStackEntry(GRAPH_ROOT) }
    return hiltViewModel(parentEntry)
}
