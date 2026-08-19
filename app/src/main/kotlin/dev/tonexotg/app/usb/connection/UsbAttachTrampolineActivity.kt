package dev.tonexotg.app.usb.connection

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * No-display trampoline the platform launches directly when `res/xml/device_filter.xml` matches a
 * freshly-attached USB device (S12, issue #17's "auto-launch on attach" path -- the strictly
 * better-UX path when the user has ticked "use by default for this USB device": subsequent
 * attachments auto-launch and auto-grant with no dialog at all).
 *
 * `USB_DEVICE_ATTACHED` can only target an `<activity>`, not a `<service>` (issue #17) -- there is
 * no S13 foreground service yet for this to hand off to (that's the very next story after this
 * one), so this hands the freshly-attached [UsbDevice] straight to [UsbConnectionManager], the
 * shared connection-lifecycle owner this Activity and any future S13 service are both meant to
 * drive, then finishes immediately. `android:theme="@android:style/Theme.NoDisplay"` in the
 * manifest means this never actually paints a frame -- the user should never see it, only whatever
 * screen (or none) was already in front.
 *
 * Deliberately a plain [Activity], not `ComponentActivity`/Compose: there is nothing to display,
 * so none of that machinery is needed.
 */
class UsbAttachTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device != null) {
            UsbConnectionManager.getInstance(applicationContext).onDeviceAttached(device)
        }
        finish()
    }
}
