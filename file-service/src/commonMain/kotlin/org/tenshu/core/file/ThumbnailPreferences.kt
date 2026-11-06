package org.tenshu.core.file

import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringListAdapter

/**
 * The per-device thumbnail declarations (`drive-synced-thumbnails.md` §15, W-1) — declared in
 * `:file-service` beside [FileSyncPreferences] because they govern which derivatives of a file
 * exist, not how any app shows them.
 *
 * ## ⚠⚠ What a row here may say, and what it may never say
 *
 * A row is **device capability at class granularity**: *"this device renders `XLARGE`"*, *"this
 * device plays `MOTION`"*. It names **no file, no count and no time** — §10.3's rule that a
 * *per-file* demand signal is an access pattern and never syncs is untouched, and it is exactly the
 * line between the two that makes this row shippable where Phase 4's signal was not (§15.1). A
 * future writer that wants to record *which* file was wanted, or *how often*, is building the thing
 * that rule forbids and must not reuse this preference for it.
 */
object ThumbnailPreferences {

    /**
     * The set of `GetStoredThumbnail.CLASS_*` ids this device's apps render (§15.2 D-5), written by
     * the device's own proxy the first time a client names the rung — *the pull is the declaration*
     * — and read by every byte holder in the realm as `wanted(realm)`, the union over all devices'
     * rows.
     *
     * ⚠ Only [DECLARABLE_CLASSES] ever appear here. `LIST` and `LARGE` are wanted everywhere
     * implicitly (every list row renders them, and upload mints them unconditionally), so writing
     * them would be noise a reader has to filter; [declarable] does that filtering in one place.
     */
    val WantedClasses = Preference(
        id = "thumbnails.wantedClasses",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )

    /**
     * The backfill's completion marker (§15.3 D-6): the **hash of the wanted set** — plus the mint
     * format — that this device's last *clean* pass completed for, as a one-element list.
     *
     * ⚠ A hash rather than a boolean, so the pass re-arms itself: a new device declaring a class, a
     * class that did not exist yesterday, or a changed encoder all change the value and the marker
     * stops matching. ⚠⚠ A partial run (quota, budget, a failed mint) never writes it — the pass
     * resumes on its next tick instead.
     */
    val BackfillMarker = Preference(
        id = "thumbnails.backfillMarker",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )

    /**
     * The classes a device may declare, and therefore the only ones the backfill can ever mint.
     * ⚠ The next rung (`XXLARGE`, `XSLARGE` — §15.6) is added **here** as well as to
     * `GetStoredThumbnail`; a class missing from this set is one no device can ask for.
     */
    val DECLARABLE_CLASSES: Set<String> = setOf(
        GetStoredThumbnail.CLASS_XXLARGE,
        GetStoredThumbnail.CLASS_XLARGE,
        GetStoredThumbnail.CLASS_MOTION,
    )

    /**
     * [classes] restricted to [DECLARABLE_CLASSES], in a stable order — the shape every reader
     * wants, and the one the backfill marker hashes.
     *
     * ⚠ `sorted().toSet()`, never `sortedSetOf`: this is `commonMain` and `java.util.SortedSet` is
     * JVM-only, so the latter compiles here and breaks the JS and K/N lanes.
     */
    fun declarable(classes: Iterable<String>): Set<String> =
        classes.filter { it in DECLARABLE_CLASSES }.sorted().toSet()
}
