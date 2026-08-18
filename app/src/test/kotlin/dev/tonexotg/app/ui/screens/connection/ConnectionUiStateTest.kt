package dev.tonexotg.app.ui.screens.connection

import dev.tonexotg.app.ui.components.ConnectionStatus
import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.TonexError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConnectionState] (the protocol's 5-value state machine, S9) mapped onto [ConnectionUiState]
 * (the 4-value screen model, matching [ConnectionStatus]/S15). Covers the full collapse table and
 * that an [ConnectionUiState.ErrorState] always carries a real, matching presentation — never a
 * status that reads as anything but "error."
 */
class ConnectionUiStateTest {

    @Test
    fun idle_mapsToNotConnected() {
        val ui = ConnectionState.Idle.toConnectionUiState()
        assertEquals(ConnectionUiState.NotConnected, ui)
        assertEquals(ConnectionStatus.Disconnected, ui.status)
    }

    @Test
    fun connecting_mapsToConnectingStatus() {
        val ui = ConnectionState.Connecting.toConnectionUiState()
        assertEquals(ConnectionUiState.Connecting, ui)
        assertEquals(ConnectionStatus.Connecting, ui.status)
    }

    @Test
    fun hello_alsoMapsToConnectingStatus() {
        val ui = ConnectionState.Hello.toConnectionUiState()
        assertEquals(ConnectionUiState.Connecting, ui)
        assertEquals(ConnectionStatus.Connecting, ui.status)
    }

    @Test
    fun getState_alsoMapsToConnectingStatus() {
        val ui = ConnectionState.GetState.toConnectionUiState()
        assertEquals(ConnectionUiState.Connecting, ui)
        assertEquals(ConnectionStatus.Connecting, ui.status)
    }

    @Test
    fun ready_mapsToConnected() {
        val ui = ConnectionState.Ready.toConnectionUiState()
        assertEquals(ConnectionUiState.Connected, ui)
        assertEquals(ConnectionStatus.Connected, ui.status)
    }

    @Test
    fun error_mapsToErrorStateCarryingTheCauseAndItsPresentation() {
        val cause = TonexError.CrcMismatch(expected = 0x1234, actual = 0xABCD)
        val ui = ConnectionState.Error(cause).toConnectionUiState()

        assertTrue(ui is ConnectionUiState.ErrorState)
        ui as ConnectionUiState.ErrorState
        assertEquals(ConnectionStatus.Error, ui.status)
        assertEquals(cause, ui.cause)
        assertEquals(cause.toPresentation(), ui.presentation)
    }

    @Test
    fun theFourStatuses_havePairwiseDistinctLabels() {
        val labels = listOf(
            ConnectionUiState.NotConnected.label,
            ConnectionUiState.Connecting.label,
            ConnectionUiState.Connected.label,
            ConnectionUiState.ErrorState(TonexError.TransportFailure(RuntimeException("x"))).label,
        )
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun connectedLabel_neverAppearsForAnErrorState() {
        // Direct check against "no failure path implies success": an ErrorState's label/status
        // must never equal the Connected one's.
        val errorUi = ConnectionState.Error(TonexError.Timeout("hello", 1_000)).toConnectionUiState()
        assertNotEquals(ConnectionUiState.Connected.label, errorUi.label)
        assertNotEquals(ConnectionUiState.Connected.status, errorUi.status)
    }
}
