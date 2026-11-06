package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToLong

/**
 * A deterministic in-test [ReverseGeocoder] (place-geocoding.md G1) — canned coordinate → result,
 * keyed by **E7 integers** (the storage form, so fixtures share keys with `Place` rows). Records
 * every lookup in [calls] so tests can assert memoisation / consent gating; [onResolve] lets a
 * test interleave a concurrent write (e.g. a user rename) between the enricher's scan and its
 * blank-only re-check.
 */
class FakeReverseGeocoder(
    private val results: Map<Pair<Long, Long>, GeocodeResult> = emptyMap(),
    private val onResolve: suspend (latE7: Long, lngE7: Long) -> Unit = { _, _ -> },
    /**
     * Canned transit stops as `coordinate → (name, distance in metres)`.
     *
     * ⚠ The distance is modelled, not ignored: the enricher passes the fix's own accuracy as the
     * search radius, and a fake that returned a stop regardless would hide the case where the stop
     * is real but too far to speak for this dwell.
     */
    private val stops: Map<Pair<Long, Long>, Pair<String, Double>> = emptyMap(),
    /** Canned street answers as `coordinate → street` (§32.9). */
    private val streets: Map<Pair<Long, Long>, String> = emptyMap(),
) : ReverseGeocoder {

    /** Every lookup made, as E7 coordinate pairs, in order. */
    val calls = mutableListOf<Pair<Long, Long>>()

    /** Every transit-stop lookup, as `(coordinate, radius)` — so tests can assert it is not spent. */
    val stopCalls = mutableListOf<Pair<Pair<Long, Long>, Double>>()

    /** Every street lookup — so tests can assert a precise dwell never spends one. */
    val streetCalls = mutableListOf<Pair<Long, Long>>()

    override suspend fun street(lat: Double, lng: Double): String? {
        val key = (lat * 1e7).roundToLong() to (lng * 1e7).roundToLong()
        streetCalls += key
        return streets[key]
    }

    override suspend fun transitStop(lat: Double, lng: Double, withinM: Double): String? {
        val key = (lat * 1e7).roundToLong() to (lng * 1e7).roundToLong()
        stopCalls += key to withinM
        val (name, distance) = stops[key] ?: return null
        return name.takeIf { distance <= withinM }
    }

    override suspend fun resolve(lat: Double, lng: Double): GeocodeResult? {
        val key = (lat * 1e7).roundToLong() to (lng * 1e7).roundToLong()
        calls += key
        onResolve(key.first, key.second)
        return results[key]
    }
}

/** An in-memory [PlaceGeocodingControl] — the platform-persisted flag, without a platform. */
class FakePlaceGeocodingControl(initiallyEnabled: Boolean = true) : PlaceGeocodingControl {
    override val enabled = MutableStateFlow(initiallyEnabled)
    override fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }
}
