@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import org.tenshu.sync.common.deviceIdForPublicKey
import app.tenshu.drive.data.RepositoryResult
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.sync.common.SyncManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1a coverage (ios-drive-parity.md): the route-1 in-process runtime assembles the FULL
 * production proxy graph in this process and serves Drive reads over a test replica
 * (`TestReplicas`) — including rows another runtime instance wrote (the in-process
 * approximation of "Control wrote the replica, Drive reads it"; the true cross-process A/B
 * runs on the ad-hoc-signed simulator via the `-localRuntime` harness).
 *
 * Real dispatchers on purpose ([runBlocking], not `runTest`): the proxy graph owns live
 * `Dispatchers.Default` scopes and the executor initialises lazily on the first op —
 * virtual time has nothing to pump there.
 *
 * ⚠⚠ **…which is exactly why `Main` has to be redirected.** [runBlocking] runs the test body on
 * the test thread, and on Kotlin/Native that thread *is* main — so for as long as a test body is
 * running, anything dispatched to `Dispatchers.Main` never gets to run.
 * `FilesScreenViewModel.pagedFiles` is `.cachedIn(viewModelScope)` (SR-4) and a `ViewModel`'s scope
 * is `Main.immediate`, so without the redirect `cachedIn`'s sharing coroutine has nowhere to run and
 * [pagedListingDeliversRowsOverTheLocalRuntime] hangs for its full 30 s with an empty snapshot while
 * the plain `getFiles` tests beside it pass.
 *
 * ⚠ Redirecting to [Dispatchers.Default] rather than a `TestDispatcher`: the point of this class is
 * that everything is real. A virtual-time dispatcher would need pumping that the proxy graph's own
 * live scopes would not honour anyway.
 */
class DriveLocalRuntimeTest {

    // Main is blocked by runBlocking for the duration of every test body (see the class KDoc), so
    // give `viewModelScope` a dispatcher that actually runs.
    @BeforeTest
    fun redirectMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun restoreMain() = Dispatchers.resetMain()

    private fun tempReplicaDir(): String =
        NSTemporaryDirectory() + "drive-local-runtime-test-" + Random.nextLong().toString(16)

    @Test
    fun assemblesTheFullRuntimeAndListsAnEmptyRoot() = runBlocking {
        val assembly = DriveLocalRuntime.assemble(
            replicaDirectory = tempReplicaDir(),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        val listing = assembly.client.filesRepository.getFiles("/").first()
        assertTrue(listing.isEmpty(), "a fresh replica lists an empty root, got: $listing")
    }

    @Test
    fun readsAFolderWrittenByAnotherRuntimeOverTheSameReplica() = runBlocking {
        val dir = tempReplicaDir()

        val writer = DriveLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        val created = writer.client.filesRepository.createFolder("/route1-shared-folder")
        assertTrue(created is RepositoryResult.Success, "createFolder failed: $created")

        // A second, independent runtime over the SAME replica — its rows are shared by every
        // assembly over its name, so the reader sees the writer's rows the way Drive sees
        // Control's.
        val reader = DriveLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        val listing = reader.client.filesRepository.getFiles("/").first()
        assertTrue(
            listing.any { it.name == "route1-shared-folder" },
            "reader runtime lists the writer's folder, got: $listing",
        )
    }

    @Test
    fun fullMutationSetLandsDurablyAndACoResidentRuntimeSeesTheFinalTree() = runBlocking {
        // P1b: create / rename / move / delete through the real op path, every write behind the
        // replica's one lock (both assemblies share the test replica's), then an independent
        // runtime reads the final tree.
        val dir = tempReplicaDir()
        val writer = DriveLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        val repo = writer.client.filesRepository

        repo.createFolder("/docs").let { assertTrue(it is RepositoryResult.Success, "create /docs: $it") }
        repo.createFolder("/pics").let { assertTrue(it is RepositoryResult.Success, "create /pics: $it") }
        repo.createFolder("/scratch").let { assertTrue(it is RepositoryResult.Success, "create /scratch: $it") }
        repo.renameFile("/docs", "docs2").let { assertTrue(it is RepositoryResult.Success, "rename: $it") }
        repo.moveFile("/pics", "/docs2").let { assertTrue(it is RepositoryResult.Success, "move: $it") }
        repo.deleteFile("/scratch").let { assertTrue(it is RepositoryResult.Success, "delete: $it") }

        val reader = DriveLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        val root = reader.client.filesRepository.getFiles("/").first().map { it.name }
        val inDocs2 = reader.client.filesRepository.getFiles("/docs2").first().map { it.name }
        assertTrue("docs2" in root, "renamed folder listed at root, got: $root")
        assertTrue("docs" !in root, "old name gone after rename, got: $root")
        assertTrue("pics" !in root, "moved folder gone from root, got: $root")
        assertTrue("scratch" !in root, "deleted folder gone, got: $root")
        assertTrue("pics" in inDocs2, "moved folder listed inside /docs2, got: $inDocs2")
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun adoptedRuntimeRidesTheHandedOffIdentityAndEmitsSyncBlocksOnSave() = runBlocking {
        // P1c (Q1): with handed-off material the runtime is a LIVE realm device — Control's
        // deviceId + A4, isEnabled true — and a save goes through the STANDARD emission path,
        // landing a durable tenshu/sync/block row (what P1b proved sealed saves never produce).
        // The relay override points at a closed port so the drain's pushes fail harmlessly
        // offline; the block row is the emission proof, the live push is the A/B harness's.
        val dir = tempReplicaDir()
        val a4Public = Random.nextBytes(32)
        val material = RealmKeyMaterial(
            sharedIdentityKey = Random.nextBytes(32),
            sharedVolumeKey = Random.nextBytes(32),
            // A real-shaped A4 blob: libsodium's signer takes a 32-byte Ed25519 seed as the
            // private half (the emission path signs with A4), so both halves are 32 bytes.
            deviceSigningBlob = (
                Base64.encode(a4Public) + "|" + Base64.encode(Random.nextBytes(32))
                ).encodeToByteArray(),
            ownerId = "adopt-test-owner",
            deviceId = deviceIdForPublicKey(a4Public),
            realmEncryptionPublicKey = Random.nextBytes(32),
            realmEncryptionPrivateKey = Random.nextBytes(32),
        )
        val runtime = DriveLocalRuntime.assemble(
            replicaDirectory = dir,
            secureStorage = InMemorySecureStorageDelegate(),
            handedOffMaterial = material,
            relayBaseUrl = "http://127.0.0.1:1",
        )

        val syncManager = runtime.koin.get<SyncManager>()
        // The app rides Control's identity through the KEY: its id is that key's derivation
        // (workstream B), never a handed-over string.
        assertEquals(deviceIdForPublicKey(a4Public), syncManager.currentDeviceId, "rides Control's A4, hence its id")
        assertEquals("adopt-test-owner", syncManager.currentOwnerId, "rides Control's ownerId")
        assertTrue(syncManager.isEnabled(), "adoption makes the realm live (derived keys present)")
        assertTrue(
            syncManager.getRealmEncryptionKeyPair() != null,
            "A13 rides the hand-off (P5) — the sharing cluster's invite sealing needs it",
        )

        val created = runtime.client.filesRepository.createFolder("/adopted-folder")
        assertTrue(created is RepositoryResult.Success, "createFolder failed: $created")

        withTimeout(30_000) {
            while (runtime.koin.get<ObjectStore>().rowsOfTypes(setOf("tenshu/sync/block")).isNullOrEmpty()) {
                delay(100)
            }
        }
    }

    @Test
    fun uploadsAPickerFileThroughTheLocalRuntime() = runBlocking {
        // P3a: the real ingest path — a picker-shaped file:// URI through
        // IosFilesRepositoryPlatformDelegate → the common byte-based upload op → durable rows +
        // local plaintext, with the source's own mtime riding along as the CreatedAt seed.
        val dir = tempReplicaDir()
        val runtime = DriveLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())

        val srcDir = NSTemporaryDirectory() + "upload-src-" + Random.nextLong().toString(16)
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
            srcDir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        val srcPath = "$srcDir/report.txt"
        val payload = "hello route-1 upload".encodeToByteArray()
        assertTrue(
            writeTestFile(srcPath, payload),
            "test source file written",
        )

        val delegate = IosFilesRepositoryPlatformDelegate()
        assertEquals("report.txt", delegate.getPlatformFileName("file://$srcPath"))
        val mtime = delegate.sourceModifiedAt(srcPath)
        assertTrue(mtime != null && mtime > 0, "source mtime readable for the CreatedAt seed, got $mtime")

        val uploaded = runtime.client.filesRepository.uploadFile(
            uriString = "file://$srcPath",
            parentPath = "/",
            fileName = "report.txt",
        )
        assertTrue(uploaded is RepositoryResult.Success, "upload failed: $uploaded")
        val listing = runtime.client.filesRepository.getFiles("/").first()
        assertTrue(listing.any { it.name == "report.txt" }, "uploaded file listed, got: $listing")
        assertTrue(runtime.client.filesRepository.hasLocalData("/report.txt"), "plaintext bytes present")
    }

    private fun writeTestFile(path: String, bytes: ByteArray): Boolean =
        memScoped {
            platform.Foundation.NSData.create(
                bytes = allocArrayOf(bytes),
                length = bytes.size.toULong(),
            ).writeToFile(path, atomically = true)
        }

    @Test
    fun realOfflinePreferencesPersistDurablyOverTheLocalRuntime() = runBlocking {
        // P5: the :preference-service-backed offline pinning replaces the Noop seam — the
        // pinned list round-trips through the real WriteSetting/read ops and survives a
        // "relaunch" (same delegate → same deviceId; the pref rows are durable replica state).
        val dir = tempReplicaDir()
        val delegate = InMemorySecureStorageDelegate()
        val runtime = DriveLocalRuntime.assemble(dir, delegate)
        val prefs = app.tenshu.drive.data.TenshuOfflinePreferences(runtime.client.tenshuClient)
        prefs.setOffline(listOf("/pinned.txt", "/docs/report.pdf"))
        assertEquals(
            listOf("/pinned.txt", "/docs/report.pdf"),
            prefs.observe().first(),
            "pinned list reads back through the preference service",
        )

        val relaunch = DriveLocalRuntime.assemble(dir, delegate)
        assertEquals(
            listOf("/pinned.txt", "/docs/report.pdf"),
            app.tenshu.drive.data.TenshuOfflinePreferences(relaunch.client.tenshuClient).observe().first(),
            "device-scoped pins survive a relaunch over the same replica + device identity",
        )
    }

    @Test
    fun backgroundRefreshIsSafeInAnyProvisioningState() = runBlocking {
        // P6a: the BGAppRefreshTask entry must never crash a background launch — whatever the
        // provisioning state of the test host's Keychain, it returns a status quickly. (The
        // provisioned path composes install/syncNow, each covered above; the live
        // OS wake is the P7 on-device pass.)
        val status = DriveLocalRuntime.runBackgroundRefresh(maxWaitSeconds = 1)
        assertTrue(status.startsWith("bg-refresh:"), "got: $status")
        DriveReadSurface.installFakes()
    }

    /**
     * ⚠⚠ Assembly runs bare from Swift, where any exception is an `abort()` on every launch of every
     * route-1 app. A handed-over A4 blob whose public half does not decode must therefore assemble
     * (the manager then mints its own key, as before workstream B) — never throw while deriving the id.
     */
    @Test
    fun aMalformedHandedOffKeyBlob_stillAssembles_andPrimesNoId() {
        val delegate = InMemorySecureStorageDelegate()
        val material = RealmKeyMaterial(
            sharedIdentityKey = Random.nextBytes(32),
            sharedVolumeKey = Random.nextBytes(32),
            deviceSigningBlob = "not base64!|also-not".encodeToByteArray(),
            ownerId = "malformed-owner",
            deviceId = "stale-copy",
        )
        DriveLocalRuntime.assemble(tempReplicaDir(), delegate, material, relayBaseUrl = "http://127.0.0.1:1")
        assertTrue(
            delegate.getString(org.tenshu.sync.proxy.SyncManagerImpl.Keys.DEVICE_ID) != "stale-copy",
            "the bridge's separate id copy is never written as this device's id",
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun adoptionIsDurableAcrossRelaunchAndDeprovisionPurgesIt() = runBlocking {
        // P2a: the persisted joiner payload rehydrates the adoption on a bridge-less relaunch —
        // which is exactly why deprovision must purge Drive's own custody copies, or a cleared
        // provisioning would silently resurrect on the next launch.
        val dir = tempReplicaDir()
        val delegate = InMemorySecureStorageDelegate()
        val a4Public = Random.nextBytes(32)
        val material = RealmKeyMaterial(
            sharedIdentityKey = Random.nextBytes(32),
            sharedVolumeKey = Random.nextBytes(32),
            deviceSigningBlob = (
                Base64.encode(a4Public) + "|" + Base64.encode(Random.nextBytes(32))
                ).encodeToByteArray(),
            ownerId = "durable-owner",
            deviceId = deviceIdForPublicKey(a4Public),
        )
        DriveLocalRuntime.assemble(dir, delegate, material, relayBaseUrl = "http://127.0.0.1:1")

        // "Relaunch" without the bridge: same delegate, no material → the joiner-payload
        // autoload restores the adopted identity.
        val relaunch = DriveLocalRuntime.assemble(dir, delegate, relayBaseUrl = "http://127.0.0.1:1")
        val relaunched = relaunch.koin.get<SyncManager>()
        withTimeout(15_000) {
            while (!relaunched.isEnabled()) {
                delay(50)
            }
        }
        assertEquals("durable-owner", relaunched.currentOwnerId, "joiner payload rehydrates adoption")
        assertEquals(deviceIdForPublicKey(a4Public), relaunched.currentDeviceId, "the adopted key at rest carries the id")

        // Deprovision: seal the graph + purge the custody copies → the next launch stays sealed.
        relaunched.lock()
        DriveLocalRuntime.purgeCustody(delegate)
        assertEquals(null, delegate.getSecret(org.tenshu.sync.proxy.SyncManagerImpl.Keys.JOINER_PAYLOAD))
        val afterPurge = DriveLocalRuntime.assemble(dir, delegate, relayBaseUrl = "http://127.0.0.1:1")
        val purged = afterPurge.koin.get<SyncManager>()
        delay(1_000) // give the autoload its chance to (wrongly) rehydrate
        assertTrue(!purged.isEnabled(), "purged custody must not resurrect the adoption")
    }

    @Test
    fun sealedRuntimeStaysReadOnlyWithNoMaterial() = runBlocking {
        // The no-bridge path (Control never provisioned): assembly stays sealed — reads work
        // (P1a), saves emit nothing. Guards the adopt gate itself.
        val dir = tempReplicaDir()
        val runtime = DriveLocalRuntime.assemble(dir, InMemorySecureStorageDelegate())
        val syncManager = runtime.koin.get<SyncManager>()
        assertTrue(!syncManager.isEnabled(), "no material → sealed")
        runtime.client.filesRepository.createFolder("/sealed-folder")
        delay(500)
        assertTrue(
            runtime.koin.get<ObjectStore>().rowsOfTypes(setOf("tenshu/sync/block")).isNullOrEmpty(),
            "sealed saves must not emit blocks",
        )
    }

    @Test
    fun pagedListingDeliversRowsOverTheLocalRuntime() = runBlocking {
        // The app's actual listing path: FilesScreenViewModel.pagedFiles through the
        // TenshuPagingPresenter (DriveReadSurface.create) — the observer-backed paged chain,
        // NOT the plain getFiles the tests above cover.
        val assembly = DriveLocalRuntime.assemble(
            replicaDirectory = tempReplicaDir(),
            secureStorage = InMemorySecureStorageDelegate(),
        )
        val created = assembly.client.filesRepository.createFolder("/paged-folder")
        assertTrue(created is RepositoryResult.Success, "createFolder failed: $created")

        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val bound = DriveReadSurface.create(
                assembly.client.filesRepository,
                scope,
                Dispatchers.Default,
                "/",
            )
            withTimeout(30_000) {
                while (bound.presenter.snapshot.value.none { it.name == "paged-folder" }) {
                    delay(100)
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
