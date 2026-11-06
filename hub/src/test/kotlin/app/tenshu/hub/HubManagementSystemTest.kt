package app.tenshu.hub

import io.ktor.client.HttpClient
import java.io.File as JavaFile
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.hub.management.HubCommand
import org.tenshu.hub.management.HubStatus
import org.tenshu.hub.management.client.getHubStatus
import org.tenshu.hub.management.client.hubManagementService
import org.tenshu.hub.management.client.restartHub
import org.tenshu.hub.management.client.runBackupNow
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.client.syncService
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.systemtest.TestClient
import org.tenshu.systemtest.TestClientRole
import org.tenshu.systemtest.TestSystem
import org.tenshu.systemtest.bootstrapAsHost

/**
 * H4 (hub proposal §8, as-built): `HubManagementService` v1 round trip over
 * the H1/H2/H3 harness — the hub **publishes** a [HubStatus] row, a second
 * (manager) realm device **reads** it through the client surface, issues a
 * [HubCommand] which the hub executes **exactly once** (idempotence pinned:
 * re-delivering the same command row is a no-op), and the refreshed status
 * (new `lastBackupPassAt`, command outcome) syncs back to the manager.
 */
class HubManagementSystemTest {

    @Test
    fun statusPublishes_backupCommandExecutesExactlyOnce_outcomeSyncsBack() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-h4-mgmt-test").toFile()
        var runtime: HubRuntime? = null
        var agent: HubManagementAgent? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)
            val hubDeviceId = runtime.syncManager.currentDeviceId!!

            // Give the replica something to hold, so the status row carries
            // real figures once the commanded backup pass has run.
            val payload = ByteArray(64_000) { (it % 251).toByte() }
            assertTrue(
                phone.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "photo.bin", data = payload)
                        is TResult.Success,
            )

            agent = HubManagementAgent(
                runtime = runtime,
                deviceName = "Backup Hub",
                version = "h4-test",
                refreshTickMs = 200,
                restartHook = { error("no restart in this test") },
            )
            agent.start()
            agent.publishStatus(force = true)

            // 1. Hub publishes → the MANAGER device reads it via the client
            //    surface (a local read of the synced row).
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                phone.tenshuClient.hubManagementService.getHubStatus()
                    ?.deviceId?.get() == hubDeviceId
            }
            val firstStatus = assertNotNull(phone.tenshuClient.hubManagementService.getHubStatus())
            assertEquals("Backup Hub", firstStatus.name.get())
            assertEquals("h4-test", firstStatus.version.get())
            assertEquals(true, firstStatus.paired.get())
            assertEquals(runtime.startedAtEpochMs, firstStatus.startedAtEpochMs.get())
            assertTrue(firstStatus.freshAsOf.get() > 0L)
            // Disk figures for Home's storage tile — real filesystem probes.
            assertTrue(firstStatus.diskTotalBytes.get() > 0L)
            assertTrue(firstStatus.diskFreeBytes.get() in 1..firstStatus.diskTotalBytes.get())

            // 2. Manager issues RunBackupNow; the hub executes exactly once and
            //    the refreshed status (new lastBackupPassAt + outcome) syncs back.
            val commandId = "h4-backup-cmd-1"
            assertTrue(
                phone.tenshuClient.hubManagementService
                    .runBackupNow(targetDeviceId = hubDeviceId, commandId = commandId)
                        is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                val s = phone.tenshuClient.hubManagementService.getHubStatus()
                s?.lastCommandId?.get() == commandId &&
                    s.lastCommandOutcome.get() == HubStatus.OUTCOME_OK
            }
            assertEquals(1L, runtime.backupPassCount, "the commanded pass must have run")
            assertEquals(1L, agent.executedCommandCount)
            val afterBackup = assertNotNull(phone.tenshuClient.hubManagementService.getHubStatus())
            assertTrue(afterBackup.lastBackupPassAtEpochMs.get() > 0L)
            // The pass fetched the uploaded bytes → the replica figures are live.
            assertTrue(afterBackup.replicaFileCount.get() >= 1L)
            assertTrue(afterBackup.replicaByteCount.get() >= payload.size.toLong())

            // 3. Idempotence: re-delivering the SAME command row is a no-op —
            //    the issuer re-writes the identical commandId (same row key, a
            //    fresh sync emission), and the hub's consumed journal drops it.
            assertTrue(
                phone.tenshuClient.hubManagementService
                    .runBackupNow(targetDeviceId = hubDeviceId, commandId = commandId)
                        is TResult.Success,
            )
            delay(2_000) // several sync cycles at the 200–250 ms test cadence
            assertEquals(1L, runtime.backupPassCount, "re-delivered command must not re-run the pass")
            assertEquals(1L, agent.executedCommandCount)
        } finally {
            agent?.stop()
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    @Test
    fun restartCommand_invokesHookOnce_systemModeIsUnsupported() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-h4-restart-test").toFile()
        var runtime: HubRuntime? = null
        var agent: HubManagementAgent? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            assertTrue(phone.bootstrapAsHost(deviceName = "Phone") is TResult.Success)

            runtime = assembleHub(system, dataRoot)
            runtime.start()
            pairViaOfferFlow(phone, runtime)
            val hubDeviceId = runtime.syncManager.currentDeviceId!!

            // Stubbed exit hook — production wires shutdown + exitProcess(EXIT_RESTART).
            val restartInvocations = AtomicInteger(0)
            agent = HubManagementAgent(
                runtime = runtime,
                deviceName = "Backup Hub",
                version = "h4-test",
                refreshTickMs = 200,
                restartHook = { restartInvocations.incrementAndGet() },
            )
            agent.start()
            agent.publishStatus(force = true)

            // RestartHub(mode="proxy") → hook invoked, RESTARTING outcome published.
            val restartCmd = "h4-restart-cmd-1"
            assertTrue(
                phone.tenshuClient.hubManagementService.restartHub(
                    targetDeviceId = hubDeviceId,
                    mode = HubCommand.MODE_PROXY,
                    commandId = restartCmd,
                ) is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                val s = phone.tenshuClient.hubManagementService.getHubStatus()
                s?.lastCommandId?.get() == restartCmd &&
                    s.lastCommandOutcome.get() == HubStatus.OUTCOME_RESTARTING
            }
            assertEquals(1, restartInvocations.get())

            // Re-delivery of the same restart row: journal makes it a no-op.
            assertTrue(
                phone.tenshuClient.hubManagementService.restartHub(
                    targetDeviceId = hubDeviceId,
                    mode = HubCommand.MODE_PROXY,
                    commandId = restartCmd,
                ) is TResult.Success,
            )
            delay(2_000)
            assertEquals(1, restartInvocations.get(), "re-delivered restart must not re-fire")

            // mode="system" is v1-unimplemented → typed UNSUPPORTED outcome, no hook.
            val systemCmd = "h4-restart-cmd-2"
            assertTrue(
                phone.tenshuClient.hubManagementService.restartHub(
                    targetDeviceId = hubDeviceId,
                    mode = HubCommand.MODE_SYSTEM,
                    commandId = systemCmd,
                ) is TResult.Success,
            )
            system.advanceUntil(timeout = 30.seconds, pollInterval = 200.milliseconds) {
                val s = phone.tenshuClient.hubManagementService.getHubStatus()
                s?.lastCommandId?.get() == systemCmd &&
                    s.lastCommandOutcome.get() == HubStatus.OUTCOME_UNSUPPORTED
            }
            assertEquals(1, restartInvocations.get(), "mode=system must not invoke the restart hook")
        } finally {
            agent?.stop()
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    // ---- harness glue (H1/H2/H3 shapes) -----------------------------------------------

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
