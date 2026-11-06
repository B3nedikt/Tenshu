package app.tenshu.hub

import io.ktor.client.HttpClient
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.getOrNull
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingOffer
import org.tenshu.sync.client.syncService
import org.tenshu.sync.respondToPairingOffer
import org.tenshu.core.common.util.PairingUri
import org.tenshu.core.common.util.ParsedPairingUri
import org.tenshu.sync.PairingFailedError
import org.tenshu.sync.PairingFailure
import org.tenshu.sync.offerHubPairing
import org.tenshu.sync.respondToHubPairing
import org.tenshu.systemtest.TestClientRole
import org.tenshu.systemtest.TestSystem
import org.tenshu.systemtest.bootstrapAsHost

/**
 * H1 deliverable (b): two-party system test over the in-process relay
 * (`:system-test-utils`, the `PairingVmFlowSystemTest` harness shape) proving the
 * headless hub runtime — the REAL production assembly, sqlite store and all — completes
 * offer-flow pairing driven by [HubPairingCli] (a `TestClient` phone plays the §4
 * responder) and then converges realm state: a folder the phone creates after pairing
 * shows up in the hub's store via the ordinary scheduler drain/pull.
 *
 * Threading mirrors `PairingSystemTest`: `runBlocking` + real time (the offer poll and
 * both sync schedulers run at real, test-fast cadences).
 */
class HubPairingSystemTest {

    @Test
    fun headlessHub_pairsViaOfferFlowCli_thenSyncsARowEndToEnd() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-system-test").toFile()
        var runtime: HubRuntime? = null
        try {
            // Phone: an existing realm device (the responder).
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            val bootstrap = phone.bootstrapAsHost(deviceName = "Phone")
            assertTrue(bootstrap is TResult.Success, "phone realm bootstrap failed: $bootstrap")

            // Hub: the production headless assembly, pointed at the in-process relay.
            runtime = HubRuntime.assemble(
                dataRoot = dataRoot,
                syncIntervalMs = 250,
                overrides = listOf(
                    module {
                        // The testApplication client dispatches into the in-process relay
                        // pipeline; paths are relative, so the base URL is empty — the
                        // same two swaps `systemTestPlatformModule` makes.
                        single<HttpClient> { system.relayHttpClient }
                        single(named("syncRelayUrl")) { "" }
                    },
                ),
            )
            runtime.start()
            assertFalse(runtime.isPaired(), "hub must start unpaired on a fresh data dir")

            // 1. Hub arms the STANDARD offerer role via the CLI; capture its output.
            val lines = Collections.synchronizedList(mutableListOf<String>())
            val cli = HubPairingCli(
                client = runtime.client,
                out = { lines += it },
                pollInterval = 200.milliseconds,
                timeout = 30.seconds,
            )
            val outcome = async(Dispatchers.Default) { cli.runOnce("Test Hub") }

            // 2. The CLI prints the tenshu://offer URI (below the ASCII QR).
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
            assertTrue(offerUri.startsWith("tenshu://offer"), "expected an offer URI, got $offerUri")

            // 3. Phone "scans" + approves — the §4 responder role.
            val promptResult = phone.tenshuClient.syncService.respondToPairingOffer(offerUri)
            assertTrue(promptResult is TResult.Success, "respondToPairingOffer failed: $promptResult")
            val prompt = promptResult.getOrNull()!!
            assertEquals("Test Hub", prompt.deviceName.get())
            val approve = phone.tenshuClient.syncService
                .approvePairingOffer(prompt.pairingSecretHash.get())
            assertTrue(approve is TResult.Success, "approvePairingOffer failed: $approve")

            // 4. The hub's poll lands on approved; keys are installed; SAS matches the
            //    responder's prompt (the offer flow's post-hoc comparison, §4.5).
            // ⚠⚠ 45 s against the CLI's own 30 s, and NOT the same number. `runOnce`'s budget
            // now covers the WHOLE session (arming included) — it used to cover
            // `pollForOutcome` only, and to test `elapsedNow() < timeout` at the TOP of each
            // poll, so one slow `getOfferStatus` overran it. With equal budgets the
            // overrun expires this wait first and the failure is a bare
            // `TimeoutCancellationException: Timed out waiting for 30000 ms` naming nothing. Forced
            // pair, 2026-09-15: equal budgets give exactly that; a larger outer budget gives
            // `expected approval, got TimedOut`. `docs/tech-debt.md` § "Two 30-second budgets".
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
            assertEquals(prompt.sas.get(), terminal.sas, "post-hoc SAS must match the phone's prompt")
            assertEquals(
                phone.syncManager.currentOwnerId,
                runtime.syncManager.currentOwnerId,
                "the hub must have joined the phone's realm",
            )
            assertTrue(runtime.isPaired())

            // 5. End-to-end row sync: the phone creates a folder AFTER pairing; the
            //    hub's ordinary scheduler pull surfaces it in the hub's own store.
            val folderPath = "/HubFolder"
            val created = phone.tenshuClient.fileService.defaultFileSystem.createFolder(folderPath)
            assertTrue(created is TResult.Success, "phone createFolder failed: $created")

            val hubContext = runtime.koin.get<ClientRegistry>()
                .getExecutionContext(HubRuntime.PACKAGE_NAME)
            system.advanceUntil(timeout = 30.seconds) {
                hubContext.rootFilePaths().contains(folderPath)
            }
        } finally {
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    /**
     * H2 (hub proposal §11): the full **printed-static-key** headless pairing flow —
     * provision → arm (signed ephemeral offer onto `/sync/pairing/hub-offer`) → the phone
     * responder fetches the offer, verifies it against the printed label's key, approves →
     * the hub's standard poll installs keys → a row created after pairing syncs into the
     * hub's store. Plus the §11.4 negatives: a responder holding a WRONG printed verify
     * key must refuse (hard error), and an un-armed hub yields the not-armed error.
     */
    @Test
    fun headlessHub_pairsViaPrintedLabel_thenSyncsARowEndToEnd() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-h2-system-test").toFile()
        var runtime: HubRuntime? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            val bootstrap = phone.bootstrapAsHost(deviceName = "Phone")
            assertTrue(bootstrap is TResult.Success, "phone realm bootstrap failed: $bootstrap")

            runtime = HubRuntime.assemble(
                dataRoot = dataRoot,
                syncIntervalMs = 250,
                overrides = listOf(
                    module {
                        single<HttpClient> { system.relayHttpClient }
                        single(named("syncRelayUrl")) { "" }
                    },
                ),
            )
            runtime.start()

            // 0. Provision — the factory step that mints the printed label.
            val label = HubProvisioning.provision(dataRoot, runtime.syncManager)

            // Negative (pre-arm): nothing is waiting at the rendezvous yet.
            val early = phone.tenshuClient.syncService.respondToHubPairing(label.uri)
            assertTrue(early is TResult.Failure, "responding before the hub arms must fail")
            // ⚠⚠ The typed reason, not a message prefix. The `ERROR_*` codes this used to match
            // travelled inside `GenericError.message`, so the only way to assert one was to search
            // English — which `core-error-copy-catalogue.md` §5.6 removed on purpose.
            assertEquals(
                PairingFailure.OfferNotFound,
                (early.error as PairingFailedError).reason,
                "expected the not-armed error, got: ${early.error}",
            )

            // 1. Hub arms: posts the signed ephemeral offer, then polls the return leg.
            val lines = Collections.synchronizedList(mutableListOf<String>())
            val cli = HubPairingCli(
                client = runtime.client,
                out = { lines += it },
                pollInterval = 200.milliseconds,
                timeout = 30.seconds,
            )
            val outcome = async(Dispatchers.Default) { cli.runHubOnce(label.uri, "Printed Hub") }

            // 2. Wait until the signed offer is actually on the relay.
            try {
                withTimeout(10.seconds) {
                    while (lines.toList().none { it.contains("Signed pairing offer posted") }) delay(50)
                }
            } catch (e: TimeoutCancellationException) {
                // ⚠⚠ Where an arming stall lands — NOT the terminal wait below. `offerHubPairing` runs
                // BEFORE the CLI prints anything, so a hang there expires THIS 10 s wait long
                // before the CLI's own (now whole-session) budget, and never reaches that one.
                // Forced pair, 2026-09-15: bare, it reports `Timed out waiting for 10000 ms`
                // and names nothing.
                // `docs/tech-debt.md` § "Two 30-second budgets".
                // The "[...]" markers are the CLI's state machine; the rest is QR and the URI.
                val armed = lines.toList().filter { it.startsWith("[") }
                throw AssertionError("CLI never posted the signed offer within 10s; armed=$armed", e)
            }

            // 3. Phone "scans" the PRINTED label and responds: fetches the relay offer,
            //    verifies the signature against the printed key, and gets the approval
            //    prompt — WITHOUT an SAS (§11.4: the signature replaces it).
            val promptResult = phone.tenshuClient.syncService.respondToHubPairing(label.uri)
            assertTrue(promptResult is TResult.Success, "respondToHubPairing failed: $promptResult")
            val prompt = promptResult.getOrNull()!!
            assertEquals("Printed Hub", prompt.deviceName.get())
            assertEquals("", prompt.sas.get(), "the hub flow must not carry an SAS")

            // 4. Standard approval — the offer-flow op, unchanged.
            val approve = phone.tenshuClient.syncService
                .approvePairingOffer(prompt.pairingSecretHash.get())
            assertTrue(approve is TResult.Success, "approvePairingOffer failed: $approve")

            // 5. The hub's poll lands on approved; keys installed; realm joined.
            // ⚠⚠ 45 s against the CLI's own 30 s, and NOT the same number. `runHubOnce`'s budget
            // now covers the WHOLE session (arming included) — it used to cover
            // `pollForOutcome` only, and to test `elapsedNow() < timeout` at the TOP of each
            // poll, so one slow `getOfferStatus` overran it. With equal budgets the
            // overrun expires this wait first and the failure is a bare
            // `TimeoutCancellationException: Timed out waiting for 30000 ms` naming nothing. Forced
            // pair, 2026-09-15: equal budgets give exactly that; a larger outer budget gives
            // `expected approval, got TimedOut`. `docs/tech-debt.md` § "Two 30-second budgets".
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
            assertEquals(
                phone.syncManager.currentOwnerId,
                runtime.syncManager.currentOwnerId,
                "the hub must have joined the phone's realm",
            )
            assertTrue(runtime.isPaired())

            // 6. End-to-end row sync into the hub's store.
            val folderPath = "/HubLabelFolder"
            val created = phone.tenshuClient.fileService.defaultFileSystem.createFolder(folderPath)
            assertTrue(created is TResult.Success, "phone createFolder failed: $created")
            val hubContext = runtime.koin.get<ClientRegistry>()
                .getExecutionContext(HubRuntime.PACKAGE_NAME)
            system.advanceUntil(timeout = 30.seconds) {
                hubContext.rootFilePaths().contains(folderPath)
            }
        } finally {
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    @Test
    fun responderWithWrongPrintedKey_refusesTheOffer() = runBlocking {
        val system = TestSystem.builder().build()
        val dataRoot = createTempDirectory("hub-h2-wrong-key-test").toFile()
        var runtime: HubRuntime? = null
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            val bootstrap = phone.bootstrapAsHost(deviceName = "Phone")
            assertTrue(bootstrap is TResult.Success, "phone realm bootstrap failed: $bootstrap")

            runtime = HubRuntime.assemble(
                dataRoot = dataRoot,
                syncIntervalMs = 250,
                overrides = listOf(
                    module {
                        single<HttpClient> { system.relayHttpClient }
                        single(named("syncRelayUrl")) { "" }
                    },
                ),
            )
            runtime.start()
            val label = HubProvisioning.provision(dataRoot, runtime.syncManager)

            // Arm once (no poll loop needed — post the signed offer directly).
            val armed = runtime.client.syncService.offerHubPairing(label.uri, "Printed Hub")
            assertTrue(armed is TResult.Success, "offerHubPairing failed: $armed")

            // The attacker-label scenario (T-P-11 shape): same device id + secret, but a
            // DIFFERENT printed verify key. Since workstream B the label's id is no longer its key's,
            // so the phone refuses it with the same VerifyFailed before even fetching the offer (and
            // a label re-derived for the new key names an id no hub offer carries — also VerifyFailed).
            val parsed = PairingUri.parse(label.uri) as ParsedPairingUri.Hub
            val tamperedLabel = PairingUri.hubUri(
                hubDeviceId = parsed.hubDeviceId,
                hubVerifyKey = ByteArray(32) { (it + 99).toByte() },
                pairingSecret = parsed.pairingSecret,
            )
            val refused = phone.tenshuClient.syncService.respondToHubPairing(tamperedLabel)
            assertTrue(refused is TResult.Failure, "a wrong printed key must refuse, got: $refused")
            assertEquals(
                PairingFailure.VerifyFailed,
                (refused.error as PairingFailedError).reason,
                "expected the hard verification error, got: ${refused.error}",
            )
            assertFalse(runtime.isPaired(), "no pairing may result from a refused offer")
        } finally {
            runtime?.shutdown()
            system.shutdown()
            dataRoot.deleteRecursively()
        }
    }

    /** Root-folder listing straight from a proxy's store — same shape as `PostPairingFolderSyncSystemTest`. */
    private fun ExecutionContext.rootFilePaths(): Set<String> {
        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = rootId,
                fieldRef = Folder.Fields.FILES,
            ),
            isEntity = false,
        )
        return (getArrayData(ref)?.toList() ?: emptyList()).map { entry ->
            when (entry) {
                is Entity<*> -> entry.getFieldValue<TString>(File.Field.PATH).get()
                else -> (entry as org.tenshu.core.Composite)
                    .getField<TString>(File.Field.PATH).get()
            }
        }.toSet()
    }
}
