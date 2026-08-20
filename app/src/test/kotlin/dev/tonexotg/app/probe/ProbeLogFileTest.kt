package dev.tonexotg.app.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [ProbeLogFile] and [ProbeLog.attachFileSink] on the API 26-28 fallback
 * path (issue #71's documented gap below the scoped-storage cutover at 29 -- see
 * [ProbeLogFile]'s class KDoc). Covers the same acceptance criteria issue #69 established:
 * opening the log creates a timestamped file, and every [ProbeLog] entry lands on disk, flushed,
 * as it happens (not just when "Save & share log" is tapped) -- unchanged behavior on this range.
 *
 * The API 29+ `MediaStore`-backed path is covered separately by [ProbeLogFileScopedStorageTest];
 * this class is pinned to `sdk = [28]` specifically so it keeps exercising the legacy
 * `File`-under-`getExternalFilesDir` behavior regardless of whatever Robolectric's own default
 * SDK happens to be.
 *
 * Deliberately reads the log back via `File(logFile.displayPath)`, never `logFile.shareUri`:
 * `shareUri` on this fallback path calls `FileProvider.getUriForFile`, whose resolved-roots cache
 * is a static field Robolectric does not reset between tests (robolectric/robolectric#8773) --
 * touching it here would make every test after the first one in this JVM fail with "Failed to
 * find configured root", since each test's sandboxed `getExternalFilesDir` root is a fresh temp
 * directory the first test's cached roots no longer match. See [ProbeLogFile.shareUri]'s KDoc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProbeLogFileTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun readBack(logFile: ProbeLogFile): String = File(logFile.displayPath).readText()

    @Test
    fun create_writesUnderProbeLogsDirectory_withTimestampedName() {
        val logFile = ProbeLogFile.create(context())
        try {
            assertTrue(
                "expected the legacy fallback's path to end in probe-logs/tonex-probe-<ts>.log, got ${logFile.displayPath}",
                logFile.displayPath.matches(Regex(""".*probe-logs/tonex-probe-\d{4}-\d{2}-\d{2}_\d{6}\.log""")),
            )
        } finally {
            logFile.close()
        }
    }

    @Test
    fun appendLine_isVisibleOnDiskImmediately_withoutClosingTheStream() {
        val logFile = ProbeLogFile.create(context())
        try {
            logFile.appendLine("first line")
            logFile.appendLine("second line")

            // Deliberately read the file back without closing `logFile` first: appendLine's
            // whole point (issue #69) is that entries are durable/readable immediately, not
            // only once the underlying stream happens to be closed.
            assertEquals("first line\nsecond line\n", readBack(logFile))
        } finally {
            logFile.close()
        }
    }

    @Test
    fun probeLog_attachFileSink_mirrorsEveryEntryToTheFileInRealTime() {
        val logFile = ProbeLogFile.create(context())
        try {
            val log = ProbeLog()
            log.attachFileSink(logFile)

            log.info("info line")
            log.finding("finding line")
            log.warn("warn line")
            log.error("error line")

            val onDisk = readBack(logFile)

            assertTrue(onDisk.contains("[INFO] info line"))
            assertTrue(onDisk.contains("[FINDING] finding line"))
            assertTrue(onDisk.contains("[WARN] warn line"))
            assertTrue(onDisk.contains("[ERROR] error line"))
            // The file already on disk (no explicit save step) agrees line-for-line with what
            // "Save & share log" would separately render from the same entries.
            assertEquals(log.render() + "\n", onDisk)
        } finally {
            logFile.close()
        }
    }

    @Test
    fun probeLog_withoutAnAttachedSink_stillBehavesExactlyAsBefore() {
        // issue #69 must not change ProbeLog's existing public API/behavior for anything
        // unrelated to real-time file durability -- no sink attached at all is the pre-#69 case,
        // and other code in the app may depend on it.
        val log = ProbeLog()

        log.info("hello")

        assertEquals(1, log.entries.value.size)
        assertTrue(log.render().contains("[INFO] hello"))
    }
}
