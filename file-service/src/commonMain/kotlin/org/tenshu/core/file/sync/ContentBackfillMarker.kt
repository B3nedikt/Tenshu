package org.tenshu.core.file.sync

import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringListAdapter

/**
 * The content-declaration backfill's completion marker (`content-plane-refcount.md` §9.7).
 *
 * ⚠⚠ The preference id (`file.reclamation.backfillComplete`) never changes: renaming it would
 * read every latched device as never having run, and re-walk the fleet for nothing.
 */
object ContentBackfillMarker {

    /**
     * Whether this device has completed the content-declaration backfill
     * (`content-plane-refcount.md` §9.7), and **at which declaration format**.
     *
     * ⚠ A version marker rather than a boolean, and that is the point: if what a declaration must
     * carry changes, bumping [BACKFILL_FORMAT] re-runs the pass instead of leaving every device
     * latched on a description that is no longer sufficient.
     *
     * ⚠⚠ Latched only on a **clean** run. A pass whose walk did not finish, or whose manifests
     * would not resolve, declares what it found and leaves this empty — the pass runs once by
     * design, so a marker written over a degraded run strands whatever it missed for ever.
     *
     * ⚠ DEVICE-scoped: it records this device's own progress. Another device
     * runs its own pass, and declaring the same edges twice is a no-op because the relay holds a
     * set.
     */
    val BackfillComplete = Preference(
        id = "file.reclamation.backfillComplete",
        scope = PreferenceScope.DEVICE,
        default = emptyList<String>(),
        adapter = StringListAdapter,
    )

    /**
     * Bump to make every device re-run the backfill. See [BackfillComplete]. A bump is the only
     * lever that re-arms an already-latched device; each value names what its pass adds
     * (`content-plane-refcount.md` §9.7 onward records why each was spent):
     *
     * - `v2-settled` — re-walks devices that latched over an empty store left by a store wipe and
     *   re-synced their corpus behind the latch.
     * - `v3-relay-durable` — re-walks devices whose declarations the relay accepted but did not
     *   persist.
     * - `v4-full-walk` — the walk enumerates every declaring type, not only Drive files (a
     *   `Syncable(OnDemand)` `Workout.Streams` left undeclared is indistinguishable from an orphan).
     * - `v5-wire-names` — holders are named by their keyed wire name (the walks hand the sync layer a
     *   `DataId`), so a delete's tombstone can undeclare a backfilled edge. ⚠ Re-declaring adds the
     *   correctly named edges; it does not remove stale ones, which §9.14's reconciliation owes.
     * - `v6-realign-described` — re-describes every content from the manifest the relay holds, which
     *   realigns a description left on a different chunk set than its manifest, and re-declares every
     *   live holder's edge. ⚠ Spent only once every emitting device stops re-emitting published
     *   content under fresh keys: a pass that latches before that re-misaligns behind the marker.
     * - `v7-counted-walk` — the walk counts the rows it could not decode, key or resolve
     *   ([DeclarableContent.Skipped]), so a pass's log line says whether it saw the realm. ⇒ ⚠ Read it
     *   before spending the next bump: `from N rows` against what the device should hold, and
     *   `ROWS SKIPPED UNDECLARED` or `PROPERTIES SKIPPED UNDECLARED` at all.
     * - `v8-repair-manifestless` — hands a content the relay holds no manifest for to the device's
     *   repair (`ContentDeclarationBackfill.repairAbsent`). ⚠ The repair needs the UPLOADING device,
     *   so this costs every other device one more walk that hands nothing over.
     *
     * ⚠⚠ Before spending the next bump, check that the relay **keeps** a declare —
     * `sqlite3 -readonly relay.db "SELECT COUNT(*) FROM content_edge"` — and that it **covers** more
     * than one service: `SELECT property_id, COUNT(*) FROM content_edge GROUP BY 1`. A pass that
     * latches against a relay that drops or under-counts declarations is unrecoverable without
     * another bump.
     */
    const val BACKFILL_FORMAT = "v8-repair-manifestless"
}
