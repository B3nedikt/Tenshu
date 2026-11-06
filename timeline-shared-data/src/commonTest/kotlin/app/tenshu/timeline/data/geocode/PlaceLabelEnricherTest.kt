package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.place.recordPlace
import org.tenshu.core.time.UnixInstant
import org.tenshu.place.client.placeService
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.sync.common.SemanticMergerRegistry

/**
 * The enricher's behavioural contract (place-geocoding.md §3), device-free over the in-process
 * `:place-service` stack: blank-only fill (D3, incl. a rename racing the write), the consent
 * gate (D5), the injected throttle seam, and per-session memoisation of fruitless lookups.
 */
class PlaceLabelEnricherTest {

    private val berlin = 525_200_000L to 134_050_000L
    private val lisbon = 387_200_000L to -91_400_000L

    @Test
    fun fillsBlankLabelsThrottledAndPreservesTheRow() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, capturedAtMs = 123_456_789L)
        recordPlace(client, proxy, "b", lisbon)
        recordPlace(client, proxy, "named", 100L to 200L, label = "Home", labelSource = LabelSource.USER)

        val geocoder = FakeReverseGeocoder(
            mapOf(berlin to GeocodeResult("Blue Bottle"), lisbon to GeocodeResult("Bistro")),
        )
        val delays = mutableListOf<Long>()
        enricher(client, proxy, geocoder, delayFn = { delays += it }).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        val rows = readPlaces(client).associateBy { it.id }
        assertEquals("Blue Bottle", rows.getValue("a").label)
        assertEquals("Bistro", rows.getValue("b").label)
        // The user-named row is never looked up, let alone overwritten (4-C).
        assertEquals("Home", rows.getValue("named").label)
        assertEquals(setOf(berlin, lisbon), geocoder.calls.toSet())
        // The re-record preserves the stored coordinates + capturedAt; only the label changed.
        assertEquals(berlin.first, rows.getValue("a").latE7)
        assertEquals(berlin.second, rows.getValue("a").lngE7)
        assertEquals(123_456_789L, rows.getValue("a").capturedAtMs)
        // Throttle between lookups (n − 1 delays), via the injected seam — no real sleeping.
        assertEquals(listOf(1_000L), delays)
    }

    @Test
    fun consentOffIsASilentNoOp() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin)

        val geocoder = FakeReverseGeocoder(mapOf(berlin to GeocodeResult("Blue Bottle")))
        enricher(client, proxy, geocoder, consent = FakePlaceGeocodingControl(initiallyEnabled = false))
            .backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertTrue(geocoder.calls.isEmpty())
        assertEquals("", readPlaces(client).single().label)
    }

    @Test
    fun fruitlessLookupsAreMemoisedForTheSession() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin)

        val geocoder = FakeReverseGeocoder() // nothing there — always null
        val enricher = enricher(client, proxy, geocoder)
        enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        // The no-result coordinate is not re-queried in a loop (proposal §3).
        assertEquals(1, geocoder.calls.size)
    }

    @Test
    fun renameBetweenScanAndWriteIsNeverOverwritten() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin)

        // The user renames the place while the lookup is in flight — the enricher's re-read
        // immediately before the write (D3) must see the rename and back off.
        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult("Blue Bottle")),
            onResolve = { _, _ ->
                recordPlace(client, proxy, "a", berlin, label = "My café", labelSource = LabelSource.USER)
            },
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("My café", readPlaces(client).single().label)
    }

    @Test
    fun improvesAnAlreadyGeocodedLabel() = runTest {
        // 4-C: a better lookup reaches places already in the store — a machine-provenanced
        // ("geocoded") street label is refined to a venue, not left because it is non-blank.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, label = "Street", labelSource = LabelSource.GEOCODED)

        val geocoder = FakeReverseGeocoder(mapOf(berlin to GeocodeResult("Office")))
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Office", readPlaces(client).single().label)
        assertEquals(listOf(berlin), geocoder.calls)
    }

    @Test
    fun neverOverwritesOrRequeriesAUserLabel() = runTest {
        // 4-C: a user-authored label (rename / place-pick) is never a candidate — not overwritten
        // by a better lookup, and never even queried (no wasted egress on names the user chose).
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, label = "Office", labelSource = LabelSource.USER)

        val geocoder = FakeReverseGeocoder(mapOf(berlin to GeocodeResult("Clinic")))
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Office", readPlaces(client).single().label)
        assertTrue(geocoder.calls.isEmpty())
    }

    @Test
    fun animprovedGeocodedLabelIsNotRequeriedTwiceInASession() = runTest {
        // Once-per-session: a geocoded label is refined at most once per process, so a repeated
        // backfill does not re-query it in a loop (a fresh app start is when a refinement re-runs).
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, label = "Some Street", labelSource = LabelSource.GEOCODED)

        val geocoder = FakeReverseGeocoder(mapOf(berlin to GeocodeResult("Office")))
        val enricher = enricher(client, proxy, geocoder)
        enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        enricher.backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals(1, geocoder.calls.size)
        assertEquals("Office", readPlaces(client).single().label)
    }

    @Test
    fun enrichTouchesOnlyTheGivenIds() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin)
        recordPlace(client, proxy, "b", lisbon)

        val geocoder = FakeReverseGeocoder(
            mapOf(berlin to GeocodeResult("Blue Bottle"), lisbon to GeocodeResult("Bistro")),
        )
        enricher(client, proxy, geocoder).enrich(listOf("a"), beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals(listOf(berlin), geocoder.calls)
        val rows = readPlaces(client).associateBy { it.id }
        assertEquals("Blue Bottle", rows.getValue("a").label)
        assertEquals("", rows.getValue("b").label)
    }

    @Test
    fun coarsensToTheCityWhenTheDwellAccuracyIsPoor() = runTest {
        // 4-B (§4.3): a wide-accuracy dwell (indoors, floored by the ±100 m network provider) must
        // not be named with a confidently-wrong venue — the area/city is written instead.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 150)

        val geocoder = FakeReverseGeocoder(
            mapOf(berlin to GeocodeResult(label = "Clinic", city = "München")),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("München", readPlaces(client).single().label)
    }

    @Test
    fun keepsThePreciseVenueWhenTheDwellAccuracyIsGood() = runTest {
        // The complement: a real GPS fix (±15 m) stays below the coarsening threshold, so the
        // precise venue is kept — coarsening only fires when we *know* the fix is poor.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 15)
        recordPlace(client, proxy, "b", lisbon, accuracyM = 0) // unknown accuracy → never coarsens

        val geocoder = FakeReverseGeocoder(
            mapOf(
                berlin to GeocodeResult(label = "Office", city = "München"),
                lisbon to GeocodeResult(label = "Bistro", city = "Lisboa"),
            ),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        val rows = readPlaces(client).associateBy { it.id }
        assertEquals("Office", rows.getValue("a").label)
        assertEquals("Bistro", rows.getValue("b").label)
    }

    /**
     * ⚠⚠ Codex P1 on 6ae61f405: the realm gate starts holding while the (slow, throttled) geocode
     * lookup is in flight. The enrichment's `recordPlace` goes through the same write boundary as
     * the timeline write, so it is refused — the label stays unwritten — and the outbox entry is
     * KEPT, because an entry is removed only once every write in it landed. Paired with the gate
     * staying open: the label lands and the entry is removed.
     */
    @Test
    fun aGateThatHoldsMidEnrichmentRefusesTheLabelWriteAndKeepsTheEntry() = runTest {
        for (holdsDuringLookup in listOf(true, false)) {
            val proxy = placeProxy(this)
            val client = clientOf(proxy)
            var held = false
            val geocoder = FakeReverseGeocoder(
                mapOf(berlin to GeocodeResult("Blue Bottle")),
                onResolve = { _, _ -> if (holdsDuringLookup) held = true },
            )
            val outbox = app.tenshu.timeline.data.capture.CapturedVisitOutbox(
                app.tenshu.timeline.data.capture.FakeVisitOutboxStore(),
            )
            val writer = app.tenshu.timeline.data.capture.CapturedVisitWriter(
                config = app.tenshu.timeline.data.capture.VisitDetectionConfig(),
                enricher = enricher(client, proxy, geocoder),
                outbox = outbox,
                writeTimeoutMs = 30_000,
                // The visit's own place, recorded blank at Berlin so the enricher has a candidate.
                writeTimeline = { t -> t.places.forEach { recordPlace(client, proxy, it.placeId, berlin) } },
                writesHeld = { held },
            )

            writer.write(
                app.tenshu.timeline.data.capture.CapturedVisit(
                    latE7 = berlin.first, lngE7 = berlin.second, arrivalMs = 1_000_000, departureMs = 1_400_000,
                ),
            )

            val label = readPlaces(client).single().label
            if (holdsDuringLookup) {
                assertEquals("", label, "the label write was refused by the realm gate")
                assertEquals(1, outbox.pending().size, "the entry is kept when any write in it was refused")
            } else {
                assertEquals("Blue Bottle", label)
                assertTrue(outbox.pending().isEmpty(), "every write landed, so the entry is removed")
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun enricher(
        client: TenshuClient,
        proxy: TestProxy,
        geocoder: ReverseGeocoder,
        consent: PlaceGeocodingControl = FakePlaceGeocodingControl(),
        delayFn: suspend (Long) -> Unit = {},
        trajectory: suspend () -> Map<String, TransitAdjacency.Verdict> = { emptyMap() },
    ) = PlaceLabelEnricher(
        client, proxy.tContext, geocoder, consent,
        delayFn = delayFn,
        trajectory = trajectory,
    )

    private suspend fun recordPlace(
        client: TenshuClient,
        proxy: TestProxy,
        id: String,
        coord: Pair<Long, Long>,
        label: String = "",
        labelSource: String = LabelSource.GEOCODED,
        accuracyM: Int = 0,
        capturedAtMs: Long = 1_800_000_000_000L,
    ) {
        client.placeService.recordPlace(
            Place.create(
                context = proxy.tContext,
                placeId = id,
                latE7 = coord.first,
                lngE7 = coord.second,
                capturedAt = UnixInstantDto(capturedAtMs),
                label = label,
                labelSource = labelSource,
                accuracyM = accuracyM,
            ),
        )
    }

    private data class Row(val id: String, val label: String, val latE7: Long, val lngE7: Long, val capturedAtMs: Long)

    private suspend fun readPlaces(client: TenshuClient): List<Row> =
        client.placeService.places
            .select {
                fields(
                    Place.Fields.PLACE_ID, Place.Fields.LABEL, Place.Fields.LAT_E7,
                    Place.Fields.LNG_E7, Place.Fields.CAPTURED_AT,
                )
            }
            .flow().first()
            .fold(
                onSuccess = { list ->
                    list.toList().map { e: Entity<out Place> ->
                        Row(
                            id = e.getFieldValue<TString>(Place.Fields.PLACE_ID).get(),
                            label = e.getFieldValue<TString>(Place.Fields.LABEL).get(),
                            latE7 = e.getFieldValue<TInteger>(Place.Fields.LAT_E7).get(),
                            lngE7 = e.getFieldValue<TInteger>(Place.Fields.LNG_E7).get(),
                            capturedAtMs = e.getFieldValue<UnixInstant>(Place.Fields.CAPTURED_AT).toEpochMilliseconds(),
                        )
                    }
                },
                onFailure = { emptyList() },
            )

    // ── an in-process proxy hosting just :place-service ─────────────────────

    private fun placeProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(placeServiceCommonModule); registerConfig(placeServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, _ -> placeServiceProxyImplementationFactory(id) },
        ).also { it.executionContext.initPlaceService(SemanticMergerRegistry()) }
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

    // ── §32.9: the street sits between the venue and the city ──────────────────

    @Test
    fun aCoarseDwellWithNoStopIsNamedByItsStreetNotTheCity() = runTest {
        // ⚠⚠ The measured defect. On the real store three separate places are called "München" —
        // one of them the user's office — because refusing the venue (right: a venue is a point and a
        // ±100 m fix cannot claim one) fell all the way to the city (wrong: the same string for
        // everywhere in the city, colliding with every other poor-fix dwell). The office's own accuracy
        // was 100 m and it sits on a street.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 100)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Building", city = "München")),
            streets = mapOf(berlin to "Street"),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Street", readPlaces(client).single().label)
    }

    @Test
    fun aTransitStopStillBeatsTheStreet() = runTest {
        // Ordering, pinned: stop → street → city. A platform dwell is at the station, not on the road
        // beside it, so §28 must keep winning where it fired before.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 300)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Car dealer", city = "München")),
            stops = mapOf(berlin to ("Odeonsplatz" to 82.0)),
            streets = mapOf(berlin to "Side street"),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Odeonsplatz", readPlaces(client).single().label)
    }

    @Test
    fun aPreciseDwellNeverSpendsAStreetLookupAndKeepsItsVenue() = runTest {
        // ⚠⚠ The safety argument, as an assertion — the same one §28's stop lookup carries. The
        // street tier lives *inside* the coarse branch, so a well-fixed café is never renamed after
        // the road outside it, and the extra lookup is never paid for on a precise dwell.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 12)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Bistro", city = "München")),
            streets = mapOf(berlin to "Side street"),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Bistro", readPlaces(client).single().label)
        assertTrue(geocoder.streetCalls.isEmpty(), "a precise dwell must not spend a street lookup")
    }

    @Test
    fun theCityStillWinsWhenThereIsNoStreetEither() = runTest {
        // The floor is unchanged: with neither stop nor street, the city is still better than a
        // confidently-wrong venue.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 150)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Clinic", city = "München")),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("München", readPlaces(client).single().label)
    }

    // ── §28: a transit stop beats the city when the fix is too coarse to name a venue ──────────

    @Test
    fun aTransitStopBeatsTheCityWhenTheFixIsCoarse() = runTest {
        // A 300 m dwell on a transit platform: the nearest venue (a car showroom 14 m away) and the
        // city are both wrong; the stop is where the user was.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 300)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Car dealer", city = "München")),
            stops = mapOf(berlin to ("Odeonsplatz" to 82.0)),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Odeonsplatz", readPlaces(client).single().label)
    }

    @Test
    fun aStopBeyondTheFixesOwnUncertaintyDoesNotSpeakForIt() = runTest {
        // ⚠ The radius is the row's accuracy. A stop 419 m away says nothing about a ±94 m dwell, so
        // the city still wins — this is what stops a café being renamed after a nearby station.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 94)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Café", city = "München")),
            stops = mapOf(berlin to ("Universität" to 419.0)),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("München", readPlaces(client).single().label)
    }

    @Test
    fun aPreciseFixKeepsItsVenueAndIsNeverAskedForAStop() = runTest {
        // ⚠⚠ The safety argument, as an assertion: the stop lookup lives *inside* the coarse branch,
        // so a well-fixed café beside a station keeps its venue — and costs no extra request either.
        // Measured, 38 % of Paris cafés have a named stop within 150 m, so a gate that fired on
        // distance alone would rename them.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 12)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Bistro", city = "München")),
            stops = mapOf(berlin to ("Odeonsplatz" to 40.0)),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Bistro", readPlaces(client).single().label)
        assertTrue(geocoder.stopCalls.isEmpty(), "a precise fix must not spend a stop lookup")
    }

    @Test
    fun theSearchRadiusIsTheFixesAccuracyCappedAtTheCeiling() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 5_000) // a wild fix

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Somewhere", city = "München")),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        // ⚠ Capped: an unbounded radius would reach across a city and name a station nobody was near.
        assertEquals(300.0, geocoder.stopCalls.single().second)
    }

    @Test
    fun aCoarseFixWithNoStopAndNoCityKeepsTodaysBehaviour() = runTest {
        // Neither fallback is available → the venue label stands, exactly as before §28.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 300)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Car dealer")),
        )
        enricher(client, proxy, geocoder).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Car dealer", readPlaces(client).single().label)
    }


    // ── §31: the trajectory gate may only tighten ────────────────────────────────

    @Test
    fun aConfidentlyWalkedDwellIsNotRenamedAfterANearbyStation() = runTest {
        // The case this exists for. Poor fix, walked to and from with confidence, and a stop 82 m away
        // — the Paris-café shape (38 % of them have one within 150 m). The city must win.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 300)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Bistro", city = "München")),
            stops = mapOf(berlin to ("Odeonsplatz" to 82.0)),
        )
        enricher(
            client, proxy, geocoder,
            trajectory = { mapOf("a" to TransitAdjacency.Verdict.NOT_TRANSIT) },
        ).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("München", readPlaces(client).single().label)
    }

    @Test
    fun theOdeonsplatzShapeStillGetsItsStation() = runTest {
        // ⚠⚠ The regression guard. Odeonsplatz's outbound leg is the U5 read as walk/conf=0, so the
        // verdict is UNKNOWN — and UNKNOWN must leave §28 exactly as it was.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 300)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Car dealer", city = "München")),
            stops = mapOf(berlin to ("Odeonsplatz" to 82.0)),
        )
        enricher(
            client, proxy, geocoder,
            trajectory = { mapOf("a" to TransitAdjacency.Verdict.UNKNOWN) },
        ).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Odeonsplatz", readPlaces(client).single().label)
    }

    @Test
    fun aTransitBracketedDwellKeepsTheFullRadius() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 100)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Bahnhofstraße", city = "Schwindegg")),
            stops = mapOf(berlin to ("Schwindegg" to 6.0)),
        )
        enricher(
            client, proxy, geocoder,
            trajectory = { mapOf("a" to TransitAdjacency.Verdict.TRANSIT) },
        ).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Schwindegg", readPlaces(client).single().label)
    }

    @Test
    fun aStopUnderfootSurvivesEvenAConfidentWalk() = runTest {
        // ⚠ Tightening is not a veto — 6 m is where you were standing.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 300)

        val geocoder = FakeReverseGeocoder(
            results = mapOf(berlin to GeocodeResult(label = "Bahnhofstraße", city = "Schwindegg")),
            stops = mapOf(berlin to ("Schwindegg" to 6.0)),
        )
        enricher(
            client, proxy, geocoder,
            trajectory = { mapOf("a" to TransitAdjacency.Verdict.NOT_TRANSIT) },
        ).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Schwindegg", readPlaces(client).single().label)
    }

    @Test
    fun aPreciseDwellNeverPaysForTheTrajectoryRead() = runTest {
        // ⚠ Loaded lazily: a sweep with no coarse candidate must not read the moment stream at all.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        recordPlace(client, proxy, "a", berlin, accuracyM = 12)
        var loads = 0
        val geocoder = FakeReverseGeocoder(mapOf(berlin to GeocodeResult(label = "Bistro")))
        enricher(client, proxy, geocoder, trajectory = { loads++; emptyMap() }).backfill(beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("Bistro", readPlaces(client).single().label)
        assertEquals(0, loads, "the trajectory read must not happen for a precise fix")
    }

}
