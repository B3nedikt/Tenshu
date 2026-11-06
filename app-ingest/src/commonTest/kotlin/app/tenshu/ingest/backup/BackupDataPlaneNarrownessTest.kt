package app.tenshu.ingest.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seam stays narrow (`photos-app.md` PH-4b).
 *
 * ## ⚠⚠ Why a test rather than a comment
 *
 * `SyncedFolderController` lived in `:drive-shared-logic` for one reason: it took Drive's whole
 * `FilesRepository` — forty-odd methods for listing, previewing, sharing, moving and pinning — and
 * called five of them. A neutral module cannot depend on an app's entire repository, so Photos
 * could *manage* backup sources but never *add* one.
 *
 * Narrowing to five methods is what let the engine move. The pressure to widen it back is
 * constant and each individual step looks reasonable — the drain wants a listing "just to check",
 * a path resolved "just for the log line" — and every one of them re-couples the engine to an app.
 *
 * ⚠ So this holds the count, and it is meant to be **annoying**: adding a method here is a
 * deliberate act with a failing test in front of it, not a quiet import. If a new method genuinely
 * belongs, the rule to apply is the interface's own — *does it serve one upload?* — and then bump
 * the number with the reason in the commit message.
 */
class BackupDataPlaneNarrownessTest {

    /**
     * A complete implementation. ⚠ Spelled out rather than delegated on purpose: if the interface
     * grows a method, **this stops compiling**, which is the loudest possible way to notice.
     */
    private class Everything : BackupDataPlane {
        override suspend fun <T> withBulkUploadTask(kind: String, block: suspend () -> T): T = block()
        override suspend fun ensureBackupDestination(parentPath: String) = Unit
        override suspend fun contentHashOf(uriString: String): String? = null
        override suspend fun uploadForBackup(
            parentPath: String,
            fileName: String,
            uriString: String,
        ): BackupUploadOutcome = BackupUploadOutcome.Skipped
        override fun storageStatus(): Flow<StorageStatusModel?> = flowOf(null)
        override suspend fun uploadOverMeteredOnce() = Unit
    }

    @Test
    fun theDataPlaneIsStillSixMethods() {
        // ⚠ Reflection over an interface is not portable across KMP targets, so the guard is the
        // exhaustive implementation above plus this count of what the drain may call. Both have to
        // be edited together, which is the point.
        val callable = listOf(
            "withBulkUploadTask", "ensureBackupDestination", "contentHashOf",
            "uploadForBackup", "storageStatus",
            // Sixth (2026-10-23): it serves the upload — the user's one-time "use mobile data"
            // override is worthless unless the bytes it handed over may also leave the device.
            "uploadOverMeteredOnce",
        )
        assertEquals(
            6,
            callable.size,
            "The backup data plane is deliberately six methods. Adding one re-couples the drain " +
                "to an app's repository, which is what kept it out of a neutral module until " +
                "PH-4b. Apply the interface's own rule — does it serve one upload? — and if it " +
                "does, change this number with the reason.",
        )
    }

    @Test
    fun aFakeCanImplementTheWholeThing() {
        // ⚠ The practical measure of narrowness: a test double is six short methods. Drive's
        // `FakeFilesRepository` is hundreds of lines, and that asymmetry is the whole argument.
        val plane: BackupDataPlane = Everything()
        assertTrue(plane.storageStatus() is Flow<*>)
    }
}
