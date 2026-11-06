package app.tenshu.fit.ui

import app.tenshu.fit.ui.navigation.Destination
import org.tenshu.core.entity.DataId
import org.tenshu.core.get

/**
 * The workout a cross-app open names, or `null` when this target is not Fit's to show
 * (`control-cross-app-open.md` TT-2b).
 *
 * ⚠ The type is checked, never assumed: Control routes by type (CO-D2), so a target of any other
 * type means the table and this app disagree — and opening `WorkoutDetail` on someone else's key
 * would render that disagreement as a plausible-looking wrong screen.
 */
internal fun destinationForTarget(target: DataId): Destination? {
    val id = target as? DataId.StringDataId ?: return null
    if (id.type.get() != WORKOUT_TYPE) return null
    return id.keyValue.get().takeIf { it.isNotEmpty() }?.let(Destination::WorkoutDetail)
}

// ⚠ A literal, like `HandlerTable`'s: a UI module must not depend on `:health-service` to know its
// own type id, and `Workout.ID` is pinned against this same string on the owning side.
private const val WORKOUT_TYPE = "tenshu/health/workout"
