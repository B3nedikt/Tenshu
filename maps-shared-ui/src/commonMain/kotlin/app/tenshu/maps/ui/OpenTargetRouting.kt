package app.tenshu.maps.ui

import app.tenshu.maps.ui.navigation.Destination
import org.tenshu.core.entity.DataId
import org.tenshu.core.get

/**
 * The place a cross-app open names, or `null` when this target is not Maps' to show
 * (`control-cross-app-open.md` TT-2b).
 *
 * ⚠ Maps is the **fallback** handler for a place — Timeline is preferred, because a cited place
 * almost always came from a moment. Maps is reached only on a device where Timeline is not
 * installed, which is exactly why the candidate list is ordered rather than a single winner.
 */
internal fun destinationForTarget(target: DataId): Destination? {
    val id = target as? DataId.StringDataId ?: return null
    if (id.type.get() != PLACE_TYPE) return null
    return id.keyValue.get().takeIf { it.isNotEmpty() }?.let(Destination::PlaceDetail)
}

private const val PLACE_TYPE = "tenshu/place/place"
