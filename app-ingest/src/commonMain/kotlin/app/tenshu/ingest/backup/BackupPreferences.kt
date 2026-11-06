package app.tenshu.ingest.backup

import kotlinx.coroutines.CancellationException
import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.ingest.DeviceIdentity
import app.tenshu.ingest.IngestPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.getOrNull
import org.tenshu.identity.client.identityService
import org.tenshu.identity.deviceName
import org.tenshu.preference.ControlPreferences
import org.tenshu.preference.Preference
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.readForDevice
import org.tenshu.preference.client.readOnce
import org.tenshu.preference.client.readOnceForDevice
import org.tenshu.preference.client.readOnceForDeviceOrNull
import org.tenshu.preference.client.set
import org.tenshu.preference.client.setForDevice

/**
 * This device's Synced Folders auto-backup settings + per-bucket cursors
 * (`docs/proposals/drive-synced-folders.md` §4.2), read/written through the
 * preference service.
 *
 * Mirrors [TenshuOfflinePreferences] verbatim (§9): the underlying preferences
 * are [org.tenshu.preference.PreferenceScope.DEVICE]-scoped, so every read/write
 * is keyed by this device's sync identity, resolved via [DeviceIdentity] and
 * cached. Device scope is load-bearing — a bucket id / directory path is a
 * device-local identity that must not roam (§3.1).
 */
interface BackupPreferences {
    fun observeEnabled(): Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)

    /** The user's explicitly selected source buckets (opaque ids). */
    fun observeSelectedBuckets(): Flow<List<String>>
    suspend fun setSelectedBuckets(ids: List<String>)

    /** All known Control-owned desktop grants, independent of selection state. */
    fun observeGrantedBuckets(): Flow<List<String>>
    suspend fun readGrantedBuckets(): List<String>
    suspend fun addGrantedBucket(id: String)

    /** Remove one Control-owned desktop grant from the known-folder set. */
    suspend fun removeGrantedBucket(id: String) = Unit

    /** Remember a desktop grant and select it without exposing its read-modify-write to the shell. */
    suspend fun addGrantedBucketAndSelect(id: String) {
        addGrantedBucket(id)
        val selected = readSelectedBuckets()
        if (id !in selected) setSelectedBuckets(selected + id)
    }

    // ── One-shot reads ──────────────────────────────────────────────────────────
    //
    // ⚠⚠ Every `observe*()` above is a **screen** read: it emits a seeded default
    // immediately so a UI has something to render while this device's sync identity
    // resolves, then switches to the stored row (see [observe] in the implementation and
    // [DeviceIdentity]). That makes `.first()` on them return the **placeholder**, always
    // — which is exactly how Synced Folders came to enumerate an empty selection on every
    // drain and never upload anything. Anything deciding *once* — a background worker's
    // gate, a drain's inputs, a read-modify-write — must use the accessors below, which
    // read the row itself and fall back to the declared default only when it is absent.

    /** Whether auto-backup is on, right now. */
    suspend fun readEnabled(): Boolean

    /**
     * [readEnabled] that can say **"could not read"** (`null`): this device's identity did not
     * resolve, or the settings could not be read from the proxy.
     *
     * ⚠⚠ What anything that *cancels* on "off" must use. [readEnabled] answers an unreadable row
     * with the default, `false` — so a WorkManager cold start with Control dead or slow read as
     * "backup switched off" and cancelled every backup job. Only a stored `false` is an answer.
     * ⚠ Defaulted to [readEnabled], so a fake that cannot fail needs no change.
     */
    suspend fun readEnabledOrNull(): Boolean? = readEnabled()

    /** The selected source buckets, right now. */
    suspend fun readSelectedBuckets(): List<String>

    /** The SAF document-tree roots, right now. */
    suspend fun readDocumentTreeUris(): List<String>

    /** Whether video items are included, right now. */
    suspend fun readVideos(): Boolean

    /** Whether backup is restricted to unmetered networks, right now. */
    suspend fun readWifiOnly(): Boolean

    /** Whether backup is restricted to charging, right now. */
    suspend fun readChargingOnly(): Boolean

    /** Whether the new-folder prompt is enabled, right now. */
    suspend fun readBackupNewFolders(): Boolean

    /**
     * The SAF document-tree roots added for backup (Phase 3b) — the selected
     * "buckets" of the `drive.document-tree` source. Android-only; empty elsewhere.
     */
    fun observeDocumentTreeUris(): Flow<List<String>>

    /** Add one persisted `OPEN_DOCUMENT_TREE` URI to the backup set (idempotent). */
    suspend fun addDocumentTreeUri(uri: String)

    /** Remove one document-tree URI from the backup set (stops backing it up). */
    suspend fun removeDocumentTreeUri(uri: String)

    /** Whether to notify about gallery folders that aren't backed up yet
     *  (`drive-synced-folders-new-folder-prompt.md`). */
    fun observeBackupNewFolders(): Flow<Boolean>
    suspend fun setBackupNewFolders(enabled: Boolean)

    /** One-shot read of the new-folder prompt's known-set. Empty means "never
     *  scanned" — the scan seeds a baseline rather than announcing everything. */
    suspend fun readKnownBuckets(): Set<String>

    /** Union [ids] into the known-set (idempotent read-modify-write). */
    suspend fun addKnownBuckets(ids: Set<String>)

    fun observeVideos(): Flow<Boolean>
    suspend fun setVideos(enabled: Boolean)

    /**
     * "Back up over Wi-Fi only". **Folded into the generic, realm-scoped
     * background-data policy** (`ControlPreferences.WifiOnly`,
     * drive-synced-folders-phase2.md §2): one shared switch governs both
     * background sync and every ingest source, set once for the realm — no
     * Drive-local duplicate. (Charging, below, stays a backup-specific device
     * option — the generic policy has no charging dimension.)
     */
    fun observeWifiOnly(): Flow<Boolean>
    suspend fun setWifiOnly(enabled: Boolean)

    fun observeChargingOnly(): Flow<Boolean>
    suspend fun setChargingOnly(enabled: Boolean)

    /**
     * The user pressed **Stop** on a running backup (ST-2b). A *pause*, not an off switch: the
     * queue is remembered and Resume picks it up. See [IngestPreferences.BackupPausedByUser] for
     * why this is not just `enabled = false`.
     */
    fun observePausedByUser(): Flow<Boolean>
    suspend fun setPausedByUser(paused: Boolean)
    suspend fun readPausedByUser(): Boolean

    /**
     * One-shot read of one source's per-bucket cursor map (§3.5), keyed by bucket id
     * (the `sourceId` namespace prefix is stripped). Cursors are namespaced by
     * [sourceId] in the stored blob so two sources can't collide on a shared bucket
     * key (Phase 3a — the engine drives multiple sources).
     */
    suspend fun readCursors(sourceId: String): Map<String, String>

    /** Advance one (source, bucket) cursor; a read-modify-write over the cursor blob. */
    suspend fun setCursor(sourceId: String, bucketId: String, cursor: String)

    /**
     * Forget one (source, bucket) cursor, so the next drain enumerates that bucket from the
     * beginning again (§3.9).
     *
     * ⚠⚠ **This is the only way to undo an upload decision, and without it the model is
     * one-directional.** The cursor is a monotonic *watermark*, not a ledger: it can say "I
     * have got this far", never "those were deleted on purpose" or "do them again". So a
     * destination folder deleted from any device could never be rebuilt — the drain kept
     * finding zero new items and reporting `UP_TO_DATE` forever, which looks exactly like a
     * broken feature and was reported as one.
     *
     * ⚠ Re-scanning is **cheap when the files are still there**: `uploadForBackup` resolves a
     * destination collision to [BackupUploadOutcome.Skipped] (the §3.5 backstop), so a reset
     * costs one round trip per item and re-uploads only what is genuinely missing. It is not a
     * re-upload of the bucket.
     */
    suspend fun clearCursor(sourceId: String, bucketId: String)

    /**
     * A stable, collision-free label for this device, for the
     * `/Backup/<device>/…` destination root (§4.5): the friendly device name
     * (e.g. "Pixel 8") from the synced device registry, falling back to the raw
     * sync `deviceId` until that name resolves. `null` only when the sync identity
     * itself isn't resolved yet.
     */
    suspend fun deviceLabel(): String?
}

class TenshuBackupPreferences(
    private val client: TenshuClient,
    private val identity: DeviceIdentity = DeviceIdentity(client),
) : BackupPreferences {

    // Retries across the startup race instead of silently no-op'ing (see [DeviceIdentity]).
    private suspend fun deviceId(): String? = identity.awaitOrNull()

    // Seed the fallback, then await identity — a read flow that completes while identity is
    // still resolving strands its collector on the fallback forever (see [DeviceIdentity]).
    // ⚠ The seed is for **screens**. It means `.first()` on anything built from this returns
    // the fallback rather than the stored row; one-shot callers use [readOnce] below.
    private fun <T> observe(preference: Preference<T>, fallback: T): Flow<T> = flow {
        emit(fallback)
        emitAll(client.preferenceService.readForDevice(preference, identity.await()))
    }

    /**
     * The unseeded twin of [observe]: this device's stored row, once. Falls back to
     * [Preference.default] when the row is absent — and to [fallback] when the device's
     * identity has not resolved within [DeviceIdentity.awaitOrNull]'s window, which is the
     * one case where "we cannot know yet" is genuinely indistinguishable from "unset".
     */
    private suspend fun <T> readOnce(preference: Preference<T>, fallback: T): T {
        val id = deviceId() ?: return fallback
        return client.preferenceService.readOnceForDevice(preference, id)
    }

    private suspend fun <T> set(preference: Preference<T>, value: T) {
        val id = identity.awaitOrNull() ?: return
        client.preferenceService.setForDevice(preference, value, id)
    }

    override fun observeEnabled(): Flow<Boolean> =
        observe(IngestPreferences.AutoBackupEnabled, false)

    override suspend fun setEnabled(enabled: Boolean) =
        set(IngestPreferences.AutoBackupEnabled, enabled)

    override suspend fun readEnabled(): Boolean =
        readOnce(IngestPreferences.AutoBackupEnabled, false)

    override suspend fun readEnabledOrNull(): Boolean? {
        val id = deviceId() ?: return null
        // ⚠ Reaching the service can throw too (no proxy bound yet) — that is "could not read".
        return try {
            client.preferenceService.readOnceForDeviceOrNull(IngestPreferences.AutoBackupEnabled, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    override fun observeSelectedBuckets(): Flow<List<String>> =
        observe(IngestPreferences.BackupBuckets, emptyList())

    override suspend fun setSelectedBuckets(ids: List<String>) =
        set(IngestPreferences.BackupBuckets, ids)

    override suspend fun readSelectedBuckets(): List<String> =
        readOnce(IngestPreferences.BackupBuckets, emptyList())

    override fun observeGrantedBuckets(): Flow<List<String>> =
        observe(IngestPreferences.BackupGrantedBuckets, emptyList())

    override suspend fun readGrantedBuckets(): List<String> =
        readOnce(IngestPreferences.BackupGrantedBuckets, emptyList())

    override suspend fun addGrantedBucket(id: String) {
        if (id.isBlank()) return
        val device = deviceId() ?: return
        val current = client.preferenceService
            .readOnceForDevice(IngestPreferences.BackupGrantedBuckets, device)
        if (id in current) return
        set(IngestPreferences.BackupGrantedBuckets, current + id)
    }

    override suspend fun removeGrantedBucket(id: String) {
        if (id.isBlank()) return
        val device = deviceId() ?: return
        val current = client.preferenceService
            .readOnceForDevice(IngestPreferences.BackupGrantedBuckets, device)
        if (id !in current) return
        set(IngestPreferences.BackupGrantedBuckets, current - id)
    }

    override suspend fun addGrantedBucketAndSelect(id: String) {
        if (id.isBlank()) return
        val device = deviceId() ?: return
        val granted = client.preferenceService
            .readOnceForDevice(IngestPreferences.BackupGrantedBuckets, device)
        val selected = client.preferenceService
            .readOnceForDevice(IngestPreferences.BackupBuckets, device)
        if (id !in granted) {
            set(IngestPreferences.BackupGrantedBuckets, granted + id)
        }
        if (id !in selected) {
            set(IngestPreferences.BackupBuckets, selected + id)
        }
    }

    override fun observeDocumentTreeUris(): Flow<List<String>> =
        observe(IngestPreferences.DocumentTreeUris, emptyList())

    override suspend fun readDocumentTreeUris(): List<String> =
        readOnce(IngestPreferences.DocumentTreeUris, emptyList())

    override suspend fun addDocumentTreeUri(uri: String) {
        val id = deviceId() ?: return
        val current = client.preferenceService
            .readOnceForDevice(IngestPreferences.DocumentTreeUris, id)
        if (uri in current) return
        set(IngestPreferences.DocumentTreeUris, current + uri)
    }

    override suspend fun removeDocumentTreeUri(uri: String) {
        val id = deviceId() ?: return
        val current = client.preferenceService
            .readOnceForDevice(IngestPreferences.DocumentTreeUris, id)
        if (uri !in current) return
        set(IngestPreferences.DocumentTreeUris, current - uri)
    }

    override fun observeBackupNewFolders(): Flow<Boolean> =
        observe(IngestPreferences.BackupNewFolders, true)

    override suspend fun setBackupNewFolders(enabled: Boolean) =
        set(IngestPreferences.BackupNewFolders, enabled)

    override suspend fun readBackupNewFolders(): Boolean =
        readOnce(IngestPreferences.BackupNewFolders, true)

    override suspend fun readKnownBuckets(): Set<String> {
        val id = deviceId() ?: return emptySet()
        return client.preferenceService
            .readOnceForDevice(IngestPreferences.BackupKnownBuckets, id)
            .toSet()
    }

    override suspend fun addKnownBuckets(ids: Set<String>) {
        if (ids.isEmpty()) return
        val id = deviceId() ?: return
        val current = client.preferenceService
            .readOnceForDevice(IngestPreferences.BackupKnownBuckets, id)
        val next = current.toSet() + ids
        if (next.size == current.size) return
        set(IngestPreferences.BackupKnownBuckets, next.toList())
    }

    override fun observeVideos(): Flow<Boolean> =
        observe(IngestPreferences.BackupVideos, false)

    override suspend fun setVideos(enabled: Boolean) =
        set(IngestPreferences.BackupVideos, enabled)

    override suspend fun readVideos(): Boolean =
        readOnce(IngestPreferences.BackupVideos, false)

    // Folded into the generic realm-scoped background-data policy (§2): the
    // backup Wi-Fi switch IS `ControlPreferences.WifiOnly` — the same row that
    // gates background sync — so it roams across the realm and there is no
    // Drive-local duplicate. Realm-scoped, so `read`/`set` (not `*ForDevice`).
    override fun observeWifiOnly(): Flow<Boolean> =
        client.preferenceService.read(ControlPreferences.WifiOnly)

    override suspend fun setWifiOnly(enabled: Boolean) {
        client.preferenceService.set(ControlPreferences.WifiOnly, enabled)
    }

    // Realm-scoped, so the realm one-shot — no device identity needed.
    override suspend fun readWifiOnly(): Boolean =
        client.preferenceService.readOnce(ControlPreferences.WifiOnly)

    override fun observeChargingOnly(): Flow<Boolean> =
        observe(IngestPreferences.BackupChargingOnly, false)

    override suspend fun setChargingOnly(enabled: Boolean) =
        set(IngestPreferences.BackupChargingOnly, enabled)

    override suspend fun readChargingOnly(): Boolean =
        readOnce(IngestPreferences.BackupChargingOnly, false)

    override fun observePausedByUser(): Flow<Boolean> =
        observe(IngestPreferences.BackupPausedByUser, false)

    override suspend fun setPausedByUser(paused: Boolean) =
        set(IngestPreferences.BackupPausedByUser, paused)

    override suspend fun readPausedByUser(): Boolean =
        readOnce(IngestPreferences.BackupPausedByUser, false)

    override suspend fun readCursors(sourceId: String): Map<String, String> {
        val id = deviceId() ?: return emptyMap()
        val encoded = client.preferenceService
            .readOnceForDevice(IngestPreferences.BackupCursors, id)
        return BackupCursors.forSource(BackupCursors.decode(encoded), sourceId)
    }

    override suspend fun setCursor(sourceId: String, bucketId: String, cursor: String) {
        val id = deviceId() ?: return
        val current = BackupCursors.decode(
            client.preferenceService.readOnceForDevice(IngestPreferences.BackupCursors, id)
        )
        val next = current + (BackupCursors.key(sourceId, bucketId) to cursor)
        client.preferenceService.setForDevice(
            IngestPreferences.BackupCursors, BackupCursors.encode(next), id,
        )
    }

    override suspend fun clearCursor(sourceId: String, bucketId: String) {
        val id = deviceId() ?: return
        val current = BackupCursors.decode(
            client.preferenceService.readOnceForDevice(IngestPreferences.BackupCursors, id)
        )
        val key = BackupCursors.key(sourceId, bucketId)
        if (key !in current) return
        client.preferenceService.setForDevice(
            IngestPreferences.BackupCursors, BackupCursors.encode(current - key), id,
        )
    }

    // Cache only a *resolved* friendly name: while only the deviceId fallback is
    // available (the Device row hasn't synced yet) we keep retrying each drain, so a
    // late-arriving name still takes effect; once resolved it's pinned for
    // destination stability within the process.
    private var cachedDeviceLabel: String? = null

    override suspend fun deviceLabel(): String? {
        cachedDeviceLabel?.let { return it }
        val id = deviceId() ?: return null
        val name = runCatchingCancellable { client.identityService.deviceName(id) }.getOrNull()
        if (name != null) cachedDeviceLabel = name
        return name ?: id
    }
}

/**
 * Pure codec for the per-bucket cursor map stored as a `List<String>` blob
 * (§3.5 / open-Q2). Each entry is `bucketId<U+0001>cursor` (a control char that
 * cannot occur in a `MediaStore` bucket id or a filesystem path). Kept pure +
 * separate from the preference plumbing so the round-trip is desktop-unit-tested.
 * Entries without the separator (or with an empty bucket id) are dropped
 * defensively on decode.
 */
object BackupCursors {
    private const val SEP = ''

    /**
     * The stored blob key for one (source, bucket) cursor: `"$sourceId/$bucketId"`.
     * A `sourceId` never contains `/` (it is a reverse-DNS-ish id like
     * `drive.gallery`), so the namespace is recovered by splitting on the *first*
     * `/` — a bucket id may itself contain `/` (a desktop path), and the remainder
     * is taken verbatim. (Phase 3a — the engine namespaces cursors per source.)
     */
    fun key(sourceId: String, bucketId: String): String = "$sourceId/$bucketId"

    /** Project the full blob to one source's `bucketId -> cursor` map (prefix stripped). */
    fun forSource(all: Map<String, String>, sourceId: String): Map<String, String> {
        val prefix = "$sourceId/"
        return all.filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
    }

    fun encode(map: Map<String, String>): List<String> =
        map.entries
            .filter { it.key.isNotEmpty() }
            .map { "${it.key}$SEP${it.value}" }

    fun decode(entries: List<String>): Map<String, String> =
        entries.mapNotNull { entry ->
            val idx = entry.indexOf(SEP)
            if (idx <= 0) return@mapNotNull null
            entry.substring(0, idx) to entry.substring(idx + 1)
        }.toMap()
}

/** No-op reader for previews / tests where the sync identity is unavailable. */
object NoopBackupPreferences : BackupPreferences {
    override fun observeEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun readEnabled(): Boolean = false
    override fun observePausedByUser(): Flow<Boolean> = flowOf(false)
    override suspend fun setPausedByUser(paused: Boolean) = Unit
    override suspend fun readPausedByUser(): Boolean = false
    override fun observeSelectedBuckets(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setSelectedBuckets(ids: List<String>) = Unit
    override suspend fun readSelectedBuckets(): List<String> = emptyList()
    override fun observeGrantedBuckets(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun readGrantedBuckets(): List<String> = emptyList()
    override suspend fun addGrantedBucket(id: String) = Unit
    override fun observeDocumentTreeUris(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun readDocumentTreeUris(): List<String> = emptyList()
    override suspend fun addDocumentTreeUri(uri: String) = Unit
    override suspend fun removeDocumentTreeUri(uri: String) = Unit
    override fun observeBackupNewFolders(): Flow<Boolean> = flowOf(true)
    override suspend fun setBackupNewFolders(enabled: Boolean) = Unit
    override suspend fun readBackupNewFolders(): Boolean = true
    override suspend fun readKnownBuckets(): Set<String> = emptySet()
    override suspend fun addKnownBuckets(ids: Set<String>) = Unit
    override fun observeVideos(): Flow<Boolean> = flowOf(false)
    override suspend fun setVideos(enabled: Boolean) = Unit
    override suspend fun readVideos(): Boolean = false
    override fun observeWifiOnly(): Flow<Boolean> = flowOf(true)
    override suspend fun setWifiOnly(enabled: Boolean) = Unit
    override suspend fun readWifiOnly(): Boolean = true
    override fun observeChargingOnly(): Flow<Boolean> = flowOf(false)
    override suspend fun setChargingOnly(enabled: Boolean) = Unit
    override suspend fun readChargingOnly(): Boolean = false
    override suspend fun readCursors(sourceId: String): Map<String, String> = emptyMap()
    override suspend fun setCursor(sourceId: String, bucketId: String, cursor: String) = Unit
    override suspend fun clearCursor(sourceId: String, bucketId: String) = Unit
    override suspend fun deviceLabel(): String? = null
}
