package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.download

/**
 * A model as the cases written before the list became rows spell it: installed or not, with an
 * update or not. ⚠ Test-only. [models] turns a list of them into what a repository emits, through
 * the production join, so no case hand-builds a row the product could not.
 */
internal data class ManagedModel(
    val modelId: String,
    val name: String,
    val byteSize: Long,
    val capabilityIds: List<String> = emptyList(),
    val licence: String = "",
    val installed: Boolean = false,
    val updateAvailable: Boolean = false,
)

/** The catalog's version in every fixture; an installed copy with an update is one behind. */
internal const val CATALOG_VERSION = 2

internal fun List<ManagedModel>.state(
    catalogUnavailable: Boolean = false,
    stale: Boolean = false,
    fetching: Map<String, ModelBytes?> = emptyMap(),
    last: Map<String, LastDownload> = emptyMap(),
    storeReadOnly: StoreReadOnly? = null,
): ModelsState {
    fun ManagedModel.facts(version: Int) = ModelFacts(modelId, name, byteSize, version, capabilityIds, licence)
    return ModelsState(
        availability = modelsSnapshot(
            installed = filter { it.installed }.map { it.facts(if (it.updateAvailable) CATALOG_VERSION - 1 else CATALOG_VERSION) },
            available = if (catalogUnavailable) null else map { it.facts(CATALOG_VERSION) },
            stale = stale,
            fetching = fetching,
            last = last,
        ),
        serves = if (catalogUnavailable) emptyMap() else associate { it.modelId to it.capabilityIds },
        storeReadOnly = storeReadOnly,
    )
}

internal val ModelRow.installed: Boolean get() = setup is ModelSetup.Held

internal val ModelRow.updateAvailable: Boolean
    get() = (setup as? ModelSetup.Held)?.update is ModelSetup.Update.Available

internal val ModelRow.fetching: Boolean get() = setup.download != null

/** Every row something is in flight for: asked, being fetched, or being removed. */
internal val ModelsUiState.busy: Set<String>
    get() = starting + removing + models.filter { it.fetching }.map { it.modelId }
