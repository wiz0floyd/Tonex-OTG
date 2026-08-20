package dev.tonexotg.app.ui.screens.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.tonexotg.app.ui.theme.TonexTheme
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Screen-level integration tests for S18 (issue #23): the connection status bar, error panel,
 * reconnect wiring, and the "disconnect mid-edit doesn't lose the user's place" requirement.
 * Runs as a plain JVM Robolectric unit test, same as [dev.tonexotg.app.ui.components.ConnectionStatusIndicatorTest]
 * — no emulator/device required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionStatusScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val allErrors: List<TonexError> = listOf(
        TonexError.TransportFailure(RuntimeException("device disconnected")),
        TonexError.Timeout(operation = "hello", timeoutMillis = 3_000),
        TonexError.MalformedFrame(reason = "unescaped delimiter"),
        TonexError.CrcMismatch(expected = 0x1234, actual = 0xABCD),
        TonexError.ProtocolStateViolation(state = ConnectionState.Connecting, details = "setParameter requires Ready"),
        TonexError.UnsupportedByFirmware(operation = "single-parameter-write"),
        TonexError.StaleSessionState(details = "superseded by a later read", sameSession = true),
        TonexError.UnexpectedBlobShape(context = "state blob", expectedSize = 4096, actualSize = 12),
        TonexError.BlobTooShortToPatch(minimumSize = 256, actualSize = 18),
        TonexError.BlobSizeChangedSinceHandshake(pinnedSize = 4096, actualSize = 2048),
        TonexError.ImplausibleStateBlobShape(actualSize = 4096),
        TonexError.OversizedStateBlob(maxSize = 8192, actualSize = 16384),
        TonexError.InvalidPresetIndex(value = 255),
        TonexError.NoSnapshotAvailable(presetIndex = PresetIndex(3)),
        TonexError.RevertIncomplete(
            presetIndex = PresetIndex(3),
            appliedCount = 47,
            totalCount = 109,
            failedParameter = ParameterId(47),
            cause = TonexError.Timeout(operation = "parameter-write", timeoutMillis = 500),
        ),
        TonexError.ActivePresetChangedDuringRevert(
            intendedPreset = PresetIndex(3),
            observedPreset = PresetIndex(5),
            appliedCount = 20,
            totalCount = 109,
            nextParameter = ParameterId(20),
        ),
        TonexError.ParameterValueOutOfRange(id = ParameterId(20), value = 99f, min = 0f, max = 10f),
    )

    // ---- Every TonexError variant renders a distinct message, enumerated ----------------------

    @Test
    fun everyTonexErrorVariant_rendersItsOwnDistinctTitleAndDetailInThePanel() {
        // A single composition stacking all 17 panels (createComposeRule only supports one
        // setContent per test) — every variant's title and detail must be independently findable.
        composeTestRule.setContent {
            TonexTheme {
                Column {
                    for (error in allErrors) {
                        ConnectionErrorPanel(presentation = error.toPresentation(), onReconnect = {})
                    }
                }
            }
        }
        for (error in allErrors) {
            val presentation = error.toPresentation()
            composeTestRule.onNodeWithText(presentation.title).assertExists(
                "expected ${error::class.simpleName}'s title '${presentation.title}' to render",
            )
            composeTestRule.onNodeWithText(presentation.detail).assertExists(
                "expected ${error::class.simpleName}'s detail to render verbatim",
            )
        }
    }

    @Test
    fun everyTonexErrorVariant_exposesItsFullPresentationAsAccessibleContentDescription() {
        // Mirrors S15's non-visual semantics-tree pattern: the meaning must survive even if the
        // visual text isn't legible (D1 §2.3's color/shape/text redundancy requirement extends
        // naturally to the error panel).
        composeTestRule.setContent {
            TonexTheme {
                Column {
                    for (error in allErrors) {
                        ConnectionErrorPanel(presentation = error.toPresentation(), onReconnect = {})
                    }
                }
            }
        }
        for (error in allErrors) {
            val presentation = error.toPresentation()
            composeTestRule
                .onNodeWithContentDescription("${presentation.title}. ${presentation.detail} ${presentation.advice}")
                .assertExists()
        }
    }

    // ---- No failure path renders a UI that implies success ------------------------------------

    @Test
    fun errorStatus_neverShowsTheConnectedLabel() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusBar(
                    uiState = ConnectionUiState.ErrorState(TonexError.TransportFailure(RuntimeException("x"))),
                    onReconnect = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Connected").assertDoesNotExist()
    }

    @Test
    fun disconnectedStatus_neverShowsTheConnectedLabel() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusBar(uiState = ConnectionUiState.NotConnected, onReconnect = {})
            }
        }
        composeTestRule.onNodeWithText("Connected").assertDoesNotExist()
    }

    @Test
    fun connectingStatus_neverShowsTheConnectedLabel() {
        // "Connecting…" must not collapse to (or be confused with) the fully-connected label —
        // a still-establishing link is not yet a success state.
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusBar(uiState = ConnectionUiState.Connecting, onReconnect = {})
            }
        }
        composeTestRule.onNodeWithText("Connected").assertDoesNotExist()
    }

    // ---- Reconnect affordance -------------------------------------------------------------------

    @Test
    fun reconnectButton_isOfferedWhenNotConnected() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusBar(uiState = ConnectionUiState.NotConnected, onReconnect = {})
            }
        }
        composeTestRule.onNodeWithText("Reconnect").assertExists()
    }

    @Test
    fun reconnectButton_isOfferedOnError() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusBar(
                    uiState = ConnectionUiState.ErrorState(TonexError.TransportFailure(RuntimeException("x"))),
                    onReconnect = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Reconnect").assertExists()
    }

    @Test
    fun reconnectButton_isNotOfferedWhileAlreadyConnecting() {
        // A second connect attempt racing the one already in flight would not help.
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusBar(uiState = ConnectionUiState.Connecting, onReconnect = {})
            }
        }
        composeTestRule.onNodeWithText("Reconnect").assertDoesNotExist()
    }

    @Test
    fun reconnectButton_isNotOfferedWhileConnected() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusBar(uiState = ConnectionUiState.Connected, onReconnect = {})
            }
        }
        composeTestRule.onNodeWithText("Reconnect").assertDoesNotExist()
    }

    @Test
    fun tappingReconnect_invokesTheProvidedCallback() {
        var invoked = false
        composeTestRule.setContent {
            TonexTheme {
                ConnectionStatusBar(uiState = ConnectionUiState.NotConnected, onReconnect = { invoked = true })
            }
        }
        composeTestRule.onNodeWithText("Reconnect").performClick()
        composeTestRule.waitForIdle()
        assertEquals(true, invoked)
    }

    @Test
    fun reconnectButton_wiredThroughTheRealViewModel_invokesTheReconnectCallback() {
        // S23 (issue #74; docs/architecture/s23-ui-wiring.md §3.6): ConnectionStatusViewModel no
        // longer opens a protocol session itself -- controller.connect(transportFactory()) from
        // the UI layer bypassed the foreground-service gate entirely. reconnect() now just
        // invokes an injected callback; this test asserts that callback fires, replacing the
        // old assertion that pinned the now-removed direct-connect behavior.
        val fake = FakeTonexController(initialState = ConnectionState.Idle)
        var reconnectRequests = 0

        composeTestRule.setContent {
            TonexTheme {
                val viewModel = rememberConnectionStatusViewModel(
                    controller = fake,
                    onReconnectRequested = { reconnectRequests++ },
                )
                val uiState by viewModel.uiState.collectAsState()
                ConnectionStatusBar(uiState = uiState, onReconnect = viewModel::reconnect)
            }
        }

        composeTestRule.onNodeWithText("Reconnect").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, reconnectRequests)
        assertEquals(0, fake.connectCallCount)
        assertEquals(0, fake.disconnectCallCount)
    }

    // ---- Disconnect mid-edit: content survives, user's place is not lost ----------------------

    @Test
    fun connectionAwareHost_preservesWrappedContentStateAcrossADisconnect() {
        var externalUiState by mutableStateOf<ConnectionUiState>(ConnectionUiState.Connected)

        composeTestRule.setContent {
            TonexTheme {
                ConnectionAwareHost(uiState = externalUiState, onReconnect = {}) {
                    // Simulates a screen's own in-progress local state (e.g. a scroll position or
                    // an open numeric-entry sheet) that must not reset when the connection drops.
                    var counter by remember { mutableIntStateOf(0) }
                    Column {
                        Text("counter:$counter")
                        Button(onClick = { counter++ }) { Text("Increment") }
                    }
                }
            }
        }

        // Establish some local state while connected.
        composeTestRule.onNodeWithText("Increment").performClick()
        composeTestRule.onNodeWithText("Increment").performClick()
        composeTestRule.onNodeWithText("counter:2").assertExists()

        // Simulate a disconnect happening out from under the screen.
        externalUiState = ConnectionUiState.ErrorState(TonexError.TransportFailure(RuntimeException("unplugged")))
        composeTestRule.waitForIdle()

        // The content is still there, still showing the same counter value — not reset, not
        // unmounted and rebuilt from scratch — and the error is now visible alongside it, not in
        // place of it.
        composeTestRule.onNodeWithText("counter:2").assertExists()
        composeTestRule.onNodeWithText("Connection Lost").assertExists()

        // The place is still usable: further local interaction still works post-disconnect.
        composeTestRule.onNodeWithText("Increment").performClick()
        composeTestRule.onNodeWithText("counter:3").assertExists()
    }

    @Test
    fun connectionAwareHost_neverHidesContentBehindAFullScreenReplacement() {
        composeTestRule.setContent {
            TonexTheme {
                ConnectionAwareHost(
                    uiState = ConnectionUiState.ErrorState(TonexError.Timeout("hello", 1_000)),
                    onReconnect = {},
                ) {
                    Text("Parameter editor content")
                }
            }
        }
        // Both the error panel and the wrapped content are simultaneously present.
        composeTestRule.onNodeWithText("Pedal Didn't Respond").assertExists()
        composeTestRule.onNodeWithText("Parameter editor content").assertExists()
    }
}
