package dev.tonexotg.app.usb.connection

import dev.tonexotg.app.usb.FakeUsbIoPort
import dev.tonexotg.app.usb.TonexUsbTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM pins for [UsbConnectionDecisions] -- no Android framework, no Robolectric. Exists
 * specifically per issue #17's ask for "pure-JVM tests... for state transitions, reconnect
 * logic," separate from [UsbConnectionManagerRobolectricTest]'s coverage of the actual Android
 * wiring.
 */
class UsbConnectionDecisionsTest {

    // No mocking library in this project's :app test deps -- TonexUsbTransport's own
    // FakeUsbIoPort-backed internal constructor (S11's testability seam) is a real instance
    // that never touches the platform, which is all [UsbConnectionState.Connected] needs to
    // exist for these purely-structural equality/branching tests.
    private val transport: TonexUsbTransport = TonexUsbTransport(FakeUsbIoPort(), watchdogTimeoutMillis = 30_000L, watchdogPollIntervalMillis = 5_000L)

    private fun connected(deviceId: Int) = UsbConnectionState.Connected(deviceId, "device-$deviceId", transport)

    // -----------------------------------------------------------------------------------------
    // shouldConnect
    // -----------------------------------------------------------------------------------------

    @Test
    fun shouldConnect_trueFromDisconnected() {
        assertTrue(UsbConnectionDecisions.shouldConnect(UsbConnectionState.Disconnected, deviceId = 1))
    }

    @Test
    fun shouldConnect_trueFromFailed() {
        assertTrue(UsbConnectionDecisions.shouldConnect(UsbConnectionState.Failed("nope"), deviceId = 1))
    }

    @Test
    fun shouldConnect_falseWhenAlreadyConnectingTheSameAttachment() {
        assertFalse(UsbConnectionDecisions.shouldConnect(UsbConnectionState.Connecting(deviceId = 1), deviceId = 1))
    }

    @Test
    fun shouldConnect_trueWhenConnectingADifferentAttachment() {
        assertTrue(UsbConnectionDecisions.shouldConnect(UsbConnectionState.Connecting(deviceId = 1), deviceId = 2))
    }

    @Test
    fun shouldConnect_falseWhenAlreadyConnectedToTheSameAttachment() {
        assertFalse(UsbConnectionDecisions.shouldConnect(connected(deviceId = 1), deviceId = 1))
    }

    @Test
    fun shouldConnect_trueWhenConnectedToADifferentAttachment_reconnectCase() {
        // A replug produces a new UsbDevice/deviceId (issue #17's reconnect semantics) -- this is
        // the "device X detached, device Y attached before the detach broadcast was processed"
        // shape UsbConnectionManager relies on to still open the new attachment.
        assertTrue(UsbConnectionDecisions.shouldConnect(connected(deviceId = 1), deviceId = 2))
    }

    // -----------------------------------------------------------------------------------------
    // shouldDisconnect
    // -----------------------------------------------------------------------------------------

    @Test
    fun shouldDisconnect_falseFromDisconnected() {
        assertFalse(UsbConnectionDecisions.shouldDisconnect(UsbConnectionState.Disconnected, deviceId = 1))
    }

    @Test
    fun shouldDisconnect_falseFromFailed() {
        assertFalse(UsbConnectionDecisions.shouldDisconnect(UsbConnectionState.Failed("nope"), deviceId = 1))
    }

    @Test
    fun shouldDisconnect_trueWhenConnectingTheSameAttachment() {
        // The pedal can vanish mid-permission-request just as easily as mid-open -- see
        // UsbConnectionDecisions' KDoc.
        assertTrue(UsbConnectionDecisions.shouldDisconnect(UsbConnectionState.Connecting(deviceId = 1), deviceId = 1))
    }

    @Test
    fun shouldDisconnect_falseWhenConnectingADifferentAttachment() {
        assertFalse(UsbConnectionDecisions.shouldDisconnect(UsbConnectionState.Connecting(deviceId = 1), deviceId = 2))
    }

    @Test
    fun shouldDisconnect_trueWhenConnectedToTheSameAttachment() {
        assertTrue(UsbConnectionDecisions.shouldDisconnect(connected(deviceId = 1), deviceId = 1))
    }

    @Test
    fun shouldDisconnect_falseWhenConnectedToADifferentAttachment() {
        // A detach event for some unrelated device must never tear down an unrelated live
        // connection.
        assertFalse(UsbConnectionDecisions.shouldDisconnect(connected(deviceId = 1), deviceId = 2))
    }
}
