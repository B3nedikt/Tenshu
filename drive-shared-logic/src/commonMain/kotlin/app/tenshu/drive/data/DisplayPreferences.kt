package app.tenshu.drive.data

import app.tenshu.ingest.DeviceIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.setForDevice

/**
 * Drive's *this-device* view of the folder-listing display preferences — the sort
 * selection and the list/grid view mode (`docs/proposals/drive-listing-sort-display.md`
 * §6). A thin adapter over the preference service (the [DrivePreferences] DEVICE
 * rows), symmetric with [SyncRulesPreferences]: it resolves this device's sync
 * identity via [DeviceIdentity] and reads/writes the per-device rows so the choices
 * survive navigation and restart.
 *
 * ⚠ The sort is **not nullable**. It was, and `null` meant natural (store) order — a second
 * listing mode with its own repository path. SP-6 of `drive-sorted-folder-paging.md` retired that
 * mode (D5): every listing is sorted, and a device that has chosen nothing gets
 * [FileSort.Default].
 */
interface DisplayPreferences {
    /** Emits this device's persisted sort, re-emitting on change. Defaults to [FileSort.Default]. */
    fun observeSort(): Flow<FileSort>

    /** Persists this device's sort. No-op if identity unresolved. */
    suspend fun setSort(sort: FileSort)

    /** Emits this device's persisted view mode, re-emitting on change. */
    fun observeViewMode(): Flow<FileViewMode>

    /** Persists this device's view mode. No-op if identity unresolved. */
    suspend fun setViewMode(mode: FileViewMode)
}

class TenshuDisplayPreferences(
    private val client: TenshuClient,
    private val identity: DeviceIdentity = DeviceIdentity(client),
) : DisplayPreferences {

    // ⚠ Seed FIRST, then await identity — never `emit(default); return`. A read flow that
    // completes while identity is still resolving strands the screen on the default for the
    // rest of the session (see [DeviceIdentity]).
    override fun observeSort(): Flow<FileSort> = flow {
        emit(DrivePreferences.ListSort.default.decodeSort())
        emitAll(
            client.preferenceService.readForDevice(DrivePreferences.ListSort, identity.await())
                .map { it.decodeSort() }
                // readForDevice is silent until a row exists (core-preference-service
                // §6.5); seed the default so a cold device emits promptly.
                .onStart { emit(DrivePreferences.ListSort.default.decodeSort()) }
        )
    }

    override suspend fun setSort(sort: FileSort) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setForDevice(DrivePreferences.ListSort, sort.encode(), id)
    }

    override fun observeViewMode(): Flow<FileViewMode> = flow {
        emit(DrivePreferences.ListViewMode.default.decodeViewMode())
        emitAll(
            client.preferenceService.readForDevice(DrivePreferences.ListViewMode, identity.await())
                .map { it.decodeViewMode() }
                .onStart { emit(DrivePreferences.ListViewMode.default.decodeViewMode()) }
        )
    }

    override suspend fun setViewMode(mode: FileViewMode) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setForDevice(DrivePreferences.ListViewMode, mode.name, id)
    }
}

/** A no-op used where sync identity is unavailable (previews / tests / iOS). */
object NoopDisplayPreferences : DisplayPreferences {
    override fun observeSort(): Flow<FileSort> = flowOf(FileSort.Default)
    override suspend fun setSort(sort: FileSort) = Unit
    override fun observeViewMode(): Flow<FileViewMode> = flowOf(FileViewMode.LIST)
    override suspend fun setViewMode(mode: FileViewMode) = Unit
}

// ── codec: FileSort / FileViewMode <-> the stored String (DrivePreferences) ──
//
// `"<KEY>:<asc|desc>"`. An absent, empty or malformed value decodes to the safe
// default rather than throwing — [FileSort.Default] for the sort, list for the view mode.
//
// ⚠ `""` used to decode to `null`, meaning natural order. SP-6 retired that mode; it now means
// "nothing chosen" and lands on the same default a fresh device gets (D6).

internal fun FileSort.encode(): String =
    "${key.name}:${if (descending) "desc" else "asc"}"

internal fun String.decodeSort(): FileSort {
    if (isBlank()) return FileSort.Default
    val parts = split(":")
    val key = runCatching { FileSortKey.valueOf(parts[0]) }.getOrNull() ?: return FileSort.Default
    return FileSort(key, descending = parts.getOrNull(1) == "desc")
}

internal fun String.decodeViewMode(): FileViewMode =
    runCatching { FileViewMode.valueOf(this) }.getOrNull() ?: FileViewMode.LIST
