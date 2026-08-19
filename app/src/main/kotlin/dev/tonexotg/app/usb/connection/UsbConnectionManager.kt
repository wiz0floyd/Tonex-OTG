package dev.tonexotg.app.usb.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.tonexotg.app.usb.TonexUsbTransport
import dev.tonexotg.app.usb.UsbTonexDeviceOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the ToneX One's USB attach/detach/permission lifecycle end to end (S12, issue #17): reacts
 * to `ACTION_USB_DEVICE_ATTACHED`/`ACTION_USB_DEVICE_DETACHED`, drives
 * [UsbTonexDeviceOpener]'s permission-request-and-open sequence, and hands out the resulting
 * [TonexUsbTransport] via [state]/[currentTransport].
 *
 * There is no S13 foreground service yet (that's the very next story after this one) for this to
 * hand off to, so this class is the connection owner in its own right today -- both
 * [UsbAttachTrampolineActivity] (the manifest-driven cold-launch-on-attach path) and any future
 * S13 service are meant to drive the same shared instance via [getInstance], not re-derive this
 * logic. Nothing here assumes a foreground service exists; nothing here blocks on one either.
 *
 * ### Two receivers, two different registration rules (issue #17)
 * [UsbTonexDeviceOpener]'s own `ACTION_USB_PERMISSION` receiver is a custom, app-defined action,
 * so it registers with `RECEIVER_NOT_EXPORTED` -- see that class. [start]'s receiver here only
 * ever handles `ACTION_USB_DEVICE_ATTACHED`/`ACTION_USB_DEVICE_DETACHED`, both **system-protected
 * broadcasts** nothing but the platform can ever send, which is the documented exception: "If
 * your app is registering a receiver only for system broadcasts... then it shouldn't specify a
 * flag." [SYSTEM_BROADCAST_RECEIVER_FLAGS] is `0` for exactly that reason -- not an oversight.
 *
 * ### Never caches a `UsbDeviceConnection` across a detach
 * Issue #17, FR2: "permission is scoped to the physical attachment... never cache a
 * `UsbDeviceConnection` across a detach." This class enforces that by construction, not by
 * discipline: it never stores a `UsbDeviceConnection` at all. The only handle it ever holds is a
 * [TonexUsbTransport] (which owns and eventually releases the connection itself), and that handle
 * lives *inside* [UsbConnectionState.Connected] -- there is no field anywhere that could outlive
 * the state transition off [UsbConnectionState.Connected] on detach. [UsbConnectionDecisions]
 * keys every transition on `UsbDevice.getDeviceId()`, the platform's own per-attachment identity,
 * which is guaranteed to differ after a replug -- so a stale attachment's transport is always
 * torn down (see [closeTransport]) before a new one for the same physical pedal can ever be
 * opened.
 *
 * ### State is published *before* the slow close, not after
 * [onDeviceAttached] and [onDeviceDetached] both capture any previous [TonexUsbTransport] and
 * advance [_state] off [UsbConnectionState.Connected] *before* calling [closeTransport] on it, not
 * after. Found in review (PR #63, Opus, M1): the original ordering awaited the close first, which
 * left [_state] (and therefore [currentTransport]) reporting [UsbConnectionState.Connected] --
 * handing out a transport that was already closing or closed -- for the entire close, measured at
 * ~300ms on an ordinary detach and over 1.5s via [TonexUsbTransport.teardown]'s leak branch
 * against a close-behavior-faithful fake `UsbIoPort` (the `FakeUsbIoPort` this module's other
 * tests use hides the gap entirely, since its `requestWait` throws on interrupt instead of
 * honoring the real native call's ~500ms bound the way `AndroidUsbIoPort` does). Not a
 * correctness/safety hazard on its own -- a write against an already-closing transport still
 * fails loudly and accurately, per S11's own guarantees -- but S13's foreground service is meant
 * to consume [state]/[currentTransport] directly, so reporting a connection that is actually
 * already gone is a real observability bug worth closing now rather than carrying into that
 * story. This reordering only narrows the window (the mutex still keeps the whole sequence
 * serialized -- see "Serialization" below); it does not change any of that section's reasoning.
 *
 * ### Serialization: [mutex] holds across the *entire* connect attempt, including the permission
 * wait
 * Every path that can create or tear down a [TonexUsbTransport] -- [onDeviceAttached],
 * [onDeviceDetached], and [disconnect] -- runs inside [mutex]. In particular
 * [UsbTonexDeviceOpener.requestPermissionAndOpen] (which can suspend for up to its own
 * `timeoutMillis`, default 30s, waiting on the user's permission-dialog answer) runs *while the
 * mutex is held*, not released around it. That is a deliberate tradeoff, not an oversight:
 * releasing the mutex during the permission wait would let a concurrent detach event tear down
 * "nothing" (no transport exists yet) and flip the state to [UsbConnectionState.Disconnected],
 * only for the still-in-flight `requestPermissionAndOpen` call to later succeed and overwrite that
 * with [UsbConnectionState.Connected] for a physically-unplugged device -- a leaked, silently
 * un-torn-down [TonexUsbTransport] nothing would ever close. Holding the mutex the whole time
 * instead guarantees a detach event that arrives mid-permission-wait simply queues behind the
 * in-flight attempt and runs immediately after it resolves (to [UsbConnectionState.Connected] or
 * [UsbConnectionState.Failed]), correctly tearing down whatever actually resulted. The visible
 * cost is that [state] can lag a real-world detach by up to that same ~30s in the specific case
 * of "user unplugs the pedal while the permission dialog is still showing" -- an acceptable
 * tradeoff for this project's stakes (CLAUDE.md: don't over-build automatic-recovery rigor aimed
 * at hypothetical edge cases) versus the alternative of a use-after-close hazard on the one thing
 * this codebase treats as non-negotiable: never risking corrupting/crashing into the pedal's own
 * connection state.
 *
 * This same mutex is also what rules out ever having two live [TonexUsbTransport]s open against
 * overlapping USB state at once: [closeTransport] always runs to completion (its
 * `withContext(Dispatchers.IO) { transport.close() }` actually returns) before either
 * [onDeviceAttached] or [onDeviceDetached] releases the lock, so a new transport can never be
 * opened while a previous one is still mid-teardown -- this holds regardless of exactly when
 * [_state] itself gets updated relative to that close (see "State is published before the slow
 * close" above); [mutex] is the actual serialization mechanism, [_state]'s timing is purely about
 * what outside observers see.
 *
 * ### Never blocks the caller's thread
 * [TonexUsbTransport.close]'s own KDoc: it can block synchronously for up to ~4.5s and must not
 * be called from the main thread. [closeTransport] always hops to [Dispatchers.IO] before calling
 * it, regardless of what dispatcher called into this class -- so nothing in this class can ANR
 * the caller no matter which thread/dispatcher invokes [onDeviceAttached], [onDeviceDetached], or
 * [disconnect].
 */
class UsbConnectionManager internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mutex = Mutex()

    private val _state = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)

    /** Current connection lifecycle state; see [UsbConnectionState]'s KDoc. */
    val state: StateFlow<UsbConnectionState> = _state.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    /** The live transport, if [state] is currently [UsbConnectionState.Connected]; else `null`. */
    fun currentTransport(): TonexUsbTransport? = (_state.value as? UsbConnectionState.Connected)?.transport

    /**
     * Registers the `ACTION_USB_DEVICE_ATTACHED`/`ACTION_USB_DEVICE_DETACHED` receiver so this
     * instance reacts to attach/detach while the process is alive -- not just via
     * [UsbAttachTrampolineActivity]'s manifest-driven cold-launch path. Idempotent: a second call
     * on an already-started instance is a no-op. [getInstance] calls this automatically; callers
     * normally never need to call it directly.
     */
    @Synchronized
    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    ?: return
                if (device.vendorId != UsbTonexDeviceOpener.VENDOR_ID || device.productId != UsbTonexDeviceOpener.PRODUCT_ID) return
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> onDeviceAttached(device)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onDeviceDetached(device)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, r, filter, SYSTEM_BROADCAST_RECEIVER_FLAGS)
        receiver = r
    }

    /** Unregisters [start]'s receiver, if registered. Mainly for tests; production code normally never needs this. */
    @Synchronized
    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * The attach-intent path (issue #17's preferred path, better UX): called by
     * [UsbAttachTrampolineActivity] on a fresh manifest-driven launch, and by [start]'s receiver
     * while this process is already running. See this class's KDoc for the full
     * serialization/never-blocks-main-thread contract.
     */
    fun onDeviceAttached(device: UsbDevice) {
        val deviceId = device.deviceId
        scope.launch {
            mutex.withLock {
                if (!UsbConnectionDecisions.shouldConnect(_state.value, deviceId)) return@withLock
                // Capture (and publish past) any previous transport *before* closing it -- see
                // this class's KDoc, "state is published before the slow close." Once _state
                // flips off Connected, currentTransport() correctly returns null for the rest of
                // this attempt even while closeTransport's up-to-~4.5s close is still running.
                val previous = (_state.value as? UsbConnectionState.Connected)?.transport
                _state.value = UsbConnectionState.Connecting(deviceId)
                previous?.let { closeTransport(it) }
                when (val result = UsbTonexDeviceOpener.requestPermissionAndOpen(context, device)) {
                    is UsbTonexDeviceOpener.OpenResult.Failure ->
                        _state.value = UsbConnectionState.Failed(result.reason)

                    is UsbTonexDeviceOpener.OpenResult.Success -> {
                        val transport = TonexUsbTransport(
                            result.connection,
                            result.dataInterface,
                            result.commInterface,
                            result.inEndpoint,
                            result.outEndpoint,
                        )
                        _state.value = UsbConnectionState.Connected(deviceId, device.deviceName, transport)
                    }
                }
            }
        }
    }

    /**
     * The `requestPermission()` fallback path (issue #17): call when the app starts (or a user
     * explicitly asks to connect) and the pedal may already be attached from before this process
     * existed -- no attach broadcast will ever fire for a device that was already plugged in.
     * [UsbTonexDeviceOpener.requestPermissionAndOpen] itself already checks
     * `UsbManager.hasPermission` first and only shows the system dialog if actually needed, so
     * this is a thin, explicit entry point for that case rather than separate logic.
     */
    fun connectToAttachedDeviceIfPresent() {
        UsbTonexDeviceOpener.findDevice(context)?.let(::onDeviceAttached)
    }

    /**
     * Reacts to a physical detach: tears down the transport for this exact attachment if one is
     * connected or being connected (see [UsbConnectionDecisions.shouldDisconnect]), a no-op
     * otherwise (e.g. a detach for some other device, or one that arrives after this attachment
     * was already torn down another way).
     */
    fun onDeviceDetached(device: UsbDevice) {
        val deviceId = device.deviceId
        scope.launch {
            mutex.withLock {
                if (!UsbConnectionDecisions.shouldDisconnect(_state.value, deviceId)) return@withLock
                val previous = (_state.value as? UsbConnectionState.Connected)?.transport
                _state.value = UsbConnectionState.Disconnected
                previous?.let { closeTransport(it) }
            }
        }
    }

    /**
     * Manual disconnect (e.g. a user-facing "disconnect" action). Safe to call regardless of
     * current [state]. Like [onDeviceAttached]/[onDeviceDetached], this queues behind [mutex] --
     * if a connect attempt's permission wait is in flight (see "Serialization" above), this call
     * does nothing observable until that attempt resolves, up to its own ~30s timeout. Correct
     * (it still runs, and still tears down whatever the attempt produced), but worth knowing if a
     * future screen ever wires this to a visible button: "tap disconnect, nothing happens for a
     * while" is an expected consequence of this design, not a bug (PR #63 review).
     */
    fun disconnect() {
        scope.launch {
            mutex.withLock {
                val previous = (_state.value as? UsbConnectionState.Connected)?.transport
                _state.value = UsbConnectionState.Disconnected
                previous?.let { closeTransport(it) }
            }
        }
    }

    /**
     * Closes [transport] off the caller's dispatcher -- see this class's KDoc, "Never blocks the
     * caller's thread." Always called with [mutex] held, and always *after* [_state] has already
     * been advanced past whatever [UsbConnectionState.Connected] held this exact [transport] --
     * see "State is published before the slow close" above. [mutex] staying held while this runs
     * is still what rules out a second transport ever existing alongside one still mid-teardown;
     * only the *visibility* of [_state]/[currentTransport] to outside callers moved earlier, not
     * the actual serialization.
     */
    private suspend fun closeTransport(transport: TonexUsbTransport) {
        withContext(Dispatchers.IO) { transport.close() }
    }

    companion object {
        /**
         * **Corrected in S13 (issue #18)** -- this was `0` (neither `RECEIVER_EXPORTED` nor
         * `RECEIVER_NOT_EXPORTED`) under the reasoning that "a receiver registering only for
         * system broadcasts shouldn't specify a flag." That reasoning is real, but it describes
         * the *raw platform* `Context.registerReceiver(receiver, filter, flags)` overload, not
         * this call site: [ContextCompat.registerReceiver] is `androidx.core`'s own compat
         * wrapper around it, and its bytecode (confirmed by decompiling the actual
         * `androidx.core:core:1.16.0` AAR this session, not assumed) throws
         * `IllegalArgumentException("One of either RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED is
         * required")` **unconditionally** whenever neither bit is set in `flags` -- there is no
         * branch anywhere in that method that special-cases a system-protected-broadcast-only
         * filter the way the platform docs' exception implies. Passing `0` here meant [start]
         * would throw the instant it ever ran, on every API level, real hardware included -- not
         * a Robolectric-only artifact. This was never caught earlier because
         * `UsbConnectionManagerRobolectricTest` (S12) never actually calls [start] at all; it
         * drives [onDeviceAttached]/[onDeviceDetached] directly. S13's own
         * `UsbConnectionServiceRobolectricTest`, which does call [start] (via [UsbConnectionService]),
         * is what surfaced this.
         *
         * Now [ContextCompat.RECEIVER_EXPORTED]: for [start]'s own receiver specifically (only
         * ever handling `ACTION_USB_DEVICE_ATTACHED`/`ACTION_USB_DEVICE_DETACHED`, both
         * system-protected broadcasts nothing but the platform can ever send -- see this class's
         * KDoc), whether it's exported or not carries no actual security difference: no
         * third-party app can spoof either action regardless. Do not reuse this constant for any
         * receiver that also handles an app-defined action (like [UsbTonexDeviceOpener]'s
         * `ACTION_USB_PERMISSION`, which correctly uses `RECEIVER_NOT_EXPORTED` -- a real
         * app-defined action *can* be spoofed by another app if exported).
         */
        private const val SYSTEM_BROADCAST_RECEIVER_FLAGS = ContextCompat.RECEIVER_EXPORTED

        @Volatile
        private var instance: UsbConnectionManager? = null

        /**
         * The process-wide shared instance, created (and [start]ed) on first call. Deliberately a
         * plain singleton rather than a full DI graph -- there's no `Application` subclass or DI
         * framework in this codebase yet, and introducing one is out of scope for this story.
         * [UsbAttachTrampolineActivity] and any future S13 service both drive this same instance.
         *
         * The backing [CoroutineScope] uses [SupervisorJob] + [Dispatchers.Default], matching
         * [dev.tonexotg.app.probe.ProbeActivity]'s own justification for that combination: it
         * must outlive any single Activity's lifecycle (a rotation, or this Activity finishing
         * right after handing off, must not cancel an in-flight connect attempt).
         */
        fun getInstance(context: Context): UsbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: UsbConnectionManager(
                    context.applicationContext,
                    CoroutineScope(SupervisorJob() + Dispatchers.Default),
                ).also {
                    it.start()
                    instance = it
                }
            }
    }
}
