package app.tenshu.maps.data

import app.tenshu.maps.model.PlaceSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
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
import org.tenshu.core.orThrow
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.PlaceRoles
import org.tenshu.core.place.RoleSource
import org.tenshu.core.place.recordPlaceRole
import org.tenshu.place.client.placeService
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tenshu.sync.common.SemanticMergerRegistry

/**
 * The Ph9 runtime-exercise (mirrors Timeline's `TimelinePipelineIntegrationTest`): stand up
 * `:place-service` in an in-process proxy + a real `TenshuClient`, seed the Maps mock POIs through
 * the real `recordPlace` op ([MapsPlaceSeeder] — seed==import), and drive
 * [PlaceProjectionRepository] end-to-end — proving the whole pipe (seed → service → relation
 * maintainer → client read → projector → Maps UI model) with **real coordinates from the store**.
 */
class MapsPlacePipelineIntegrationTest {

    @Test
    fun seededMockPlacesProjectThroughTheRepositoryWithRealLocation() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        MapsPlaceSeeder(client, proxy.tContext).seed()

        val repo = PlaceProjectionRepository(client, reach = mapsControlReachability(client, probeScope = null, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe")))
        val places = repo.places().first()

        // Every mock POI lands, each with a real Firenze location read from the store's latE7/lngE7.
        assertEquals(MapsMockData.places.size, places.size)
        val home = places.single { it.id == "home" }
        assertEquals("Home", home.name)
        assertNotNull(home.location)
        assertTrue(home.location!!.latitude in MapsMockData.FIRENZE_MIN_LAT..MapsMockData.FIRENZE_MAX_LAT)
        assertTrue(home.location!!.longitude in MapsMockData.FIRENZE_MIN_LNG..MapsMockData.FIRENZE_MAX_LNG)

        // Thin-screens tradeoff: all under Yours; no saved-list source.
        assertTrue(places.all { it.section == PlaceSection.Yours })
        assertTrue(repo.savedLists().first().isEmpty())

        // Single-entity lookup projects the same store row.
        assertEquals("Café Aurora", repo.place("aurora")?.name)

        // Re-seeding is idempotent (content-addressed by placeId) — no duplicate rows.
        MapsPlaceSeeder(client, proxy.tContext).seed()
        assertEquals(MapsMockData.places.size, repo.places().first().size)
    }

    /**
     * `PlaceRole` through the same stack: `recordPlaceRole` → proxy implementation → relation
     * maintainer → the `roles` relation read back over the client.
     *
     * This is the cheapest guard there is against a **missed registration** — the role's composite +
     * trait in the common module, its `type(...)` + `operation(...)` in the *client* module, the
     * factory dispatch branch and the service relation must all be present for one call to survive
     * the round trip, and each of them fails silently (or as an opaque `error/internal`) on its own.
     */
    @Test
    fun aPlaceRoleRoundTripsThroughTheServiceRelation() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        client.placeService.recordPlaceRole(
            PlaceRole.create(
                context = proxy.tContext,
                placeId = "home",
                role = PlaceRoles.HOME,
                roleSource = RoleSource.USER,
                decidedAt = UnixInstantDto(1_000L),
            ),
        ).orThrow("recordPlaceRole")

        assertEquals(
            listOf(Triple("home", PlaceRoles.HOME, RoleSource.USER)),
            readRoles(client),
        )

        // Content-addressed by placeId: re-recording upserts the one row rather than adding a
        // second — the same idempotence the places assertion above relies on.
        client.placeService.recordPlaceRole(
            PlaceRole.create(
                context = proxy.tContext,
                placeId = "home",
                role = PlaceRoles.WORK,
                roleSource = RoleSource.USER,
                decidedAt = UnixInstantDto(2_000L),
            ),
        ).orThrow("recordPlaceRole")

        assertEquals(
            listOf(Triple("home", PlaceRoles.WORK, RoleSource.USER)),
            readRoles(client),
        )
    }

    /** `(placeId, role, roleSource)` for every stored role row, read over the client. */
    private suspend fun readRoles(client: TenshuClient): List<Triple<String, String, String>> =
        client.placeService.roles
            .select {
                fields(
                    PlaceRole.Fields.PLACE_ID,
                    PlaceRole.Fields.ROLE,
                    PlaceRole.Fields.ROLE_SOURCE,
                )
            }
            .getAll()
            .fold(
                onSuccess = { rows ->
                    rows.toList().map { row: Entity<out PlaceRole> ->
                        Triple(
                            row.getFieldValue<TString>(PlaceRole.Fields.PLACE_ID).get(),
                            row.getFieldValue<TString>(PlaceRole.Fields.ROLE).get(),
                            row.getFieldValue<TString>(PlaceRole.Fields.ROLE_SOURCE).get(),
                        )
                    }
                },
                onFailure = { error("reading PlaceService.roles failed: ${it.schema.id}") },
            )

    // ── one in-process proxy hosting :place-service ──────────────────────────

    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(placeServiceCommonModule)
            registerConfig(placeServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, _ -> placeServiceProxyImplementationFactory(id) },
        ).also {
            it.executionContext.initPlaceService(SemanticMergerRegistry())
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
