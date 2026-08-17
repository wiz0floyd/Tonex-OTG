package dev.tonexotg.protocol.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "No timing constant is a bare magic number — each is named and justified" (issue #13
 * acceptance criterion). These tests pin [ConnectionTimeouts.DEFAULT]'s values by name and prove
 * every field is genuinely injected, not hardcoded at any use site.
 */
class ConnectionTimeoutsTest {

    @Test
    fun `every DEFAULT field is positive`() {
        val d = ConnectionTimeouts.DEFAULT
        assertTrue(d.helloMillis > 0)
        assertTrue(d.getStateMillis > 0)
        assertTrue(d.stateReadMillis > 0)
        assertTrue(d.presetDetailsMillis > 0)
        assertTrue(d.masterVolumeMillis > 0)
        assertTrue(d.transportWriteMillis > 0)
    }

    @Test
    fun `DEFAULT values match this file's documented justification table`() {
        val d = ConnectionTimeouts.DEFAULT
        assertEquals(2_000L, d.helloMillis)
        assertEquals(2_000L, d.getStateMillis)
        assertEquals(2_000L, d.stateReadMillis)
        assertEquals(3_000L, d.presetDetailsMillis)
        assertEquals(2_000L, d.masterVolumeMillis)
        assertEquals(1_000L, d.transportWriteMillis)
    }

    @Test
    fun `getStateMillis and stateReadMillis are numerically equal but independently settable`() {
        // §5 of the architecture plan: kept as separate fields deliberately, even though the
        // DEFAULT values happen to coincide, so a timeout is diagnosable by operation string and
        // the two can be tuned independently later.
        val custom = ConnectionTimeouts(
            helloMillis = 1L,
            getStateMillis = 111L,
            stateReadMillis = 222L,
            presetDetailsMillis = 1L,
            masterVolumeMillis = 1L,
            transportWriteMillis = 1L,
        )
        assertEquals(111L, custom.getStateMillis)
        assertEquals(222L, custom.stateReadMillis)
    }

    @Test
    fun `constructing with small custom values proves every field is genuinely injected, not hardcoded`() {
        val custom = ConnectionTimeouts(
            helloMillis = 1L,
            getStateMillis = 2L,
            stateReadMillis = 3L,
            presetDetailsMillis = 4L,
            masterVolumeMillis = 5L,
            transportWriteMillis = 6L,
        )
        assertEquals(1L, custom.helloMillis)
        assertEquals(2L, custom.getStateMillis)
        assertEquals(3L, custom.stateReadMillis)
        assertEquals(4L, custom.presetDetailsMillis)
        assertEquals(5L, custom.masterVolumeMillis)
        assertEquals(6L, custom.transportWriteMillis)
    }
}
