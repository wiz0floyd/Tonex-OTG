package dev.tonexotg.app.probe

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single, already-open raw file handle backing [ProbeLog]'s real-time durability (issue #69).
 *
 * Deliberately not just "open a [java.io.FileWriter] each call": [ProbeCrashHandler] needs a
 * handle that is already open and ready to accept a write the instant an uncaught exception is
 * thrown, without depending on anything that could itself be broken by whatever caused the crash
 * (Compose state, coroutines, even opening a fresh [File] handle under memory pressure). One
 * instance owns the underlying [FileOutputStream] for the rest of the process's life; [ProbeLog]
 * and [ProbeCrashHandler] both write through the same instance, and [appendLine] is
 * `synchronized` so a normal [ProbeLog] entry on one thread can't interleave/tear a crash
 * handler's write on another.
 *
 * Never closed by [ProbeActivity] on purpose -- see that class's `onDestroy` KDoc: probe/reader
 * work deliberately keeps running after the Activity is destroyed, and a process-wide crash can
 * happen at any later point too, so the file has to stay writable for the rest of the process's
 * life. [close] exists only for tests that want to release the descriptor between cases.
 */
class ProbeLogFile private constructor(val file: File, private val stream: FileOutputStream) {

    /**
     * Writes [line] plus a trailing newline and flushes immediately -- both the Java-level
     * buffer ([FileOutputStream.flush]) and, best-effort, the OS page cache
     * ([java.io.FileDescriptor.sync]) -- so the bytes are actually durable on disk by the time
     * this call returns, not just handed to a buffer that a crashing process could lose along
     * with everything else. `fsync` failure (e.g. an unusual filesystem that doesn't support it)
     * is swallowed: the plain `flush()` already got the bytes out of this process's own memory,
     * which is the part a JVM crash actually threatens.
     */
    @Synchronized
    fun appendLine(line: String) {
        stream.write((line + "\n").toByteArray(Charsets.UTF_8))
        stream.flush()
        runCatching { stream.fd.sync() }
    }

    /** Test-only cleanup; production code never calls this -- see class KDoc. */
    @Synchronized
    fun close() {
        runCatching { stream.close() }
    }

    companion object {
        /**
         * Creates a fresh timestamped file under `probe-logs/` in [context]'s external files
         * dir, using the exact directory/naming convention [ProbeActivity] has always used
         * (`tonex-probe-<yyyy-MM-dd_HHmmss>.log`) so `probe_log_file_paths.xml`'s
         * `external-files-path` entry keeps matching and "Save & share log" keeps working.
         */
        fun create(context: Context): ProbeLogFile {
            val dir = File(context.getExternalFilesDir(null), "probe-logs").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "tonex-probe-$timestamp.log")
            return ProbeLogFile(file, FileOutputStream(file, true))
        }
    }
}
