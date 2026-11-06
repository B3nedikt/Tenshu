package app.tenshu.hub

import app.tenshu.home.data.ClientHubStatusSource
import app.tenshu.home.data.HubAwareHomeDevicesRepository
import app.tenshu.home.data.MockHomeDevicesRepository
import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.DeviceState
import io.ktor.client.HttpClient
import java.io.File as JavaFile
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.hub.management.client.getHubStatus
import org.tenshu.hub.management.client.hubManagementService
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.client.syncService
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.systemtest.TestClient
import org.tenshu.systemtest.TestClientRole
import org.tenshu.systemtest.TestSystem
import org.tenshu.systemtest.bootstrapAsHost

/**
 * Home's storage tile over REAL hub data, end to end (hub proposal §13 H4 →
 * `home-and-mia-apps.md` M1 tile): the hub publishes a [org.tenshu.hub.management.HubStatus]
 * with disk + replica figures → a manager-side client (assembled the way
 * `:home-desktop` wires it — a `TenshuClient` under `ClientHubStatusSource` +
 * `HubAwareHomeDevicesRepository`) reads it through Home's repository → the
 * tile model carries the real free/total + replica figures. Then the hub goes
 * silent → the tile flips to stale after the threshold, figures still shown.
 */
class HomeStorageTileSystemTest {

    @Test
    fun hubStatusDrivesTheStorageTile_thenGoesStaleWhenTheHubFallsSilent() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-home-tile-test").toFile()
        var runtime: HubRuntime? = null
        var agent: HubManagementAgent? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)

            // Give the replica real content so the tile's replica figures are live.
            val payload = ByteArray(48_000) { (it % 251).toByte() }
            assertTrue(
                phone.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "photo.bin", data = payload)
                        is TResult.Success,
            )

            agent = HubManagementAgent(
                runtime = runtime,
                deviceName = "Backup Hub",
                version = "home-tile-test",
                // ⚠ Tightened TOGETHER with the reader's `staleAfterMs` below, in production's ratio
                // (a 1 h heartbeat under a 2 h stale window — `HubAwareHomeDevicesRepository`). The
                // default 1 h heartbeat left `freshAsOf` frozen at the last forced publish once the
                // figures stopped changing (the tick is change-gated), so "not stale" was a property
                // of how fast the next 3 s went — the 2026-08-17 red, and deterministic with a
                // 3.5 s hold before the subscription. A live hub is what "not stale" asserts.
                heartbeatMs = 1_500,
                refreshTickMs = 200,
                restartHook = { error("no restart in this test") },
            )
            agent.start()

            // Drive backup passes until the uploaded bytes have converged into
            // the replica AND the refreshed row (with live replica + disk
            // figures) has synced back to the manager — the hub needs a sync
            // cycle or two before the file's metadata is visible to the pass.
            system.advanceUntil(timeout = 30.seconds, pollInterval = 500.milliseconds) {
                runtime!!.runBackupPassNow()
                agent!!.publishStatus(force = true)
                val row = phone.tenshuClient.hubManagementService.getHubStatus()
                (row?.replicaFileCount?.get() ?: 0L) >= 1L
            }

            // Home's repository, exactly as home-desktop assembles it (mock
            // delegate + the client-backed source over a real TenshuClient) —
            // only the staleness knobs are test-tightened.
            val repository = HubAwareHomeDevicesRepository(
                delegate = MockHomeDevicesRepository(),
                source = ClientHubStatusSource(phone.tenshuClient),
                staleAfterMs = 3_000,
                recheckIntervalMs = 200,
            )

            // 1. The tile goes live with the hub's real figures (the converged
            //    row — earlier pre-backup snapshots are real too, just not yet
            //    carrying the replica content this test uploaded) — and FRESH:
            //    the first converged row this subscription sees may already be
            //    a heartbeat old under load; the next heartbeat's row is not,
            //    and waiting for it is what the tile itself does.
            val liveDevices = withTimeout(30.seconds) {
                repository.devices().first { devices ->
                    devices.storageState()?.live?.let { it.replicaFileCount >= 1L && !it.stale } == true
                }
            }
            val storage = assertNotNull(liveDevices.storageState())
            val live = assertNotNull(storage.live)
            assertFalse(live.stale)
            assertEquals("Backup Hub", live.hubName)
            assertEquals(dataRoot.totalSpace, storage.totalBytes, "total = the data-dir filesystem's capacity")
            assertTrue(storage.usedBytes in 1 until storage.totalBytes)
            assertTrue(storage.freeBytes in 1 until storage.totalBytes)
            assertTrue(live.replicaFileCount >= 1L, "backup pass landed at least the uploaded file")
            assertTrue(live.replicaByteCount >= payload.size.toLong())
            assertTrue(live.lastBackupPassAtEpochMs > 0L)
            assertTrue(live.freshAsOfEpochMs > 0L)
            // The Hub device row rides the same freshness signal (stretch).
            assertEquals(
                DeviceState.Connectivity(online = true),
                liveDevices.first { it.kind == DeviceKind.Hub }.state,
            )

            // 2. Hub falls silent → staleness flips after the threshold; the
            //    (real) figures stay visible, marked stale; the Hub row goes offline.
            agent.stop()
            agent = null
            val staleDevices = withTimeout(30.seconds) {
                repository.devices().first { it.storageState()?.live?.stale == true }
            }
            val staleStorage = assertNotNull(staleDevices.storageState())
            assertEquals(storage.totalBytes, staleStorage.totalBytes)
            assertEquals(
                DeviceState.Connectivity(online = false),
                staleDevices.first { it.kind == DeviceKind.Hub }.state,
            )
        } finally {
            agent?.stop()
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    private fun List<Device>.storageState(): DeviceState.Storage? =
        firstOrNull { it.kind == DeviceKind.Storage }?.state as? DeviceState.Storage

    // ---- harness glue (H1/H2/H4 shapes) -----------------------------------------------

    private fun assembleHub(system: TestSystem, dataRoot: JavaFile): HubRuntime =
        HubRuntime.assemble(
            dataRoot = dataRoot,
            syncIntervalMs = 250,
            overrides = listOf(
                module {
                    single<HttpClient> { system.relayHttpClient }
                    single(named("syncRelayUrl")) { "" }
                },
            ),
        )

    /** The H1 offer-flow pairing dance: hub CLI arms, the phone responds + approves. */
    private suspend fun pairViaOfferFlow(phone: TestClient, runtime: HubRuntime) = coroutineScope {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val cli = HubPairingCli(
            client = runtime.client,
            out = { lines += it },
            pollInterval = 200.milliseconds,
            timeout = 30.seconds,
        )
        val outcome = async(Dispatchers.Default) { cli.runOnce("Backup Hub") }
        val offerUri = try {
            withTimeout(10.seconds) {
                while (true) {
                    lines.toList().firstOrNull { it.startsWith("Offer URI: ") }
                        ?.let { return@withTimeout it.removePrefix("Offer URI: ") }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: TimeoutCancellationException) {
            // ⚠⚠ Where an arming stall lands — NOT the terminal wait below. `offerPairing` runs
            // BEFORE the CLI prints anything, so a hang there expires THIS 10 s wait long
            // before the CLI's own (now whole-session) budget, and never reaches that one.
            // Forced pair, 2026-09-15: bare, it reports `Timed out waiting for 10000 ms`
            // and names nothing.
            // `docs/tech-debt.md` § "Two 30-second budgets".
            // The "[...]" markers are the CLI's state machine; the rest is QR and the URI.
            val armed = lines.toList().filter { it.startsWith("[") }
            throw AssertionError("CLI never printed an offer URI within 10s; armed=$armed", e)
        }
        val promptResult = phone.tenshuClient.syncService.respondToPairingOffer(offerUri)
        assertTrue(promptResult is TResult.Success, "respondToPairingOffer failed: $promptResult")
        val approve = phone.tenshuClient.syncService
            .approvePairingOffer(promptResult.getOrNull()!!.pairingSecretHash.get())
        assertTrue(approve is TResult.Success, "approvePairingOffer failed: $approve")
        // ⚠⚠ 45 s against the CLI's own 30 s, and NOT the same number. `runOnce`'s budget
        // now covers the WHOLE session, arming included; it used to cover
        // `pollForOutcome` only and to test `elapsedNow() < timeout` at the TOP of each
        // poll, so one slow `getOfferStatus` overran it. With equal budgets the overrun
        // expires
        // this wait first and the failure is a bare `TimeoutCancellationException: Timed out
        // waiting for 30000 ms` naming nothing. Forced pair, 2026-09-15: equal budgets give exactly
        // that; a larger outer budget gives `expected approval, got TimedOut`. `docs/tech-debt.md`
        // § "Two 30-second budgets".
        val terminal = try {
            withTimeout(45.seconds) { outcome.await() }
        } catch (e: TimeoutCancellationException) {
            // Armed, but no terminal outcome: the poll stalled. An arming stall cannot reach
            // here — the wait above catches that one.
            // The "[...]" markers are the CLI's state machine; the rest is QR and the URI.
            val armed = lines.toList().filter { it.startsWith("[") }
            throw AssertionError("CLI did not return within 45s; armed=$armed", e)
        }
        assertTrue(terminal is PairingOutcome.Approved, "expected approval, got $terminal")
        assertEquals(phone.syncManager.currentOwnerId, runtime.syncManager.currentOwnerId)
    }
}
