package app.tenshu.ingest.backup

import org.tenshu.core.file.BACKUP_ROOT

/**
 * What a path under [BACKUP_ROOT] *is*, recovered from the path itself.
 *
 * The inverse of [backupDestinationFolder], and it lives beside it deliberately: the two
 * encode the same `/Backup/<device>/<bucket>` convention, and a convention split across
 * two files that neither owns is the join that degrades silently when one side changes.
 *
 * ⚠ This reads the **path**, not the ingest registry, because the registry cannot answer
 * the question. `IngestSource` is a synced row carrying `deviceId` / `displayName` /
 * `sourceKind` / `watchedPaths`, but on mobile `watchedPaths` holds `MediaStore` bucket
 * **ids** while the destination folder is named from the bucket's **display name**, and
 * the per-bucket selection never leaves the device that made it (it is a local preference
 * blob — `drive-synced-folders.md` §3.5). So no synced row joins this folder to the bucket
 * that feeds it. That is why this classifier can *name* the device and the folder but
 * cannot offer to switch the backup off; see §3.9 for the slice that would.
 *
 * ⚠ Labels are the **sanitised** segments, so they are what the user sees in Drive rather
 * than necessarily the exact device name — [backupDestinationFolder] replaces path
 * separators on the way in and that is not reversible. Fine for prose ("backed up from
 * Pixel"); not sound as a key, and nothing here uses it as one.
 */
sealed interface BackupDestination {

    /** The device label as it appears in the path. */
    val deviceLabel: String

    /**
     * `/Backup/<device>` — every bucket this device backs up. Deleting it takes all of
     * them, so the copy has to say so.
     */
    data class Device(override val deviceLabel: String) : BackupDestination

    /** `/Backup/<device>/<bucket>` — one backed-up folder. */
    data class Bucket(
        override val deviceLabel: String,
        val bucketLabel: String,
    ) : BackupDestination

    companion object {
        /**
         * Classify [path], or `null` when it is not a backup destination — which includes
         * [BACKUP_ROOT] itself and anything **below** a bucket.
         *
         * ⚠ Deeper paths are deliberately not classified. A file inside a backed-up folder
         * is an ordinary file: deleting one photo is not the "you are deleting a device's
         * backup" event this exists to explain, and warning on it would train the user to
         * dismiss the warning that matters.
         */
        fun of(path: String): BackupDestination? {
            if (path != BACKUP_ROOT && !path.startsWith("$BACKUP_ROOT/")) return null
            val segments = path.removePrefix("$BACKUP_ROOT/")
                .split('/')
                .filter { it.isNotEmpty() }
                .takeIf { path != BACKUP_ROOT }
                ?: return null

            return when (segments.size) {
                1 -> Device(deviceLabel = segments[0])
                2 -> Bucket(deviceLabel = segments[0], bucketLabel = segments[1])
                else -> null
            }
        }
    }
}
