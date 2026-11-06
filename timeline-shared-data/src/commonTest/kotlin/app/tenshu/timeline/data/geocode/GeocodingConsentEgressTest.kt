package app.tenshu.timeline.data.geocode

import app.tenshu.timeline.data.ImportedMoment
import app.tenshu.timeline.data.ImportedMovementTrace
import app.tenshu.timeline.data.ImportedTimeline
import app.tenshu.timeline.data.TimelineImportWriter
import app.tenshu.timeline.data.TimelineProjectionRepository
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION
import app.tenshu.timeline.data.capture.MovementMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.mapregion.proxy.ProxyReverseGeocoderEgress
import org.tenshu.mapregion.proxy.RegionDownloadProgressHolder
import org.tenshu.mapregion.proxy.StreamingRegionSource
import org.tenshu.mapregion.proxy.mapRegionServiceProxyImplementationFactory
import org.tenshu.mapregion.test.FakeRegionCatalogSource
import org.tenshu.mapregion.test.FakeRegionDownloader
import org.tenshu.mapregion.test.FakeRegionRangeFetcher
import org.tenshu.mapregion.test.FakeRegionStore
import org.tenshu.mapregion.test.createMapRegionTestRegistry
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.initTimelineService
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **With place-name lookups off, no coordinate reaches the third-party geocoder** — not from the
 * transit-stop fallback, not from the place picker (`place-geocoding.md` D5, threat model A35(d)).
 *
 * ### The defect this pins
 *
 * `TimelineProjectionRepository` resolved every transit leg's stops by calling `nearestTransitStop`
 * itself, with no opt-in check. The proxy answers from its tiles first and falls back to komoot's
 * public Photon, so every leg the tiles could not name sent both endpoints to Photon — with the
 * opt-in off and before the user had ever been asked. The picker's `reverseGeocodeCandidates` had
 * the same hole.
 *
 * ### Why it is driven end to end
 *
 * The real repository, over a real client, over a real in-process proxy whose `:map-region-service`
 * is the production implementation — with a **recording egress where Photon would be** and a tile
 * tier that never answers, so every lookup the app lets through reaches the recorder. A fake at the
 * repository's edge could not show the bug: the leak was the repository choosing to call at all.
 */
class GeocodingConsentEgressTest {

    /** Ostbahnhof → Odeonsplatz: the two endpoints of the seeded transit leg. */
    private val origin = 481_272_000L to 116_045_000L
    private val destination = 481_425_000L to 115_775_000L

    /** What crossed the last seam before the network. */
    private class RecordingEgress : ProxyReverseGeocoderEgress {
        val stopLookups = mutableListOf<Pair<Double, Double>>()
        val candidateLookups = mutableListOf<Pair<Double, Double>>()
        val placeLookups = mutableListOf<Pair<Double, Double>>()

        override suspend fun label(lat: Double, lng: Double): String? {
            placeLookups += lat to lng
            return "Somewhere"
        }

        override suspend fun candidates(lat: Double, lng: Double): List<String> {
            candidateLookups += lat to lng
            return listOf("Café Kreutzkamm", "Odeonsplatz")
        }

        override suspend fun transitStop(lat: Double, lng: Double, withinM: Double): String? {
            stopLookups += lat to lng
            return if (lat > 48.14) "Odeonsplatz" else "München Ost"
        }

        val total get() = stopLookups.size + candidateLookups.size + placeLookups.size
    }

    @Test
    fun withTheOptInOffATransitLegSendsNothingToTheGeocoder() = runTest {
        val egress = RecordingEgress()
        val (proxy, client) = stack(this, egress)
        seedTransitLeg(client, proxy)

        val repo = repository(client, FakePlaceGeocodingControl(initiallyEnabled = false))
        val leg = repo.homeDays().first().flatMap { it.moments }.single { it.id == LEG_MOMENT_ID }

        assertEquals(emptyList(), egress.stopLookups, "a transit endpoint was sent to the geocoder with the opt-in off")
        // ⚠ Degrades, never fails: the leg is still on the feed, just without stop names.
        assertEquals(null, leg.originPlace)
        assertEquals(null, leg.destinationPlace)
    }

    @Test
    fun withNoControlBoundAtAllNothingIsSentEither() = runTest {
        // ⚠⚠ Absence is NO. A platform that binds no PlaceGeocodingControl must not read as "ungated".
        val egress = RecordingEgress()
        val (proxy, client) = stack(this, egress)
        seedTransitLeg(client, proxy)

        val repo = repository(client, consent = null)
        repo.homeDays().first()
        repo.placeSuggestions(destination.first, destination.second)

        assertEquals(0, egress.total, "lookups reached the geocoder with no opt-in bound at all")
    }

    @Test
    fun withTheOptInOnTheFallbackStillNamesTheStops() = runTest {
        val egress = RecordingEgress()
        val (proxy, client) = stack(this, egress)
        seedTransitLeg(client, proxy)

        val repo = repository(client, FakePlaceGeocodingControl(initiallyEnabled = true))
        val leg = repo.homeDays().first().flatMap { it.moments }.single { it.id == LEG_MOMENT_ID }

        assertEquals("München Ost", leg.originPlace)
        assertEquals("Odeonsplatz", leg.destinationPlace)
        assertEquals(2, egress.stopLookups.size, "one lookup per endpoint, cached thereafter")
    }

    @Test
    fun thePickerOffersNoSuggestionsWithTheOptInOffAndDoesWithItOn() = runTest {
        val egress = RecordingEgress()
        val (_, client) = stack(this, egress)
        val consent = FakePlaceGeocodingControl(initiallyEnabled = false)
        val repo = repository(client, consent)

        assertEquals(emptyList(), repo.placeSuggestions(destination.first, destination.second))
        assertEquals(0, egress.candidateLookups.size, "the picker sent the coordinate with the opt-in off")

        // Read per call, not captured at construction: opting in takes effect on the next lookup.
        consent.setEnabled(true)
        assertEquals(
            listOf("Café Kreutzkamm", "Odeonsplatz"),
            repo.placeSuggestions(destination.first, destination.second),
        )
        assertEquals(1, egress.candidateLookups.size)
    }

    @Test
    fun theGateRefusesEveryOpItOwnsWhenOff() = runTest {
        // The single door, op by op — including `place`, which only the Android proxy tier uses.
        val egress = RecordingEgress()
        val (_, client) = stack(this, egress)
        val gate = PlaceGeocodingEgress(client, FakePlaceGeocodingControl(initiallyEnabled = false))

        assertEquals(null, gate.transitStopNear(origin.first, origin.second, 300))
        assertEquals(emptyList(), gate.placeCandidates(origin.first, origin.second))
        assertEquals(null, gate.placeAt(origin.first, origin.second))
        assertEquals(0, egress.total)

        val open = PlaceGeocodingEgress(client, FakePlaceGeocodingControl(initiallyEnabled = true))
        assertEquals("München Ost", open.transitStopNear(origin.first, origin.second, 300))
        assertEquals("Somewhere", open.placeAt(origin.first, origin.second)?.label?.get())
        assertTrue(egress.total >= 2)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun repository(client: TenshuClient, consent: PlaceGeocodingControl?) =
        TimelineProjectionRepository(
            client,
            geocodingConsent = consent,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )

    /** One transit trace plus the movement moment that points at it — no bracketing visits. */
    private suspend fun seedTransitLeg(client: TenshuClient, proxy: TestProxy) {
        val t0 = 1_800_000_000_000L
        TimelineImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            ImportedTimeline(
                traces = listOf(
                    ImportedMovementTrace(
                        traceId = LEG_TRACE_ID,
                        mode = MovementMode.TRANSIT,
                        distanceM = 3_000,
                        startMs = t0,
                        endMs = t0 + 10 * 60_000,
                        pointCount = 2,
                        minLatE7 = origin.first,
                        minLngE7 = destination.second,
                        maxLatE7 = destination.first,
                        maxLngE7 = origin.second,
                        polyline = ByteArray(0),
                        classifierVersion = MOVEMENT_CLASSIFIER_VERSION.toLong(),
                        modeConfidence = 80,
                        medianSpeedMmPerS = 9_000,
                        rawPointCount = 2,
                        startLatE7 = origin.first,
                        startLngE7 = origin.second,
                        endLatE7 = destination.first,
                        endLngE7 = destination.second,
                    ),
                ),
                moments = listOf(
                    ImportedMoment(
                        momentId = LEG_MOMENT_ID,
                        timestampMs = t0,
                        kind = Moment.MomentKind.MOVEMENT,
                        title = "",
                        subjectKind = SubjectKind.TRACE,
                        subjectId = LEG_TRACE_ID,
                        durationSec = 600,
                    ),
                ),
            ),
        )
    }

    /** Timeline + place + health + the real map-region proxy, with [egress] where Photon would be. */
    private fun stack(scope: CoroutineScope, egress: ProxyReverseGeocoderEgress): Pair<TestProxy, TenshuClient> {
        val registry = createMapRegionTestRegistry().apply {
            registerConfig(timelineServiceCommonModule); registerConfig(timelineServiceClientModule)
            registerConfig(placeServiceCommonModule); registerConfig(placeServiceClientModule)
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        val catalog = FakeRegionCatalogSource()
        val store = FakeRegionStore()
        val streaming = StreamingRegionSource(catalogSource = catalog, store = store, fetcher = FakeRegionRangeFetcher())
        val progress = RegionDownloadProgressHolder()
        val proxy = TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, p ->
                timelineServiceProxyImplementationFactory(id)
                    ?: placeServiceProxyImplementationFactory(id)
                    ?: healthServiceProxyImplementationFactory(id, p.fileDatabase)
                    ?: mapRegionServiceProxyImplementationFactory(
                        id, catalog, store, FakeRegionDownloader(), streaming, progress,
                        reverseGeocoder = egress,
                        // An uncatalogued region: the tiles never answer, so every lookup the app
                        // lets through goes on to the network tier — the case that leaked.
                        transitStopTiles = { _, _, _ -> null },
                    )
            },
        ).also {
            it.executionContext.initTimelineService(SemanticMergerRegistry())
            it.executionContext.initPlaceService(SemanticMergerRegistry())
            it.executionContext.initHealthService()
        }
        val client = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        return proxy to client
    }

    private companion object {
        const val LEG_TRACE_ID = "cap-trace:consent"
        const val LEG_MOMENT_ID = "cap-move:consent"
    }
}
