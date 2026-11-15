package org.tenshu.systemtest

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.TestApplication
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.HlcTipStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.mailbox.RecoveryChallengeStore
import org.tenshu.sync.relay.module

class TestSystemBuilder {

    private var relayConfig: RelayConfig = RelayConfig(
        // §9.4: SystemTests run with devMode = false so the real JWT path is
        // exercised by default. Fixture-fast tests can opt into devMode = true
        // via [.relayConfig].
        devMode = false,
        bindHost = "127.0.0.1",
        port = 0
    )

    fun relayConfig(config: RelayConfig): TestSystemBuilder {
        relayConfig = config
        return this
    }

    private var durable: DurableContentPlane? = null

    private var stepped = false

    /**
     * Build every [TestClient] of this system in **stepped** mode: no device's `SyncScheduler` or
     * `ScheduleDrainLoop` loop ever starts, and a device ticks only when the test says so —
     * [TestSystem.sync], [TestSystem.syncUntil], [TestSystem.syncUntilQuiet]. See [TestSystem]'s
     * KDoc and `docs/testing.md` §12 for when to reach for it and when not.
     */
    fun stepped(): TestSystemBuilder {
        stepped = true
        return this
    }

    /**
     * CR-2 B5r: run the relay's content plane on the SQLite + filesystem set production runs, in [dir], with
     * the G2 clock and the sweep on [clock], and the sweep in [sweepMode] with T = [graceMs]. The sweep is
     * not scheduled: the test runs it ([TestSystem.contentSweep]) after moving [clock] past T.
     *
     * ⚠ Only the content plane (and the Mode-A share store, which shares its database and blob store): the
     * mailbox and the rest stay in memory, so the push's transaction spans the edge, ref and freed tables,
     * which is what a free and a push serialise on.
     */
    fun durableContentPlane(
        dir: java.io.File,
        clock: () -> Long,
        sweepMode: org.tenshu.sync.relay.ContentSweepMode = org.tenshu.sync.relay.ContentSweepMode.ARMED,
        graceMs: Long = RelayConfig.DEFAULT_CONTENT_GRACE_MS,
    ): TestSystemBuilder {
        durable = DurableContentPlane(dir, clock, sweepMode, graceMs)
        return this
    }

    private class DurableContentPlane(
        val dir: java.io.File,
        val clock: () -> Long,
        val sweepMode: org.tenshu.sync.relay.ContentSweepMode,
        val graceMs: Long,
    )

    suspend fun build(): TestSystem {
        val knownDevices = KnownDevicesStore()
        val realmStore = RealmStore()
        val challengeStore = RecoveryChallengeStore()
        val hlcTipStore = HlcTipStore()
        val syncMailbox = SyncMailbox()
        val admissionRevocationStore = AdmissionRevocationStore()
        val plane = durable
        val clock: () -> Long = plane?.clock ?: System::currentTimeMillis
        val database = plane?.let {
            it.dir.mkdirs()
            org.tenshu.sync.relay.storage.RelayDatabase("jdbc:sqlite:${java.io.File(it.dir, "relay.db").absolutePath}")
        }
        val files = plane?.let { org.tenshu.sync.relay.storage.FileBlobStore(java.io.File(it.dir, "blobs")) }
        // ⚠ Passed explicitly rather than left to `module`'s default, so a test can read them: a
        // lifecycle assertion has to read the ref index and the bytes. Freeing the bytes while leaving
        // the index behind is a real failure mode and is invisible from the client side.
        val contentRefStore: org.tenshu.sync.relay.mailbox.ContentRefRegistry =
            database?.let { org.tenshu.sync.relay.storage.SqliteContentRefStore(it) } ?: ContentRefStore()
        val contentEdgeStore: org.tenshu.sync.relay.mailbox.ContentEdgeRegistry =
            database?.let { org.tenshu.sync.relay.storage.SqliteContentEdgeStore(it, clock) } ?: ContentEdgeStore()
        val contentBlobStore: org.tenshu.sync.relay.mailbox.ContentBlobRegistry =
            if (database != null && files != null) org.tenshu.sync.relay.storage.SqliteContentBlobStore(database, files) else ContentBlobStore()
        // CR-2 B4: exposed so a test can drive a logical free (B5's sweep is the only production caller).
        val contentFreedStore: org.tenshu.sync.relay.mailbox.ContentFreeing =
            database?.let { org.tenshu.sync.relay.storage.SqliteContentFreedStore(it, contentRefStore, clock) }
                ?: org.tenshu.sync.relay.mailbox.ContentFreedStore(contentEdgeStore, contentRefStore, contentBlobStore)
        val reclaimJob = if (database != null && files != null) org.tenshu.sync.relay.storage.ContentReclaimJob(database, files) else null
        // Mode-A shares ride the durable plane too, so a test can read the `share_record` row production
        // writes (F3: it must carry no clear file name, MIME type or size).
        val shareStore: org.tenshu.sync.relay.mailbox.ShareRegistry =
            if (database != null && files != null) org.tenshu.sync.relay.storage.SqliteShareStore(database, files)
            else org.tenshu.sync.relay.mailbox.ShareStore()
        // CR-2 B5r: built here (not from the config) so its clock is the plane's, and never scheduled.
        val contentSweep = plane?.let {
            org.tenshu.sync.relay.storage.ContentSweepJob(
                mode = it.sweepMode,
                graceMs = it.graceMs,
                intervalMs = RelayConfig.DEFAULT_CONTENT_SWEEP_INTERVAL_MS,
                edges = contentEdgeStore,
                freeing = contentFreedStore,
                refs = contentRefStore,
                blobs = contentBlobStore,
                drainer = reclaimJob,
                clock = it.clock,
                log = { line -> println(line) },
            )
        }

        // Long-lived TestApplication (vs the closure-scoped `testApplication { }`
        // function) so the relay stays up across multiple TestClient setup calls
        // and across the test body itself.
        val relayApp = TestApplication {
            application {
                module(
                    config = relayConfig,
                    knownDevices = knownDevices,
                    realmStore = realmStore,
                    challengeStore = challengeStore,
                    hlcTipStore = hlcTipStore,
                    syncMailbox = syncMailbox,
                    admissionRevocationStore = admissionRevocationStore,
                    contentRefStore = contentRefStore,
                    contentEdgeStore = contentEdgeStore,
                    contentBlobStore = contentBlobStore,
                    contentFreeing = contentFreedStore,
                    contentReclaimJob = reclaimJob,
                    // ⚠ Its mode is what D3/D5 read. `module` schedules a non-off job every 5 REAL minutes,
                    // longer than any system test, so in practice only the test runs it.
                    contentSweepJob = contentSweep,
                    shareStore = shareStore,
                )
            }
        }
        relayApp.start()

        // The default `relayApp.client` has no plugins. Production wires
        // `install(ContentNegotiation) { json() }` on the proxy's HttpClient
        // (`:control-shared` desktop `PlatformProxyModule`); the relay's
        // routes accept JSON bodies, so without this `RestSyncRelay`'s
        // first JSON POST fails with "Fail to prepare request body for sending".
        val configuredClient = relayApp.createClient {
            install(ContentNegotiation) { json() }
        }

        return TestSystem(
            relayApp = relayApp,
            relayHttpClient = configuredClient,
            knownDevices = knownDevices,
            realmStore = realmStore,
            hlcTipStore = hlcTipStore,
            admissionRevocations = admissionRevocationStore,
            syncMailbox = syncMailbox,
            contentRefs = contentRefStore,
            contentEdges = contentEdgeStore,
            contentBlobs = contentBlobStore,
            contentFreeing = contentFreedStore,
            contentSweep = contentSweep,
            stepped = stepped,
            syncClock = if (stepped) TestSyncClock(startMs = System.currentTimeMillis()) else null,
            onShutdown = { database?.close() },
        )
    }
}
