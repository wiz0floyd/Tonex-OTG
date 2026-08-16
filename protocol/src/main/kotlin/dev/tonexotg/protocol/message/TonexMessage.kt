/*
 * Ported/adapted from: TonexOneController
 * Upstream repository: https://github.com/Builty/TonexOneController
 * Upstream file:        source/main/usb_tonex_one.c
 * Upstream licence:     Apache-2.0 — Copyright 2025 Greg Smith
 * Full licence text:    LICENSE
 *
 * This file is a Kotlin port/adaptation of logic originally written for the
 * upstream project named above. See /CREDITS.md for full attribution.
 */
package dev.tonexotg.protocol.message

import dev.tonexotg.protocol.codec.DecodedMessage
import dev.tonexotg.protocol.codec.MessageType

/**
 * A [DecodedMessage] classified by [MessageType] into one of the five catalogued shapes, or
 * [Other] for anything uncatalogued. Produced by [TonexMessageDecoder.decode].
 *
 * This is a *classification*, not a full field-level parse: [PresetDetails.payload],
 * [StateUpdate.payload], and [Hello.payload] are handed through unparsed. Extracting further
 * structure from them is either this package's job elsewhere ([PresetNameExtractor] for a preset
 * name, [SingleParameterPayloadCodec] for a parameter-changed value) or a different layer's
 * entirely ([dev.tonexotg.protocol.PedalState]'s full state-blob field offsets, S8) — see each
 * variant's KDoc.
 *
 * Every payload here is carried by reference from the originating [DecodedMessage], so each variant
 * overrides `equals`/`hashCode`/`toString` by hand (matching [DecodedMessage] itself) rather than
 * relying on `data class`'s reference-based `ByteArray` comparison.
 */
sealed class TonexMessage {

    /**
     * A Hello response ([MessageType.Hello]). Upstream does not interpret this message's payload
     * beyond acknowledging receipt (`usb_tonex_one.c:1095-1100`), so [payload] is carried through
     * unparsed — there is nothing further this package can extract from it.
     */
    class Hello(val payload: ByteArray) : TonexMessage() {
        override fun equals(other: Any?): Boolean = other is Hello && payload.contentEquals(other.payload)
        override fun hashCode(): Int = payload.contentHashCode()
        override fun toString(): String = "TonexMessage.Hello(payload=${payload.size} bytes)"
    }

    /**
     * A preset-details response — [MessageType.PresetDetailsSummary] (`full = false`, ~2 KB) or
     * [MessageType.FullPresetDetails] (`full = true`, ~30 KB). Pass [payload] to
     * [PresetNameExtractor.extract] to recover the preset's name; per-parameter field values within
     * a full-detail payload are not parsed by this package.
     */
    class PresetDetails(val full: Boolean, val payload: ByteArray) : TonexMessage() {
        override fun equals(other: Any?): Boolean =
            other is PresetDetails && full == other.full && payload.contentEquals(other.payload)
        override fun hashCode(): Int = 31 * full.hashCode() + payload.contentHashCode()
        override fun toString(): String = "TonexMessage.PresetDetails(full=$full, payload=${payload.size} bytes)"
    }

    /**
     * A state-update response ([MessageType.StateUpdate]). Field-level interpretation of the state
     * blob (current preset, slot assignments, global parameter values, ...) belongs to
     * [dev.tonexotg.protocol.PedalState] (S8), not this package — [payload] is handed through
     * entirely unparsed.
     */
    class StateUpdate(val payload: ByteArray) : TonexMessage() {
        override fun equals(other: Any?): Boolean = other is StateUpdate && payload.contentEquals(other.payload)
        override fun hashCode(): Int = payload.contentHashCode()
        override fun toString(): String = "TonexMessage.StateUpdate(payload=${payload.size} bytes)"
    }

    /**
     * A single-parameter-changed notification ([MessageType.ParameterChanged]). Pass [payload] to
     * [SingleParameterPayloadCodec.decode] to recover the changed index/value; see that codec's
     * KDoc for what is (and is not) confirmed about how its `index` field maps back onto
     * [dev.tonexotg.protocol.ParameterId].
     */
    class ParameterChanged(val payload: ByteArray) : TonexMessage() {
        override fun equals(other: Any?): Boolean = other is ParameterChanged && payload.contentEquals(other.payload)
        override fun hashCode(): Int = payload.contentHashCode()
        override fun toString(): String = "TonexMessage.ParameterChanged(payload=${payload.size} bytes)"
    }

    /**
     * Anything whose [MessageType] is not one of the four catalogued response types above — either
     * an uncatalogued wire ID ([MessageType.Unknown]), or a catalogued-but-not-a-response type this
     * package does not further specialise here (e.g. the outbound `0x0000` envelope type shared by
     * [HelloMessage]/[RequestStateMessage]'s own *requests*, which would only appear here if fed
     * back through this decoder).
     */
    class Other(val type: MessageType, val payload: ByteArray) : TonexMessage() {
        override fun equals(other: Any?): Boolean =
            other is Other && type == other.type && payload.contentEquals(other.payload)
        override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
        override fun toString(): String = "TonexMessage.Other(type=$type, payload=${payload.size} bytes)"
    }
}

/**
 * Classifies a [DecodedMessage] (S5's [dev.tonexotg.protocol.codec.MessageHeaderCodec] output) into
 * a [TonexMessage] by its [MessageType]. Purely a dispatch by an already-decoded field — never
 * fails, so this returns [TonexMessage] directly rather than a [dev.tonexotg.protocol.TonexResult].
 */
object TonexMessageDecoder {

    /** Classifies [message] by [DecodedMessage.header]'s [MessageType]. See [TonexMessage]. */
    fun decode(message: DecodedMessage): TonexMessage = when (val type = message.header.type) {
        MessageType.Hello -> TonexMessage.Hello(message.payload)
        MessageType.PresetDetailsSummary -> TonexMessage.PresetDetails(full = false, payload = message.payload)
        MessageType.FullPresetDetails -> TonexMessage.PresetDetails(full = true, payload = message.payload)
        MessageType.StateUpdate -> TonexMessage.StateUpdate(message.payload)
        MessageType.ParameterChanged -> TonexMessage.ParameterChanged(message.payload)
        is MessageType.Unknown -> TonexMessage.Other(type, message.payload)
    }
}
