package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins delete-then-reupload of a folder: the user uploads a folder, deletes it,
 * and uploads the same folder again. Neither a surviving row (`AlreadyExistsError`,
 * "Item already exists", for a folder gone from the listing) nor a late deletion
 * of the recreated folder may surface.
 *
 * The unit-level `DeleteFileOperationTest` already covers
 * `CreateFolder` → `UploadFile` → `DeleteFile` → re-`CreateFolder`/`UploadFile`
 * by calling `executionContext.executeAsync` directly. That bypasses the SDK
 * request chain + `ProxyGateway.call`. This SystemTest exercises the same
 * scenario through the **production read/write path**:
 *
 * - writes: `fileService.defaultFileSystem.createFolder` / `uploadFile` /
 *   `deleteFile` — all SDK chain → `ProxyGateway` → `*Implementation`,
 * - reads: `root.files.search<File> { path equalTo … }.get()` — the same
 *   chain `TenshuFilesRepository.getFiles` / `getRemoteFile` use.
 *
 * Two scenarios:
 *
 *  1. Re-create the folder + re-upload the same files at the same paths.
 *  2. Same shape with one child only — the smallest case that would surface
 *     a surviving-child ghost.
 *
 * Single-client (no pairing): no second device is required to trip it. A
 * cross-device delete-then-recreate variant (tombstone retention + remote
 * create racing past it) is its own bug class and belongs in its own SystemTest.
 */
class DeleteAndReuploadFolderSystemTest {

    @Test fun deletedFolderWithFiles_canBeReuploadedAtTheSamePaths() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Tablet")

            // ⚠ A diagnostic, not a guard — see [DeletionWitness]. If `/photos` disappears between
            // two successful uploads, the deleter is on another coroutine by the time the assertion
            // runs; the witness names it.
            val witness = DeletionWitness.watch(host.executionContext, "/photos")
            // ⚠⚠ Appended to EVERY assertion after the delete, not just the last: /photos can be
            // gone the moment createFolder returns Success, so the FIRST sibling can fail too.
            fun witnessTail() = "\n\nDELETIONS OF /photos AFTER THE USER'S DELETE:\n" + witness.report()

            val folderPath = "/photos"
            val nameA = "a.txt"
            val nameB = "b.txt"
            val pathA = "$folderPath/$nameA"
            val pathB = "$folderPath/$nameB"
            val fs = host.tenshuClient.fileService.defaultFileSystem

            // ---- Initial upload of /photos with two files. ----
            assertTrue(fs.createFolder(folderPath) is TResult.Success)
            assertTrue(
                fs.uploadFile(parentPath = folderPath, fileName = nameA, data = byteArrayOf(1))
                        is TResult.Success
            )
            assertTrue(
                fs.uploadFile(parentPath = folderPath, fileName = nameB, data = byteArrayOf(2))
                        is TResult.Success
            )

            // ---- Delete the folder. ----
            assertTrue(fs.deleteFile(folderPath) is TResult.Success)
            // Everything up to here is the delete's own legitimate cascade; anything after is the
            // suspect. Marked here rather than after the recreate so a failure of the recreate
            // assertion itself is covered too.
            witness.mark("the user's deleteFile returned")

            // ---- Re-upload the identical structure. None of these calls
            // ---- may return AlreadyExistsError.
            val recreate = fs.createFolder(folderPath)
            assertTrue(
                recreate is TResult.Success,
                "Re-creating $folderPath after delete must succeed; got $recreate" + witnessTail()
            )
            // ⚠ Everything before this point is the ORIGINAL delete's legitimate cascade. What we
            // are hunting is a deletion that lands after the folder is back.
            val reuploadA = fs.uploadFile(
                parentPath = folderPath, fileName = nameA, data = byteArrayOf(1)
            )
            assertTrue(
                reuploadA is TResult.Success,
                "Re-uploading $pathA after delete must succeed; got $reuploadA" + witnessTail()
            )
            val reuploadB = fs.uploadFile(
                parentPath = folderPath, fileName = nameB, data = byteArrayOf(2)
            )
            assertTrue(
                reuploadB is TResult.Success,
                // ⚠⚠ Two opposite failure shapes land here: `AlreadyExists` (a surviving row) and
                // `No folder at /photos` (a missing parent — a deletion after the recreate). The
                // witness report tells them apart.
                "Re-uploading $pathB after delete must succeed; got $reuploadB" + witnessTail()
            )

        } finally {
            system.shutdown()
        }
    }

    /**
     * Tighter variant: delete a folder, then re-upload a file inside the
     * recreated folder *without* recreating any of the original children.
     * If a child row survived the delete, the only way to detect it is to
     * try uploading a fresh child at one of the surviving paths.
     */
    @Test fun deletedFolder_canHostFreshChildrenAtSurvivingPaths() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            host.bootstrapAsHost(deviceName = "Host Tablet")

            val folderPath = "/docs"
            val name = "report.txt"
            val fs = host.tenshuClient.fileService.defaultFileSystem

            assertTrue(fs.createFolder(folderPath) is TResult.Success)
            assertTrue(
                fs.uploadFile(parentPath = folderPath, fileName = name, data = byteArrayOf(9))
                        is TResult.Success
            )
            assertTrue(fs.deleteFile(folderPath) is TResult.Success)

            assertTrue(
                fs.createFolder(folderPath) is TResult.Success,
                "Re-create after delete must succeed"
            )
            // Same path as the deleted child — the surviving-row case.
            val freshUpload = fs.uploadFile(
                parentPath = folderPath, fileName = name, data = byteArrayOf(10)
            )
            assertTrue(
                freshUpload is TResult.Success,
                "Uploading a file at a previously-deleted path must not surface " +
                        "AlreadyExistsError; got $freshUpload"
            )
        } finally {
            system.shutdown()
        }
    }
}
