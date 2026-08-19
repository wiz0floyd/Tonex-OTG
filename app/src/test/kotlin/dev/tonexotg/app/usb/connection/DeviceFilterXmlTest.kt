package dev.tonexotg.app.usb.connection

import dev.tonexotg.app.usb.UsbTonexDeviceOpener
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Regression guard for issue #17's explicit warning: "IDs are decimal, not hex -- a very common
 * bug." Parses the actual committed `res/xml/device_filter.xml` (not a copy of its contents) and
 * asserts its `vendor-id`/`product-id` equal [UsbTonexDeviceOpener.VENDOR_ID]/
 * [UsbTonexDeviceOpener.PRODUCT_ID] in *decimal* -- 0x1963 -> 6499, 0x00D1 -> 209.
 *
 * A hex literal here (e.g. `"1963"`) would still parse and install fine -- Android's XML resource
 * compiler has no way to know it's wrong -- so only a value comparison against the real int
 * VID/PID (not just "the file parses") catches the bug this test exists for, per CLAUDE.md's
 * "byte-exact / literal-exact acceptance criteria are gold" guidance.
 */
class DeviceFilterXmlTest {

    @Test
    fun vendorAndProductIdMatchTheRealDecimalIds() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(findDeviceFilterXml())
        val element = doc.getElementsByTagName("usb-device").item(0) as Element

        assertEquals(UsbTonexDeviceOpener.VENDOR_ID.toString(), element.getAttribute("vendor-id"))
        assertEquals(UsbTonexDeviceOpener.PRODUCT_ID.toString(), element.getAttribute("product-id"))

        // Belt-and-suspenders against the literal itself silently drifting from the pedal's real
        // IDs alongside VENDOR_ID/PRODUCT_ID (which would make the comparison above pass for the
        // wrong reason): pin the exact decimal strings issue #17 calls out.
        assertEquals("6499", element.getAttribute("vendor-id"))
        assertEquals("209", element.getAttribute("product-id"))
    }

    /**
     * Gradle's unit-test working directory is the module dir (`app/`) when run via
     * `:app:testDebugUnitTest`, but IDE-driven runs sometimes use the repo root instead -- try
     * both rather than hardcoding one.
     */
    private fun findDeviceFilterXml(): File {
        val candidates = listOf(
            File("src/main/res/xml/device_filter.xml"),
            File("app/src/main/res/xml/device_filter.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "device_filter.xml not found relative to ${File(".").absolutePath} -- tried " +
                    candidates.joinToString { it.path },
            )
    }
}
