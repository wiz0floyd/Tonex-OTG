package dev.tonexotg.app.probe

import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs a process-wide [Thread.UncaughtExceptionHandler] that writes the crashing thread's
 * name, the exception, and its full stack trace to [logFile] (issue #69) before chaining to
 * whatever handler was already installed -- captured via [Thread.getDefaultUncaughtExceptionHandler]
 * *before* this call overwrites it, so the process still terminates/reports normally afterward.
 * This never swallows a crash.
 *
 * Call as early as possible in [ProbeActivity.onCreate]. `Thread.setDefaultUncaughtExceptionHandler`
 * is process-global: once installed, it keeps catching crashes for the rest of the process's
 * life regardless of whether [ProbeActivity] itself is still alive -- deliberately so, since a
 * crash on some other screen after the diagnostic Activity has been opened at least once should
 * still land in the log. Idempotent: if a [ProbeCrashHandler] is already the default (e.g. the
 * user backs out of and relaunches [ProbeActivity] without the process dying), this is a no-op
 * rather than stacking a redundant duplicate wrapper.
 */
fun installProbeCrashHandler(logFile: ProbeLogFile) {
    val current = Thread.getDefaultUncaughtExceptionHandler()
    if (current is ProbeCrashHandler) return
    Thread.setDefaultUncaughtExceptionHandler(ProbeCrashHandler(logFile, current))
}

/**
 * `internal` (not `private`) so tests can invoke [uncaughtException] directly rather than
 * actually crashing the test JVM.
 */
internal class ProbeCrashHandler(
    private val logFile: ProbeLogFile,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            logFile.appendLine("[CRASH] Thread \"${thread.name}\" — uncaught exception:\n$trace")
        } catch (loggingFailure: Throwable) {
            // A crash handler must not let a failure in its own logging path swallow the real
            // crash -- fall through to `previous` regardless. Best-effort visibility via logcat
            // since ProbeLog's usual logcat mirror can't be trusted to still work here.
            Log.e("TonexProbe", "Crash handler failed to write $logFile", loggingFailure)
        }
        previous?.uncaughtException(thread, throwable)
    }
}
