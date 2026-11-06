package app.tenshu.ingest.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [BackupDestination] is the inverse of [backupDestinationFolder], so the round trip is
 * the property worth pinning: whatever the drain writes to must classify back to the
 * device and bucket it was built from, or the warning names the wrong thing.
 */
class BackupDestinationTest {

    @Test
    fun classifiesWhatTheDrainActuallyWrites() {
        val path = backupDestinationFolder(deviceLabel = "Pixel 9", bucketName = "Camera")

        assertEquals(
            BackupDestination.Bucket(deviceLabel = "Pixel 9", bucketLabel = "Camera"),
            BackupDestination.of(path),
            "The classifier must invert the destination builder — they encode one convention",
        )
    }

    @Test
    fun classifiesTheDeviceLevelFolder() {
        assertEquals(
            BackupDestination.Device(deviceLabel = "Pixel 9"),
            BackupDestination.of("/Backup/Pixel 9"),
        )
    }

    @Test
    fun aFileInsideABackedUpFolderIsAnOrdinaryFile() {
        assertNull(
            BackupDestination.of("/Backup/Pixel 9/Camera/IMG_0001.jpg"),
            "Deleting one photo is not the event the warning exists to explain; warning on " +
                "it would train the user to dismiss the warning that matters",
        )
    }

    @Test
    fun theBackupRootItselfIsNotADestination() {
        assertNull(BackupDestination.of("/Backup"))
        assertNull(BackupDestination.of("/Backup/"))
    }

    @Test
    fun ordinaryPathsAreNotDestinations() {
        assertNull(BackupDestination.of("/"))
        assertNull(BackupDestination.of("/Documents/Camera"))
        // ⚠ Prefix-matching on the bare root would claim this one: it is a different
        // top-level folder that merely starts with the same letters.
        assertNull(BackupDestination.of("/Backups/Pixel/Camera"))
    }

    @Test
    fun aSanitisedSegmentClassifiesAsTheUserSeesIt() {
        // `backupDestinationFolder` maps separators to `_` on the way in, so a device
        // named "Ben/Phone" lands as one segment and must classify as one segment.
        val path = backupDestinationFolder(deviceLabel = "Ben/Phone", bucketName = "DCIM")

        assertEquals(
            BackupDestination.Bucket(deviceLabel = "Ben_Phone", bucketLabel = "DCIM"),
            BackupDestination.of(path),
        )
    }
}
