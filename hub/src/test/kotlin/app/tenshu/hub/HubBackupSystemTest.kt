package app.tenshu.hub

import io.ktor.client.HttpClient
import java.io.File as JavaFile
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.file.client.fileSyncDefaultPolicyOrNull
import org.tenshu.preference.client.preferenceService
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.client.syncService
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.systemtest.TestClient
import org.tenshu.systemtest.TestClientRole
import org.tenshu.systemtest.TestSystem
import org.tenshu.systemtest.bootstrapAsHost

/**
 * H3 (hub proposal §12): the paired hub is the household's **full second copy** —
 * every file's content bytes retained durably on the hub's disk via the
 * keep-everything device policy + the existing offline keeper, surviving hub
 * restarts and **relay loss**. Extends the H1/H2 harness (in-process relay from
 * `:system-test-utils`, real production hub assembly, sqlite store and all).
 */
class HubBackupSystemTest {

    /** Dead local relay for post-relay-death restarts: fails fast + offline. */
    private val deadRelay = module {
        single(named("syncRelayUrl")) { "http://127.0.0.1:9" }
    }

    /**
     * The "relay dies → data intact at home" claim, end to end:
     *
     *  1. a phone uploads files WITH content bytes (incl. a 0-byte file);
     *  2. the hub pairs, converges, and a backup pass (keep-everything policy pin +
     *     offline-keeper drain) lands every byte on the hub's disk;
     *  3. the RELAY IS SHUT DOWN and the hub runtime RESTARTED (fresh assembly,
     *     same data dir, dead relay) — and the files' bytes are still readable
     *     from the hub, byte for byte, with no relay available.
     */
    @Test
    fun hubHoldsEveryByte_thenRelayDies_dataIsIntactAtHome() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-h3-backup-test").toFile()
        var runtime: HubRuntime? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)

            // 1. Peer uploads: real content bytes + the 0-byte edge case.
            val payload = ByteArray(300_000) { (it % 251).toByte() }
            val photoPath = "/photo.bin"
            val emptyPath = "/empty.bin"
            assertTrue(
                phone.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "photo.bin", data = payload)
                        is TResult.Success,
            )
            assertTrue(
                phone.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "empty.bin", data = ByteArray(0))
                        is TResult.Success,
            )

            // 2. Backup passes until the keeper has fetched everything. (Each pass
            //    pins the device-scoped keep-everything policy once, then drains
            //    the HeadlessSafe OfflineKeeper op — no bespoke fetch pipeline.)
            val hubDb = runtime.koin.get<FileDatabase>()
            system.advanceUntil(timeout = 60.seconds, pollInterval = 500.milliseconds) {
                runtime!!.runBackupPassNow()
                hubDb.fileSize(photoPath) != null && hubDb.fileSize(emptyPath) != null
            }
            assertContentEquals(payload, assertNotNull(hubDb.readBytes(photoPath)).get())
            // 0-byte file: present-and-empty, not absent...
            assertEquals(0L, hubDb.fileSize(emptyPath))
            assertEquals(0, assertNotNull(hubDb.readBytes(emptyPath)).get().size)
            // ...and a further pass must not re-select it (no re-fetch loop) nor
            // disturb anything — the keeper's "has local bytes" probe sees 0L ≠ null.
            runtime.runBackupPassNow()
            assertEquals(0L, hubDb.fileSize(emptyPath))
            assertContentEquals(payload, assertNotNull(hubDb.readBytes(photoPath)).get())

            // The copy is a real on-disk replica under the hub's data dir.
            assertTrue(JavaFile(dataRoot, "content/photo.bin").isFile)

            // The keep-everything policy is DEVICE-scoped to the hub: the phone's
            // own default-policy row was never touched.
            assertEquals(
                SyncPolicy.KEEP_OFFLINE,
                runtime.client.preferenceService
                    .fileSyncDefaultPolicyOrNull(runtime.syncManager.currentDeviceId!!),
            )
            assertNull(
                phone.tenshuClient.preferenceService
                    .fileSyncDefaultPolicyOrNull(phone.syncManager.currentDeviceId!!),
                "the hub's keep-everything pin must not leak into the phone's device policy",
            )

            // 3. Relay dies; hub restarts fresh over the same data dir.
            runtime.shutdown()
            runtime = null
            system.shutdown() // the relay (blocks + content plane) is GONE

            val restarted = assembleHubOffline(dataRoot)
            runtime = restarted
            restarted.start()
            assertTrue(restarted.isPaired(), "the restarted hub must still be realm-bound")

            val restartedDb = restarted.koin.get<FileDatabase>()
            assertContentEquals(
                payload,
                assertNotNull(
                    restartedDb.readBytes(photoPath),
                    "bytes must be readable from the hub with NO relay available",
                ).get(),
            )
            assertEquals(0L, restartedDb.fileSize(emptyPath))
            assertEquals(0, assertNotNull(restartedDb.readBytes(emptyPath)).get().size)
        } finally {
            runtime?.shutdown()
            runCatching { system.shutdown() } // idempotent-ish; already stopped in the happy path
            dataRoot.deleteRecursively()
        }
    }

    /**
     * Catch-up: a file uploaded while the hub is STOPPED is fetched once the hub
     * comes back — ordinary drain brings the metadata/manifest, the next backup
     * pass brings the bytes.
     */
    @Test
    fun fileUploadedWhileHubIsStopped_isFetchedOnCatchUp() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-h3-catchup-test").toFile()
        var runtime: HubRuntime? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)

            val earlyPayload = byteArrayOf(1, 2, 3, 4, 5)
            assertTrue(
                phone.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "early.bin", data = earlyPayload)
                        is TResult.Success,
            )
            val hubDb = runtime.koin.get<FileDatabase>()
            system.advanceUntil(timeout = 60.seconds, pollInterval = 500.milliseconds) {
                runtime!!.runBackupPassNow()
                hubDb.fileSize("/early.bin") != null
            }

            // Hub goes down; life goes on: the phone uploads another file.
            runtime.shutdown()
            runtime = null
            val latePayload = ByteArray(4096) { (it % 199).toByte() }
            assertTrue(
                phone.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "late.bin", data = latePayload)
                        is TResult.Success,
            )

            // Hub restarts (same data dir, relay still up): ordinary drain pulls the
            // new metadata, the backup pass fetches the missing bytes.
            val restarted = assembleHub(system, dataRoot)
            runtime = restarted
            restarted.start()
            val restartedDb = restarted.koin.get<FileDatabase>()
            system.advanceUntil(timeout = 60.seconds, pollInterval = 500.milliseconds) {
                restarted.runBackupPassNow()
                restartedDb.fileSize("/late.bin") != null
            }
            assertContentEquals(latePayload, assertNotNull(restartedDb.readBytes("/late.bin")).get())
            // The earlier copy is still intact.
            assertContentEquals(earlyPayload, assertNotNull(restartedDb.readBytes("/early.bin")).get())
        } finally {
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    // ---- harness glue (H1/H2 shapes) -------------------------------------------------

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

    /** Post-relay-death assembly: same data dir, relay unreachable (fails fast). */
    private fun assembleHubOffline(dataRoot: JavaFile): HubRuntime =
        HubRuntime.assemble(dataRoot = dataRoot, syncIntervalMs = 250, overrides = listOf(deadRelay))

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
