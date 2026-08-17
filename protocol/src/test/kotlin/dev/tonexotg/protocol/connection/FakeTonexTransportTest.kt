package dev.tonexotg.protocol.connection

import app.cash.turbine.test
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.TonexResult
import dev.tonexotg.protocol.codec.MessageType
import dev.tonexotg.protocol.framing.decodeFrames
import dev.tonexotg.protocol.message.PresetNameExtractor
import dev.tonexotg.protocol.message.SingleParameterPayload
import dev.tonexotg.protocol.message.SingleParameterPayloadCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sanity tests for [FakeTonexTransport] and the fixture builders in `ConnectionTestFixtures.kt` —
 * step 4 of the S9 build order (test-only, no production code). Confirms the fixtures actually
 * decode the way [DefaultTonexController]'s reader loop will expect, before that loop exists.
 */
class FakeTonexTransportTest {

    @Test
    fun `write captures bytes and returns writeBehavior's result`() = runTest {
        val fake = FakeTonexTransport()
        val written = fake.write(byteArrayOf(1, 2, 3))
        assertEquals(3, written)
        assertEquals(1, fake.written.size)
        assertTrue(fake.written[0].contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `writeBehavior can simulate a short write`() = runTest {
        val fake = FakeTonexTransport()
        fake.writeBehavior = { it.size - 1 }
        assertEquals(2, fake.write(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `writeThrows makes write throw instead of returning`() = runTest {
        val fake = FakeTonexTransport()
        fake.writeThrows = java.io.IOException("boom")
        try {
            fake.write(byteArrayOf(1))
            kotlin.test.fail("expected write to throw")
        } catch (e: java.io.IOException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `emitMessage frames and delivers the message, decodable via decodeFrames`() = runTest {
        val fake = FakeTonexTransport()
        fake.emitMessage(helloResponse())
        fake.endStream()

        fake.incoming().decodeFrames().test {
            val item = awaitItem()
            assertIs<TonexResult.Success<ByteArray>>(item)
            awaitComplete()
        }
    }

    @Test
    fun `endStream completes the incoming flow cleanly`() = runTest {
        val fake = FakeTonexTransport()
        fake.endStream()
        fake.incoming().test {
            awaitComplete()
        }
    }

    @Test
    fun `failStream completes the incoming flow with the given cause`() = runTest {
        val fake = FakeTonexTransport()
        val cause = RuntimeException("transport died")
        fake.failStream(cause)
        fake.incoming().test {
            val error = awaitError()
            // Channel.close(cause) does not guarantee the exact same exception instance survives
            // the round trip through receiveAsFlow() - assert on type and message instead of `===`.
            assertIs<RuntimeException>(error)
            assertEquals("transport died", error.message)
        }
    }

    @Test
    fun `close is idempotent and observable via closed`() {
        val fake = FakeTonexTransport()
        assertTrue(!fake.closed)
        fake.close()
        assertTrue(fake.closed)
        fake.close() // must not throw
        assertTrue(fake.closed)
    }

    @Test
    fun `writtenMessages decodes captured writes back to DecodedMessage`() = runTest {
        val fake = FakeTonexTransport()
        fake.write(dev.tonexotg.protocol.framing.HdlcFrame.encode(helloResponse()))

        val decoded = fake.writtenMessages()

        assertEquals(1, decoded.size)
        assertEquals(MessageType.Hello, decoded[0].header.type)
    }

    // ---- fixture builders decode correctly -----------------------------------------------------

    @Test
    fun `helloResponse decodes as MessageType Hello`() {
        val decoded = decodeOrFail(helloResponse())
        assertEquals(MessageType.Hello, decoded.header.type)
    }

    @Test
    fun `stateUpdateMessage decodes as MessageType StateUpdate carrying the blob verbatim`() {
        val blob = plausibleBlob()
        val decoded = decodeOrFail(stateUpdateMessage(blob))
        assertEquals(MessageType.StateUpdate, decoded.header.type)
        assertTrue(decoded.payload.contentEquals(blob))
    }

    @Test
    fun `presetDetailsSummary decodes and the name extracts back out via PresetNameExtractor`() {
        val decoded = decodeOrFail(presetDetailsSummary("My Preset"))
        assertEquals(MessageType.PresetDetailsSummary, decoded.header.type)
        val name = PresetNameExtractor.extract(decoded.payload)
        assertIs<TonexResult.Success<String>>(name)
        assertEquals("My Preset", name.value)
    }

    @Test
    fun `presetDetailsSummaryMissingMarker fails PresetNameExtractor as intended`() {
        val decoded = decodeOrFail(presetDetailsSummaryMissingMarker())
        val name = PresetNameExtractor.extract(decoded.payload)
        assertIs<TonexResult.Failure>(name)
    }

    @Test
    fun `masterVolumeChanged decodes as ParameterChanged with index 0 and the given native value`() {
        val decoded = decodeOrFail(masterVolumeChanged(7.5f))
        assertEquals(MessageType.ParameterChanged, decoded.header.type)
        val payload = SingleParameterPayloadCodec.decode(decoded.payload)
        assertIs<TonexResult.Success<SingleParameterPayload>>(payload)
        assertEquals(0, payload.value.index)
        assertEquals(SingleParameterPayloadCodec.KIND_MASTER_VOLUME, payload.value.kind)
        assertEquals(7.5f, payload.value.value)
    }

    @Test
    fun `uncatalogued decodes as an unrecognised wire ID, not a failure`() {
        val decoded = decodeOrFail(uncatalogued())
        assertIs<MessageType.Unknown>(decoded.header.type)
        assertEquals(0x0999L, (decoded.header.type as MessageType.Unknown).wireId)
    }

    @Test
    fun `plausibleBlob writes the given slot assignments and active slot at the documented offsets`() {
        val blob = plausibleBlob(size = 250, activeSlot = PresetSlot.C, a = 4, b = 8, c = 12)
        assertEquals(4, blob[250 - 18].toInt() and 0xFF)
        assertEquals(8, blob[250 - 16].toInt() and 0xFF)
        assertEquals(12, blob[250 - 14].toInt() and 0xFF)
        assertEquals(PresetSlot.C.ordinal, blob[250 - 11].toInt() and 0xFF)
    }

    private fun decodeOrFail(message: ByteArray) =
        when (val r = dev.tonexotg.protocol.codec.MessageHeaderCodec.decode(message)) {
            is TonexResult.Success -> r.value
            is TonexResult.Failure -> kotlin.test.fail("expected a decodable message, got ${r.error.message}")
        }
}
