package dev.tonexotg.app.ui.screens.connection

import dev.tonexotg.protocol.ConnectionState
import dev.tonexotg.protocol.ParameterId
import dev.tonexotg.protocol.PresetIndex
import dev.tonexotg.protocol.TonexError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers this story's core acceptance criterion — "every [TonexError] variant has a distinct,
 * actionable presentation" — by enumerating **all 17** current variants explicitly (not a
 * sample) and checking each one individually, plus cross-cutting checks (pairwise distinctness,
 * no variant looking like success) over the full set.
 *
 * One instance of each variant is built here, with plausible field values; the exhaustive `when`
 * in `ErrorPresentation.kt` itself is what guarantees a *future* 18th variant can't reach this
 * list unnoticed (this test only proves today's 17 are each handled correctly).
 */
class ErrorPresentationTest {

    private val allErrors: List<TonexError> = listOf(
        TonexError.TransportFailure(RuntimeException("device disconnected")),
        TonexError.Timeout(operation = "hello", timeoutMillis = 3_000),
        TonexError.MalformedFrame(reason = "unescaped delimiter"),
        TonexError.CrcMismatch(expected = 0x1234, actual = 0xABCD),
        TonexError.ProtocolStateViolation(state = ConnectionState.Connecting, details = "setParameter requires Ready"),
        TonexError.UnsupportedByFirmware(operation = "single-parameter-write"),
        TonexError.StaleSessionState(details = "superseded by a later read", sameSession = true),
        TonexError.UnexpectedBlobShape(expectedSize = 4096, actualSize = 12),
        TonexError.BlobTooShortToPatch(minimumSize = 256, actualSize = 18),
        TonexError.BlobSizeChangedSinceHandshake(pinnedSize = 4096, actualSize = 2048),
        TonexError.ImplausibleStateBlobShape(actualSize = 4096),
        TonexError.OversizedStateBlob(maxSize = 8192, actualSize = 16384),
        TonexError.InvalidPresetIndex(value = 255),
        TonexError.NoSnapshotAvailable(presetIndex = PresetIndex(3)),
        TonexError.RevertIncomplete(
            presetIndex = PresetIndex(3),
            appliedCount = 47,
            totalCount = 109,
            failedParameter = ParameterId(47),
            cause = TonexError.Timeout(operation = "parameter-write", timeoutMillis = 500),
        ),
        TonexError.ActivePresetChangedDuringRevert(
            intendedPreset = PresetIndex(3),
            observedPreset = PresetIndex(5),
            appliedCount = 20,
            totalCount = 109,
            nextParameter = ParameterId(20),
        ),
        TonexError.ParameterValueOutOfRange(id = ParameterId(20), value = 99f, min = 0f, max = 10f),
    )

    // ---- Individual-variant checks: distinct, actionable, non-empty --------------------------

    @Test
    fun transportFailure_hasDistinctActionablePresentation() {
        val p = TonexError.TransportFailure(RuntimeException("device disconnected")).toPresentation()
        assertEquals("Connection Lost", p.title)
        assertTrue(p.recommendReconnect)
        assertTrue(p.advice.isNotBlank())
    }

    @Test
    fun timeout_hasDistinctActionablePresentation() {
        val p = TonexError.Timeout("hello", 3_000).toPresentation()
        assertEquals("Pedal Didn't Respond", p.title)
        assertTrue(p.recommendReconnect)
    }

    @Test
    fun malformedFrame_hasDistinctActionablePresentation() {
        val p = TonexError.MalformedFrame("unescaped delimiter").toPresentation()
        assertEquals("Corrupted Data Received", p.title)
        assertTrue(p.recommendReconnect)
    }

    @Test
    fun crcMismatch_hasDistinctActionablePresentation() {
        val p = TonexError.CrcMismatch(0x1234, 0xABCD).toPresentation()
        assertEquals("Data Check Failed", p.title)
        assertTrue(p.recommendReconnect)
    }

    @Test
    fun protocolStateViolation_hasDistinctActionablePresentation() {
        val p = TonexError.ProtocolStateViolation(ConnectionState.Connecting, "setParameter requires Ready").toPresentation()
        assertEquals("Not Ready For That Yet", p.title)
    }

    @Test
    fun unsupportedByFirmware_recommendsNoReconnect() {
        val p = TonexError.UnsupportedByFirmware("single-parameter-write").toPresentation()
        assertEquals("Not Supported By This Pedal", p.title)
        // Reconnecting cannot fix a firmware capability gap — must not mislead the player into
        // trying it.
        assertFalse(p.recommendReconnect)
    }

    @Test
    fun staleSessionState_recommendsNoReconnectAndNoDataLoss() {
        val p = TonexError.StaleSessionState("superseded by a later read", sameSession = true).toPresentation()
        assertEquals("Write Blocked — Outdated Data", p.title)
        assertFalse(p.recommendReconnect)
    }

    @Test
    fun unexpectedBlobShape_hasDistinctActionablePresentation() {
        val p = TonexError.UnexpectedBlobShape(4096, 12).toPresentation()
        assertEquals("Unexpected Pedal Data", p.title)
    }

    @Test
    fun blobTooShortToPatch_hasDistinctActionablePresentation() {
        val p = TonexError.BlobTooShortToPatch(256, 18).toPresentation()
        assertEquals("Incomplete Pedal Data", p.title)
    }

    @Test
    fun blobSizeChangedSinceHandshake_hasDistinctActionablePresentation() {
        val p = TonexError.BlobSizeChangedSinceHandshake(4096, 2048).toPresentation()
        assertEquals("Pedal Data Changed Unexpectedly", p.title)
    }

    @Test
    fun implausibleStateBlobShape_hasDistinctActionablePresentation() {
        val p = TonexError.ImplausibleStateBlobShape(4096).toPresentation()
        assertEquals("Pedal Data Looks Wrong", p.title)
    }

    @Test
    fun oversizedStateBlob_hasDistinctActionablePresentation() {
        val p = TonexError.OversizedStateBlob(8192, 16384).toPresentation()
        assertEquals("Pedal Data Too Large", p.title)
    }

    @Test
    fun invalidPresetIndex_hasDistinctActionablePresentation() {
        val p = TonexError.InvalidPresetIndex(255).toPresentation()
        assertEquals("Invalid Preset Index", p.title)
        assertFalse(p.recommendReconnect)
    }

    @Test
    fun noSnapshotAvailable_recommendsNoReconnect() {
        val p = TonexError.NoSnapshotAvailable(PresetIndex(3)).toPresentation()
        assertEquals("No Snapshot To Revert To", p.title)
        assertFalse(p.recommendReconnect)
    }

    @Test
    fun revertIncomplete_adviceSaysRetryIsSafe() {
        val p = TonexError.RevertIncomplete(
            presetIndex = PresetIndex(3),
            appliedCount = 47,
            totalCount = 109,
            failedParameter = ParameterId(47),
            cause = TonexError.Timeout("parameter-write", 500),
        ).toPresentation()
        assertEquals("Revert Stopped Partway", p.title)
        assertFalse(p.recommendReconnect)
        // This is the one case where "retry" IS the honest advice (per TonexError.kt's own
        // KDoc: "retrying revert is safe and idempotent").
        assertTrue(
            "advice should say retry is safe: ${p.advice}",
            p.advice.contains("safe", ignoreCase = true) && p.advice.contains("Revert", ignoreCase = true),
        )
    }

    @Test
    fun activePresetChangedDuringRevert_adviceExplicitlyWarnsAgainstImmediateRetry() {
        val p = TonexError.ActivePresetChangedDuringRevert(
            intendedPreset = PresetIndex(3),
            observedPreset = PresetIndex(5),
            appliedCount = 20,
            totalCount = 109,
            nextParameter = ParameterId(20),
        ).toPresentation()
        assertEquals("Revert Interrupted By Pedal", p.title)
        assertFalse(p.recommendReconnect)
        // Per TonexError.kt's KDoc: "Do not advise 'reselect the preset and retry' anywhere this
        // error is surfaced; there is no safe automatic recovery here." Pin that the UI layer
        // actually honors this and doesn't invent a "just retry" suggestion.
        assertTrue(
            "advice must warn against retrying: ${p.advice}",
            p.advice.contains("not retry", ignoreCase = true) || p.advice.contains("do not", ignoreCase = true),
        )
    }

    @Test
    fun parameterValueOutOfRange_hasDistinctActionablePresentation() {
        val p = TonexError.ParameterValueOutOfRange(ParameterId(20), 99f, 0f, 10f).toPresentation()
        assertEquals("Value Out Of Range", p.title)
        assertFalse(p.recommendReconnect)
    }

    // ---- Cross-cutting checks over the full set -----------------------------------------------

    @Test
    fun allSeventeenVariants_areCovered() {
        // Pins the enumeration itself against silent drift — if TonexError grows or shrinks a
        // variant, this count is the first thing that should force a look at this file.
        assertEquals(17, allErrors.size)
    }

    @Test
    fun everyVariant_hasNonBlankTitleDetailAndAdvice() {
        for (error in allErrors) {
            val p = error.toPresentation()
            assertTrue("${error::class.simpleName} title is blank", p.title.isNotBlank())
            assertTrue("${error::class.simpleName} detail is blank", p.detail.isNotBlank())
            assertTrue("${error::class.simpleName} advice is blank", p.advice.isNotBlank())
        }
    }

    @Test
    fun everyVariant_detailMatchesTheUnderlyingTonexErrorMessage() {
        // The presentation layer must not paraphrase or drop information from the already
        // carefully-worded TonexError.message (see TonexError.kt's own KDoc on this).
        for (error in allErrors) {
            assertEquals(error.message, error.toPresentation().detail)
        }
    }

    @Test
    fun everyVariant_hasAPairwiseDistinctTitle() {
        val titles = allErrors.map { it.toPresentation().title }
        assertEquals(
            "titles must be pairwise distinct so no two TonexError variants collapse into the same category",
            titles.size,
            titles.toSet().size,
        )
    }

    @Test
    fun noVariant_hasAGenericSomethingWentWrongTitle() {
        val genericPhrases = listOf("something went wrong", "an error occurred", "error", "unknown error")
        for (error in allErrors) {
            val title = error.toPresentation().title.lowercase()
            for (generic in genericPhrases) {
                assertNotEquals(
                    "${error::class.simpleName} title collapsed to a generic phrase",
                    generic,
                    title,
                )
            }
        }
    }

    @Test
    fun noVariant_impliesSuccessInItsPresentation() {
        // FR11 / this story's AC: "no failure path results in a UI that implies success." None
        // of these words should ever appear in an error's title as a whole word. Word-boundary
        // matching (not plain substring) is deliberate: a naive `contains("complete")` would
        // false-positive on "Incomplete Pedal Data" ([BlobTooShortToPatch]'s title), which is the
        // *opposite* of implying success.
        val successWords = listOf("success", "connected", "done", "complete")
        for (error in allErrors) {
            val p = error.toPresentation()
            val titleLower = p.title.lowercase()
            for (word in successWords) {
                val wordBoundaryMatch = Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(titleLower)
                assertFalse(
                    "${error::class.simpleName} title '${p.title}' contains success-implying word '$word'",
                    wordBoundaryMatch,
                )
            }
        }
    }

    @Test
    fun onlyConnectionEndingFailures_recommendReconnect() {
        // Operation-level failures that leave the connection Ready (firmware gap, stale write,
        // out-of-range value, no snapshot, revert-specific failures) must not suggest reconnect
        // as if it would help.
        val shouldNotRecommendReconnect = setOf(
            TonexError.UnsupportedByFirmware::class,
            TonexError.StaleSessionState::class,
            TonexError.InvalidPresetIndex::class,
            TonexError.NoSnapshotAvailable::class,
            TonexError.RevertIncomplete::class,
            TonexError.ActivePresetChangedDuringRevert::class,
            TonexError.ParameterValueOutOfRange::class,
        )
        for (error in allErrors) {
            val p = error.toPresentation()
            val expected = error::class !in shouldNotRecommendReconnect
            assertEquals(
                "${error::class.simpleName} recommendReconnect mismatch",
                expected,
                p.recommendReconnect,
            )
        }
    }
}
