package dev.tonexotg.app.probe

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single, already-open raw file handle backing [ProbeLog]'s real-time durability (issue #69)
 * and, since issue #71, backed by a genuinely public, user-browsable location on API 29+.
 *
 * Deliberately not just "open a [java.io.FileWriter] each call": [ProbeCrashHandler] needs a
 * handle that is already open and ready to accept a write the instant an uncaught exception is
 * thrown, without depending on anything that could itself be broken by whatever caused the crash
 * (Compose state, coroutines, even opening a fresh handle under memory pressure). One instance
 * owns the underlying [FileOutputStream] for the rest of the process's life; [ProbeLog] and
 * [ProbeCrashHandler] both write through the same instance, and [appendLine] is `synchronized` so
 * a normal [ProbeLog] entry on one thread can't interleave/tear a crash handler's write on
 * another.
 *
 * Never closed by [ProbeActivity] on purpose -- see that class's `onDestroy` KDoc: probe/reader
 * work deliberately keeps running after the Activity is destroyed, and a process-wide crash can
 * happen at any later point too, so the file has to stay writable for the rest of the process's
 * life. [close] exists only for tests that want to release the descriptor between cases.
 *
 * ## Storage location (issue #71)
 *
 * On API 29+ (scoped storage), the log lives in `Documents/tonex-logs/` via `MediaStore` --
 * genuinely browsable in a normal file manager, not just reachable through this app's own share
 * sheet or ADB (the whole point of #71; app-private `getExternalFilesDir` storage from #69 was
 * hidden from most file managers). A raw `File()` into public Documents does not work here
 * without `MANAGE_EXTERNAL_STORAGE`, a heavy Play-policy-flagged permission this project avoids
 * per #71's explicit constraint; `MediaStore.Files` lets an app create and write its own entries
 * under `Documents/` or `Download/` (and only those two root directories, for a non-media mime
 * type -- confirmed against Android's actual `MediaProvider` behavior, not just this issue's
 * text) without `WRITE_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`.
 *
 * On API 26-28 (`minSdk`, below the scoped-storage cutover at 29), this falls back to the #69
 * behavior unchanged: a plain [File] under `context.getExternalFilesDir(null)/probe-logs/`. This
 * is a deliberate, documented gap, not an oversight -- the real target device is API 36, and
 * building a full legacy runtime-permission UI for a 3-API-level range the hardware never
 * exercises would be over-building per this project's calibration (see CLAUDE.md). "Save & share
 * log" still works on that range via the existing `probelogprovider` [FileProvider].
 *
 * ### What's verified against source/docs vs. what still needs a real device (issue #71)
 *
 * Verified: the `Documents/`+`Download/`-only root-directory restriction on `MediaStore.Files`
 * for non-media mime types, the no-extra-permission-needed claim for an app's own inserts on API
 * 29+, that `ContentResolver.openFileDescriptor` hands back a real fd usable for direct
 * `write`/`fsync` syscalls without further IPC, and that `MediaColumns.DATE_EXPIRES`
 * auto-populates (roughly a week out) whenever `IS_PENDING` is set and isn't reliably cleared
 * just by clearing `IS_PENDING` -- hence [createScopedStorageLogFile] explicitly nulls it out in
 * the same `update()` call, rather than leaving a long-running probe session's log to a stale-
 * pending cleanup pass days later.
 *
 * NOT verified, because Robolectric 4.16 (this project's test runtime) has no `MediaProvider`
 * emulation at all -- `ShadowMediaStore` covers only bitmap/thumbnail helpers, and
 * `ContentResolver.openFileDescriptor` isn't shadowed, so it falls straight through to the real
 * framework method and throws without a registered provider: whether a real device's file manager
 * actually lists the file under `Documents/tonex-logs/` the instant `IS_PENDING` clears, and the
 * exact real-world stale-pending expiry window. [ProbeLogFileScopedStorageTest] pins the literal
 * `ContentValues` this class inserts (the part that's actually undocumented-but-real, per
 * commonsware's "Scoped Storage Stories" -- see [scopedStorageInsertValues]) rather than
 * attempting to fake the rest of the round trip end to end.
 *
 * Known limitation, not a bug: `MediaProvider`'s `SIZE` column generally isn't refreshed until the
 * fd is closed or the file is rescanned, so a file manager checked mid-probe-session may show this
 * file as 0 bytes even though `appendLine` has already durably flushed real content to it. Doesn't
 * affect correctness (the bytes are genuinely on disk, and this file's actual content is always
 * readable), but is worth knowing before concluding "browsable at all" (#71's acceptance
 * criterion) failed from a stale size shown mid-session alone.
 */
class ProbeLogFile private constructor(
    /** Human-readable location for log messages -- a `Documents/...` relative path on API 29+, an absolute path on the API<29 fallback. */
    val displayPath: String,
    shareUriProvider: () -> Uri,
    private val stream: FileOutputStream,
) {

    /**
     * A `content://` Uri usable directly in an `ACTION_SEND` intent for "Save & share log" -- see
     * [ProbeActivity]. Lazy, not eagerly computed in [create]: on the API<29 fallback this calls
     * `FileProvider.getUriForFile`, whose `SimplePathStrategy` caches its resolved roots in a
     * static field that is NOT reset between Robolectric tests (confirmed against
     * robolectric/robolectric#8773) -- computing it eagerly on every [create] call broke every
     * Robolectric test after the first one in the same JVM, since each test's sandboxed
     * `getExternalFilesDir` root is a fresh, different temp directory the FIRST test's cached
     * roots no longer match. Deferring to first real access (the actual share-button tap in
     * production; simply never touched by [ProbeLogFileTest]/[ProbeCrashHandlerTest], which read
     * the file back directly) sidesteps that entirely, and matches how this Uri was computed
     * before #71 anyway (lazily, in `ProbeActivity`'s click handler).
     */
    val shareUri: Uri by lazy(LazyThreadSafetyMode.NONE, shareUriProvider)

    /**
     * Writes [line] plus a trailing newline and flushes immediately -- both the Java-level
     * buffer ([FileOutputStream.flush]) and, best-effort, the OS page cache
     * ([java.io.FileDescriptor.sync]) -- so the bytes are actually durable on disk by the time
     * this call returns, not just handed to a buffer that a crashing process could lose along
     * with everything else. `fsync` failure (e.g. an unusual filesystem that doesn't support it)
     * is swallowed: the plain `flush()` already got the bytes out of this process's own memory,
     * which is the part a JVM crash actually threatens. Works identically for the API 29+
     * `MediaStore`-backed stream and the API<29 plain-`File` stream: both are real, already-open
     * file descriptors once opened (see class KDoc), so `write`/`flush`/`sync` are ordinary
     * syscalls against the underlying fd either way -- no further IPC per call.
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

    override fun toString(): String = displayPath

    companion object {
        private const val DOCUMENTS_RELATIVE_PATH = "Documents/tonex-logs"
        private const val LEGACY_SUBDIRECTORY = "probe-logs"

        /**
         * Creates a fresh timestamped log file, named `tonex-probe-<yyyy-MM-dd_HHmmss>.log`
         * (unchanged from #69, so `probe_log_file_paths.xml`'s `external-files-path` entry keeps
         * matching on the API<29 fallback path). Picks the storage backend per API level -- see
         * class KDoc.
         */
        fun create(context: Context): ProbeLogFile {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val name = "tonex-probe-$timestamp.log"
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createScopedStorageLogFile(context, name)
            } else {
                createLegacyAppPrivateLogFile(context, name)
            }
        }

        /**
         * The exact `ContentValues` inserted for a scoped-storage log named [name] -- pulled out
         * of [createScopedStorageLogFile] purely so [ProbeLogFileScopedStorageTest] can pin these
         * literal values directly. `internal`, not `private`, only for that reason (same pattern
         * as [ProbeCrashHandler] being `internal` rather than `private` -- see its KDoc).
         */
        internal fun scopedStorageInsertValues(name: String): ContentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, DOCUMENTS_RELATIVE_PATH)
            // Set while the entry is being created so no other app can open it mid-insert;
            // cleared immediately in createScopedStorageLogFile once the fd is in hand -- see
            // that function's comment for why this can't wait until the file is "done".
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }

        /**
         * API 29+: a `MediaStore.Files` entry under `Documents/tonex-logs/` -- see class KDoc for
         * why this location/API and not a raw [File] or `MANAGE_EXTERNAL_STORAGE`, and for what's
         * verified against source/docs versus what still needs a real device.
         */
        private fun createScopedStorageLogFile(context: Context, name: String): ProbeLogFile {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri("external")
            val uri = resolver.insert(collection, scopedStorageInsertValues(name))
                ?: throw IllegalStateException(
                    "MediaStore rejected the probe log insert into $DOCUMENTS_RELATIVE_PATH/$name",
                )
            val pfd = resolver.openFileDescriptor(uri, "rwt")
                ?: throw IllegalStateException("MediaStore returned no file descriptor for $uri")
            // ParcelFileDescriptor.AutoCloseOutputStream (not a hand-rolled
            // FileOutputStream(pfd.fileDescriptor)): closing a plain FileOutputStream built from
            // another object's FileDescriptor does NOT close that ParcelFileDescriptor -- they're
            // independent "owners" of the same fd per java.io.FileDescriptor's reference
            // tracking. AutoCloseOutputStream is the SDK's own wrapper that closes both together.
            val stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            // Clear IS_PENDING immediately, not when the file is eventually closed: this log is
            // written to continuously for the life of the process (issue #69's whole point --
            // it's not a create-then-close-then-publish MediaStore write). Leaving IS_PENDING=1
            // for that whole span would both hide the file from file managers the entire time
            // (defeating #71's actual goal) and risk MediaStore's stale-pending auto-expiry
            // deleting a long-running probe session's log out from under it. DATE_EXPIRES is
            // cleared in the same call: it auto-populates (roughly a week out) whenever
            // IS_PENDING is set, and is not reliably cleared just by clearing IS_PENDING on its
            // own, so a lingering DATE_EXPIRES could still get this file swept up in a stale-
            // pending maintenance pass days later even though it stopped being pending.
            val publish = ContentValues().apply {
                put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                putNull(MediaStore.Files.FileColumns.DATE_EXPIRES)
            }
            resolver.update(uri, publish, null, null)
            return ProbeLogFile(displayPath = resolveDisplayPath(resolver, uri, name), shareUriProvider = { uri }, stream = stream)
        }

        /**
         * Reads back the [MediaStore.Files.FileColumns.RELATIVE_PATH] and
         * [MediaStore.Files.FileColumns.DISPLAY_NAME] MediaStore actually stored, rather than
         * assuming the requested [requestedName] stuck: on a `DISPLAY_NAME` collision MediaStore
         * silently de-duplicates by appending e.g. `(1)` to the name it actually creates.
         * [displayPath] is shown to a human whose whole task is "go find this exact file" (issue
         * #71's acceptance criterion), so it must reflect what's really there. Falls back to the
         * requested name only if the readback itself is unavailable (defensive; not expected in
         * practice immediately after a successful insert).
         */
        private fun resolveDisplayPath(resolver: ContentResolver, uri: Uri, requestedName: String): String {
            val projection = arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH, MediaStore.Files.FileColumns.DISPLAY_NAME)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val relativePathIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    val displayNameIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val relativePath = relativePathIndex.takeIf { it >= 0 }?.let(cursor::getString)
                    val displayName = displayNameIndex.takeIf { it >= 0 }?.let(cursor::getString)
                    if (relativePath != null && displayName != null) return "$relativePath$displayName"
                }
            }
            return "$DOCUMENTS_RELATIVE_PATH/$requestedName"
        }

        /**
         * API 26-28 fallback: unchanged #69 behavior, a plain [File] under
         * `context.getExternalFilesDir(null)/probe-logs/`. See class KDoc -- this is a
         * deliberate, documented gap for a range the real target hardware (API 36) never
         * exercises, not an oversight.
         */
        private fun createLegacyAppPrivateLogFile(context: Context, name: String): ProbeLogFile {
            val dir = File(context.getExternalFilesDir(null), LEGACY_SUBDIRECTORY).apply { mkdirs() }
            val file = File(dir, name)
            val stream = FileOutputStream(file, true)
            return ProbeLogFile(
                displayPath = file.absolutePath,
                shareUriProvider = { FileProvider.getUriForFile(context, "${context.packageName}.probelogprovider", file) },
                stream = stream,
            )
        }
    }
}
