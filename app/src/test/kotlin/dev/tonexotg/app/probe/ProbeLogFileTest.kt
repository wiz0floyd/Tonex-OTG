package dev.tonexotg.app.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric coverage for [ProbeLogFile] and [ProbeLog.attachFileSink] (issue #69) -- covers
 * acceptance criterion (a): opening the log creates a timestamped file under the existing
 * `probe-logs/` convention, and every [ProbeLog] entry lands on disk, flushed, as it happens
 * (not just when "Save & share log" is tapped).
 */
@RunWith(RobolectricTestRunner::class)
class ProbeLogFileTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun create_writesUnderProbeLogsDirectory_withTimestampedName() {
        val logFile = ProbeLogFile.create(context())
        try {
            assertEquals("probe-logs", logFile.file.parentFile?.name)
            assertTrue(
                "expected tonex-probe-<yyyy-MM-dd_HHmmss>.log, got ${logFile.file.name}",
                logFile.file.name.matches(Regex("""tonex-probe-\d{4}-\d{2}-\d{2}_\d{6}\.log""")),
            )
            assertTrue("file should already exist as soon as it's opened", logFile.file.exists())
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
            assertEquals("first line\nsecond line\n", logFile.file.readText())
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

            val onDisk = logFile.file.readText()

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
