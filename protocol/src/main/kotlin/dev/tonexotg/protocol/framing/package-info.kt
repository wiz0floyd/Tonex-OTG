/**
 * HDLC-style framing for the Tonex USB wire protocol (S4).
 *
 * This package is the layer immediately above [dev.tonexotg.protocol.TonexTransport]: it turns
 * an opaque byte stream (arbitrary-length chunks, no alignment with protocol boundaries) into a
 * sequence of delimited, CRC-verified payloads, and turns payloads back into bytes ready to hand
 * to [dev.tonexotg.protocol.TonexTransport.write]. Nothing in this package interprets the
 * *contents* of a payload — message parsing (varints, command IDs, parameter tables) is a layer
 * above this one (S7).
 *
 * ## Wire format
 *
 * ```
 * 0x7E | stuffed(payload) | stuffed(CRC_lo) | stuffed(CRC_hi) | 0x7E
 * ```
 *
 * - **Delimiter**: `0x7E` marks both the start and the end of a frame. Consecutive frames may
 *   share a single `0x7E` (one frame's closing flag doubling as the next frame's opening flag),
 *   or have two adjacent flags between them — both are valid.
 * - **Byte stuffing**: within the stuffed region (payload followed by the two CRC bytes), any
 *   byte equal to `0x7E` or `0x7D` is replaced by the two-byte sequence `0x7D`, `byte xor 0x20`.
 *   **No other bytes are ever escaped** — there is no 0x00-0x1F control-character escaping, unlike
 *   some other HDLC-family protocols.
 * - **CRC**: CRC-16/X-25 (a.k.a. CRC-16/IBM-SDLC, HDLC FCS-16) computed over the *unstuffed*
 *   payload only (not the delimiters, not the CRC bytes themselves). Appended **little-endian**
 *   (low byte first, then high byte), and — like the payload — the two CRC bytes are themselves
 *   subject to byte stuffing. See [dev.tonexotg.protocol.framing.Crc16X25] for the exact
 *   algorithm and why it is *not* the same as CRC-CCITT-FALSE despite upstream calling it that.
 *
 * ## Entry points
 *
 * - [dev.tonexotg.protocol.framing.HdlcFrame] — encode a payload into a single complete frame,
 *   or decode a single complete frame (both delimiters present, nothing more, nothing less) back
 *   into a payload.
 * - [dev.tonexotg.protocol.framing.FrameReassembler] — the streaming counterpart: feed it
 *   arbitrarily-chunked bytes as they arrive off the wire (a chunk may split a frame anywhere, or
 *   contain several frames back to back) and it emits one [dev.tonexotg.protocol.TonexResult] per
 *   complete frame it finds, in order.
 * - [dev.tonexotg.protocol.framing.decodeFrames] — a `Flow<ByteArray>` -> `Flow<TonexResult<ByteArray>>`
 *   convenience wrapping a [dev.tonexotg.protocol.framing.FrameReassembler] around
 *   [dev.tonexotg.protocol.TonexTransport.incoming]'s output shape.
 */
package dev.tonexotg.protocol.framing
