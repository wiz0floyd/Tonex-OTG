package dev.tonexotg.app.usb.connection

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * No-display trampoline the platform launches directly when `res/xml/device_filter.xml` matches a
 * freshly-attached USB device (S12, issue #17's "auto-launch on attach" path -- the strictly
 * better-UX path when the user has ticked "use by default for this USB device": subsequent
 * attachments auto-launch and auto-grant with no dialog at all).
 *
 * `USB_DEVICE_ATTACHED` can only target an `<activity>`, not a `<service>` (issue #17), which is
 * exactly why this trampoline exists at all rather than a manifest `<service>` intent-filter
 * directly. As of S13 (issue #18), it starts [UsbConnectionService] (forwarding the freshly-
 * attached [UsbDevice] as an intent extra) instead of calling [UsbConnectionManager] directly --
 * see [UsbConnectionService]'s KDoc, point 2, for why this Activity specifically (a genuine, if
 * invisible, foreground launch) is a safe place to call `startForegroundService()` from, when
 * [UsbConnectionManager]'s own runtime attach receiver is deliberately not. This keeps there being
 * exactly one path that drives [UsbConnectionManager]'s attach handling on a cold start --
 * [UsbConnectionService.onStartCommand] -- rather than this Activity and the service both calling
 * into the manager independently. `android:theme="@android:style/Theme.NoDisplay"` in the manifest
 * means this never actually paints a frame -- the user should never see it, only whatever screen
 * (or none) was already in front.
 *
 * Deliberately a plain [Activity], not `ComponentActivity`/Compose: there is nothing to display,
 * so none of that machinery is needed.
 */
class UsbAttachTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device != null) {
            val serviceIntent = Intent(applicationContext, UsbConnectionService::class.java)
                .putExtra(UsbManager.EXTRA_DEVICE, device)
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        }
        finish()
    }
}
