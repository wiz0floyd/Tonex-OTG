package dev.tonexotg.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.tonexotg.app.session.TonexSessionHolder
import dev.tonexotg.app.ui.screens.about.AboutScreen
import dev.tonexotg.app.ui.screens.connection.ConnectionStatusBar
import dev.tonexotg.app.ui.screens.connection.rememberConnectionStatusViewModel
import dev.tonexotg.app.ui.screens.parameters.ParameterEditorScreen
import dev.tonexotg.app.ui.screens.parameters.ParameterEditorViewModel
import dev.tonexotg.app.ui.screens.presets.PresetListScreen
import dev.tonexotg.app.ui.screens.presets.PresetListViewModel
import dev.tonexotg.app.ui.screens.presets.rememberPresetListViewModel
import dev.tonexotg.protocol.PresetIndex

/**
 * The app shell (S23, issue #74; `docs/architecture/s23-ui-wiring.md` §2/§4). A plain [Column],
 * not an app-level [Scaffold] -- [ParameterEditorScreen] already owns its own `Scaffold` (top
 * bar, bottom "Master Volume" dock, snackbar host), and nesting that inside a second, app-level
 * `Scaffold` fights over insets and the bottom bar. See doc §2.3.
 *
 * All three of [PresetListViewModel][dev.tonexotg.app.ui.screens.presets.PresetListViewModel],
 * [ParameterEditorViewModel], and
 * [ConnectionStatusViewModel][dev.tonexotg.app.ui.screens.connection.ConnectionStatusViewModel]
 * are constructed once here, above the [NavHost], keyed on [sessionHolder.controller]'s identity
 * (stable for the life of the process -- see [TonexSessionHolder]'s own KDoc) rather than
 * per-route inside it. This is a deliberate deviation from the obvious "construct
 * [ParameterEditorViewModel] inside its own route composable, call `onCleared()` in
 * `DisposableEffect { onDispose { ... } }`" approach: that class's `onCleared()` calls
 * `ParameterWriteThrottler.cancelAll()`, which is documented to discard any buffered-but-not-yet-
 * sent parameter write without waiting for it. A `NavHost` destination leaves composition on
 * `popBackStack()`, so a per-route-scoped editor VM would call `onCleared()` on every back tap --
 * meaning a slider drag immediately followed by the back button could silently drop a write to
 * the pedal. Hoisting the VM here avoids that entirely: it is never torn down while [TonexApp]
 * itself remains composed (i.e. for as long as the hosting `Activity` is alive), matching how
 * [PresetListViewModel][dev.tonexotg.app.ui.screens.presets.PresetListViewModel] and
 * [ConnectionStatusViewModel][dev.tonexotg.app.ui.screens.connection.ConnectionStatusViewModel]
 * are already never explicitly torn down either. The one cost is that per-screen local state
 * (accordion expansion, an open numeric-entry sheet) also survives navigating away and back --
 * judged an acceptable, arguably-better, side effect rather than a regression.
 */
@Composable
fun TonexApp(
    sessionHolder: TonexSessionHolder,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val editorScope = rememberCoroutineScope()

    val presetListViewModel = rememberPresetListViewModel(
        controller = sessionHolder.controller,
        aliasStore = sessionHolder.aliasStore,
    )
    val parameterEditorViewModel = remember(sessionHolder.controller) {
        ParameterEditorViewModel(sessionHolder.controller, editorScope, sessionHolder.effectiveBounds)
    }
    // Memoized independently of recomposition so identity is stable for
    // rememberConnectionStatusViewModel's own remember(controller, onReconnectRequested) key --
    // an inline lambda literal here would otherwise be a fresh identity on every recomposition of
    // this composable, silently rebuilding the VM (and its stateIn flow) far more often than the
    // "constructed once, for the life of the process" invariant this file's own KDoc claims.
    val onReconnectRequested = remember(sessionHolder, context) {
        { sessionHolder.requestReconnect(context) }
    }
    val connectionStatusViewModel = rememberConnectionStatusViewModel(
        controller = sessionHolder.controller,
        onReconnectRequested = onReconnectRequested,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        val connectionUiState by connectionStatusViewModel.uiState.collectAsState()
        ConnectionStatusBar(
            uiState = connectionUiState,
            onReconnect = connectionStatusViewModel::reconnect,
        )

        // Doc §3.4, consequence 1: a live USB attachment with no foreground-service protection
        // must surface as a visible reason, not just silently withhold the connection. The
        // banner is additive chrome in the shell, not a new ConnectionUiState variant -- widening
        // that sealed type would drag ConnectionStatusScreenTest's fully-enumerated variant
        // coverage in for no benefit (see doc §3.4 / advisor note on this story).
        val blockedReason by sessionHolder.blockedReason.collectAsState()
        blockedReason?.let { reason -> BlockedReasonBanner(reason = reason) }

        NavHost(
            navController = navController,
            startDestination = TonexRoute.PRESET_LIST.route,
            modifier = Modifier.weight(1f),
        ) {
            composable(TonexRoute.PRESET_LIST.route) {
                PresetListRoute(
                    viewModel = presetListViewModel,
                    onPresetOpened = { navController.navigate(TonexRoute.PARAMETER_EDITOR.route) },
                    onAboutClick = { navController.navigate(TonexRoute.ABOUT.route) },
                )
            }
            composable(TonexRoute.PARAMETER_EDITOR.route) {
                ParameterEditorScreen(
                    viewModel = parameterEditorViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(TonexRoute.ABOUT.route) {
                AboutScreen()
            }
        }
    }
}

/**
 * Doc §3.4, consequence 1: a live USB attachment with no foreground-service protection produces a
 * non-null [TonexSessionHolder.blockedReason] instead of opening a protocol session -- this is
 * the only place that reason is rendered. Sits below [ConnectionStatusBar] in the shell, visible
 * on every route, same as that bar.
 */
@Composable
private fun BlockedReasonBanner(reason: String, modifier: Modifier = Modifier) {
    Text(
        text = reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The preset-list *route* composable -- owns the `Scaffold` + `TopAppBar` wrapper doc §2.3
 * requires ("keep that wrapper in the nav file so `PresetListScreen` stays directly previewable
 * and testable as-is"). [PresetListScreen] itself stays a bare, app-bar-less `Column`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetListRoute(
    viewModel: PresetListViewModel,
    onPresetOpened: (PresetIndex) -> Unit,
    onAboutClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Presets", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    TextButton(
                        onClick = onAboutClick,
                        modifier = Modifier.testTag("presetList.aboutButton"),
                    ) {
                        Text("About")
                    }
                },
            )
        },
    ) { contentPadding ->
        PresetListScreen(
            viewModel = viewModel,
            modifier = Modifier.padding(contentPadding),
            onPresetOpened = onPresetOpened,
        )
    }
}
