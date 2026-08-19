package dev.tonexotg.app.usb.connection

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import dev.tonexotg.app.MainActivity
import dev.tonexotg.app.R
import dev.tonexotg.app.usb.UsbTonexDeviceOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground [Service] that keeps the ToneX One's USB session alive across screen-off and
 * backgrounding (S13, issue #18). Owns nothing about the connection itself -- it drives
 * [UsbConnectionManager] (the shared, process-wide connection-lifecycle owner from S12) and
 * projects its [UsbConnectionManager.state] onto a persistent notification, per this story's
 * scope. It never re-derives any attach/detach/permission logic.
 *
 * This class's design was shaped directly by a pre-merge Opus review (issue #18's "Highest-risk
 * platform unknown" callout, and a five-point risk pre-brief prepared in advance for this story)
 * -- the sections below cite that review's findings by number where relevant, since several of
 * them are non-obvious enough that "just read the code" would not reconstruct the reasoning.
 *
 * ### 1. `startForeground()` must be called unconditionally and immediately, never gated on a
 * connect attempt
 * `startForegroundService()` gives the service roughly 5 seconds to call `startForeground()`
 * before the platform throws `ForegroundServiceDidNotStartInTimeException` and kills it.
 * [UsbTonexDeviceOpener.requestPermissionAndOpen] can suspend for up to 30 seconds waiting on the
 * user's permission-dialog answer, and [UsbConnectionManager] holds its own mutex across that
 * whole wait (see that class's KDoc, "Serialization"). If [onStartCommand] waited for a
 * connection to resolve before calling [startForegroundSafely], the very first cold run --
 * exactly the run where the permission dialog is most likely to appear -- would crash
 * deterministically. [onStartCommand] therefore calls [startForegroundSafely] with whatever
 * [UsbConnectionManager.state] value is *already* current (almost always
 * [UsbConnectionState.Disconnected] or [UsbConnectionState.Connecting] on a cold start) before it
 * ever touches the connect path, and lets the [state] collector below update the notification as
 * the real connection resolves.
 *
 * ### 2. Where this service is started from matters (Android 12+ background-start restriction)
 * `Context.startForegroundService()` throws `ForegroundServiceStartNotAllowedException` on API
 * 31+ when called from a background context. [UsbAttachTrampolineActivity] -- a genuine
 * (if invisible) foreground `Activity` launch -- is a safe caller. [UsbConnectionManager]'s own
 * runtime `ACTION_USB_DEVICE_ATTACHED` receiver (registered once this process is already alive,
 * driving reattach without any Activity in front) is explicitly **not** used to start this
 * service, and never should be without its own try/catch and a graceful degrade -- that receiver
 * fires regardless of whether the app is foreground, so an unguarded start call from it would be
 * exactly the disallowed background-start case. This service is designed to already be running
 * (and to keep listening for reattach on its own -- see point 5) across the whole time the app's
 * process is alive, precisely so a background reattach never *needs* to start it fresh.
 *
 * ### 3. `onDestroy` never calls [UsbConnectionManager.stop] or [UsbConnectionManager.disconnect]
 * [UsbConnectionManager.getInstance] is a shared, process-wide singleton designed to outlive any
 * one component -- [UsbAttachTrampolineActivity] depends on the exact same instance for its own
 * cold-launch path. Calling [UsbConnectionManager.stop] here would unregister that instance's
 * attach/detach receiver for the rest of the process's life with no error surfaced anywhere, and
 * calling [UsbConnectionManager.disconnect] would tear down a working pedal connection out from
 * under a user who never asked to disconnect, purely because this service instance happened to
 * stop (a plain `stopService()` call, or the OS reclaiming it under memory pressure with
 * [START_STICKY] queued to restart it). [onDestroy] only cancels [serviceScope] (the observation
 * coroutine) and releases [wakeLock] if held -- both being *this service's own* resources, never
 * anything [UsbConnectionManager] owns.
 *
 * ### 4. `onDestroy` runs on the main thread -- nothing here may block on it
 * Cancelling a [CoroutineScope] and releasing a [PowerManager.WakeLock] are both synchronous,
 * non-blocking calls. Neither touches [TonexUsbTransport.close] (which
 * [UsbConnectionManager.closeTransport] already always hops to `Dispatchers.IO` for, on its own
 * schedule, independent of this service's lifecycle) -- so there is nothing here to `runBlocking`
 * on or await, and [onDestroy] cannot ANR regardless of what state a connect/disconnect happens
 * to be in when it runs.
 *
 * ### 5. [START_STICKY], and always handling a `null` (or device-less) [Intent]
 * Returned deliberately: this service's job is "exist and keep listening," not to process a
 * specific queued request -- there is nothing about a *specific* start [Intent] worth losing if
 * the process dies and the OS restarts the service later. A sticky restart redelivers a `null`
 * [Intent]; a `UsbDevice`/deviceId captured before that restart would be meaningless afterward
 * anyway (`UsbDevice.getDeviceId()` is scoped to a physical attachment, not persisted across a
 * process death). [onStartCommand] therefore never assumes a non-null [Intent]: when the intent
 * carries [UsbManager.EXTRA_DEVICE] (the normal path -- [UsbAttachTrampolineActivity] forwarding
 * the device that triggered this start), it routes straight into
 * [UsbConnectionManager.onDeviceAttached]; otherwise (a `null`/redelivered intent, or a start with
 * no device extra for any other reason) it falls back to
 * [UsbConnectionManager.connectToAttachedDeviceIfPresent] -- built for exactly this "may already
 * be attached, no attach broadcast will ever fire for it" case.
 *
 * ### 6. The `connectedDevice` prerequisite: two mitigations, in the right order, neither
 * load-bearing alone
 * issue #18 documents the runtime prerequisite for `connectedDevice` as "call
 * `UsbManager.requestPermission()`." Google's own foreground-service-types documentation actually
 * lists three independent ways to satisfy it, one of which is that call -- but on this app's
 * preferred, better-UX attach-intent path (S12, "use by default for this USB device"), permission
 * is granted by the platform without this app's code ever calling
 * `UsbManager.requestPermission()` at all (`UsbTonexDeviceOpener.requestPermissionAndOpen` checks
 * `hasPermission()` first and skips the call entirely when it's already true) -- so this is the
 * *normal* case, not a rare edge case, and its true runtime behavior is genuinely undocumented
 * anywhere reliable.
 *
 * Two mitigations, verified against AOSP's actual `UsbUserPermissionManager.requestPermission`
 * source during this story's review (not assumed from the platform docs alone):
 * - [pingPermissionPrerequisiteIfAlreadyGranted], called *before* [startForegroundSafely] on every
 *   [onStartCommand] (non-blocking -- `UsbManager.requestPermission()` returns immediately either
 *   way, so this costs nothing against the ~5s `startForeground()` deadline). It calls
 *   `UsbManager.requestPermission()` for a currently-attached matching device **only when
 *   `hasPermission()` is already `true`** -- confirmed from AOSP source: that path in
 *   `UsbUserPermissionManager` responds immediately with no dialog, so this is safe to call on
 *   every start. Deliberately gated on `hasPermission()` rather than unconditional: calling it
 *   when permission is *not yet* granted would race a second, competing permission request
 *   against [UsbTonexDeviceOpener.requestPermissionAndOpen]'s own call for the exact same device
 *   (which does fire in that case) -- two requests in flight for one physical attachment, worst
 *   on the very first run. Gating on `hasPermission()` targets precisely (and only) the gap
 *   `requestPermissionAndOpen`'s own short-circuit leaves open.
 * - Regardless, [startForegroundSafely] wraps the [startForeground] call in `try`/`catch` for
 *   [SecurityException] (the documented failure mode for an unmet foreground-service-type
 *   prerequisite) and, by class-name string match rather than a `catch` clause naming them
 *   directly (see [NEWER_FOREGROUND_START_EXCEPTION_NAMES]'s own KDoc for why), the API 31+/34+
 *   `ForegroundServiceStartNotAllowedException`/`InvalidForegroundServiceTypeException`/
 *   `MissingForegroundServiceTypeException` family -- and degrades loudly on any of them: logs
 *   the full exception at [Log.e] with enough detail to answer issue #18's "documented from real
 *   observation" acceptance criterion directly from a hardware session's logcat, then stops
 *   itself (**and [onStartCommand] returns immediately without starting the observer or
 *   attempting a connection** -- PR #64 review, L1: an earlier version of this class called
 *   `stopSelf()` but then fell through into the rest of `onStartCommand` anyway, starting real
 *   work on an instance already shutting down; there is no foreground promotion to lose here, so
 *   there is nothing degraded left to attempt). This is what actually protects the unresolvable
 *   case -- a genuine first-ever run, where no `requestPermission()` call of *any* kind has
 *   happened yet by the time `startForeground()` is first attempted, so the ping above cannot
 *   help it (its own `hasPermission()` gate is false).
 *
 * **This is the one behavior in this class that cannot be verified without a real API 34+
 * device** -- see this class's own follow-up note in issue #18 for the exact observation still
 * needed.
 *
 * The ping's own [PendingIntent] deliberately: uses [PendingIntent.FLAG_MUTABLE] (not
 * `FLAG_IMMUTABLE` -- the platform appends result extras via `PendingIntent.send(context, 0,
 * intent)`, which are silently dropped against an immutable one; this project already shipped the
 * opposite mistake once, per [UsbTonexDeviceOpener]'s own `requestPermission` -- read that
 * implementation directly rather than trusting any comment/description about it, including this
 * one), calls [Intent.setPackage] (the exact Android 14+ implicit-mutable-PendingIntent trap
 * issue #25 hit and fixed for the real permission flow -- this ping targets a *different* action
 * than that flow does, but the same platform rule applies), and uses its own distinct action
 * ([ACTION_FGS_PREREQUISITE_PING], never [UsbTonexDeviceOpener]'s `ACTION_USB_PERMISSION]`) so it
 * can never alias that class's own pending permission request and spuriously resume its
 * suspended coroutine.
 *
 * ### Wakelock: a starting point, not a proven-necessary mitigation
 * issue #18 is explicit that the evidence for USB I/O stalling under Doze is weaker than the
 * `connectedDevice` prerequisite risk, and says to treat "FGS + `PARTIAL_WAKE_LOCK`" as a
 * hypothesis to build, not a confirmed requirement -- CLAUDE.md's "don't over-build" calibration
 * applies directly here. [wakeLock] is acquired only while [UsbConnectionState.Connected] and
 * released the moment [state] leaves that (not held through the subsequent close -- see
 * [UsbConnectionManager]'s own KDoc for why `state` already flips *before* the slow teardown, so
 * "leaves Connected" is a faithful, prompt signal). Acquired with **no timeout**
 * (`@SuppressLint("WakelockTimeout")`, deliberately, not an oversight): a bounded timeout would
 * silently drop the lock mid-session on a long rehearsal/gig, which is a worse failure mode than
 * an indefinite lock for this story's actual goal ("survive a full gig without a restart"). Two
 * independent release paths guard against ever holding it forever regardless -- the state-driven
 * release above, and a second, `isHeld`-guarded release in [onDestroy] -- and
 * [PowerManager.WakeLock.setReferenceCounted] is explicitly disabled (`false`) so a second
 * `acquire()` while already held (e.g. re-observing the same already-[UsbConnectionState.Connected]
 * value) can never require more than one `release()` to actually free it.
 *
 * [wakeLock] is created eagerly in [onCreate] (main thread), not lazily on first
 * [UsbConnectionState.Connected] the way an earlier version of this class did (PR #64 review,
 * M1): the state collector runs on `Dispatchers.Default`, and a plain (non-`@Volatile`) field
 * lazily assigned there had no guaranteed happens-before edge back to [onDestroy]'s read on the
 * main thread -- `serviceScope.cancel()` cancels the coroutine, it does not synchronize memory
 * with it. A stale/`null` read in [onDestroy] would have silently skipped the one release path
 * meant to catch every other case, leaking the [PowerManager.WakeLock] for the rest of the
 * process's life. Assigning [wakeLock] once in [onCreate] -- strictly before [onStartCommand] can
 * ever run, let alone launch [serviceScope]'s collector -- means every later read (from either
 * thread) sees the same already-fully-constructed [PowerManager.WakeLock] instance: `launch`
 * itself establishes a happens-before edge from the code preceding it (here, all the way back
 * through [onCreate] on the main thread) into the coroutine body, and [onDestroy] runs on the
 * same main thread strictly after [onCreate] regardless. [PowerManager.WakeLock] itself is a
 * `Binder`-backed system-service handle, safe to call `acquire()`/`release()`/`isHeld` on from
 * multiple threads without any locking of this class's own.
 *
 * ### Testability seam
 * [connectionManager] is settable (not resolved eagerly in [onCreate]) specifically so Robolectric
 * tests can inject a directly-constructed [UsbConnectionManager] (bypassing the process-wide
 * [UsbConnectionManager.getInstance] singleton, the same seam [UsbConnectionManagerRobolectricTest]
 * itself already uses) before triggering [onStartCommand] -- matching this codebase's established
 * pattern (S11's `UsbIoPort`/`FakeUsbIoPort`) of adding a minimal seam only where the real
 * platform API resists deterministic testing, rather than an interface/DI layer this project has
 * no other need for.
 */
class UsbConnectionService : Service() {

    /**
     * Settable for tests (see class KDoc, "Testability seam"); resolved lazily to the real
     * process-wide singleton in production. Never construct a second [UsbConnectionManager]
     * instance in production code -- see point 3 above.
     */
    internal var connectionManager: UsbConnectionManager? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private var hasStartedObserving = false

    /** See class KDoc, "Wakelock" -- assigned once in [onCreate], never reassigned. */
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also { it.setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = resolvedManager()

        // Point 6: fire the (gated, non-blocking) prerequisite ping *before* startForeground --
        // it costs nothing against the ~5s deadline and is strictly no worse than firing after.
        pingPermissionPrerequisiteIfAlreadyGranted()

        // Point 1: unconditional and immediate, before anything that could suspend.
        if (!startForegroundSafely(buildNotification(manager.state.value))) {
            // See class KDoc, point 6 (L1): startForeground() itself failed and
            // degradeAfterFailedStartForeground() already called stopSelf(). Stop here rather
            // than starting the observer/connect flow below on an instance that's already
            // shutting down -- there is no foreground promotion to hand a connection off to.
            // START_NOT_STICKY: an immediate system-triggered restart would hit the exact same
            // unmet prerequisite again with nothing having changed -- a restart loop, not a
            // recovery.
            return START_NOT_STICKY
        }

        if (!hasStartedObserving) {
            hasStartedObserving = true
            // Idempotent/harmless via the production getInstance() path (already started -- see
            // that method's KDoc), but load-bearing for a directly-constructed instance injected
            // through connectionManager in tests (see class KDoc, "Testability seam") -- not
            // dead code.
            manager.start()
            observeJob = serviceScope.launch {
                manager.state.collect { state ->
                    NotificationManagerCompat.from(this@UsbConnectionService)
                        .notifySafely(NOTIFICATION_ID, buildNotification(state))
                    updateWakeLock(state)
                }
            }
        }

        // Point 5: never assume a non-null intent, or that it carries a device extra. Safe to
        // return START_STICKY precisely because nothing below (or above) reads intent again after
        // this block -- a sticky restart's redelivered null intent falls through to the same
        // connectToAttachedDeviceIfPresent() fallback a first cold start with no device extra
        // would. Do not add a later read of intent without re-checking this reasoning.
        val device = intent?.let {
            IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        }
        if (device != null) {
            manager.onDeviceAttached(device)
        } else {
            manager.connectToAttachedDeviceIfPresent()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Point 3: never manager.stop() or manager.disconnect() here -- see class KDoc.
        observeJob?.cancel()
        serviceScope.cancel()
        releaseWakeLockIfHeld()
        super.onDestroy()
    }

    private fun resolvedManager(): UsbConnectionManager =
        connectionManager ?: UsbConnectionManager.getInstance(applicationContext).also { connectionManager = it }

    /**
     * See class KDoc, point 6. Returns whether `startForeground()` actually succeeded, so
     * [onStartCommand] knows whether to proceed (see L1 in that same KDoc section).
     *
     * `ForegroundServiceStartNotAllowedException` (API 31+) and
     * `InvalidForegroundServiceTypeException`/`MissingForegroundServiceTypeException` (API 34+)
     * are matched by [Throwable.javaClass]` .name` string comparison against
     * [NEWER_FOREGROUND_START_EXCEPTION_NAMES] instead of by `catch` clause, deliberately -- an
     * earlier version of this method caught them directly by type. That compiles and is a
     * harmless `instanceof` check at runtime on any API level (these classes simply never get
     * thrown on an OS that predates them), but `:app:lintDebug`'s `NewApi` check flags a `catch`
     * clause naming a higher-`minSdk` class directly as an **Error** regardless (confirmed:
     * 6 of `:app:lintDebug`'s 7 reported errors on this file were exactly these four `catch`
     * clauses and their implicit casts -- PR #64 review, B1). Matching by class name instead
     * means those class *literals* never appear in a `catch` table at all, which satisfies the
     * verifier without weakening the actual behavior one bit.
     *
     * `@SuppressLint("InlinedApi")`: `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` is an
     * API 29 constant against `minSdk` 26, but it's a compile-time-inlined `int` safe to reference
     * on any API level -- [ServiceCompat.startForeground] is the whole point of using the compat
     * wrapper here: it does its own `SDK_INT` branching internally and only actually passes the
     * type through on API levels that understand it.
     */
    @SuppressLint("InlinedApi")
    private fun startForegroundSafely(notification: Notification): Boolean {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            return true
        } catch (e: SecurityException) {
            degradeAfterFailedStartForeground(e)
        } catch (e: RuntimeException) {
            if (e.javaClass.name in NEWER_FOREGROUND_START_EXCEPTION_NAMES) {
                degradeAfterFailedStartForeground(e)
            } else {
                throw e
            }
        }
        return false
    }

    private fun degradeAfterFailedStartForeground(e: Exception) {
        Log.e(
            TAG,
            "startForeground(FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) failed -- the " +
                "connectedDevice prerequisite (issue #18) may not have been satisfied by the " +
                "attach-intent-only permission path. This is the real-hardware observation issue " +
                "#18 asks for; please copy this log line into that issue's thread. " +
                "SDK_INT=${Build.VERSION.SDK_INT}, MANUFACTURER=${Build.MANUFACTURER}",
            e,
        )
        stopSelf()
    }

    /** See class KDoc, point 6, for the full reasoning and the [PendingIntent] hazards avoided here. */
    private fun pingPermissionPrerequisiteIfAlreadyGranted() {
        val device = UsbTonexDeviceOpener.findDevice(this) ?: return
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) return // requestPermissionAndOpen's own call covers this case instead.

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_FGS_PREREQUISITE_PING).setPackage(packageName),
            flags,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    // ---------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------

    private fun ensureNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.usb_connection_channel_name))
            .setDescription(getString(R.string.usb_connection_channel_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(state: UsbConnectionState): Notification {
        val (title, text) = state.toNotificationText()
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // No app-original icon assets exist yet anywhere in this project (no design sign-off
            // -- D2, issue #6 -- has landed to produce one). android.R.drawable.stat_sys_data_usb
            // (used by other USB-status system notifications) is not part of the public SDK
            // surface (confirmed against the actual android-36 android.jar this session -- it's
            // absent from android.R.drawable's public fields); stat_notify_sdcard_usb is the
            // closest public framework icon with "usb" in its own name. Swap for a real themed
            // icon once design assets exist.
            .setSmallIcon(android.R.drawable.stat_notify_sdcard_usb)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent)
            // Not dismissible into confusion (issue #18's acceptance criterion): ongoing +
            // no swipe-to-dismiss, and content always reflects live state, never a static label.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun UsbConnectionState.toNotificationText(): Pair<String, String> {
        val title = getString(R.string.app_name)
        val text = when (this) {
            is UsbConnectionState.Disconnected -> getString(R.string.usb_connection_notification_disconnected)
            is UsbConnectionState.Connecting -> getString(R.string.usb_connection_notification_connecting)
            is UsbConnectionState.Connected -> getString(R.string.usb_connection_notification_connected)
            is UsbConnectionState.Failed -> getString(R.string.usb_connection_notification_failed, reason)
        }
        return title to text
    }

    /**
     * **Corrected in PR #64 review (M2)** -- an earlier version of this KDoc claimed
     * [NotificationManagerCompat.notify] internally checks `POST_NOTIFICATIONS` (API 33+) and
     * no-ops instead of posting when it's missing. That's not what actually happens:
     * [NotificationManagerCompat.notify] performs no permission check of its own at all -- it
     * delegates straight through to the platform `NotificationManager.notify`, which is what
     * actually never throws for a missing `POST_NOTIFICATIONS` specifically (unlike most runtime
     * permissions): the system silently declines to *display* the notification while the call
     * itself still returns normally. That's the real reason this is safe to call without a
     * request flow (per the manifest's own comment) -- not an internal check-and-skip inside
     * this method. `@SuppressLint` is still required regardless of the platform's actual runtime
     * behavior: lint's own `MissingPermission` check flags this call site at Error severity
     * either way (this was error #7 of `:app:lintDebug`'s 7).
     */
    @SuppressLint("MissingPermission")
    private fun NotificationManagerCompat.notifySafely(id: Int, notification: Notification) {
        notify(id, notification)
    }

    // ---------------------------------------------------------------------------------------
    // Wakelock
    // ---------------------------------------------------------------------------------------

    private fun updateWakeLock(state: UsbConnectionState) {
        if (state is UsbConnectionState.Connected) {
            acquireWakeLockIfNeeded()
        } else {
            releaseWakeLockIfHeld()
        }
    }

    /**
     * No timeout, deliberately -- see class KDoc, "Wakelock." `WakelockTimeout` is a real lint
     * warning for an untimed `acquire()`; suppressed here rather than silenced project-wide,
     * since this is the one call site where an indefinite hold is the actual intent.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLockIfNeeded() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
    }

    private fun releaseWakeLockIfHeld() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    companion object {
        private const val TAG = "UsbConnectionService"
        private const val CHANNEL_ID = "usb_connection"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "TonexOTG:UsbConnectionWakeLock"

        /**
         * Deliberately distinct from [UsbTonexDeviceOpener]'s own `ACTION_USB_PERMISSION` -- see
         * class KDoc, point 6, for why aliasing that action would be a real hazard, not just an
         * untidy reuse.
         */
        private const val ACTION_FGS_PREREQUISITE_PING = "dev.tonexotg.app.usb.connection.FGS_PREREQ_PERMISSION_PING"

        /**
         * The fully-qualified class names of the higher-`minSdk` exceptions
         * [startForegroundSafely] treats as an unmet `connectedDevice` prerequisite --
         * `ForegroundServiceStartNotAllowedException` (API 31) and
         * `InvalidForegroundServiceTypeException`/`MissingForegroundServiceTypeException` (API
         * 34), against a `minSdk` of 26. Matched by [Throwable.javaClass]`.name` string
         * comparison, not referenced anywhere in this file as an actual class literal (no
         * `catch (e: ForegroundServiceStartNotAllowedException)`, no import) -- see
         * [startForegroundSafely]'s own KDoc for why: a `catch` clause naming one of these
         * classes directly is exactly what `:app:lintDebug`'s `NewApi` check flags as an Error,
         * even though the equivalent runtime behavior (a `catch` that simply never matches
         * pre-31/34) is completely harmless. Keeping the class literals out of this file
         * entirely -- string names only -- keeps them off the lint verifier's path.
         */
        private val NEWER_FOREGROUND_START_EXCEPTION_NAMES = setOf(
            "android.app.ForegroundServiceStartNotAllowedException",
            "android.app.InvalidForegroundServiceTypeException",
            "android.app.MissingForegroundServiceTypeException",
        )
    }
}
