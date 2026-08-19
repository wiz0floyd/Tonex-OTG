package dev.tonexotg.app.probe

import dev.tonexotg.protocol.TonexTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * ⚠️ DIAGNOSTIC-ONLY. Wraps a real [TonexTransport] and mirrors every byte written to, and read
 * from, the pedal into [log] as a hex dump, tagged with [tag].
 *
 * Exists because a protocol-layer decode failure (e.g. [dev.tonexotg.protocol.TonexError.UnexpectedBlobShape]
 * or [dev.tonexotg.protocol.TonexError.MalformedFrame]) only surfaces the error's own summary —
 * not the bytes that triggered it — so there is currently no way to tell whether a given
 * decode failure is a real firmware/protocol difference or a transport-level data loss without
 * capturing the raw wire traffic directly. See issue #25's "Unexpected state blob size: expected
 * 43, got 42" finding, which this wrapper exists to get raw bytes for on the next probe run.
 */
class LoggingTonexTransport(
    private val delegate: TonexTransport,
    private val log: ProbeLog,
    private val tag: String,
) : TonexTransport {

    override suspend fun write(bytes: ByteArray): Int {
        log.info("[$tag] WRITE ${bytes.size} byte(s):\n${UsbDeviceOpener.hexDump(bytes)}")
        return delegate.write(bytes)
    }

    override fun incoming(): Flow<ByteArray> = delegate.incoming().onEach { bytes ->
        log.info("[$tag] READ ${bytes.size} byte(s):\n${UsbDeviceOpener.hexDump(bytes)}")
    }

    override fun close() = delegate.close()
}
