package app.tenshu.timeline.data.wifi

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.fold
import org.tenshu.core.place.FingerprintProvenance
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.place.client.placeService
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import org.tenshu.sync.common.SemanticMergerRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SyncedWifiFingerprintStore] — F4 (`timeline-recognition-and-place-naming.md` §9.3) over a real
 * place-service proxy: bindings leave the device as hashed tags and come back to any device that
 * mirrors them.
 */
class SyncedWifiFingerprintStoreTest {

    private val bssid = "a4:2b:8c:10:00:01"
    private val place = "cap:490320000,209030000"

    private class FakeLocal : WifiFingerprintStore {
        val bssids = mutableMapOf<String, String>()
        val ssids = mutableMapOf<String, String>()
        override fun remember(bssid: String, placeId: String) { bssids[bssid] = placeId }
        override fun placeFor(bssid: String): String? = bssids[bssid]
        override fun rememberSsid(ssid: String, placeId: String) { ssids[ssid] = placeId }
        override fun placeForSsid(ssid: String): String? = ssids[ssid]
        override fun knownPlaceIds(): Collection<String> = bssids.values.toSet()
    }

    @Test
    fun aLearnedBindingIsWrittenLocallyAndSyncedAsAHashedTag() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        val local = FakeLocal()
        val store = SyncedWifiFingerprintStore(local, client, proxy.tContext, this) { 42L }

        store.remember(bssid, place)
        advanceUntilIdle()

        // Durable immediately, before any sync — capture keeps working offline.
        assertEquals(place, local.bssids[bssid])

        val rows = fingerprints(client)
        assertEquals(1, rows.size)
        val (tag, placeId, provenance) = rows.single()
        assertEquals(place, placeId)
        assertEquals(FingerprintProvenance.LEARNED, provenance)
        // ⚠ The raw BSSID never leaves the device.
        assertEquals(NetworkTag.of(bssid), tag)
        assertTrue(!tag.contains("a4:2b:8c", ignoreCase = true))
    }

    @Test
    fun aRenameTimeBindingSyncsAsUserProvenance() = runTest {
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        val store = SyncedWifiFingerprintStore(FakeLocal(), client, proxy.tContext, this) { 42L }

        store.rememberUserBinding(bssid, place)
        advanceUntilIdle()

        assertEquals(FingerprintProvenance.USER, fingerprints(client).single().third)
    }

    @Test
    fun aMirroredBindingIsRecognisedWithoutAnyLocalRecord() = runTest {
        // The point of F4: a device that never learned this network still knows the place.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        val writer = SyncedWifiFingerprintStore(FakeLocal(), client, proxy.tContext, this) { 42L }
        writer.remember(bssid, place)
        advanceUntilIdle()

        val fresh = SyncedWifiFingerprintStore(FakeLocal(), client, proxy.tContext, this) { 42L }
        assertNull(fresh.placeFor(bssid), "nothing is known before the mirror hydrates")
        fresh.refresh()

        assertEquals(place, fresh.placeFor(bssid))
        assertTrue(place in fresh.knownPlaceIds())
    }

    @Test
    fun theSsidIndexIsNeverSynced() = runTest {
        // Q9 (§10.4): one SSID can span a campus or a chain, and SSIDs are user-authored strings that
        // often contain a person's name. It stays device-local.
        val proxy = placeProxy(this)
        val client = clientOf(proxy)
        val local = FakeLocal()
        val store = SyncedWifiFingerprintStore(local, client, proxy.tContext, this) { 42L }

        store.rememberSsid("Office", place)
        advanceUntilIdle()

        assertEquals(place, local.ssids["Office"])
        assertTrue(fingerprints(client).isEmpty(), "an SSID must never reach the realm")
        assertEquals(place, store.placeForSsid("Office"))
    }

    @Test
    fun theLocalStoreStillAnswersWhenNothingHasSynced() = runTest {
        val proxy = placeProxy(this)
        val local = FakeLocal().apply { remember("aa:bb:cc:dd:ee:ff", "cap:1,2") }
        val store = SyncedWifiFingerprintStore(local, clientOf(proxy), proxy.tContext, this) { 42L }
        assertEquals("cap:1,2", store.placeFor("aa:bb:cc:dd:ee:ff"))
    }

    // ── harness ────────────────────────────────────────────────────────────────

    private suspend fun fingerprints(client: TenshuClient): List<Triple<String, String, String>> =
        client.placeService.networkFingerprints
            .select {
                fields(
                    PlaceNetworkFingerprint.Fields.NETWORK_TAG,
                    PlaceNetworkFingerprint.Fields.PLACE_ID,
                    PlaceNetworkFingerprint.Fields.PROVENANCE,
                )
            }
            .flow().first()
            .fold(
                onSuccess = { rows ->
                    rows.toList().map {
                        Triple(
                            it.getFieldValue<TString>(PlaceNetworkFingerprint.Fields.NETWORK_TAG).get(),
                            it.getFieldValue<TString>(PlaceNetworkFingerprint.Fields.PLACE_ID).get(),
                            it.getFieldValue<TString>(PlaceNetworkFingerprint.Fields.PROVENANCE).get(),
                        )
                    }
                },
                onFailure = { emptyList() },
            )

    private fun placeProxy(scope: kotlinx.coroutines.CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(placeServiceCommonModule)
            registerConfig(placeServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, _ -> placeServiceProxyImplementationFactory(id) },
        ).also { it.executionContext.initPlaceService(SemanticMergerRegistry()) }
    }

    private fun clientOf(proxy: TestProxy): TenshuClient = DefaultTenshuClient(
        TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
    )
}
