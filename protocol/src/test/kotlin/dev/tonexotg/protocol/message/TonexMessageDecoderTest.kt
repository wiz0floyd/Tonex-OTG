package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.codec.DecodedMessage
import dev.tonexotg.protocol.codec.MessageHeader
import dev.tonexotg.protocol.codec.MessageType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TonexMessageDecoderTest {

    // unknownB = null matches what MessageHeaderCodec.decode() always produces for a real inbound
    // header (only 3 wire fields) -- see MessageHeaderCodec's class KDoc.
    private fun decoded(type: MessageType, payload: ByteArray): DecodedMessage =
        DecodedMessage(MessageHeader(type, payload.size.toLong(), unknownA = 11, unknownB = null), payload)

    // ---- all five catalogued message types ----------------------------------------------------------

    @Test
    fun `Hello wire ID 0x02 decodes to TonexMessage Hello carrying the payload`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val message = TonexMessageDecoder.decode(decoded(MessageType.Hello, payload))
        val hello = assertIs<TonexMessage.Hello>(message)
        assertTrue(hello.payload.contentEquals(payload))
    }

    @Test
    fun `FullPresetDetails wire ID 0x0303 decodes to PresetDetails with full=true`() {
        val payload = ByteArray(16) { it.toByte() }
        val message = TonexMessageDecoder.decode(decoded(MessageType.FullPresetDetails, payload))
        val details = assertIs<TonexMessage.PresetDetails>(message)
        assertTrue(details.full)
        assertTrue(details.payload.contentEquals(payload))
    }

    @Test
    fun `PresetDetailsSummary wire ID 0x0304 decodes to PresetDetails with full=false`() {
        val payload = ByteArray(8) { it.toByte() }
        val message = TonexMessageDecoder.decode(decoded(MessageType.PresetDetailsSummary, payload))
        val details = assertIs<TonexMessage.PresetDetails>(message)
        assertTrue(!details.full)
        assertTrue(details.payload.contentEquals(payload))
    }

    @Test
    fun `StateUpdate wire ID 0x0306 decodes to TonexMessage StateUpdate carrying the payload`() {
        val payload = ByteArray(12) { (it * 3).toByte() }
        val message = TonexMessageDecoder.decode(decoded(MessageType.StateUpdate, payload))
        val stateUpdate = assertIs<TonexMessage.StateUpdate>(message)
        assertTrue(stateUpdate.payload.contentEquals(payload))
    }

    @Test
    fun `ParameterChanged wire ID 0x0309 decodes to TonexMessage ParameterChanged carrying the payload`() {
        val payload = SingleParameterPayloadCodec.encode(kind = 0x02, index = 5, value = 1.0f)
        val message = TonexMessageDecoder.decode(decoded(MessageType.ParameterChanged, payload))
        val parameterChanged = assertIs<TonexMessage.ParameterChanged>(message)
        assertTrue(parameterChanged.payload.contentEquals(payload))
    }

    // ---- uncatalogued type -----------------------------------------------------------------------------

    @Test
    fun `an uncatalogued Unknown wire ID decodes to TonexMessage Other, carrying the type and payload`() {
        val payload = byteArrayOf(0xAA.toByte())
        val message = TonexMessageDecoder.decode(decoded(MessageType.Unknown(0x1234), payload))
        val other = assertIs<TonexMessage.Other>(message)
        assertEquals(MessageType.Unknown(0x1234), other.type)
        assertTrue(other.payload.contentEquals(payload))
    }

    @Test
    fun `the outbound-request envelope type 0x0000 decodes to Other, not one of the four response types`() {
        val payload = byteArrayOf(0xB9.toByte(), 0x02, 0x02, 0x0B)
        val message = TonexMessageDecoder.decode(decoded(MessageType.fromWireId(0x0000L), payload))
        assertIs<TonexMessage.Other>(message)
    }

    // ---- hand-written equals/hashCode/toString: content, not reference, for every variant ------------

    @Test
    fun `Hello equals compares payload content, not reference`() {
        val a = TonexMessage.Hello(byteArrayOf(1, 2, 3))
        val b = TonexMessage.Hello(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Hello with different payload content is not equal`() {
        val a = TonexMessage.Hello(byteArrayOf(1, 2, 3))
        val b = TonexMessage.Hello(byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `PresetDetails equals compares both full and payload content`() {
        val a = TonexMessage.PresetDetails(full = true, payload = byteArrayOf(9, 9))
        val b = TonexMessage.PresetDetails(full = true, payload = byteArrayOf(9, 9))
        val differentFull = TonexMessage.PresetDetails(full = false, payload = byteArrayOf(9, 9))
        assertEquals(a, b)
        assertNotEquals(a, differentFull)
    }

    @Test
    fun `StateUpdate equals compares payload content, not reference`() {
        val a = TonexMessage.StateUpdate(byteArrayOf(5, 6))
        val b = TonexMessage.StateUpdate(byteArrayOf(5, 6))
        assertEquals(a, b)
    }

    @Test
    fun `ParameterChanged equals compares payload content, not reference`() {
        val a = TonexMessage.ParameterChanged(byteArrayOf(7))
        val b = TonexMessage.ParameterChanged(byteArrayOf(7))
        assertEquals(a, b)
    }

    @Test
    fun `Other equals compares both type and payload content`() {
        val a = TonexMessage.Other(MessageType.Unknown(1), byteArrayOf(1))
        val b = TonexMessage.Other(MessageType.Unknown(1), byteArrayOf(1))
        val differentType = TonexMessage.Other(MessageType.Unknown(2), byteArrayOf(1))
        assertEquals(a, b)
        assertNotEquals(a, differentType)
    }

    @Test
    fun `toString reports the payload size for a payload-carrying variant`() {
        val hello = TonexMessage.Hello(ByteArray(4))
        assertTrue(hello.toString().contains("4 bytes"))
    }
}
