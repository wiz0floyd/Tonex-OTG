package dev.tonexotg.protocol

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for the S9 (issue #13) additions to [TonexError]: [TonexError.NoSnapshotAvailable] and
 * [TonexError.ParameterValueOutOfRange]. Both complete a contract [TonexController]'s own KDoc
 * already promised, so these tests just pin the message content down.
 */
class TonexErrorTest {

    @Test
    fun `NoSnapshotAvailable message names the preset index and explains the refusal`() {
        val error = TonexError.NoSnapshotAvailable(PresetIndex(4))

        assertTrue(error.message.contains("4"), "message should name the preset index: ${error.message}")
        assertTrue(
            error.message.contains("No snapshot"),
            "message should say no snapshot was captured: ${error.message}",
        )
    }

    @Test
    fun `ParameterValueOutOfRange message names the parameter, value, and bounds`() {
        val error = TonexError.ParameterValueOutOfRange(id = ParameterId(2), value = 150f, min = -100f, max = 0f)

        assertTrue(error.message.contains("2"), "message should name the parameter index: ${error.message}")
        assertTrue(error.message.contains("150.0"), "message should name the rejected value: ${error.message}")
        assertTrue(error.message.contains("-100.0..0.0"), "message should name the valid range: ${error.message}")
    }

    @Test
    fun `both new cases are data classes with structural equality, matching every existing case`() {
        assertTrue(TonexError.NoSnapshotAvailable(PresetIndex(1)) == TonexError.NoSnapshotAvailable(PresetIndex(1)))
        assertTrue(
            TonexError.ParameterValueOutOfRange(ParameterId(0), 1f, 0f, 2f) ==
                TonexError.ParameterValueOutOfRange(ParameterId(0), 1f, 0f, 2f),
        )
    }

    @Test
    fun `RevertIncomplete message mentions the applied and total counts and names the failed parameter`() {
        val error = TonexError.RevertIncomplete(
            presetIndex = PresetIndex(4),
            appliedCount = 47,
            totalCount = 109,
            failedParameter = ParameterId(47),
            cause = TonexError.TransportFailure(RuntimeException("wedged")),
        )

        assertTrue(error.message.contains("4"), "message should name the preset index: ${error.message}")
        assertTrue(error.message.contains("47"), "message should name the applied count: ${error.message}")
        assertTrue(error.message.contains("109"), "message should name the total count: ${error.message}")
        assertTrue(
            error.message.contains("retained") || error.message.contains("retrying"),
            "message should say the snapshot is retained and retry is safe: ${error.message}",
        )
    }

    @Test
    fun `RevertIncomplete is a data class with structural equality`() {
        val cause = TonexError.TransportFailure(RuntimeException("x"))
        assertTrue(
            TonexError.RevertIncomplete(PresetIndex(0), 1, 109, ParameterId(1), cause) ==
                TonexError.RevertIncomplete(PresetIndex(0), 1, 109, ParameterId(1), cause),
        )
    }
}
