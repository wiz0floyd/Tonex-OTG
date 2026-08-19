package dev.tonexotg.protocol.connection

import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.PresetSlot
import dev.tonexotg.protocol.PresetSnapshot
import dev.tonexotg.protocol.codec.MessageType
import dev.tonexotg.protocol.codec.TonexVarint
import dev.tonexotg.protocol.message.MasterVolumeMessage
import dev.tonexotg.protocol.message.PresetNameExtractor
import dev.tonexotg.protocol.message.PresetParameterExtractor
import dev.tonexotg.protocol.message.SingleParameterPayloadCodec
import dev.tonexotg.protocol.params.ParameterRegistry
import dev.tonexotg.protocol.state.StateBlobOffsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertEquals

/**
 * Message fixture builders shared by [DefaultTonexController]'s test suite — build the exact
 * inbound frame shapes the reader loop expects to classify, without duplicating this construction
 * logic across every test file.
 */

/**
 * Encodes [type]/[unknown]/[payload] as a real **inbound** (pedal -> host) frame: `B9 03 <type>
 * <size> <unknown> <payload>` — three header fields, not [MessageHeaderCodec.encode]'s four.
 *
 * This exists specifically because [MessageHeaderCodec.encode] produces the *outbound* (host ->
 * pedal) 4-field shape, which is a different, confirmed-distinct wire format from what a real
 * pedal response looks like — see [MessageHeaderCodec]'s class KDoc for the full evidence. Every
 * fixture below that simulates "a message the pedal sent us" must go through this function, not
 * [MessageHeaderCodec.encode], or it silently drifts back into testing the wrong shape — exactly
 * the bug that let every [DefaultTonexController] test pass for months while real hardware
 * (issue #25) hit an actual 1-byte decode mismatch on the very first Hello response.
 *
 * Uses [TonexVarint.encodeInt]'s generic minimal-marker form for each field rather than
 * replicating [MessageHeaderCodec.encode]'s per-field marker conventions (`0x82` for size, etc.):
 * those conventions are specifically about matching real *outbound* literals byte-for-byte, and
 * have no bearing on inbound frames, which nothing here claims to match byte-for-byte — decode()
 * itself is agnostic to which valid marker form was used.
 */
fun encodeInboundFrame(type: MessageType, unknown: Long, payload: ByteArray): ByteArray {
    val out = ArrayList<Byte>(2 + 6 + payload.size)
    out.add(0xB9.toByte())
    out.add(0x03.toByte())
    out.addAll(TonexVarint.encodeInt(type.wireId).asIterable())
    out.addAll(TonexVarint.encodeInt(payload.size.toLong()).asIterable())
    out.addAll(TonexVarint.encodeInt(unknown).asIterable())
    out.addAll(payload.asIterable())
    return out.toByteArray()
}

/** A [MessageType.Hello] response with an opaque, arbitrary payload — decode never inspects it. */
fun helloResponse(): ByteArray {
    val payload = byteArrayOf(0xB9.toByte(), 0x02, 0x02, 0x0B)
    return encodeInboundFrame(MessageType.Hello, unknown = 11L, payload = payload)
}

/**
 * A [MessageType.StateUpdate] frame carrying [blob] as its payload — the shape a real pedal's
 * `GetState`/state-push response has on the wire (see [encodeInboundFrame]). Deliberately does
 * NOT reuse [dev.tonexotg.protocol.message.SetStateMessage.encode] (this app's own *outbound*
 * whole-state write) — that produces the different, 4-field outbound envelope; sharing message
 * type `0x0306` does not mean sharing header shape, and treating those as interchangeable was
 * itself part of this suite's original bug.
 */
fun stateUpdateMessage(blob: ByteArray): ByteArray = encodeInboundFrame(MessageType.StateUpdate, unknown = 11L, payload = blob)

/** A [MessageType.PresetDetailsSummary] response whose payload carries [name] via the marker+field shape. */
fun presetDetailsSummary(name: String): ByteArray {
    val nameField = ByteArray(PresetNameExtractor.NAME_FIELD_LENGTH)
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, nameField.size))
    val payload = PresetNameExtractor.marker() + nameField
    return encodeInboundFrame(MessageType.PresetDetailsSummary, unknown = 11L, payload = payload)
}

/** The parameter block: `BA 03 BA 6D` followed by 109 x (0x88 + LE float32) — see [PresetParameterExtractor]. */
fun presetParameterBlock(values: FloatArray): ByteArray {
    require(values.size == PresetSnapshot.PARAMETER_COUNT)
    var bytes = PresetParameterExtractor.marker()
    for (v in values) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v)
        bytes += byteArrayOf(0x88.toByte()) + buf.array()
    }
    return bytes
}

/**
 * A [MessageType.PresetDetailsSummary] response carrying BOTH the name field and the parameter
 * block — the real response shape (S9b plan §0): name marker + 32-byte field, then the parameter
 * block.
 */
fun presetDetailsSummaryWithParameters(name: String, values: FloatArray): ByteArray {
    val nameField = ByteArray(PresetNameExtractor.NAME_FIELD_LENGTH)
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    nameBytes.copyInto(nameField, 0, 0, minOf(nameBytes.size, nameField.size))
    val payload = PresetNameExtractor.marker() + nameField + presetParameterBlock(values)
    return encodeInboundFrame(MessageType.PresetDetailsSummary, unknown = 11L, payload = payload)
}

/**
 * 109 distinct, in-range values, one per [ParameterId.PRESET_RANGE] index: for each index `i`,
 * `spec.min + (spec.max - spec.min) * (i % 9) / 9f`. Distinct per index (mod 9, not a constant
 * fraction) so a cross-index copy or off-by-one in capture/replay shows up as a mismatch rather
 * than hiding behind coincidentally-equal values — the same reasoning as [plausibleBlob]'s
 * non-zero filler.
 */
fun snapshotValues(): FloatArray = FloatArray(PresetSnapshot.PARAMETER_COUNT) { i ->
    val spec = requireNotNull(ParameterRegistry.byIndex(i)) { "no registry entry for preset index $i" }
    spec.min + (spec.max - spec.min) * (i % 9) / 9f
}

/**
 * 109 distinct, in-range values offset from [snapshotValues] by [offset] (default modulus base
 * shifted, mod 9 as above) so the two arrays never collide for any preset index — used by the
 * issue #45/#46 retention tests to represent "a second, different set of live values read off the
 * pedal," e.g. a preset re-arrived at after external edits or a half-reverted replay.
 */
fun alteredSnapshotValues(offset: Int): FloatArray = FloatArray(PresetSnapshot.PARAMETER_COUNT) { i ->
    val spec = requireNotNull(ParameterRegistry.byIndex(i)) { "no registry entry for preset index $i" }
    spec.min + (spec.max - spec.min) * ((i + offset) % 9) / 9f
}

/**
 * Asserts that [actual] (a recorded [PresetSnapshot], or `null`) holds exactly [expected]'s 109
 * PRESET-scoped values — the issue #45/#46 retention tests' core assertion: that a snapshot was
 * (or was not) overwritten by a later capture.
 */
fun assertSnapshotValuesEqual(expected: FloatArray, actual: PresetSnapshot?, label: String) {
    val snapshot = requireNotNull(actual) { "$label: expected a snapshot to be present, found none" }
    for (i in ParameterId.PRESET_RANGE) {
        assertEquals(expected[i], snapshot.valueOf(ParameterId(i)), 1e-3f, "$label, ParameterId($i)")
    }
}

/**
 * A [MessageType.PresetDetailsSummary] response whose payload is missing the name marker entirely
 * — for exercising the "preset-name harvest failure is fatal" test.
 */
fun presetDetailsSummaryMissingMarker(): ByteArray {
    val payload = ByteArray(40) { 0x00 } // no B9 04 B9 02 BC 21 marker anywhere in here
    return encodeInboundFrame(MessageType.PresetDetailsSummary, unknown = 11L, payload = payload)
}

/** A [MessageType.ParameterChanged] notification reporting master volume's new native-units value. */
fun masterVolumeChanged(native: Float): ByteArray {
    val payload = SingleParameterPayloadCodec.encode(
        kind = SingleParameterPayloadCodec.KIND_MASTER_VOLUME,
        index = 0,
        value = native,
    )
    return encodeInboundFrame(MessageType.ParameterChanged, unknown = 11L, payload = payload)
}

/** A [MessageType.ParameterChanged] notification for a nonzero index — never applied by the reader. */
fun parameterChanged(index: Int, value: Float): ByteArray {
    val payload = SingleParameterPayloadCodec.encode(kind = SingleParameterPayloadCodec.KIND_PARAMETER, index = index, value = value)
    return encodeInboundFrame(MessageType.ParameterChanged, unknown = 11L, payload = payload)
}

/** An uncatalogued wire ID (`0x0999`) — must always be dropped, never treated as an error. */
fun uncatalogued(): ByteArray {
    val payload = byteArrayOf(0x01, 0x02)
    return encodeInboundFrame(MessageType.fromWireId(0x0999L), unknown = 11L, payload = payload)
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

/**
 * Drives [fake] through a full, successful handshake (Hello, GetState, all 20 preset-detail
 * responses, the master-volume harvest, and the S9b snapshot-capture read) up to and including
 * [ConnectionState.Ready] — the shared setup every post-`Ready` test needs. Uses
 * `testScheduler.runCurrent()` (never `advanceUntilIdle()`, which would fast-forward straight past
 * an in-flight timeout) between each response so the reader and `connect()` coroutines are
 * genuinely subscribed and waiting before the next response arrives, matching how a real pedal
 * only ever responds after receiving a request.
 *
 * @param captureValues values for the S9b snapshot-capture response, or `null` to never answer
 *   that read at all (for tests exercising the no-snapshot / capture-timeout path).
 *
 * ⚠️ **Why the master-volume response is now emitted unconditionally.** Before S9b,
 * `harvestMasterVolume()` was `connect()`'s last step, so capability-confirmed tests simply let it
 * time out during `connectDeferred.await()` (which advances virtual time). With the capture read
 * appended *after* it, this fixture would have to advance past that 2s timeout before it could
 * emit the capture response, breaking this function's deliberate `runCurrent()`-only discipline.
 * Answering the harvest is simpler, faster, and closer to what a real pedal does. With
 * `NONE_CONFIRMED` the harvest returns without ever awaiting and this frame is simply dropped by
 * the reader (`tryEmit` with no subscriber) — harmless either way. **This changes one existing
 * behaviour:** `parameterValues` now contains `MASTER_VOLUME` after this function in
 * capability-confirmed tests.
 */
suspend fun TestScope.driveToReady(
    fake: FakeTonexTransport,
    activeSlot: PresetSlot = PresetSlot.A,
    a: Int = 0,
    b: Int = 1,
    c: Int = 2,
    captureValues: FloatArray? = snapshotValues(),
) {
    testScheduler.runCurrent()
    fake.emitMessage(helloResponse())
    testScheduler.runCurrent()
    fake.emitMessage(stateUpdateMessage(plausibleBlob(activeSlot = activeSlot, a = a, b = b, c = c)))
    for (i in PresetIndex.VALID_RANGE) {
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummary("Preset $i"))
    }

    // S9b: harvestMasterVolume() runs before the capture read whenever the capability is
    // confirmed. Answer it so the sequence proceeds on runCurrent() alone.
    testScheduler.runCurrent()
    fake.emitMessage(masterVolumeChanged(MasterVolumeMessage.decibelsToNative(0f)))

    // S9b: the snapshot-capture read.
    if (captureValues != null) {
        testScheduler.runCurrent()
        fake.emitMessage(presetDetailsSummaryWithParameters("Capture", captureValues))
    }
    testScheduler.runCurrent()
}
