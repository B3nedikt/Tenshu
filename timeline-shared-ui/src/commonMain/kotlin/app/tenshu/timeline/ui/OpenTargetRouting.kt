package app.tenshu.timeline.ui

import app.tenshu.timeline.ui.navigation.Destination
import org.tenshu.core.entity.DataId
import org.tenshu.core.get

/**
 * The screen a cross-app open names, or `null` when this target is not Timeline's to show
 * (`control-cross-app-open.md` TT-2b).
 *
 * ⚠⚠ **Timeline is the first app that handles TWO types**, and the type is what picks the screen —
 * never the key's shape. A `placeId` and a `momentId` are both opaque strings, so a router that
 * guessed from the value would open the wrong screen on a plausible-looking id, which is far harder
 * to notice than a tap that refuses.
 *
 * ⚠ Only ever a `StringDataId`: both rows are keyed by one string. A multi-part id is not one of
 * ours whatever its type claims.
 */
internal fun destinationForTarget(target: DataId): Destination? {
    val id = target as? DataId.StringDataId ?: return null
    val key = id.keyValue.get().takeIf { it.isNotEmpty() } ?: return null
    return when (id.type.get()) {
        PLACE_TYPE -> Destination.PlaceDetail(key)
        MOMENT_TYPE -> Destination.MomentDetail(key)
        else -> null
    }
}

// ⚠⚠ Literals, not `Place.ID` / `Moment.ID`: `:timeline-shared-ui` is a UI module and the owning
// services pin their own halves (`HandlerTable` spells the same strings for the same reason). A
// stale spelling here costs a refused tap, never a wrong screen — the `else` branch sees to that.
private const val PLACE_TYPE = "tenshu/place/place"
private const val MOMENT_TYPE = "tenshu/timeline/moment"
