package dev.tonexotg.app.usb.connection

/**
 * Pure attach/detach decision logic for [UsbConnectionManager] (S12, issue #17). No Android
 * imports at all -- factored out specifically so it can be pinned with plain JVM unit tests (no
 * Robolectric, no framework classes needed), per issue #17's own request for "pure-JVM tests...
 * for state transitions, reconnect logic."
 *
 * ### Why `deviceId`, not the `UsbDevice` instance
 * Issue #17's stated reconnect semantics: "permission is scoped to the physical attachment --
 * after a replug you get a *new* `UsbDevice` instance and must re-open." So identity here can't
 * be reference equality on a cached `UsbDevice`, nor equality on the `UsbDevice` itself (its
 * `equals()` is not documented to be attachment-scoped either). `UsbDevice.getDeviceId()` *is*
 * documented as the platform's own per-attachment identity -- stable while a device stays
 * attached, guaranteed to differ after a detach/reattach cycle -- so it's the only thing this
 * decision logic keys on. [UsbConnectionManager] is responsible for reading that `int` off the
 * real `UsbDevice` and passing it in; nothing here ever touches the Android type.
 */
internal object UsbConnectionDecisions {

    /**
     * Whether an attach event for physical attachment [deviceId] should start a fresh connect
     * attempt against [state]. `false` only when [state] already represents *this exact*
     * attachment, connecting or connected -- covers the ordinary double-delivery case where both
     * the manifest-driven trampoline (cold launch) and the runtime-registered receiver
     * (already-running app) fire for the same physical attach.
     *
     * `true` for a *different* `deviceId` while already [UsbConnectionState.Connected] or
     * [UsbConnectionState.Connecting] -- an edge case (two devices sharing this pedal's VID/PID
     * attached at once isn't realistic hardware, but nothing here assumes it can't happen) that
     * [UsbConnectionManager] handles by tearing down the old attachment before opening the new
     * one, never running both at once.
     */
    fun shouldConnect(state: UsbConnectionState, deviceId: Int): Boolean = when (state) {
        is UsbConnectionState.Connecting -> state.deviceId != deviceId
        is UsbConnectionState.Connected -> state.deviceId != deviceId
        UsbConnectionState.Disconnected, is UsbConnectionState.Failed -> true
    }

    /**
     * Whether a detach event for physical attachment [deviceId] should tear down [state].
     *
     * `true` for [UsbConnectionState.Connecting] on a matching [deviceId] too, not just
     * [UsbConnectionState.Connected]: the pedal can physically vanish mid-permission-request just
     * as easily as mid-open, and [UsbConnectionManager] must not leave a stale connect attempt
     * racing to open (or a transport it already opened) against a `UsbDevice` that no longer
     * exists.
     */
    fun shouldDisconnect(state: UsbConnectionState, deviceId: Int): Boolean = when (state) {
        is UsbConnectionState.Connecting -> state.deviceId == deviceId
        is UsbConnectionState.Connected -> state.deviceId == deviceId
        UsbConnectionState.Disconnected, is UsbConnectionState.Failed -> false
    }
}
