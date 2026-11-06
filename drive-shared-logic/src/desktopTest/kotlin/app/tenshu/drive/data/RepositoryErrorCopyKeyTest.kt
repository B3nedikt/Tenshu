package app.tenshu.drive.data

import app.tenshu.error.ErrorCopyKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a user-facing surface may render for a repository failure —
 * `core-error-copy-catalogue.md` §5.0, and `control-cross-app-open.md` §6b Q2.
 *
 * ⚠⚠ **The defect these pin, observed on a device 2026-09-01.** Drive's three preview view models
 * set `error = result.error.toString()`, so the screen rendered the sealed class's own
 * `toString()` — `Unknown(copyKey=ErrorCopyKey(value=error_not_found))`. On a cross-app open that
 * string was the *entire app*: CO-D11 makes the target the only back-stack entry, so there was no
 * Drive underneath it and nothing else on screen.
 */
class RepositoryErrorCopyKeyTest {

    @Test
    fun anUnknownFailureCarriesItsOwnKeyThrough() {
        val key = ErrorCopyKey("error_not_found")
        assertEquals(key, RepositoryError.Unknown(key).copyKey())
    }

    @Test
    fun theOtherArmsMapToCatalogueKeys() {
        assertEquals(ErrorCopyKey("error_already_exists"), RepositoryError.AlreadyExists.copyKey())
        assertEquals(
            ErrorCopyKey("error_insufficient"),
            RepositoryError.InsufficientStorage(requiredBytes = 10, availableBytes = 1).copyKey(),
        )
    }

    @Test
    fun anUnexpectedErrorsMessageNeverBecomesCopy() {
        // ⚠⚠ THE guard. `UnexpectedError.message` is a diagnostic authored below the proxy
        // boundary — on Android, in another app's process entirely. §5.0 exists to keep it off a
        // user's screen, and `toString()` put it there verbatim.
        val leak = "java.lang.IllegalStateException: no such row 7f3a"
        val key = RepositoryError.UnexpectedError(leak).copyKey()

        assertEquals(ErrorCopyKey("error_unexpected"), key)
        assertFalse(leak in key.value, "the diagnostic must not survive into the copy key")
    }

    @Test
    fun everyKeyIsACatalogueKeyAndNotASentence() {
        // A key is a name, never prose: `ErrorCopyKey.PREFIX` + lower_snake. A sentence here would
        // render as itself on a miss, which is the failure §5.0 describes.
        val keys = listOf(
            RepositoryError.AlreadyExists,
            RepositoryError.StillSyncing,
            RepositoryError.MoveNeedsLocalBytes,
            RepositoryError.UnexpectedError("boom"),
            RepositoryError.InsufficientStorage(1, 0),
            RepositoryError.Unknown(ErrorCopyKey("error_not_found")),
        ).map { it.copyKey().value }

        for (value in keys) {
            assertTrue(value.startsWith(ErrorCopyKey.PREFIX), "not a catalogue key: $value")
            assertFalse(' ' in value, "a key must not be a sentence: $value")
            assertEquals(value.lowercase(), value, "keys are lower_snake: $value")
        }
    }
}
