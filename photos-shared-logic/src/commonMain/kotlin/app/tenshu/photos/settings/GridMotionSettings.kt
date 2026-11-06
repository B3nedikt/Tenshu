package app.tenshu.photos.settings

import app.tenshu.ingest.DeviceIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.client.setForDevice

/**
 * Photos' own preferences — the ones no other app reads (contrast `IngestPreferences`, which is
 * shared with Drive's drain and therefore lives in `:app-ingest`).
 */
object PhotosPreferences {

    /**
     * *"Play videos in the grid"* — the one switch behind the feed's rotating player
     * (`photos-video-support.md` §5.4 / D2). **On by default**, and deliberately **not** a policy
     * matrix: no Wi-Fi rule, no battery rule. The rotation puts at most one ~90 KB clip in flight
     * at any moment regardless of library size, which is a bounded cost that does not vary, so the
     * only honest knob is the taste one — for people who simply dislike motion.
     *
     * ⚠ DEVICE-scoped: a phone the user watches in bed and a desktop on a desk are different
     * questions, and the cost, small as it is, is this device's.
     */
    val PlayVideosInGrid = Preference(
        id = "photos.grid.playVideos",
        scope = PreferenceScope.DEVICE,
        default = true,
        adapter = BooleanAdapter,
    )
}

/** The feed's motion switch, read and written on this device's own row. */
interface GridMotionSettings {

    /** The stored value, then every change — declared default first, so a screen has something at once. */
    fun observePlayVideosInGrid(): Flow<Boolean>

    suspend fun readPlayVideosInGrid(): Boolean

    suspend fun setPlayVideosInGrid(enabled: Boolean)
}

/** The `IngestSettingsStore` shape, for the same reasons it has that shape. */
class GridMotionSettingsStore(
    private val client: TenshuClient,
    private val identity: DeviceIdentity = DeviceIdentity(client),
) : GridMotionSettings {

    override fun observePlayVideosInGrid(): Flow<Boolean> = flow {
        emit(PhotosPreferences.PlayVideosInGrid.default)
        emitAll(
            client.preferenceService.readForDevice(
                PhotosPreferences.PlayVideosInGrid,
                identity.await(),
            )
        )
    }

    /** ⚠ Falls back to the declared default (**on**) while this device's identity has not resolved. */
    override suspend fun readPlayVideosInGrid(): Boolean {
        val id = identity.awaitOrNull() ?: return PhotosPreferences.PlayVideosInGrid.default
        return client.preferenceService.readOnceForDevice(PhotosPreferences.PlayVideosInGrid, id)
    }

    override suspend fun setPlayVideosInGrid(enabled: Boolean) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setForDevice(PhotosPreferences.PlayVideosInGrid, enabled, id)
    }
}
