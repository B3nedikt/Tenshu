package org.tenshu.place.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.time.UnixInstant
import org.tenshu.sync.common.SemanticMerger

/**
 * Converges two devices' versions of one [Place]
 * (`docs/proposals/timeline-recognition-and-place-naming.md` §9.2 / F5).
 *
 * ⚠⚠ **This exists because `Place` on last-writer-wins loses user data.** The guard that stops a
 * blank capture erasing a user's name (`TimelineImportWriter`, §4.4) reads the **local** store — so a
 * peer that captures a visit *before* it has pulled your rename legitimately writes a blank label,
 * and under LWW that newer whole row replaced the renamed one on every device. The conflict is
 * invisible to the writer by construction; the only place that can see both sides is here.
 *
 * ⚠⚠ **A merger only runs when the incoming block is strictly newer** (`SyncProcessor` gates the
 * `ConflictStrategy.Merge` branch on `isNewer`; an older block is dropped whole). So this is *not* a
 * commutative CRDT join — it is "the newer state folds the older one in". Every rule below is
 * therefore written **side-independently** ("the user label wins wherever it is", "never blank a
 * non-blank") rather than "keep mine" / "take theirs", or the outcome would depend on which device
 * happened to pull first. ⚠ Where a rule *does* prefer `incoming` — two user labels, the accuracy —
 * that is "the newer observation wins", which is well defined precisely because of the gate above.
 *
 * The field rules, and why each is the way round it is:
 * - **label / labelSource** — a `user` label beats a `geocoded` one on either side; a non-blank beats
 *   a blank; between two `user` labels the newer (incoming) wins, since the user changed their mind.
 *   This is §4.4's local guard, restated where it can actually see the conflict.
 * - **city / countryCode** — never blank a non-blank (the enricher's own rule).
 * - **accuracyM** — the **newer** non-zero reading; `0` means "unknown" and loses. ⚠ Not the smallest:
 *   since §32.10 this field is "how well we know where this place is", not "fix quality", so keeping
 *   the minimum froze every place at the most optimistic claim ever made about it (§32.16).
 * - **capturedAt** — the **earliest**, so the field keeps meaning "when we first saw this place"
 *   rather than "when the last device happened to write".
 *
 * The *merge pointer* is not here: it lives in its own [org.tenshu.core.place.PlaceMerge] row with
 * its own merger, because `Place` rides as an operation parameter and so cannot gain a field at all
 * until E2's nested reshapes land (§9.4 as-built).
 */
class PlaceMerger : SemanticMerger {
    override val typeId: String = Place.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val existingLabel = string(existing, Place.Fields.LABEL)
        val incomingLabel = string(incoming, Place.Fields.LABEL)
        val existingSource = string(existing, Place.Fields.LABEL_SOURCE)
        val incomingSource = string(incoming, Place.Fields.LABEL_SOURCE)

        val (label, labelSource) = mergeLabel(
            existingLabel = existingLabel,
            existingSource = existingSource,
            incomingLabel = incomingLabel,
            incomingSource = incomingSource,
        )

        return Entity.create<Place>(
            context = executionContext.context,
            composite = Place.create(
                context = executionContext.context,
                placeId = string(incoming, Place.Fields.PLACE_ID),
                latE7 = int(incoming, Place.Fields.LAT_E7),
                lngE7 = int(incoming, Place.Fields.LNG_E7),
                capturedAt = earliest(existing, incoming),
                label = label,
                labelSource = labelSource,
                city = preferNonBlank(string(incoming, Place.Fields.CITY), string(existing, Place.Fields.CITY)),
                countryCode = preferNonBlank(
                    string(incoming, Place.Fields.COUNTRY_CODE),
                    string(existing, Place.Fields.COUNTRY_CODE),
                ),
                accuracyM = mergedAccuracy(
                    int(existing, Place.Fields.ACCURACY_M).toInt(),
                    int(incoming, Place.Fields.ACCURACY_M).toInt(),
                ),
            ),
        )
    }

    /** Visible for tests — the rule the ⚠⚠ in the class KDoc is about. */
    internal fun mergeLabel(
        existingLabel: String,
        existingSource: String,
        incomingLabel: String,
        incomingSource: String,
    ): Pair<String, String> {
        val existingIsUser = LabelSource.isUser(existingSource) && existingLabel.isNotBlank()
        val incomingIsUser = LabelSource.isUser(incomingSource) && incomingLabel.isNotBlank()
        return when {
            // Two user labels: the newer state wins — the user renamed it again.
            incomingIsUser -> incomingLabel to LabelSource.USER
            existingIsUser -> existingLabel to LabelSource.USER
            incomingLabel.isNotBlank() -> incomingLabel to incomingSource
            existingLabel.isNotBlank() -> existingLabel to existingSource
            else -> "" to LabelSource.GEOCODED
        }
    }

    /**
     * The place's positional uncertainty: **the newer measurement**, with `0` = unknown losing.
     *
     * ### ⚠⚠ Why this is not `minOf` any more (§32.16)
     *
     * It was, and the rule made sense while `accuracyM` meant *fix quality* — a tighter fix genuinely
     * is a better one, so keeping the smallest reading kept the best evidence. **§32.10 changed what
     * the field means**: it is now the worse of fix quality and cluster spread, i.e. *how well we know
     * where this place is*. Under that meaning `minOf` keeps the most **optimistic** claim ever made
     * about a place, permanently, and every consumer fails dangerously in that direction —
     * `PlaceProximity.radiusFor` folds too little (one venue becomes several rows),
     * `PlaceLabelEnricher` takes the name-a-venue branch on a dwell it should have called by street or
     * stop, and §31's trajectory tightening reads a confidence it has not earned.
     *
     * That is the cross-device half of the reported bar row: a row minted at ±14 m under the old rule
     * could not be corrected by any later, honest ±128 m measurement, because `min` refused it.
     *
     * ⚠ **And `maxOf` is not the answer either** — it is the same ratchet pointing the other way. One
     * bad indoor visit would coarsen a well-known venue forever and cost it its name. Both extremes
     * are absorbing states; a measurement should not have one.
     *
     * ⚠ Side-independent as this class requires, and it follows the **precedent already set by
     * [mergeLabel]**: between two claims of the same kind, the newer state wins, because it is the
     * later observation of a thing that can genuinely change. A merger only ever runs on a *strictly
     * newer* incoming block (`SyncProcessor` gates it), so "incoming" is a well-defined "newer", not a
     * "theirs" — which is exactly the distinction the class KDoc insists on.
     */
    internal fun mergedAccuracy(existing: Int, incoming: Int): Int = when {
        incoming > 0 -> incoming
        else -> existing.coerceAtLeast(0)
    }

    private fun preferNonBlank(first: String, second: String): String =
        if (first.isNotBlank()) first else second

    private fun earliest(existing: Data, incoming: Data): UnixInstant {
        val a = timestamp(existing)
        val b = timestamp(incoming)
        return if (a.toEpochMilliseconds() <= b.toEpochMilliseconds()) a else b
    }

    private fun string(data: Data, fieldId: String): String = when (data) {
        is Entity<*> -> data.getFieldValue<TString>(fieldId).get()
        is Composite -> data.getField<TString>(fieldId).get()
        else -> ""
    }

    private fun int(data: Data, fieldId: String): Long = when (data) {
        is Entity<*> -> data.getFieldValue<TInteger>(fieldId).get()
        is Composite -> data.getField<TInteger>(fieldId).get()
        else -> 0L
    }

    private fun timestamp(data: Data): UnixInstant = when (data) {
        is Entity<*> -> data.getFieldValue(Place.Fields.CAPTURED_AT)
        is Composite -> data.getField(Place.Fields.CAPTURED_AT)
        else -> error("Place has no ${Place.Fields.CAPTURED_AT}")
    }
}
