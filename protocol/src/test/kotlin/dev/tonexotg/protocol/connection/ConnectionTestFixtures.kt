package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.codec.MessageHeader
import dev.tonexotg.protocol.codec.MessageHeaderCodec
import dev.tonexotg.protocol.codec.MessageType
import dev.tonexotg.protocol.message.PresetNameExtractor
import dev.tonexotg.protocol.message.SetStateMessage
import dev.tonexotg.protocol.message.SingleParameterPayloadCodec
import dev.tonexotg.protocol.state.StateBlobOffsets

/**
 * Message fixture builders shared by [DefaultTonexController]'s test suite — build the exact
 * inbound frame shapes the reader loop expects to classify, without duplicating this construction
 * logic across every test file.
 */

/** A [MessageType.Hello] response with an opaque, arbitrary payload — decode never inspects it. */
fun helloResponse(): ByteArray {
    val payload = byteArrayOf(0xB9.toByte(), 0x02, 0x02, 0x0B)
    val header = MessageHeader(type = MessageType.Hello, declaredSize = payload.size.toLong(), unknownA = 11L, unknownB = 3L)
    return MessageHeaderCodec.encode(header, payload)
}

/**
 * A [MessageType.StateUpdate] frame carrying [blob] — the same wire shape
 * [SetStateMessage.encode] produces, since the pedal's own inbound push and this app's outbound
 * whole-state write share the identical `0x0306` envelope (see [SetStateMessage]'s KDoc).
 */
fun stateUpdateMessage(blob: ByteArray): ByteArray = SetStateMessage.encode(blob)

/** A [MessageType.PresetDetailsSummary] response whose payload carries [name] via the marker+field shape. */
fun presetDetailsSummary(name: String): ByteArray {
    val nameField = ByteArray(PresetNameExtractor.NAME_FIELD_LENGTH)
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, nameField.size))
    val payload = PresetNameExtractor.marker() + nameField
    val header = MessageHeader(
        type = MessageType.PresetDetailsSummary,
        declaredSize = payload.size.toLong(),
        unknownA = 11L,
        unknownB = 3L,
    )
    return MessageHeaderCodec.encode(header, payload)
}

/**
 * A [MessageType.PresetDetailsSummary] response whose payload is missing the name marker entirely
 * — for exercising the "preset-name harvest failure is fatal" test.
 */
fun presetDetailsSummaryMissingMarker(): ByteArray {
    val payload = ByteArray(40) { 0x00 } // no B9 04 B9 02 BC 21 marker anywhere in here
    val header = MessageHeader(
        type = MessageType.PresetDetailsSummary,
        declaredSize = payload.size.toLong(),
        unknownA = 11L,
        unknownB = 3L,
    )
    return MessageHeaderCodec.encode(header, payload)
}

/** A [MessageType.ParameterChanged] notification reporting master volume's new native-units value. */
fun masterVolumeChanged(native: Float): ByteArray {
    val payload = SingleParameterPayloadCodec.encode(
        kind = SingleParameterPayloadCodec.KIND_MASTER_VOLUME,
        index = 0,
        value = native,
    )
    val header = MessageHeader(type = MessageType.ParameterChanged, declaredSize = payload.size.toLong(), unknownA = 11L, unknownB = 3L)
    return MessageHeaderCodec.encode(header, payload)
}

/** A [MessageType.ParameterChanged] notification for a nonzero index — never applied by the reader. */
fun parameterChanged(index: Int, value: Float): ByteArray {
    val payload = SingleParameterPayloadCodec.encode(kind = SingleParameterPayloadCodec.KIND_PARAMETER, index = index, value = value)
    val header = MessageHeader(type = MessageType.ParameterChanged, declaredSize = payload.size.toLong(), unknownA = 11L, unknownB = 3L)
    return MessageHeaderCodec.encode(header, payload)
}

/** An uncatalogued wire ID (`0x0999`) — must always be dropped, never treated as an error. */
fun uncatalogued(): ByteArray {
    val payload = byteArrayOf(0x01, 0x02)
    val header = MessageHeader(type = MessageType.fromWireId(0x0999L), declaredSize = payload.size.toLong(), unknownA = 11L, unknownB = 3L)
    return MessageHeaderCodec.encode(header, payload)
}

/**
 * A blob that satisfies [StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE] and looks like a plausible
 * slot region — load-bearing fixture for every [DefaultTonexController] test that needs a
 * realistic state blob. Every byte outside the four checked offsets is distinctive, non-zero
 * filler `((i * 7 + 13) % 256)` so an accidental shift/truncation/cross-index copy shows up as a
 * mismatch rather than being hidden by coincidentally-equal filler.
 */
fun plausibleBlob(
    size: Int = 200,
    activeSlot: PresetSlot = PresetSlot.A,
    a: Int = 0,
    b: Int = 1,
    c: Int = 2,
): ByteArray {
    require(size >= StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE) {
        "plausibleBlob: size $size is below StateBlobOffsets.MIN_PLAUSIBLE_BLOB_SIZE"
    }
    val bytes = ByteArray(size) { i -> ((i * 7 + 13) % 256).toByte() }
    bytes[size - StateBlobOffsets.END_SLOT_A_PRESET] = a.toByte()
    bytes[size - StateBlobOffsets.END_SLOT_B_PRESET] = b.toByte()
    bytes[size - StateBlobOffsets.END_SLOT_C_PRESET] = c.toByte()
    bytes[size - StateBlobOffsets.END_CURRENT_SLOT] = activeSlot.ordinal.toByte()
    return bytes
}
