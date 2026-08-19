package dev.tonexotg.app.usb.connection

import dev.tonexotg.app.usb.TonexUsbTransport

/**
 * [UsbConnectionManager]'s connection lifecycle (S12, issue #17).
 *
 * The transition *decisions* (which events move which states) live in [UsbConnectionDecisions],
 * deliberately separated from this data definition and from [UsbConnectionManager]'s Android
 * plumbing (BroadcastReceiver registration, [dev.tonexotg.app.usb.UsbTonexDeviceOpener] calls) so
 * that logic can be pinned with plain JVM unit tests -- issue #17's own ask for "pure-JVM tests...
 * for state transitions, reconnect logic," matching this project's [dev.tonexotg.app.usb.UsbIoPort]
 * / `FakeUsbIoPort` testability-seam pattern from S11.
 */
sealed interface UsbConnectionState {

    /** No pedal attached, or a previously-attached one has been cleanly torn down. */
    data object Disconnected : UsbConnectionState

    /**
     * Permission is being requested and/or the device is being opened for physical attachment
     * [deviceId] (`UsbDevice.getDeviceId()` -- see [UsbConnectionDecisions]'s KDoc for why this,
     * not the `UsbDevice` instance itself, is the identity this state machine keys on).
     */
    data class Connecting(val deviceId: Int) : UsbConnectionState

    /**
     * A live, already-opened [TonexUsbTransport] for physical attachment [deviceId]. [deviceName]
     * is display-only (`UsbDevice.getDeviceName()`, e.g. a bus/port path) -- never used for
     * identity comparisons, which always go through [deviceId].
     */
    data class Connected(
        val deviceId: Int,
        val deviceName: String,
        val transport: TonexUsbTransport,
    ) : UsbConnectionState

    /**
     * The most recent connect attempt failed. [reason] is the caller-facing message from
     * [dev.tonexotg.app.usb.UsbTonexDeviceOpener.OpenResult.Failure] (permission denied/timed
     * out, no matching interfaces, `openDevice` returned null, etc.) -- see that type's KDoc for
     * the full list of ways opening the pedal can fail.
     */
    data class Failed(val reason: String) : UsbConnectionState
}
