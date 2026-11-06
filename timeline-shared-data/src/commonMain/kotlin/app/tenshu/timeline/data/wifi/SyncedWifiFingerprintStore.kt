package app.tenshu.timeline.data.wifi

import app.tenshu.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.core.TContext
import org.tenshu.core.fold
import org.tenshu.core.place.FingerprintProvenance
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.place.recordPlaceNetworkFingerprint
import org.tenshu.place.client.placeService
import kotlin.time.Clock

/**
 * The **synced** Wi-Fi fingerprint store (`timeline-recognition-and-place-naming.md` §9.3 / F4):
 * bindings become a property of the *user*, not of one handset.
 *
 * ⚠⚠ **The device-local form did not merely lose bindings on a new phone — it re-fragmented places
 * while relearning them.** F2's recognition steps only see what *this* device has learned, so a fresh
 * install mints a new place for the first AP it meets at a venue it has never "seen", exactly as
 * §8.2 describes. Syncing is what stops that, and it unlocks two things the local form could not: a
 * device with no GPS at all can recognise a place a phone taught it (§10.5), and two devices on one
 * access point become colocation evidence stronger than either one's fix (§10.4).
 *
 * ⚠ **Reads are synchronous, writes are not.** [WifiPlaceFingerprint.resolvePlaceId] runs inside the
 * capture collect loop and cannot suspend, so the synced rows are held in an in-memory map hydrated
 * by [start]; the service is never read on the hot path. Writes go through [local] first — which
 * makes them durable immediately, and keeps capture working before the first sync, offline, and
 * whenever Control is unreachable — then fire-and-forget to the service.
 *
 * ⚠ **BSSID tags only** (Q9, §10.4). One SSID can span a campus, a chain or a mesh, so it is not
 * colocation evidence; and SSIDs are user-authored strings that frequently contain a person's name.
 * The SSID index stays in [local], device-local, and is never synced.
 *
 * ⚠ Raw BSSIDs never leave the device: what syncs is [NetworkTag] — see its KDoc for what that does
 * and does not buy.
 */
class SyncedWifiFingerprintStore(
    /** The device-local store: durable cache, offline fallback, and sole home of the SSID index. */
    private val local: WifiFingerprintStore,
    private val client: TenshuClient,
    private val context: TContext,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : WifiFingerprintStore {

    /** `networkTag → placeId`, hydrated from the realm. Read on the synchronous capture path. */
    private var synced: Map<String, String> = emptyMap()

    /**
     * Begin mirroring the realm's fingerprints into memory. Idempotent per instance in practice
     * (the DI holds one); a failed read leaves the map as-is, so recognition degrades to [local]
     * rather than breaking capture.
     */
    fun start() {
        scope.launch {
            // ⚠ Hydrate once up-front, then follow. Collecting alone would leave recognition blind
            // until the stream's first emission — and the capture path is synchronous, so "blind"
            // means a venue mints a new place in the meantime, which is the exact failure F4 exists
            // to stop.
            refresh()
            try {
                fingerprintFlow()
                    .catch {
                        // ⚠ Ahead of the outer `catch (e: CancellationException)` below, which this
                        // one would otherwise never let reach: `catch` does not exempt it either.
                        if (it is CancellationException) throw it
                        Diagnostics.w("wifi", "fingerprint mirror failed: ${it.message}", code = "wifi_mirror_failed")
                    }
                    .collect { rows -> if (rows != null) synced = rows }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.w("wifi", "fingerprint mirror stopped: ${e.message}", code = "wifi_mirror_stopped")
            }
        }
    }

    /**
     * Hydrate the in-memory map once from the realm. Suspending and deterministic, so tests drive it
     * directly instead of racing a stream; [start] calls it before following.
     */
    suspend fun refresh() {
        try {
            fingerprintFlow().first()?.let { synced = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diagnostics.w("wifi", "fingerprint refresh failed: ${e.message}", code = "wifi_refresh_failed")
        }
    }

    /** `networkTag → placeId` per emission; `null` when the read failed (keep the last good map). */
    private fun fingerprintFlow() =
        client.placeService.networkFingerprints
            .select {
                fields(
                    PlaceNetworkFingerprint.Fields.NETWORK_TAG,
                    PlaceNetworkFingerprint.Fields.PLACE_ID,
                )
            }
            .flow()
            .map { result ->
                result.fold(
                    onSuccess = { rows ->
                        rows.toList().associate {
                            it.getFieldValue<TString>(PlaceNetworkFingerprint.Fields.NETWORK_TAG).get() to
                                it.getFieldValue<TString>(PlaceNetworkFingerprint.Fields.PLACE_ID).get()
                        }.filterValues { it.isNotBlank() }
                    },
                    onFailure = { null },
                )
            }

    override fun placeFor(bssid: String): String? =
        synced[NetworkTag.of(bssid)] ?: local.placeFor(bssid)

    override fun remember(bssid: String, placeId: String) {
        local.remember(bssid, placeId)
        write(bssid, placeId, FingerprintProvenance.LEARNED)
    }

    /**
     * A rename-time binding (§8.4 F2) is the user telling us directly, so it syncs as
     * [FingerprintProvenance.USER] — which `PlaceNetworkFingerprintMerger` then prefers over any
     * peer's `learned` guess for the same network.
     */
    fun rememberUserBinding(bssid: String, placeId: String) {
        local.remember(bssid, placeId)
        write(bssid, placeId, FingerprintProvenance.USER)
    }

    // ── SSID index: device-local only (Q9) ─────────────────────────────────────

    override fun rememberSsid(ssid: String, placeId: String) = local.rememberSsid(ssid, placeId)

    override fun placeForSsid(ssid: String): String? = local.placeForSsid(ssid)

    override fun knownPlaceIds(): Collection<String> =
        (synced.values + local.knownPlaceIds()).toSet()

    private fun write(bssid: String, placeId: String, provenance: String) {
        if (placeId.isBlank()) return
        val tag = NetworkTag.of(bssid)
        // Fire-and-forget: the caller is the synchronous capture path, and `local` has already made
        // the binding durable. A failed op costs the *sync* of this binding, never the binding.
        scope.launch {
            try {
                client.placeService.recordPlaceNetworkFingerprint(
                    PlaceNetworkFingerprint.create(
                        context = context,
                        networkTag = tag,
                        placeId = placeId,
                        observedAt = UnixInstantDto(nowEpochMs()),
                        provenance = provenance,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.w("wifi", "fingerprint write failed: ${e.message}", code = "wifi_write_failed")
            }
        }
    }
}
