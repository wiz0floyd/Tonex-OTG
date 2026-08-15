/**
 * The concrete Tonex One message set: encoders for outbound requests/writes, and decoders for
 * inbound responses/notifications (S7).
 *
 * This package sits directly on top of [dev.tonexotg.protocol.codec] (the [dev.tonexotg.protocol.codec.MessageHeaderCodec]
 * four-varint envelope and [dev.tonexotg.protocol.codec.TonexVarint] primitive encoding) and
 * [dev.tonexotg.protocol.framing] (HDLC byte-stuffing, below the header layer — out of scope
 * here). Every encoder in this package returns the **unframed** message bytes (header + payload,
 * matching the reference literals in upstream `usb_tonex_one.c` exactly, before
 * [dev.tonexotg.protocol.framing.HdlcFrame] adds delimiters/stuffing/CRC) — framing a message for
 * the wire, or unframing bytes read off it, is the caller's job, one layer down.
 *
 * ## What's covered
 *
 * - [HelloMessage], [RequestStateMessage] — byte-exact against the two literals recovered from
 *   upstream that carry no per-call parameters.
 * - [RequestPresetDetailsMessage] — request a preset's summary (~2 KB) or full (~30 KB) details.
 * - [ParameterWriteMessage] — the *safe* single-parameter write: touches exactly one preset-scoped
 *   value, cannot clobber global state. Global parameters other than master volume are written by
 *   patching the full state blob instead (owned by [dev.tonexotg.protocol.PedalState], not this
 *   package) — see [ParameterWriteMessage] KDoc for why this package deliberately rejects them.
 * - [MasterVolumeMessage] — master volume's own single-parameter-shaped write command, plus the
 *   engineering-units (dB) <-> native-units (0-10) conversion. **The conversion curve is assumed
 *   linear and is unverified against real hardware** — see that file's KDoc; this is an open S20
 *   question, not a confirmed fact.
 * - [SingleParameterPayloadCodec] — the shared wire shape (`B9 04 <kind> <index> 88 <f32>`) behind
 *   both [ParameterWriteMessage]/[MasterVolumeMessage]'s writes and inbound
 *   [dev.tonexotg.protocol.codec.MessageType.ParameterChanged] notifications.
 * - [PresetNameExtractor] — locates and extracts a preset's 32-byte fixed-length name field from a
 *   preset-details response. Read-only: the ToneX One has no name-*write* command, so this package
 *   deliberately does not offer one.
 * - [TonexMessage] / [TonexMessageDecoder] — classifies a decoded header+payload by
 *   [dev.tonexotg.protocol.codec.MessageType] into a typed shape. Deep field-level interpretation
 *   of a state-update payload is [dev.tonexotg.protocol.PedalState]'s job (S8), not this package's
 *   — [TonexMessageDecoder] hands that payload through unparsed.
 *
 * ## Error handling
 *
 * Every decoder here returns [dev.tonexotg.protocol.TonexResult] and never throws on malformed
 * input — truncated or marker-less payloads are an expected condition on a live USB link (a
 * firmware quirk, a partial capture, a deliberately adversarial test), not a programming error.
 */
package dev.tonexotg.protocol.message
