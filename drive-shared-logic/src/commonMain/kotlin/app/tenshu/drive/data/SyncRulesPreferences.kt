package app.tenshu.drive.data

import app.tenshu.ingest.DeviceIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.file.client.fileSyncDefaultPolicy
import org.tenshu.file.client.fileSyncMaxLocalBytes
import org.tenshu.file.client.fileSyncRuleSet
import org.tenshu.file.client.setFileSyncDefaultPolicy
import org.tenshu.file.client.setFileSyncMaxLocalBytes
import org.tenshu.file.client.setFileSyncOverrides
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.preference.client.preferenceService

/**
 * Drive's *this-device* view of the file-synchronisation settings
 * (`docs/proposals/drive-offline-online.md` §4). The settings model + storage now
 * live in `:file-service` (`FileSynchronisationSettings`); this is a thin Drive
 * adapter that resolves **this** device's sync identity (via [DeviceIdentity],
 * mirroring [TenshuOfflinePreferences]) and delegates each call to the file-service
 * facade with that device id. The legacy pin set is folded in by the consumer
 * (`SelectionViewModel`) via [SyncRuleSet.withPinnedPaths], not here.
 */
interface SyncRulesPreferences {
    /** Emits this device's effective rule set (default + overrides), re-emitting on change. */
    fun observe(): Flow<SyncRuleSet>

    /** Emits this device's default [SyncPolicy], re-emitting on change. */
    fun observeDefaultPolicy(): Flow<SyncPolicy>

    /** Writes this device's default [SyncPolicy]. No-op if the sync identity is unresolved. */
    suspend fun setDefaultPolicy(policy: SyncPolicy)

    /**
     * Replaces this device's sparse override set. Callers compute the next list from
     * the current one (e.g. via [org.tenshu.core.file.sync.withFolderPolicy]) so this
     * stays a dumb whole-list write. No-op if the sync identity is unresolved.
     */
    suspend fun setOverrides(overrides: List<PathSyncOverride>)

    /**
     * Emits this device's **cache** ceiling in bytes; `0` = no ceiling
     * (`retention-quota-model.md` Face B). ⚠ Bounds evictable cached content only —
     * files kept offline are outside it.
     */
    fun observeMaxLocalBytes(): Flow<Long>

    /** Writes this device's cache ceiling in bytes; `0` clears it. */
    suspend fun setMaxLocalBytes(maxLocalBytes: Long)
}

class TenshuSyncRulesPreferences(
    private val client: TenshuClient,
    private val identity: DeviceIdentity = DeviceIdentity(client),
) : SyncRulesPreferences {

    // Seed the default, then await identity — a read flow that completes while identity is
    // still resolving strands its collector on the default forever (see [DeviceIdentity]).
    override fun observe(): Flow<SyncRuleSet> = flow {
        emit(SyncRuleSet.Default)
        emitAll(client.preferenceService.fileSyncRuleSet(identity.await()))
    }

    override fun observeDefaultPolicy(): Flow<SyncPolicy> = flow {
        emit(SyncPolicy.ONLINE_ONLY)
        emitAll(client.preferenceService.fileSyncDefaultPolicy(identity.await()))
    }

    override suspend fun setDefaultPolicy(policy: SyncPolicy) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setFileSyncDefaultPolicy(id, policy)
    }

    override suspend fun setOverrides(overrides: List<PathSyncOverride>) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setFileSyncOverrides(id, overrides)
    }

    override fun observeMaxLocalBytes(): Flow<Long> = flow {
        emit(FileSyncPreferences.MaxLocalBytes.default)
        emitAll(client.preferenceService.fileSyncMaxLocalBytes(identity.await()))
    }

    override suspend fun setMaxLocalBytes(maxLocalBytes: Long) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setFileSyncMaxLocalBytes(id, maxLocalBytes)
    }
}

/** A no-op reader used where sync identity is unavailable (previews / tests). */
object NoopSyncRulesPreferences : SyncRulesPreferences {
    override fun observe(): Flow<SyncRuleSet> = flowOf(SyncRuleSet.Default)
    override fun observeDefaultPolicy(): Flow<SyncPolicy> = flowOf(SyncPolicy.ONLINE_ONLY)
    override suspend fun setDefaultPolicy(policy: SyncPolicy) = Unit
    override suspend fun setOverrides(overrides: List<PathSyncOverride>) = Unit
    override fun observeMaxLocalBytes(): Flow<Long> =
        flowOf(FileSyncPreferences.MaxLocalBytes.default)
    override suspend fun setMaxLocalBytes(maxLocalBytes: Long) = Unit
}
