package dev.tonexotg.app.usb.connection

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import dev.tonexotg.app.R
import dev.tonexotg.app.usb.UsbTonexDeviceOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Robolectric coverage for [UsbConnectionService] (S13, issue #18) -- lifecycle, notification
 * content reflecting live [UsbConnectionManager.state], and the wakelock acquire/release path.
 * Deliberately does NOT attempt to test the `SecurityException`/prerequisite-degrade branch
 * ([UsbConnectionService.degradeAfterFailedStartForeground]) or the AOSP-verified-but-unobservable
 * `connectedDevice` prerequisite behavior itself -- see that class's KDoc, point 6: this is the
 * one behavior that genuinely cannot be verified without a real API 34+ device, so there is
 * nothing here to fake that would prove anything real.
 *
 * Uses [UsbConnectionService.connectionManager]'s testability seam (see that class's KDoc) to
 * inject a directly-constructed [UsbConnectionManager] -- the exact same seam
 * [UsbConnectionManagerRobolectricTest] uses via its own private constructor -- rather than the
 * process-wide [UsbConnectionManager.getInstance] singleton, so each test gets an isolated
 * instance unaffected by any other test's state.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbConnectionServiceRobolectricTest {

    private fun newManager(context: Context): UsbConnectionManager =
        UsbConnectionManager(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun onStartCommand_startsForegroundWithConnectedDeviceType_andDisconnectedNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = newManager(context)

        val controller = Robolectric.buildService(UsbConnectionService::class.java)
        val service = controller.create().get()
        service.connectionManager = manager

        controller.startCommand(0, 0)

        val shadowService = shadowOf(service)
        assertFalse("service should be in the foreground, not stopped", shadowService.isForegroundStopped)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ReflectionHelpers.callInstanceMethod<Int>(shadowService, "getForegroundServiceType"),
        )

        val notification = shadowService.lastForegroundNotification
        val shadowNotification = shadowOf(notification)
        assertEquals(context.getString(R.string.usb_connection_notification_disconnected), shadowNotification.contentText)

        manager.stop()
        controller.destroy()
    }

    @Test
    fun onDestroy_doesNotUnregisterTheSharedManagersReceiver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = newManager(context)

        val controller = Robolectric.buildService(UsbConnectionService::class.java)
        val service = controller.create().get()
        service.connectionManager = manager
        controller.startCommand(0, 0)

        val application = ApplicationProvider.getApplicationContext<Application>()
        val receiverCountBeforeDestroy = shadowOf(application).registeredReceivers.size

        controller.destroy()

        val receiverCountAfterDestroy = shadowOf(application).registeredReceivers.size
        assertEquals(
            "UsbConnectionService.onDestroy must never unregister UsbConnectionManager's shared receiver",
            receiverCountBeforeDestroy,
            receiverCountAfterDestroy,
        )

        manager.stop()
    }

    @Test
    fun attachedDevice_updatesNotificationToConnected_andAcquiresTheWakeLock(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = newTonexUsbDevice()
        shadowOf(usbManager).addOrUpdateUsbDevice(device, /* hasPermission = */ false)
        grantUsbPermission(usbManager, device)

        val manager = newManager(context)
        val startIntent = Intent(context, UsbConnectionService::class.java).putExtra(UsbManager.EXTRA_DEVICE, device)
        val controller = Robolectric.buildService(UsbConnectionService::class.java, startIntent)
        val service = controller.create().get()
        service.connectionManager = manager

        try {
            controller.startCommand(0, 0)

            withTimeout(10_000) {
                manager.state.first { it is UsbConnectionState.Connected }
            }

            // The service's own notification-updating collector observes the same StateFlow
            // independently of this test's own await above; poll briefly for it to catch up
            // rather than assuming strict ordering between two independent collectors of one
            // StateFlow.
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val connectedText = context.getString(R.string.usb_connection_notification_connected)
            var sawConnectedNotification = false
            for (attempt in 1..50) {
                val notification = shadowOf(notificationManager).getNotification(NOTIFICATION_ID)
                if (notification != null && shadowOf(notification).contentText == connectedText) {
                    sawConnectedNotification = true
                    break
                }
                kotlinx.coroutines.delay(100)
            }
            assertTrue("expected the posted notification to eventually read '$connectedText'", sawConnectedNotification)

            // The notification-update and wakelock-acquire calls happen back-to-back within the
            // same collector invocation, but on a different dispatcher than this test's polling
            // loop above -- poll here too rather than assuming the wakelock already exists the
            // instant the notification content changes.
            var wakeLock: android.os.PowerManager.WakeLock? = null
            for (attempt in 1..50) {
                wakeLock = ShadowPowerManager.getLatestWakeLock()
                if (wakeLock != null && wakeLock.isHeld) break
                kotlinx.coroutines.delay(100)
            }
            assertTrue("expected the PARTIAL_WAKE_LOCK to be held while Connected", wakeLock != null && wakeLock.isHeld)
            val heldWakeLock = requireNotNull(wakeLock)

            manager.onDeviceDetached(device)
            withTimeout(10_000) {
                manager.state.first { it is UsbConnectionState.Disconnected }
            }
            var wakeLockReleased = false
            for (attempt in 1..50) {
                if (!heldWakeLock.isHeld) {
                    wakeLockReleased = true
                    break
                }
                kotlinx.coroutines.delay(100)
            }
            assertTrue("expected the wakelock to be released after detach", wakeLockReleased)
        } finally {
            manager.stop()
            controller.destroy()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Reflection-based UsbDevice construction -- see UsbConnectionManagerRobolectricTest's own
    // class KDoc for why this is necessary at all (the compileSdk stub jar hides these
    // constructors behind package-private no-args, confirmed against Robolectric's real
    // android-all-instrumented jar).
    // -----------------------------------------------------------------------------------------

    private fun grantUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        val shadow = shadowOf(usbManager)
        ReflectionHelpers.callInstanceMethod<Any?>(shadow, "grantPermission", ClassParameter.from(UsbDevice::class.java, device))
    }

    private fun newTonexUsbDevice(nameSuffix: String = "001"): UsbDevice {
        val dataInterface = newUsbInterface(id = 1, name = "data", ifaceClass = UsbConstants.USB_CLASS_CDC_DATA)
        val commInterface = newUsbInterface(id = 0, name = "comm", ifaceClass = UsbConstants.USB_CLASS_COMM)
        setEndpoints(dataInterface, newUsbEndpoint(0x87), newUsbEndpoint(0x07))
        setEndpoints(commInterface, newUsbEndpoint(0x83, UsbConstants.USB_ENDPOINT_XFER_INT))

        val configuration = newUsbConfiguration()
        setInterfaces(configuration, commInterface, dataInterface)

        val ctor = UsbDevice::class.java.getDeclaredConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            Array<UsbConfiguration>::class.java,
            Class.forName("android.hardware.usb.IUsbSerialReader"),
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        return ctor.newInstance(
            "/dev/bus/usb/001/$nameSuffix",
            UsbTonexDeviceOpener.VENDOR_ID,
            UsbTonexDeviceOpener.PRODUCT_ID,
            0,
            0,
            0,
            "IK Multimedia",
            "ToneX One",
            "1.0",
            arrayOf(configuration),
            fakeSerialReader(),
            false,
            false,
            false,
            false,
            false,
        ) as UsbDevice
    }

    private fun fakeSerialReader(): Any {
        val serialReaderClass = Class.forName("android.hardware.usb.IUsbSerialReader")
        return java.lang.reflect.Proxy.newProxyInstance(serialReaderClass.classLoader, arrayOf(serialReaderClass)) { _, _, _ -> null }
    }

    private fun newUsbInterface(id: Int, name: String, ifaceClass: Int): UsbInterface =
        UsbInterface::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(id, 0, name, ifaceClass, 0, 0)

    private fun newUsbEndpoint(address: Int, attributes: Int = UsbConstants.USB_ENDPOINT_XFER_BULK): UsbEndpoint =
        UsbEndpoint::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(address, attributes, 64, 0)

    private fun newUsbConfiguration(): UsbConfiguration =
        UsbConfiguration::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(0, "config0", 0, 0xC0)

    private fun setEndpoints(usbInterface: UsbInterface, vararg endpoints: UsbEndpoint) {
        val field = UsbInterface::class.java.getDeclaredField("mEndpoints")
        field.isAccessible = true
        field.set(usbInterface, toParcelableArray(endpoints.toList()))
    }

    private fun setInterfaces(configuration: UsbConfiguration, vararg interfaces: UsbInterface) {
        val field = UsbConfiguration::class.java.getDeclaredField("mInterfaces")
        field.isAccessible = true
        field.set(configuration, toParcelableArray(interfaces.toList()))
    }

    private fun toParcelableArray(items: List<Parcelable>): Array<Parcelable?> {
        val array = arrayOfNulls<Parcelable>(items.size)
        items.forEachIndexed { index, item -> array[index] = item }
        return array
    }

    companion object {
        // Mirrors UsbConnectionService's own private NOTIFICATION_ID -- duplicated here rather
        // than exposed from that class purely for this test, per this project's existing
        // Robolectric test convention of self-contained per-file fixtures.
        private const val NOTIFICATION_ID = 1
    }
}
