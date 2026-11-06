package app.tenshu.control.ui.screens.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.data_type_files
import app.tenshu.control.shared.resources.data_type_workouts
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.file.File
import org.tenshu.core.health.Workout

/**
 * Consumer-owned presentation for a realm-data type: its display label, icon, and ordering.
 *
 * `RealmData` is a fieldless marker (`docs/proposals/core-realm-data.md`) — the schema says
 * only *which* types are realm data (`SchemaProvider.realmDataTypeIds()`); how to label / icon
 * / order them is **presentation**, which is localisable and consumer-specific, so it lives
 * here in the Control UI rather than baked into the wire-shared metamodel. The web apex owns
 * its own equivalent registry.
 *
 * Known types name themselves through `stringResource` (ADR 0016); the derived fallback below
 * cannot, and deliberately so — see [realmDataPresentation].
 */
data class RealmDataPresentation(
    val pluralDisplayName: String,
    val icon: ImageVector,
    val sortOrder: Int,
)

/**
 * Presentation for [typeId]. Unknown types — future first-party or third-party data types not
 * yet given a bespoke entry here — still render a card with a generic icon and a label derived
 * from the type id, sorted last. This preserves the generic-walker invariant (`control-ia.md`
 * §5): a new realm-data type appears in the Data section with **zero** changes to the screen,
 * and only an optional polish pass here to give it a proper name + icon.
 *
 * ⚠ The fallback label is *not* localised, and cannot be: it is derived from a schema id at
 * runtime, so there is no catalogue key to author. That is the honest cost of the
 * zero-changes-to-ship invariant — giving a type a real entry here is exactly what localises
 * it. New entries must go through `stringResource` (ADR 0016 / `docs/agent.md` §19).
 */
@Composable
fun realmDataPresentation(typeId: String): RealmDataPresentation = when (typeId) {
    File.ID -> RealmDataPresentation(
        pluralDisplayName = stringResource(Res.string.data_type_files),
        icon = Icons.Default.Description,
        sortOrder = realmDataSortOrder(typeId),
    )

    Workout.ID -> RealmDataPresentation(
        pluralDisplayName = stringResource(Res.string.data_type_workouts),
        icon = Icons.Default.FitnessCenter,
        sortOrder = realmDataSortOrder(typeId),
    )

    else -> RealmDataPresentation(
        pluralDisplayName = typeId.substringAfterLast('/')
            .replaceFirstChar { it.uppercase() },
        icon = Icons.Default.Dataset,
        sortOrder = realmDataSortOrder(typeId),
    )
}
