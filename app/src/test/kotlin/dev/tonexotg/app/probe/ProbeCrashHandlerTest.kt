package dev.tonexotg.app.probe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [ProbeCrashHandler]/[installProbeCrashHandler] (issue #69). Covers
 * acceptance criteria (b) and (c): an uncaught exception's full stack trace lands in the log
 * file, and the previously-installed default handler still runs afterward (the crash is not
 * swallowed).
 *
 * Exercises [ProbeCrashHandler.uncaughtException] directly rather than actually crashing the
 * test JVM -- see that class's KDoc for why it's `internal`, not `private`.
 *
 * Pinned to `sdk = [28]` (issue #71's API<29 fallback, plain-`File`-backed) purely so these tests
 * can read the log back with the simplest possible backend -- [ProbeCrashHandler] itself only
 * ever calls [ProbeLogFile.appendLine], which is exercised identically regardless of which storage
 * backend is behind it, so one backend is enough here; the `MediaStore`-backed path's own
 * read/write behavior is covered by [ProbeLogFileScopedStorageTest]. Reads back via
 * `File(logFile.displayPath)`, never `logFile.shareUri` -- see [ProbeLogFileTest]'s KDoc for why
 * touching `shareUri` on this fallback path breaks every Robolectric test after the first one in
 * the same JVM (robolectric/robolectric#8773).
 *
 * `Thread.setDefaultUncaughtExceptionHandler` is a JVM-static, process-wide field shared across
 * every test in this run (Robolectric's per-test sandboxing does not reset it, since
 * `java.lang.Thread` is a bootstrap class loaded once per JVM) -- every test here saves and
 * restores whatever was actually installed beforehand, so these tests can't pollute each other
 * or any test that happens to run after them in the same process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProbeCrashHandlerTest {

    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun saveDefaultHandler() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun restoreDefaultHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun readBack(logFile: ProbeLogFile): String = File(logFile.displayPath).readText()

    @Test
    fun uncaughtException_writesThreadNameExceptionAndFullStackTraceToTheLogFile() {
        val logFile = ProbeLogFile.create(context())
        try {
            val handler = ProbeCrashHandler(logFile, previous = null)
            val exception = IllegalStateException("boom")

            handler.uncaughtException(Thread.currentThread(), exception)

            val onDisk = readBack(logFile)
            assertTrue("expected the crashing thread's name", onDisk.contains(Thread.currentThread().name))
            assertTrue("expected the exception type", onDisk.contains("IllegalStateException"))
            assertTrue("expected the exception message", onDisk.contains("boom"))
            assertTrue(
                "expected a real stack frame, not just the exception's toString()",
                onDisk.contains("ProbeCrashHandlerTest"),
            )
        } finally {
            logFile.close()
        }
    }

    @Test
    fun uncaughtException_stillCallsThePreviouslyInstalledHandler() {
        val logFile = ProbeLogFile.create(context())
        try {
            var previousHandlerSawThrowable: Throwable? = null
            val previous = Thread.UncaughtExceptionHandler { _, throwable ->
                previousHandlerSawThrowable = throwable
            }
            val handler = ProbeCrashHandler(logFile, previous)
            val exception = RuntimeException("chained")

            handler.uncaughtException(Thread.currentThread(), exception)

            assertEquals(
                "the crash must not be swallowed -- the previously installed handler must still run",
                exception,
                previousHandlerSawThrowable,
            )
        } finally {
            logFile.close()
        }
    }

    @Test
    fun installProbeCrashHandler_capturesAndChainsToWhateverWasInstalledBefore() {
        var previousInvoked = false
        Thread.setDefaultUncaughtExceptionHandler(Thread.UncaughtExceptionHandler { _, _ -> previousInvoked = true })

        val logFile = ProbeLogFile.create(context())
        try {
            installProbeCrashHandler(logFile)

            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(installed)
            installed!!.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

            assertTrue("previously installed handler should still run", previousInvoked)
            assertTrue(readBack(logFile).contains("boom"))
        } finally {
            logFile.close()
        }
    }

    @Test
    fun installProbeCrashHandler_isIdempotent_doesNotStackDuplicateHandlers() {
        val logFile = ProbeLogFile.create(context())
        try {
            installProbeCrashHandler(logFile)
            val first = Thread.getDefaultUncaughtExceptionHandler()

            installProbeCrashHandler(logFile)
            val second = Thread.getDefaultUncaughtExceptionHandler()

            assertTrue(
                "a second install (e.g. relaunching ProbeActivity in the same process) must not " +
                    "wrap another redundant handler around the first",
                first === second,
            )
        } finally {
            logFile.close()
        }
    }
}
