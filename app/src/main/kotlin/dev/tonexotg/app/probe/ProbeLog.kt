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

    fun info(message: String) = append(ProbeLogLevel.INFO, message)
    fun finding(message: String) = append(ProbeLogLevel.FINDING, message)
    fun warn(message: String) = append(ProbeLogLevel.WARN, message)
    fun error(message: String) = append(ProbeLogLevel.ERROR, message)

    private fun append(level: ProbeLogLevel, message: String) {
        _entries.update { it + ProbeLogEntry(System.currentTimeMillis(), level, message) }
        // Mirrored to logcat (issue #25, opus-reviewer-probe25 B1) so a record of what happened —
        // in particular a pre-write parameter value — survives even if the Activity dies (rotation
        // race, back press, system reclaim) before this in-memory log can be copied off-screen.
        when (level) {
            ProbeLogLevel.INFO, ProbeLogLevel.FINDING -> Log.i(LOGCAT_TAG, message)
            ProbeLogLevel.WARN -> Log.w(LOGCAT_TAG, message)
            ProbeLogLevel.ERROR -> Log.e(LOGCAT_TAG, message)
        }
    }

    private companion object {
        const val LOGCAT_TAG = "TonexProbe"
    }

    /** Plain-text rendering for the "Copy log to clipboard" button — one line per entry. */
    fun renderForClipboard(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { entry ->
            "[${fmt.format(Date(entry.timestampMillis))}] [${entry.level}] ${entry.message}"
        }
    }
}
