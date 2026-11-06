package app.tenshu.photos.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.ingest.IngestSourceInfo
import app.tenshu.ingest.IngestSettings
import app.tenshu.photos.settings.GridMotionSettings
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.ingest.IngestSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Settings tab (`photos-app.md` S7 / PH-4): what this device is backing up, and a switch per
 * source.
 *
 * ⚠⚠ [readFailed] is not [sources]`.isEmpty()`. "Could not reach Control" and "nothing is being
 * backed up" are different answers, and only one of them should reach the user as an empty screen
 * — the rule the feed's own state machine states, applied to the third screen that can be empty.
 */
data class SettingsUiState(
    val sources: List<IngestSourceInfo> = emptyList(),
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    /**
     * *"Skip photos already in the realm"* (PH-D2). ⚠ Seeded with the preference's own default
     * (**on**) rather than `false`, so the switch never renders in the position that would be a
     * lie about what the drain is doing while the stored row resolves.
     */
    val skipExistingContent: Boolean = true,
    /**
     * *"Play videos in the grid"* (`photos-video-support.md` D2). ⚠ Seeded with the preference's
     * own default (**on**) for the same reason [skipExistingContent] is.
     */
    val playVideosInGrid: Boolean = true,
    /**
     * Folders on this device that Photos could back up but is not (PH-4b).
     *
     * ⚠ Empty where nothing can enumerate them — desktop, where the drain lives in Control, and
     * any build with no `MediaSourceProvider` bound. The section is then **absent**, not an empty
     * list saying "no folders": this device cannot answer the question, which is different from
     * answering "none".
     */
    val addableBuckets: List<BackupBucketOption> = emptyList(),
    val canAddSources: Boolean = false,
) {
    /** True only when a read succeeded and this device genuinely ingests nothing. */
    val nothingBackedUp: Boolean get() = sources.isEmpty() && !loading && !readFailed
}

/** One folder the user can start or stop backing up, as the picker shows it (PH-4b). */
data class BackupBucketOption(
    val id: String,
    val displayName: String,
    val itemCount: Int,
    val selected: Boolean,
)

/**
 * ⚠⚠ `ObjCName` is load-bearing (PIO-D6): `:control-shared-logic` exports a `SettingsViewModel`
 * that `control-ios` already spells, and K/N would rename one of the two to `SettingsViewModel_`.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("PhotosSettingsViewModel")
class SettingsViewModel(
    private val registry: IngestSources,
    /**
     * ⚠ Nullable so a preview or a test can render the screen without a preference service. The
     * switch is then inert rather than absent — the sources list is the screen's subject, and
     * hiding a row because a collaborator is missing hides the feature rather than the fault.
     */
    private val settings: IngestSettings? = null,
    /**
     * How this device enumerates its own folders (PH-4b). ⚠ `null` on any platform where Photos
     * cannot see them — the picker is then not offered at all, rather than offered and empty.
     */
    private val mediaSource: MediaSourceProvider? = null,
    /**
     * The **shared** backup selection, which Drive's drain reads (PH-4b). ⚠⚠ Writing here is what
     * makes Photos able to *add* a source rather than only manage one: the drain and this screen
     * are looking at the same device-scoped rows, so a folder ticked here is backed up by whichever
     * app is running the drain — not recorded in a second place that nothing reads.
     */
    private val backupPreferences: BackupPreferences? = null,
    /**
     * The feed's motion switch (`photos-video-support.md` §5.4). ⚠ Nullable for [settings]'s
     * reason: a preview or a test renders the row inert rather than absent.
     */
    private val gridMotion: GridMotionSettings? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sources = registry.sources()
            _state.value = SettingsUiState(
                sources = sources.orEmpty(),
                loading = false,
                readFailed = sources == null,
                skipExistingContent = settings?.readSkipExistingContent()
                    ?: _state.value.skipExistingContent,
                playVideosInGrid = gridMotion?.readPlayVideosInGrid()
                    ?: _state.value.playVideosInGrid,
                addableBuckets = readBuckets(),
                canAddSources = mediaSource != null && backupPreferences != null,
            )
        }
    }

    /**
     * Flips one source's kill switch.
     *
     * ⚠ Optimistic, then reconciled: the row is synced and the drain may be mid-pass, so the
     * screen shows the new value at once and a refresh replaces it with whatever actually landed.
     * A toggle that waits for a round trip feels broken; one that never reconciles lies.
     */
    fun setEnabled(sourceId: String, enabled: Boolean) {
        _state.value = _state.value.copy(
            sources = _state.value.sources.map {
                if (it.sourceId == sourceId) it.copy(enabled = enabled) else it
            }
        )
        viewModelScope.launch {
            registry.setEnabled(sourceId, enabled)
            refresh()
        }
    }

    /**
     * Flips the ingest-time duplicate skip (PH-D2).
     *
     * ⚠ Same optimistic-then-reconciled shape as [setEnabled], and for the same reason: this
     * writes a synced device row, and a switch that waits for the round trip feels broken.
     *
     * ⚠⚠ It changes what the **drain** does, and the drain is Drive's — the setting is shared
     * (`IngestPreferences`) precisely so this switch is not a second, cosmetic copy of it.
     */
    fun setSkipExistingContent(enabled: Boolean) {
        val store = settings ?: return
        _state.value = _state.value.copy(skipExistingContent = enabled)
        viewModelScope.launch {
            store.setSkipExistingContent(enabled)
            refresh()
        }
    }

    /**
     * Flips the feed's rotating player (`photos-video-support.md` D2) — optimistic, then
     * reconciled, like [setSkipExistingContent]. ⚠ The feed reads the same device row through
     * `GridMotionSettings.observePlayVideosInGrid`, so this switch is the setting, not a copy of it.
     */
    fun setPlayVideosInGrid(enabled: Boolean) {
        val store = gridMotion ?: return
        _state.value = _state.value.copy(playVideosInGrid = enabled)
        viewModelScope.launch {
            store.setPlayVideosInGrid(enabled)
            refresh()
        }
    }

    /**
     * Start or stop backing up one folder.
     *
     * ⚠ Read-modify-write against the stored row, not against the screen's copy: the selection is
     * a synced device row that the drain may also be reading, and writing the screen's list back
     * wholesale would clobber a change made on another surface between the read and the tap.
     */
    fun setBucketSelected(bucketId: String, selected: Boolean) {
        val prefs = backupPreferences ?: return
        _state.value = _state.value.copy(
            addableBuckets = _state.value.addableBuckets.map {
                if (it.id == bucketId) it.copy(selected = selected) else it
            }
        )
        viewModelScope.launch {
            val current = prefs.readSelectedBuckets().toMutableSet()
            if (selected) current += bucketId else current -= bucketId
            prefs.setSelectedBuckets(current.toList())
            refresh()
        }
    }

    private suspend fun readBuckets(): List<BackupBucketOption> {
        val provider = mediaSource ?: return emptyList()
        val prefs = backupPreferences ?: return emptyList()
        return try {
            val selected = prefs.readSelectedBuckets().toSet()
            // ⚠ `includeVideo` follows the stored setting, so the picker lists exactly what the
            // drain would actually walk — a list that offers a video-only folder the drain then
            // skips is a promise the engine does not keep.
            provider.listBuckets(includeVideo = prefs.readVideos())
                .map { BackupBucketOption(it.id, it.displayName, it.itemCount, it.id in selected) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
}
