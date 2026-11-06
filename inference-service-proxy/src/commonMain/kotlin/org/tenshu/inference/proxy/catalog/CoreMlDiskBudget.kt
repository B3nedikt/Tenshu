package org.tenshu.inference.proxy.catalog

/**
 * How much free space a Core ML checkpoint needs **before its download starts**
 * (`ios-on-device-inference.md` §3.4, *Disk*; §14.11).
 *
 * ⚠⚠ The **additional** space, compared with free bytes: free space already excludes whatever is
 * installed, so an update is not held to its total peak.
 *
 * ⚠⚠ **A first install and an update need the same.** The space the old generation's compiled
 * directory would give back is not credited until a phone has shown how much of it returns (the
 * deletion experiment, §9.7); crediting it would admit a phone that then runs out during the
 * prepare step.
 *
 * ⚠⚠ **One estimate with the cache's own check** (§14.17.5). `CoreMlModelCache` asks, before each
 * tower, for [towerNeed] of that tower's archive free; this budget asks for [towerNeed] of the
 * whole download besides the measured figure, so a download it admits is never refused at its
 * second tower for room it did not ask for. It asked for the measured prepare alone, which is
 * less: a phone with free space in a band of about 115 MB above it passed the download and was
 * refused at the text tower, vision having been prepared first.
 */
object CoreMlDiskBudget {

    /** The download the figures below were read for: both archives and the vocabulary [M: §3.4]. */
    const val MEASURED_DOWNLOAD_BYTES: Long = 743_400_000L

    /**
     * What the prepare step took in free space for that download, on an iPhone 17e under iOS
     * 26.6.1: 555.0 MB for the vision tower and 1 281.4 MB for the text tower [M: §3.4, §9.7].
     * ⚠ The space the first open takes is counted as **not returned**.
     */
    const val MEASURED_PREPARE_BYTES: Long = 1_836_400_000L

    /**
     * ⚠⚠ **Placeholder: 256 MiB.** §3.4's figures carry no margin ("the check adds its own") and
     * nothing measured says how large it must be: one phone, one OS build, two readings a tower
     * that agree within 1.3 MB.
     *
     * **Replaced by:** the spread of the prepare step's free-space readings over more than one
     * phone and OS build, plus whatever a phone must keep free for iOS itself to go on working
     * through a prepare (neither exists; listed in §14.14).
     */
    const val PLACEHOLDER_MARGIN_BYTES: Long = 256L shl 20

    /**
     * What the cache asks to have free before it prepares one tower, as a multiple of that tower's
     * archive. ⚠ One phone's reading, not a rule: on an iPhone 17e the steps took 3.0 × the
     * vision archive and 2.3 × the text archive in free space, most of it during the first open
     * (§9).
     */
    const val TOWER_SPACE_FACTOR: Long = 3

    /** What `CoreMlModelCache` asks to have free before it prepares a tower of [archiveBytes]. */
    fun towerNeed(archiveBytes: Long): Long = archiveBytes * TOWER_SPACE_FACTOR

    /**
     * What preparing a checkpoint of [downloadBytes] takes, scaled from the measured one. ⚠ A
     * scale and not the bare constant, so a later export of another size is not budgeted with
     * this one's figure; for the measured download it is exactly [MEASURED_PREPARE_BYTES].
     */
    fun prepareBytes(downloadBytes: Long): Long {
        if (downloadBytes <= 0L) return 0L
        // Rounded up. ⚠ In two steps: the product of two byte counts of this size overflows a Long.
        val whole: Long = downloadBytes / MEASURED_DOWNLOAD_BYTES
        val rest: Long = downloadBytes % MEASURED_DOWNLOAD_BYTES
        val scaledRest: Long = (rest * MEASURED_PREPARE_BYTES + MEASURED_DOWNLOAD_BYTES - 1) / MEASURED_DOWNLOAD_BYTES
        return whole * MEASURED_PREPARE_BYTES + scaledRest
    }

    /**
     * Free bytes a download of a checkpoint of [entryBytes] needs, when [remainingDownloadBytes]
     * of it are still to be fetched: what is left to fetch, the whole prepare step, the margin.
     *
     * ⚠⚠ The prepare step as the larger of the measured figure and the cache's own per-tower
     * checks summed over the whole download ([towerNeed] of [entryBytes], which holds every
     * tower's archive). Each tower's check then finds what it asks for, in either order, as long
     * as no tower before it took more than its own [towerNeed]; only a disk that filled in between
     * refuses it.
     */
    fun additionalNeed(entryBytes: Long, remainingDownloadBytes: Long): Long =
        remainingDownloadBytes + maxOf(prepareBytes(entryBytes), towerNeed(entryBytes)) + PLACEHOLDER_MARGIN_BYTES
}
