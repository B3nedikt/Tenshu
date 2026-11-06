package app.tenshu.control.ui.screens.data

import org.tenshu.core.file.File
import org.tenshu.core.health.Workout

/**
 * Compose-free display ordering for a realm-data type. Lives in `:control-shared-logic` so
 * [DataViewModel] can order the generic card list without depending on the Compose presentation
 * (`RealmDataPresentation`, which carries an `ImageVector` icon and stays in `:control-shared`).
 * The Compose presentation reuses this so ordering has a single source of truth.
 *
 * Unknown types — future first-party / third-party realm-data types with no bespoke entry — sort
 * last ([Int.MAX_VALUE]), preserving the generic-walker invariant (`control-ia.md` §5): a new type
 * appears in the Data section with zero screen changes.
 */
fun realmDataSortOrder(typeId: String): Int = when (typeId) {
    File.ID -> 100
    Workout.ID -> 200
    else -> Int.MAX_VALUE
}
