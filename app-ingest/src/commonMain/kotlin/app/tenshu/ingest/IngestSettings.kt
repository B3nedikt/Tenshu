package app.tenshu.ingest

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.client.setForDevice

/**
 * Read and write the ingest policy both apps share (`photos-app.md` PH-6).
 *
 * ⚠ An interface so a screen's tests do not need a running Control, matching [IngestSources].
 */
interface IngestSettings {
    /** Screen read — seeded so a UI has something to render while identity resolves. */
    fun observeSkipExistingContent(): Flow<Boolean>

    /**
     * One-shot read, for the drain's gate.
     *
     * ⚠⚠ Not `.first()` on the flow above. The seeded emission is a **placeholder**, and reading
     * it as an answer is exactly how Synced Folders came to enumerate an empty bucket selection on
     * every drain — see [IngestSourceRegistry] and `BackupPreferences`' own warning.
     */
    suspend fun readSkipExistingContent(): Boolean

    suspend fun setSkipExistingContent(enabled: Boolean)
}

class IngestSettingsStore(
    private val client: TenshuClient,
    private val identity: DeviceIdentity = DeviceIdentity(client),
) : IngestSettings {

    override fun observeSkipExistingContent(): Flow<Boolean> = flow {
        emit(IngestPreferences.SkipExistingContent.default)
        emitAll(
            client.preferenceService.readForDevice(
                IngestPreferences.SkipExistingContent,
                identity.await(),
            )
        )
    }

    /**
     * ⚠ Falls back to the declared default (**on**) when this device's identity has not resolved.
     * That is the safe direction: the fallback makes the drain *ask the realm* about each candidate,
     * and a realm it cannot read defers the candidate (`photos-app.md` §40) — never skips it, never
     * re-uploads it. The unsafe direction would be a fallback that skipped without asking.
     */
    override suspend fun readSkipExistingContent(): Boolean {
        val id = identity.awaitOrNull() ?: return IngestPreferences.SkipExistingContent.default
        return client.preferenceService.readOnceForDevice(IngestPreferences.SkipExistingContent, id)
    }

    override suspend fun setSkipExistingContent(enabled: Boolean) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setForDevice(IngestPreferences.SkipExistingContent, enabled, id)
    }
}
