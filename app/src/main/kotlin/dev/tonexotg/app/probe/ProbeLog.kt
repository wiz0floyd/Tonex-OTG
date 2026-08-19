package dev.tonexotg.app.probe

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Severity tag for one [ProbeLog] line — cosmetic only (color in the UI), not load-bearing. */
enum class ProbeLogLevel { INFO, FINDING, WARN, ERROR }

data class ProbeLogEntry(
    val timestampMillis: Long,
    val level: ProbeLogLevel,
    val message: String,
)

/**
 * The probe session's append-only, timestamped log — the whole point of this harness per issue
 * #25: the product owner has no way to pull logcat remotely, so everything worth knowing has to
 * end up here, on-screen and copyable.
 */
class ProbeLog {

    private val _entries = MutableStateFlow<List<ProbeLogEntry>>(emptyList())
    val entries: StateFlow<List<ProbeLogEntry>> = _entries

    /**
     * Real-time durability sink (issue #69) -- `@Volatile` since [attachFileSink] runs on the
     * main thread from [ProbeActivity.onCreate] while [append] can run from any dispatcher a
     * probe operation happens to use.
     */
    @Volatile
    private var fileSink: ProbeLogFile? = null

    /**
     * Attaches [file] as this log's real-time durability sink: from this call on, every entry
     * is formatted the same way [render] would format it and written+flushed to [file]
     * immediately, not just when "Save & share log" is tapped. Intended to be called once, from
     * [ProbeActivity.onCreate], before any probe action can run -- see issue #69.
     */
    fun attachFileSink(file: ProbeLogFile) {
        fileSink = file
    }

    fun info(message: String) = append(ProbeLogLevel.INFO, message)
    fun finding(message: String) = append(ProbeLogLevel.FINDING, message)
    fun warn(message: String) = append(ProbeLogLevel.WARN, message)
    fun error(message: String) = append(ProbeLogLevel.ERROR, message)

    private fun append(level: ProbeLogLevel, message: String) {
        val entry = ProbeLogEntry(System.currentTimeMillis(), level, message)
        _entries.update { it + entry }
        // Mirrored to logcat (issue #25, opus-reviewer-probe25 B1) so a record of what happened —
        // in particular a pre-write parameter value — survives even if the Activity dies (rotation
        // race, back press, system reclaim) before this in-memory log can be copied off-screen.
        when (level) {
            ProbeLogLevel.INFO, ProbeLogLevel.FINDING -> Log.i(LOGCAT_TAG, message)
            ProbeLogLevel.WARN -> Log.w(LOGCAT_TAG, message)
            ProbeLogLevel.ERROR -> Log.e(LOGCAT_TAG, message)
        }
        // Real-time file durability (issue #69): the product owner currently has no way to pull
        // logcat, so this is the actual retrieval path. ProbeLogFile.appendLine flushes
        // immediately -- no buffering -- so the file on disk is current even if the process dies
        // right after this call returns.
        fileSink?.appendLine(formatLine(entry))
    }

    private fun formatLine(entry: ProbeLogEntry): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return "[${fmt.format(Date(entry.timestampMillis))}] [${entry.level}] ${entry.message}"
    }

    private companion object {
        const val LOGCAT_TAG = "TonexProbe"
    }

    /**
     * Plain-text rendering for the "Save & share log" button — one line per entry.
     *
     * Was `renderForClipboard()` / copied straight to [android.content.ClipboardManager] until
     * issue #25's full read/write test logs started overflowing the clipboard's Binder
     * transaction size limit (`TransactionTooLargeException`, roughly 1MB shared across the
     * whole process). Callers now write this to a file and share that instead — see
     * `ProbeActivity`'s "Save & share log" button. Since issue #69, this is the same formatting
     * [attachFileSink]'s real-time writes use, so the file already on disk and a fresh
     * `render()` call agree line-for-line.
     */
    fun render(): String = _entries.value.joinToString("\n") { formatLine(it) }
}
