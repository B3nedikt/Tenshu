package org.tenshu.systemtest

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.common.security.BouncyCastleEd25519Backend
import org.tenshu.core.common.security.EntitlementTokenClaims
import org.tenshu.core.common.security.encodeEntitlementToken
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.client.fileService
import org.tenshu.identity.EntitlementSignature
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.client.syncHealth
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.sync.proxy.SyncObserver
import org.tenshu.sync.relay.RelayConfig
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The content plane across BOTH halves — a real client against a real relay.
 *
 * ⚠⚠ Why this exists as a system test rather than more unit tests. The content plane's defects are
 * visible only *in combination*: the client declares and the relay stores; the client deletes and
 * the relay frees, or it does not. Each side's own tests can pass throughout — the client's assert
 * against a fake sink and nothing crosses. A fake that accepts everything cannot fail the way the
 * pair can.
 *
 * The relay holds no reference handles: two rows sharing one `contentId` are two edges in the
 * relay's registers (`content-plane-cr2-quorumless.md` §10), which `ContentSweepSystemTest` covers.
 */
class ContentLifecycleSystemTest {

    private companion object {
        const val T = 15 * 60_000L
    }

    /**
     * ⚠⚠ A delete frees nothing on the device's say-so — the relay's sweep is the only freeing path,
     * and it frees after T. So these tests run the relay's content plane durable and armed
     * (`durableContentPlane`), on a clock they can move past T, and run the sweep themselves.
     */
    private val skew = AtomicLong(0)
    private val relayClock: () -> Long = { System.currentTimeMillis() + skew.get() }

    private fun TestSystemBuilder.armed(): TestSystemBuilder =
        durableContentPlane(Files.createTempDirectory("content-lifecycle").toFile(), relayClock, graceMs = T)

    /** Past T, then one sweep pass: what the deploy's armed sweep does on its own cadence. */
    private fun sweepPastT(system: TestSystem) {
        skew.addAndGet(T)
        requireNotNull(system.contentSweep).runOnce()
    }

    private fun dataIdOf(host: TestClient, path: String): DataId =
        DataId.StringDataId.create(
            context = host.executionContext.context,
            type = File.ID,
            keyValue = path,
        )

    private fun contentIdOf(host: TestClient, path: String): String? =
        host.executionContext.database.getData(dataIdOf(host, path))?.let { row ->
            (host.executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
        }

    /** Every blob the ref index names for [contentId] is held — the content is whole on the relay. */
    private fun landed(system: TestSystem, ownerId: String, contentId: String?): Boolean {
        if (contentId == null) return false
        val refs = system.contentRefs.refsOf(ownerId, contentId)
        return refs.isNotEmpty() && system.contentBlobs.blobIndex(ownerId).keys.containsAll(refs)
    }

    private suspend fun connectivityOf(client: TestClient): String? =
        client.tenshuClient.syncService.syncHealth().first()?.connectivity?.get()

    @Test
    fun anUploadDeclaresTheRefIndex() = runBlocking {
        // ⚠ The describe is what lets the relay attribute bytes to a content at all.
        val system = TestSystem.builder().build()
        try {
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 1_000,
            )
            host.bootstrapAsHost(deviceName = "Host Tablet")

            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = "paired.bin", data = ByteArray(4_000) { it.toByte() })
                    is TResult.Success<*>
            )
            val ownerId = host.syncManager.currentRealmContext()!!.ownerId
            system.advanceUntil(timeout = 30.seconds) {
                landed(system, ownerId, contentIdOf(host, "/paired.bin"))
            }

            val contentId = contentIdOf(host, "/paired.bin")!!
            assertTrue(
                system.contentRefs.refsOf(ownerId, contentId).isNotEmpty(),
                "the ref index names this content's blobs — without refs nothing can ever free it",
            )
            assertTrue(
                system.contentBlobs.blobCount(ownerId) > 0,
                "and the bytes are actually on the relay",
            )
        } finally {
            system.shutdown()
        }
    }


    @Test
    fun deletingAFileFreesItsBytes_andClearsBOTHIndexTables() = runBlocking {
        // ⭐⭐ The freeing is the relay's sweep, not a device sweep.
        //
        // ⚠⚠ Why it has to cross the wire: that a delete clears the ref index as well as the bytes
        // cannot be shown against in-memory relay stores alone — a relay can free every byte and
        // leave stale ref rows behind with nothing on either side failing.
        val system = TestSystem.builder().armed().build()
        try {
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 200,
            )
            host.bootstrapAsHost(deviceName = "Host Tablet")
            val fs = host.tenshuClient.fileService.defaultFileSystem
            val ownerId = host.syncManager.currentRealmContext()!!.ownerId

            assertTrue(
                fs.uploadFile(parentPath = "/", fileName = "doomed.bin", data = ByteArray(4_000) { it.toByte() })
                    is TResult.Success<*>
            )
            system.advanceUntil(timeout = 30.seconds) {
                landed(system, ownerId, contentIdOf(host, "/doomed.bin"))
            }
            val contentId = contentIdOf(host, "/doomed.bin")!!
            assertTrue(system.contentRefs.refsOf(ownerId, contentId).isNotEmpty(), "precondition: refs")
            assertTrue(system.contentBlobs.blobCount(ownerId) > 0, "precondition: bytes")

            // ⚠⚠ Every byte the relay holds is named by the index. Pins the duplicate-emit defect: a
            // drain that re-emits a still-journalled intent leaves a second encryption of the same
            // chunk that the index does not name. The stray copy is not merely untidy: the manifest
            // names only the newer chunk, and reclamation resolves a content's chunks through its
            // manifest, so those bytes could never be freed by anything.
            assertEquals(
                system.contentRefs.refsOf(ownerId, contentId),
                system.contentBlobs.blobIndex(ownerId).keys,
                "every stored blob is declared for this content — an undeclared blob is a copy " +
                    "no manifest names and no sweep can reach",
            )

            assertTrue(fs.deleteFile("/doomed.bin") is TResult.Success<*>)

            // ⚠ The relay frees, after T. Wait until the delete's edge removal has reached it (the
            // content is unreferenced), then move its clock past T and run its sweep.
            system.advanceUntil(timeout = 60.seconds) {
                system.contentEdges.unreferencedSince(ownerId, contentId) != null
            }
            assertTrue(system.contentBlobs.blobCount(ownerId) > 0, "nothing is freed before T")
            sweepPastT(system)

            // ⚠⚠ The owner uploaded exactly one file and deleted it, so "zero blobs" is the whole
            // invariant — not "the blobs the manifest happened to name". The weaker version lets a
            // duplicate, manifest-less copy of every upload survive unnoticed: freeing resolves
            // chunks THROUGH the manifest, so a copy no manifest names is invisible to it and to
            // every assertion written in its terms.
            assertEquals(
                0,
                system.contentBlobs.blobCount(ownerId),
                "the bytes are freed; left=${system.contentBlobs.blobIndex(ownerId).keys}",
            )
            assertTrue(
                system.contentRefs.refsOf(ownerId, contentId).isEmpty(),
                "and the ref index goes with them — leaving it describes bytes the relay no " +
                    "longer holds",
            )
        } finally {
            system.shutdown()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // ⭐⭐ quota → `507` → free space → recover, in ONE process.
    //
    // The path behind the over-quota `507` storm and the refusal-latch deadlock. A unit test of
    // either half hands in the other half's answer, which is exactly what both failures lack.
    //
    // ⚠ The quota comes from a **bearer token**, not an injected verifier. The relay meters a token
    // write against the token's own `quotaBytes` claim (`resolveEntitlement` — commerce mints
    // off-catalogue grants, so the claim is authoritative), and the harness client takes an
    // `entitlementTokenProvider`. A dev-signed token with a small grant therefore reaches
    // the real verifier, the real enforcement gate and the real `putWithinQuota`, with no test seam in
    // the relay at all.
    //
    // ⚠⚠ Every signal is OBSERVED, never inferred. A 507 test is the easiest one to pass for the
    // wrong reason — a 402 or a dropped PUT also leaves the bytes off the relay, and a harness content
    // client without production's credential pair gets exactly that 402 (see the `ContentBlobStore`
    // binding in `systemTestPlatformModule`) — so the refusal is asserted three ways: the observer's
    // latch, the health row only a `507` edge can raise (`CONNECTIVITY_STORAGE_FULL`), and the relay
    // holding none of the file.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalEncodingApi::class)
    private fun enforcingSystem(): TestSystemBuilder = TestSystem.builder().armed().relayConfig(
        RelayConfig(
            devMode = false,
            bindHost = "127.0.0.1",
            port = 0,
            entitlementIssuerPublicKeyB64 = Base64.Default.encode(EntitlementSignature.DEV_PUBLIC_KEY),
            entitlementEnforce = true,
        ),
    )

    /** One device whose grant holds "a.bin" and not "b.bin" as well — and which has been refused B. */
    private class RefusedForSpace(
        val host: TestClient,
        val ownerId: String,
        /** The observer the upload path emits through — the one whose latch this is about. */
        val observer: SyncObserver,
        /** Fixed from the second upload on; see [refusedForSpace]. */
        val grantBytes: AtomicLong,
        val usedByA: Long,
    ) {
        val fs get() = host.tenshuClient.fileService.defaultFileSystem
    }

    private suspend fun refusedForSpace(system: TestSystem): RefusedForSpace {
        val ed25519 = BouncyCastleEd25519Backend()
        // Generous until the first file has landed, then shrunk to fit it and not the second, so the
        // ciphertext and manifest overheads are measured rather than assumed. ⚠ Room is NOT made
        // through this: the grant is fixed from the second upload on, and space is freed the way a
        // user frees it — by deleting a file.
        val grantBytes = AtomicLong(1L shl 30)
        val host = system.client(
            deviceId = "host",
            role = TestClientRole.RealmHost,
            pollingIntervalMs = 200,
            // Blinded (`ownerId = null`), so it needs no owner before genesis — and is still metered
            // (`enforceOn_blindedToken_stillQuotaMetered`).
            entitlementTokenProvider = {
                val now = System.currentTimeMillis() / 1000
                encodeEntitlementToken(
                    EntitlementTokenClaims(
                        ownerId = null,
                        tier = "tenshu.drive.test",
                        quotaBytes = grantBytes.get(),
                        issuedAtSec = now - 5,
                        expiresAtSec = now + 3_600,
                    ),
                ) { msg -> ed25519.sign(EntitlementSignature.DEV_SEED, msg) }
            },
        )
        host.bootstrapAsHost(deviceName = "Host Tablet")
        val fs = host.tenshuClient.fileService.defaultFileSystem
        val ownerId = host.syncManager.currentRealmContext()!!.ownerId
        val observer = host.koinApp.koin.get<SyncBlockEmitterHolder>().fileContentEmitter as SyncObserver

        assertTrue(
            fs.uploadFile(parentPath = "/", fileName = "a.bin", data = ByteArray(8_000) { it.toByte() })
                is TResult.Success<*>
        )
        system.advanceUntil(timeout = 30.seconds) { landed(system, ownerId, contentIdOf(host, "/a.bin")) }
        val usedByA = system.contentBlobs.bytesUsed(ownerId)

        // A plus half of B: A still fits, B's one chunk cannot.
        val bSize = 4_000
        grantBytes.set(usedByA + bSize / 2)
        assertTrue(
            fs.uploadFile(parentPath = "/", fileName = "b.bin", data = ByteArray(bSize) { (it * 7).toByte() })
                is TResult.Success<*>,
            "a full relay must not fail the upload — it is local-first, and the intent is journalled",
        )
        system.advanceUntil(timeout = 30.seconds) {
            connectivityOf(host) == SyncHealthStatus.CONNECTIVITY_STORAGE_FULL
        }
        assertTrue(observer.contentStorageRejected, "the latch is set: the 507 reached the emit")
        assertFalse(landed(system, ownerId, contentIdOf(host, "/b.bin")), "the relay holds none of B")
        assertEquals(usedByA, system.contentBlobs.bytesUsed(ownerId), "nothing of B was stored")
        assertEquals(1, observer.deferredContentFigures().count, "B is parked in the journal, not lost")
        return RefusedForSpace(host, ownerId, observer, grantBytes, usedByA)
    }

    /** The device with a backlog: its parked upload is what carries it back over the line. */
    @Test
    fun aFullQuotaRefusesWith507_parksTheUpload_andItLandsOnceSpaceIsFreed() = runBlocking {
        val system = enforcingSystem().build()
        try {
            val world = refusedForSpace(system)

            assertTrue(world.fs.deleteFile("/a.bin") is TResult.Success<*>)
            // ⚠ `content-plane-cr2-b5-spec.md` D5: once A is unreferenced the armed relay holds its
            // bytes but stops charging them, so room is made at once — before T, and with no free at all.

            // ⭐ No restart: the parked upload lands, and every signal lets go of the refusal.
            system.advanceUntil(timeout = 90.seconds) {
                landed(system, world.ownerId, contentIdOf(world.host, "/b.bin"))
            }
            system.advanceUntil(timeout = 30.seconds) {
                !world.observer.contentStorageRejected &&
                    world.observer.deferredContentFigures().count == 0 &&
                    connectivityOf(world.host) == SyncHealthStatus.CONNECTIVITY_ONLINE
            }
            assertTrue(
                system.contentBlobs.bytesUsed(world.ownerId) - system.contentFreeing!!.heldBytes(world.ownerId) <=
                    world.grantBytes.get(),
                "B landed within the grant that refused it — room was made, not granted",
            )
        } finally {
            system.shutdown()
        }
    }

    /**
     * ⚠⚠ Pins the refusal-latch deadlock: the device with NOTHING queued, which only saw a `507` —
     * gigabytes free, "Storage full", every upload refused, cleared only by a force-stop.
     *
     * It cannot recover the way the test above does, because it has no upload of its own to land,
     * and the sticky latch only lets go of an accepted write. Its health therefore has to recover
     * from what it OBSERVES: a pass that attempted nothing is not a fresh refusal. A scheduler that
     * classified a pass by the sticky level would re-arm the backoff on every such pass — and the
     * same signal would make `UploadFile` refuse the upload that could break the loop.
     *
     * ⚠ The harness wires no `StorageCapacityHolder`, so that last step — the up-front refusal —
     * is not reproduced here; what is pinned is the half that fed it, the health signal.
     */
    @Test
    fun aDeviceThatOnlySawA507_recoversWithNothingQueued_andItsNextUploadLands() = runBlocking {
        val system = enforcingSystem().build()
        try {
            val world = refusedForSpace(system)

            // Take B out of the queue, so nothing this device holds could land and clear the latch.
            assertTrue(world.fs.deleteFile("/b.bin") is TResult.Success<*>)
            system.advanceUntil(timeout = 30.seconds) { world.observer.deferredContentFigures().count == 0 }
            // …and make room, so the relay would accept a write again.
            assertTrue(world.fs.deleteFile("/a.bin") is TResult.Success<*>)
            // ⚠ Freed by the relay's sweep after T (`content-plane-cr2-b5-spec.md` D5 already stops
            // charging it once held).
            system.advanceUntil(timeout = 60.seconds) {
                system.contentFreeing!!.heldBytes(world.ownerId) > 0
            }
            sweepPastT(system)
            assertTrue(system.contentBlobs.bytesUsed(world.ownerId) < world.usedByA, "the sweep freed A's bytes")

            // ⭐ The invariant: with nothing queued, "Storage full" lets go on its own.
            system.advanceUntil(timeout = 30.seconds) {
                connectivityOf(world.host) == SyncHealthStatus.CONNECTIVITY_ONLINE
            }
            // ⚠ While the sticky level stays set — by design, since nothing has been accepted yet.
            // Asserted because it is what makes this the edge's recovery and not the level's: had the
            // level been cleared, this test could not tell the two apart.
            assertTrue(
                world.observer.contentStorageRejected,
                "the level is cleared only by an accepted write, and none has happened",
            )

            // And the same process uploads again: the level still holds the inline attempt back, so
            // the file is journalled, and the drain — no longer backed off — lands it.
            assertTrue(
                world.fs.uploadFile(parentPath = "/", fileName = "c.bin", data = ByteArray(2_000) { 3 })
                    is TResult.Success<*>
            )
            system.advanceUntil(timeout = 60.seconds) {
                landed(system, world.ownerId, contentIdOf(world.host, "/c.bin"))
            }
            system.advanceUntil(timeout = 30.seconds) {
                !world.observer.contentStorageRejected &&
                    world.observer.deferredContentFigures().count == 0 &&
                    connectivityOf(world.host) == SyncHealthStatus.CONNECTIVITY_ONLINE
            }
        } finally {
            system.shutdown()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // ⚠⚠ NOT YET REACHABLE — two references to one content → release one, bytes survive; release
    // both, bytes go.
    //
    // ⚠ `contentId` is a per-upload UUID, NOT a content hash: byte-identical uploads get different
    // ids. So identical bytes do not dedup, and the second reference has to come from a divergent
    // rename or rename-vs-edit, per `content-plane-refcount.md` §6e. `uploadFile` also exposes no
    // replace parameter, so an overwrite cannot stage it either.
    // Needs a way to drive a merge that lands two properties on one contentId.
    //
    // ⚠ Left as prose rather than an `@Ignore`d test body: an ignored test that has never run is not
    // evidence of anything, and reads as coverage when the suite is next surveyed.
    // ─────────────────────────────────────────────────────────────────────────────────────────────
}
