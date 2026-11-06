package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.place.recordPlace
import org.tenshu.core.time.UnixInstant
import org.tenshu.place.client.placeService
import kotlin.coroutines.cancellation.CancellationException

/**
 * The **blank-label enricher** (`docs/proposals/place-geocoding.md` §3): scans `Place` rows
 * whose `label` is blank (live-captured visits, new-format Google imports), resolves each
 * coordinate through the [geocoder] seam and re-records the row with the label filled.
 * `RecordPlace` is an idempotent upsert by `placeId`, so enrichment is just a second
 * `recordPlace`; the row's stored coordinates and `capturedAt` are preserved.
 *
 * Behavioural contract (proposal §3, all unit-testable device-free):
 * - **improve-unless-user fill (4-C, timeline-recognition-and-place-naming.md §4.4)** — a place is
 *   a candidate when its label is blank **or** its [Place.labelSource] is `geocoded`, so a later,
 *   better lookup can improve an already-geocoded street label to a venue; the row is re-read
 *   immediately before the write and skipped if it is now `user`-authored (a concurrent rename /
 *   place-pick is *never* overwritten). Each place is re-queried at most once per session, so a
 *   shipped query refinement reaches old rows on the next app start without a per-backfill loop;
 * - **confidence-aware coarsening (4-B, §4.3)** — when the place's stored `accuracyM` is worse than
 *   [COARSE_LABEL_ACCURACY_M], the area/city is written instead of a confidently-wrong venue;
 * - **consent-gated (D5)** — [consent] is checked before *every* resolve, so disabling stops
 *   all lookups immediately (v1 tiers are all network; a future offline tier-0 would be exempt);
 * - **throttled** — ~1 request/sec by default (Android Geocoder etiquette / the Photon
 *   public-instance policy); the delay is an injected seam so tests never real-sleep;
 * - **per-session memoisation** — failed/empty lookups are remembered for the enricher's
 *   lifetime, so a no-result coordinate isn't re-queried in a loop;
 * - **never throws** on provider garbage — untrusted-input posture, same as the parsers.
 *
 * Runs are serialised behind a [Mutex] so a capture-hook [enrich] and a manual [backfill]
 * can't interleave (and double the request rate).
 */
class PlaceLabelEnricher(
    private val client: TenshuClient,
    private val context: TContext,
    private val geocoder: ReverseGeocoder,
    private val consent: PlaceGeocodingControl,
    private val throttleMs: Long = 1_000L,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    /**
     * Trajectory context per place id (§31), loaded **once per sweep** and only when a coarse candidate
     * exists. Defaults to empty — i.e. every verdict `UNKNOWN`, which leaves §28's behaviour exactly as
     * it is, so tests, desktop and iOS are unaffected until they wire a real source.
     */
    private val trajectory: suspend () -> Map<String, TransitAdjacency.Verdict> = { emptyMap() },
) {
    private val mutex = Mutex()

    /** Place ids whose lookup failed or came back empty this session — not re-queried. */
    private val fruitless = mutableSetOf<String>()

    /**
     * Place ids already successfully (re-)geocoded this session — not re-queried, so an
     * improvable `geocoded` label is refined at most once per process rather than on every
     * backfill. A fresh app start clears this, which is when a shipped query refinement (4-A)
     * gets its one chance to improve old rows.
     */
    private val geocoded = mutableSetOf<String>()

    /**
     * Scan blank-label places, resolve throttled, blank-only re-record (D3). [beforeEachWrite] is
     * the same ADR 0025 §8.6 boundary as [enrich]'s; a refusal propagates to the caller.
     */
    suspend fun backfill(beforeEachWrite: suspend () -> Unit) = sweep(beforeEachWrite) { true }

    /**
     * Best-effort enrichment for freshly written places (capture / import hook).
     *
     * [beforeEachWrite] is the ADR 0025 §8.6 write boundary: it runs before EACH `recordPlace` and
     * throws to refuse it — geocoding is slow and throttled, so a realm gate can start holding
     * half-way through, and the refusal propagates to the caller (which keeps its outbox entry).
     */
    suspend fun enrich(placeIds: List<String>, beforeEachWrite: suspend () -> Unit) {
        if (placeIds.isEmpty()) return
        val wanted = placeIds.toSet()
        sweep(beforeEachWrite) { it.id in wanted }
    }

    private suspend fun sweep(
        beforeEachWrite: suspend () -> Unit,
        wanted: (PlaceRow) -> Boolean,
    ): Unit = mutex.withLock {
        if (!consent.enabled.value) return
        // 4-C: fillable = blank OR still machine-provenanced (`geocoded`). A `user` label is never
        // a candidate; an already-improved one is skipped via `geocoded` (once-per-session).
        val candidates = readPlaces().filter {
            wanted(it) &&
                (it.label.isBlank() || !LabelSource.isUser(it.labelSource)) &&
                it.id !in fruitless && it.id !in geocoded
        }
        // ⚠ Loaded lazily and at most once: a sweep with no coarse candidate never pays for it, and a
        // sweep with ten does not pay ten times.
        var trajectoryVerdicts: Map<String, TransitAdjacency.Verdict>? = null
        suspend fun verdictFor(placeId: String): TransitAdjacency.Verdict {
            val loaded = trajectoryVerdicts ?: runCatchingCancellable { trajectory() }.getOrDefault(emptyMap())
                .also { trajectoryVerdicts = it }
            return loaded[placeId] ?: TransitAdjacency.Verdict.UNKNOWN
        }

        var first = true
        for (row in candidates) {
            // Consent re-checked before every lookup — flipping the toggle stops mid-run.
            if (!consent.enabled.value) return
            if (!first) delayFn(throttleMs)
            first = false

            val result = resolveSafely(row.latE7 / 1e7, row.lngE7 / 1e7)
            if (result == null) {
                fruitless += row.id
                continue
            }
            // 4-B (§4.3): when the dwell's accuracy is poor (indoors, floored by the ±100 m network
            // provider), a precise venue name is *more* wrong than a coarse one — name the area/city
            // rather than a confidently-wrong POI. `GeocodeResult` already carries the locality tier,
            // so this is a consumer-side choice: no geocoder/op signature change.
            // §28: before falling back to the city, ask whether the dwell was at a transit stop.
            // ⚠⚠ Only inside the coarse branch, and that placement is the entire safety argument. A
            // *precise* fix keeps its venue, so a well-fixed café beside a station is never touched —
            // which matters because, measured across cities, 38 % of Paris cafés and 30 % of Tokyo
            // cafés have a named stop within 150 m. Here the alternative being displaced is not a
            // venue but the **city**, and a stop inside the fix's own uncertainty is a strictly better
            // answer than the city for the same coordinate.
            val coarse = row.accuracyM > COARSE_LABEL_ACCURACY_M
            val stop = if (coarse) {
                // §31: the trajectory can tighten the radius — never widen it — so a poor-fix dwell
                // that was confidently walked to and from cannot be renamed after a nearby station.
                nearestStopWithin(row, verdictFor(row.id))
            } else {
                null
            }
            // §32.9: the tier between the venue and the city. Refusing the venue was right; jumping
            // straight to `city` was not — measured on the real store, three separate places ended up
            // called "München", one of them the user's office. A street is a *line*, so a ±100 m fix
            // on it is genuinely on it; that is why it belongs here and a venue does not.
            // ⚠ Asked only when there is no stop and only in the coarse branch, so a precise dwell
            // still keeps its venue and the extra lookup is never spent on one.
            val street = if (coarse && stop == null) {
                geocoder.street(row.latE7 / 1e7, row.lngE7 / 1e7)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            // ⚠ `null` when nothing coarser is available — and then the venue stands, exactly as
            // before. Coarsening must never turn a real answer into no answer.
            val coarsened = if (!coarse) null else stop ?: street ?: result.city.takeIf { it.isNotBlank() }
            val label = coarsened ?: result.label
            if (label.isBlank()) {
                fruitless += row.id
                continue
            }

            // 4-C: re-read immediately before the write; a row that is now `user`-authored (a
            // concurrent rename / place-pick, on this or another device) is never overwritten.
            val current = readPlaces().firstOrNull { it.id == row.id } ?: continue
            if (LabelSource.isUser(current.labelSource)) continue
            beforeEachWrite()
            geocoded += current.id
            client.placeService.recordPlace(
                Place.create(
                    context = context,
                    placeId = current.id,
                    latE7 = current.latE7,
                    lngE7 = current.lngE7,
                    capturedAt = current.capturedAt,
                    label = label,
                    // G4: the structured fields ride the same fill; a provider that omitted
                    // them never blanks a value another tier already stored.
                    city = result.city.ifBlank { current.city },
                    countryCode = result.countryCode.ifBlank { current.countryCode },
                    // The enricher's writes are always machine-provenanced.
                    labelSource = LabelSource.GEOCODED,
                    // Preserve the stored capture accuracy (label changed, not the fix quality).
                    accuracyM = current.accuracyM,
                ),
            )
        }
    }

    /** One geocoder call — a throwing provider is treated as answerless (never propagates). */
    private suspend fun resolveSafely(lat: Double, lng: Double): GeocodeResult? =
        try {
            geocoder.resolve(lat, lng)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ── place reads (the projector's select idiom) ───────────────────────────

    private data class PlaceRow(
        val id: String,
        val label: String,
        val latE7: Long,
        val lngE7: Long,
        val capturedAt: UnixInstant,
        val city: String,
        val countryCode: String,
        val labelSource: String,
        val accuracyM: Int,
    )

    private suspend fun readPlaces(): List<PlaceRow> =
        client.placeService.places.select { fields(*PLACE_FIELDS) }.flow().first()
            .fold(onSuccess = { it.toList().map(::placeRow) }, onFailure = { emptyList() })

    private fun placeRow(e: Entity<out Place>) = PlaceRow(
        id = e.getFieldValue<TString>(Place.Fields.PLACE_ID).get(),
        label = e.getFieldValue<TString>(Place.Fields.LABEL).get(),
        latE7 = e.getFieldValue<TInteger>(Place.Fields.LAT_E7).get(),
        lngE7 = e.getFieldValue<TInteger>(Place.Fields.LNG_E7).get(),
        capturedAt = e.getFieldValue<UnixInstant>(Place.Fields.CAPTURED_AT),
        city = e.getFieldValue<TString>(Place.Fields.CITY).get(),
        countryCode = e.getFieldValue<TString>(Place.Fields.COUNTRY_CODE).get(),
        labelSource = e.getFieldValue<TString>(Place.Fields.LABEL_SOURCE).get(),
        accuracyM = e.getFieldValue<TInteger>(Place.Fields.ACCURACY_M).get().toInt(),
    )

    /**
     * The nearest named transit stop inside this fix's own uncertainty, or `null`.
     *
     * ⚠ The radius is the row's **own** `accuracyM`, not a constant: a stop only answers for a fix if
     * it lies where that fix could plausibly have been. ⚠ Capped at [MAX_STOP_SEARCH_M] so a wildly
     * bad fix cannot reach across a city and name a station nobody was near.
     */
    private suspend fun nearestStopWithin(
        row: PlaceRow,
        verdict: TransitAdjacency.Verdict,
    ): String? = try {
        val bounded = TransitAdjacency.searchRadiusM(verdict, minOf(row.accuracyM, MAX_STOP_SEARCH_M))
        geocoder
            .transitStop(row.latE7 / 1e7, row.lngE7 / 1e7, bounded.toDouble())
            ?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        val PLACE_FIELDS = arrayOf(
            Place.Fields.PLACE_ID, Place.Fields.LABEL, Place.Fields.LAT_E7, Place.Fields.LNG_E7,
            Place.Fields.CAPTURED_AT, Place.Fields.CITY, Place.Fields.COUNTRY_CODE,
            Place.Fields.LABEL_SOURCE, Place.Fields.ACCURACY_M,
        )

        /**
         * 4-B: above this dwell accuracy (metres) the label is coarsened to the area/city rather
         * than a confidently-wrong venue. ≈ indoors on the NETWORK provider (±100 m); a real GPS
         * fix (±5–20 m) stays below it and keeps the precise venue. (§4.3 default.)
         */
        const val COARSE_LABEL_ACCURACY_M = 80

        /**
         * Ceiling on the transit-stop search radius (metres), however poor the fix.
         *
         * ⚠ Measured: at 300 m, 85 % of Paris and 90 % of Tokyo cafés have a named stop in range, so
         * an unbounded radius would rename most poor-fix city dwells after a station. 300 is kept
         * because it is the accuracy of the real platform dwell this exists for — but it is the point
         * at which the answer stops being "inside the uncertainty" and starts being "somewhere in the
         * district", and it should tighten if a better dwell-level transit signal arrives.
         */
        const val MAX_STOP_SEARCH_M = 300
    }
}
