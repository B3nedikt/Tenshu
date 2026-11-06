package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OfferedModel
import org.tenshu.inference.InferenceCapability

/**
 * A model as the cases written before the list became rows spell it. ⚠ Test-only: it exists so
 * those cases run **unedited** against the screen as it is now, which is what says the screen
 * still does for them what it did.
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

internal fun ManagedModel.offered() = OfferedModel(modelId, name, modelVersion = 2, downloadBytes = byteSize, onDeviceBytes = byteSize, licence = licence)

/** The row the Android and desktop repository makes for it (`modelsSnapshot`'s cases). */
internal fun ManagedModel.row(
    catalogUnavailable: Boolean = false,
    download: ModelSetup.Download? = null,
    last: ModelSetup.DownloadSaid? = null,
): ModelRow = ModelRow(
    modelId,
    name,
    when {
        installed -> ModelSetup.Held(
            HeldModel(modelId, name, modelVersion = if (updateAvailable) 1 else 2, onDeviceBytes = byteSize, licence = licence),
            ModelSetup.Standing.Ready,
            when {
                download != null -> ModelSetup.Update.Fetching(download)
                catalogUnavailable -> ModelSetup.Update.Unknown
                updateAvailable -> ModelSetup.Update.Available(offered(), last)
                else -> ModelSetup.Update.None
            },
        )
        download != null -> ModelSetup.Fetching(offered(), download)
        else -> ModelSetup.Absent(offered(), last)
    },
)

internal fun listed(
    rows: List<ModelRow>,
    catalog: ModelsAvailability.Catalog = ModelsAvailability.Catalog.READ,
    downloads: ModelsAvailability.Downloads = ModelsAvailability.Downloads.KNOWN,
) = ModelsAvailability.Listed(catalog, downloads, rows)

/**
 * A state spelt the way it was before the list became [ModelsAvailability]: `loading`, a list of
 * models, `catalogUnavailable`. ⚠ A function of the class's name on purpose, so a case written
 * against the old constructor compiles as it stands. `loading` has no default, so `ModelsUiState(ControlModelsCapabilities)`
 * is still the class's own.
 */
@Suppress("TestFunctionName")
internal fun ModelsUiState(
    loading: Boolean,
    models: List<ManagedModel> = emptyList(),
    catalogUnavailable: Boolean = false,
    storeReadOnly: StoreReadOnly? = null,
    consents: List<CapabilityConsent> = ControlModelsCapabilities.features.map { CapabilityConsent(it) },
    featureOffer: InferenceCapability? = null,
    indexProgress: IndexProgressState = IndexProgressState(),
    indexingPaused: Boolean = false,
    startingIndex: Boolean = false,
    otherDevices: Map<InferenceCapability, List<OtherDeviceConsent>> = emptyMap(),
    otherDevicesKnown: Boolean = false,
    consentsRead: Boolean = true,
): ModelsUiState = ModelsUiState(
    capabilities = ControlModelsCapabilities,
    availability = if (loading) {
        ModelsAvailability.Loading
    } else {
        listed(
            // ⚠ With no catalog only what is installed is a row: that is all the repository can
            // know of, and a case must not assert a shape it never emits.
            models.filter { it.installed || !catalogUnavailable }.map { it.row(catalogUnavailable) },
            if (catalogUnavailable) ModelsAvailability.Catalog.UNAVAILABLE else ModelsAvailability.Catalog.READ,
        )
    },
    storeReadOnly = storeReadOnly,
    consents = consents,
    consentsRead = consentsRead,
    featureOffer = featureOffer,
    indexProgress = indexProgress,
    indexingPaused = indexingPaused,
    startingIndex = startingIndex,
    otherDevices = otherDevices,
    otherDevicesKnown = otherDevicesKnown,
)

/** The remove dialog's text as Control says it on Android and desktop. */
internal fun removeConfirmationText(model: ManagedModel): String =
    removeConfirmationText(model.row(), ControlModelsCapabilities.spaceReturns)
