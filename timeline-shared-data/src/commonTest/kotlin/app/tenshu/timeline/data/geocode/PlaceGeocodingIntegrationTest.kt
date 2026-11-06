package app.tenshu.timeline.data.geocode

import app.tenshu.timeline.data.FileTimelineImporter
import app.tenshu.timeline.data.TimelineImportFile
import app.tenshu.timeline.data.TimelineProjectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import org.tenshu.timeline.client.timelineServiceClientModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.initTimelineService
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.sync.common.SemanticMergerRegistry

/**
 * The G1 end-to-end (place-geocoding.md §4.1), in the style of `TimelinePipelineIntegrationTest`:
 * import a **label-less new-format** Google Timeline export (2024+ on-device exports carry no
 * place names — `GoogleTimelineParser` KDoc) through the real import entry point, run the
 * enricher's [PlaceLabelEnricher.backfill] with the fake geocoder, and read the named place back
 * through the projector's Places flow. Re-running the backfill is a no-op (idempotent — the
 * label is no longer blank, so nothing is looked up or re-written).
 */
class PlaceGeocodingIntegrationTest {

    private val berlin = 525_200_000L to 134_050_000L

    // One café visit in the new on-device export shape: `semanticSegments`, degree-string
    // coordinates, and — the point — no place name anywhere.
    private val export = """
        {
          "semanticSegments": [
            {
              "startTime": "2025-04-07T09:00:00Z",
              "endTime": "2025-04-07T10:00:00Z",
              "visit": {
                "topCandidate": {
                  "placeId": "ChIJcafe",
                  "placeLocation": { "latLng": "52.5200000°, 13.4050000°" }
                }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun importedLabelLessPlacesAreNamedByBackfillAndReRunIsANoOp() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val written = FileTimelineImporter(client, proxy.tContext).importFiles(
            listOf(TimelineImportFile("Timeline.json", export.encodeToByteArray())),
        )
        assertEquals(2, written) // 1 place + 1 visit moment

        val repo = TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))

        // Honest pre-state: the imported place exists but is label-less until geocoded.
        assertEquals("", repo.places().first().single().name)

        val geocoder = FakeReverseGeocoder(mapOf(berlin to GeocodeResult("Blue Bottle")))
        val enricher = PlaceLabelEnricher(
            client, proxy.tContext, geocoder, FakePlaceGeocodingControl(), delayFn = {},
        )
        enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        // The projector's Places flow now emits the named place (and the Visit moment's
        // subject hydration picks the name up too).
        val place = repo.places().first().single()
        assertEquals("Blue Bottle", place.name)
        assertEquals(1, place.visitsCount)
        assertTrue(repo.homeDays().first().flatMap { it.moments }.any { it.place == "Blue Bottle" })

        // Idempotent: a second backfill finds no blank labels — no lookups, nothing re-written.
        enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        assertEquals(1, geocoder.calls.size)
        assertEquals("Blue Bottle", repo.places().first().single().name)
    }

    @Test
    fun importHookEnrichesWhenAnEnricherIsBound() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        val geocoder = FakeReverseGeocoder(mapOf(berlin to GeocodeResult("Blue Bottle")))
        val enricher = PlaceLabelEnricher(
            client, proxy.tContext, geocoder, FakePlaceGeocodingControl(), delayFn = {},
        )

        // The optional import hook (proposal §3): freshly imported places are enriched inline.
        FileTimelineImporter(client, proxy.tContext, enricher).importFiles(
            listOf(TimelineImportFile("Timeline.json", export.encodeToByteArray())),
        )

        assertEquals("Blue Bottle", TimelineProjectionRepository(client, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")).places().first().single().name)
    }

    @Test
    fun geocodedCitySurfacesAndUserRenameSurvivesBackfill() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        FileTimelineImporter(client, proxy.tContext).importFiles(
            listOf(TimelineImportFile("Timeline.json", export.encodeToByteArray())),
        )

        val geocoder = FakeReverseGeocoder(
            mapOf(berlin to GeocodeResult("Blue Bottle", city = "Berlin", countryCode = "de")),
        )
        val enricher = PlaceLabelEnricher(
            client, proxy.tContext, geocoder, FakePlaceGeocodingControl(), delayFn = {},
        )
        enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        // G4: the structured fields ride the fill and surface on the presentation model.
        val repo = TimelineProjectionRepository(client, proxy.tContext, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val place = repo.places().first().single()
        assertEquals("Blue Bottle", place.name)
        assertEquals("Berlin", place.city)

        // User rename persists via the same recordPlace upsert, keeps the structured fields,
        // and the blank-only enricher (D3) never overwrites it.
        repo.renamePlace(place.id, "Our café")
        assertEquals("Our café", repo.place(place.id)?.name)
        enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        assertEquals("Our café", repo.place(place.id)?.name)
        assertEquals("Berlin", repo.place(place.id)?.city)
    }

    // ── one in-process proxy hosting all three services ──────────────────────

    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(timelineServiceCommonModule); registerConfig(timelineServiceClientModule)
            registerConfig(placeServiceCommonModule); registerConfig(placeServiceClientModule)
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy ->
                timelineServiceProxyImplementationFactory(id)
                    ?: placeServiceProxyImplementationFactory(id)
                    ?: healthServiceProxyImplementationFactory(id, proxy.fileDatabase)
            },
        ).also {
            it.executionContext.initTimelineService(SemanticMergerRegistry())
            it.executionContext.initPlaceService(SemanticMergerRegistry())
            it.executionContext.initHealthService()
        }
    }

    private fun clientOf(proxy: TestProxy): TenshuClient {
        val env = TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }
}
