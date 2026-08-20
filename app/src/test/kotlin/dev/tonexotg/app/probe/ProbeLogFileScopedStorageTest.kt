package dev.tonexotg.app.probe

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for [ProbeLogFile.scopedStorageInsertValues] -- the API 29+ `MediaStore`-backed path
 * `ProbeLogFile.create` takes on the real target hardware (API 36) since issue #71.
 *
 * Deliberately does NOT attempt to exercise the full `create()` round trip (insert + open +
 * publish) through Robolectric. Checked against Robolectric 4.16's actual shadow source before
 * writing this file, not assumed: `ShadowMediaStore` has no `MediaProvider` emulation at all --
 * its surface is limited to bitmap/thumbnail helpers -- and `ContentResolver.openFileDescriptor`
 * isn't shadowed either, so it falls straight through to the real framework method, which throws
 * `FileNotFoundException` without a real provider registered for the `media` authority. Worse,
 * `ShadowContentResolver.insert()` doesn't fail in that situation -- with no provider registered
 * it silently fabricates a placeholder `Uri` (`<collection>/<incrementing id>`) and reports
 * success. A test that called `ProbeLogFile.create(context)` here and asserted "didn't throw" or
 * "returned a content:// Uri" would be the exact self-consistent-round-trip trap CLAUDE.md warns
 * about: green, and proving nothing about real `MediaStore` behavior.
 *
 * What IS genuinely checkable on the JVM: the literal `ContentValues` this class sends to
 * `insert()` -- `RELATIVE_PATH`, `MIME_TYPE`, `IS_PENDING` -- which is exactly the undocumented-
 * but-real part of #71's behavior (per commonsware's "Scoped Storage Stories: The Undocumented
 * Documents"). [scopedStorageInsertValues] is `internal` specifically so this test can pin those
 * values directly, the same way [ProbeCrashHandler] is `internal` rather than `private` so its
 * own test can call it directly.
 *
 * Everything past that insert call -- whether a real device's file manager actually lists the
 * file under `Documents/tonex-logs/` the instant `IS_PENDING` clears, and the real-world
 * stale-pending expiry window `ProbeLogFile`'s KDoc discusses -- is NOT covered here and needs a
 * real API 29+ device to confirm; see this project's "document what needs hardware confirmation"
 * pattern (CLAUDE.md) and issue #71/#25's hardware-probe thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProbeLogFileScopedStorageTest {

    @Test
    fun scopedStorageInsertValues_targetsDocumentsTonexLogs_withTheRequestedNameAndAPlainTextMimeType() {
        val values = ProbeLogFile.scopedStorageInsertValues("tonex-probe-2026-08-19_120000.log")

        assertEquals(
            "tonex-probe-2026-08-19_120000.log",
            values.getAsString(MediaStore.Files.FileColumns.DISPLAY_NAME),
        )
        assertEquals("text/plain", values.getAsString(MediaStore.Files.FileColumns.MIME_TYPE))
        assertEquals("Documents/tonex-logs", values.getAsString(MediaStore.Files.FileColumns.RELATIVE_PATH))
    }

    @Test
    fun scopedStorageInsertValues_marksTheEntryPendingSoNoOtherAppCanOpenItMidInsert() {
        val values = ProbeLogFile.scopedStorageInsertValues("tonex-probe-2026-08-19_120000.log")

        assertEquals(1, values.getAsInteger(MediaStore.Files.FileColumns.IS_PENDING))
    }
}
