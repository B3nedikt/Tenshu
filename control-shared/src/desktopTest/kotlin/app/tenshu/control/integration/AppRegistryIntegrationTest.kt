package app.tenshu.control.integration

import app.tenshu.control.data.BundledAppManifestSource
import app.tenshu.control.data.GrantState
import app.tenshu.control.data.TenshuAppRegistry
import app.tenshu.control.data.UninstallScope
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.tenshu.core.app.registerApp
import org.tenshu.core.getOrNull
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.core.common.app.appService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `TenshuAppRegistry` over the real in-process SDK chain (Track A A1): the
 * registry reads `AppRegistration` / `AppGrant` from `AppService`'s synced
 * relations, joins them with the bundled Drive manifest, and routes grant /
 * uninstall writes back through the operations. Complements the proxy-level
 * `AppServiceLocalTest` (op semantics) by exercising the control-shared client.
 */
class AppRegistryIntegrationTest {

    private val appId = BundledAppManifestSource.DRIVE_APP_ID
    private val uploadOp = "tenshu/file/system/uploadFiles"
    private val fileSystem = "tenshu/file/system"
    private val otherAppId = BundledAppManifestSource.FIT_APP_ID

    @After
    fun tearDown() = stopAppIntegrationKoin()

    @Test
    fun installedApps_joinsRegistrationWithBundledManifest() = runBlocking {
        val client = startAppIntegrationKoin()
        // Proxy-only RegisterApp seeds the registration (no UI write path for it).
        client.appService.registerApp(appId, deviceId = "device-1", verified = true)
        val registry = TenshuAppRegistry(client, BundledAppManifestSource())

        val drive = withTimeout(10.seconds) {
            registry.installedApps().first { it.any { app -> app.appId == appId } }
        }.first { it.appId == appId }

        assertEquals("Drive", drive.name, "manifest join supplies the display name")
        assertEquals("1.0.0", drive.version)
        assertTrue(drive.verified)
        assertEquals(1, drive.deviceCount)
        assertTrue(drive.installedAt > 0L, "install date is the registration createdAt")
    }

    /**
     * D5 (`control-app-catalogue.md`): the tray filters on this, so both directions matter — a
     * flag stuck at one value passes any test that only checks the other, and here the failure
     * would be a tray that lists either nothing or everything.
     */
    @Test
    fun installedOnThisDevice_distinguishesLocalFromRemoteRegistrations() = runBlocking {
        val client = startAppIntegrationKoin()
        val thisDevice = client.syncService.getSyncStatus().getOrNull()?.deviceId?.get()
        assertNotNull(thisDevice, "the harness must have an active realm for this to mean anything")

        // Drive registered HERE; Fit registered only on some other device.
        client.appService.registerApp(appId, deviceId = thisDevice, verified = true)
        client.appService.registerApp(otherAppId, deviceId = "device-elsewhere", verified = true)
        val registry = TenshuAppRegistry(client, BundledAppManifestSource())

        val apps = withTimeout(10.seconds) {
            registry.installedApps().first { list ->
                list.any { it.appId == appId } && list.any { it.appId == otherAppId }
            }
        }

        assertTrue(
            apps.first { it.appId == appId }.installedOnThisDevice,
            "an app registered on this device is local",
        )
        assertFalse(
            apps.first { it.appId == otherAppId }.installedOnThisDevice,
            "an app registered only elsewhere must not be offered to the tray launcher",
        )
    }

    /**
     * C3: an app with no bundled manifest must still appear, flagged rather than hidden, with
     * the bare appId as its name. Both directions matter — a `recognised` flag stuck at `true`
     * restores exactly the silent fallback this replaces, and one stuck at `false` would badge
     * every app.
     */
    @Test
    fun anAppWithNoBundledManifestIsListedButFlaggedUnrecognised() = runBlocking {
        val client = startAppIntegrationKoin()
        client.appService.registerApp(appId, deviceId = "device-1", verified = true)
        client.appService.registerApp("dev.example.notes", deviceId = "device-1", verified = true)
        val registry = TenshuAppRegistry(client, BundledAppManifestSource())

        val apps = withTimeout(10.seconds) {
            registry.installedApps().first { list ->
                list.any { it.appId == appId } && list.any { it.appId == "dev.example.notes" }
            }
        }

        val known = apps.first { it.appId == appId }
        assertTrue(known.recognised, "a bundled app is recognised")
        assertEquals("Drive", known.name)

        val unknown = apps.first { it.appId == "dev.example.notes" }
        assertFalse(unknown.recognised, "an app with no manifest must be flagged, not hidden")
        assertEquals("dev.example.notes", unknown.name, "the bare id is the only name we have")
    }

    @Test
    fun setGrant_thenRevoke_reflectInAppDetail() = runBlocking {
        val client = startAppIntegrationKoin()
        client.appService.registerApp(appId, deviceId = "device-1", verified = true)
        val registry = TenshuAppRegistry(client, BundledAppManifestSource())

        registry.setGrant(appId, fileSystem, uploadOp, GrantState.Granted)
        val granted = withTimeout(10.seconds) {
            registry.appDetail(appId).first { detail ->
                detail?.permissions?.any { it.operationId == uploadOp && it.grant == GrantState.Granted } == true
            }
        }!!
        // Permissions come from the manifest, services are distinct. ⚠ A raw count, so it moves
        // whenever Drive's manifest does — it did here when `getStoredThumbnail` was declared, an
        // op Drive had been dispatching undeclared since the synced-thumbnails track.
        assertEquals(
            BundledAppManifestSource().manifest(appId)!!.permissions.size,
            granted.permissions.size,
            "every declared permission must reach the detail screen",
        )
        assertTrue(granted.services.contains(fileSystem))

        registry.revokeAllPermissions(appId)
        val revoked = withTimeout(10.seconds) {
            registry.appDetail(appId).first { detail ->
                detail?.permissions?.all { it.grant == GrantState.AskEachTime } == true
            }
        }!!
        assertTrue(revoked.permissions.all { it.grant == GrantState.AskEachTime })
    }

    @Test
    fun uninstall_allDevices_removesAppFromList() = runBlocking {
        val client = startAppIntegrationKoin()
        client.appService.registerApp(appId, deviceId = "device-1", verified = true)
        val registry = TenshuAppRegistry(client, BundledAppManifestSource())

        // Wait until it's present, then deregister realm-wide.
        withTimeout(10.seconds) { registry.installedApps().first { it.any { a -> a.appId == appId } } }
        registry.uninstall(appId, UninstallScope.AllDevices)

        // The Removed tombstone drops it from the (Active-only) list.
        val afterRemoval = withTimeout(10.seconds) {
            registry.installedApps().first { list -> list.none { it.appId == appId } }
        }
        assertTrue(afterRemoval.none { it.appId == appId })
    }

    /**
     * ⚠⚠ The tray's actual failure mode (`docs/tech-debt.md`, 2026-08-14): every other test here
     * registers **before** it collects, which is the easy direction. Control runs for hours with
     * its list already read, and an app registers *then* — Mia did, on its first message, and the
     * tray kept showing the old set until Control was restarted.
     *
     * So this collects first and registers second. It is the difference between "the query reads
     * current state" and "the query stays live", and only the second is what a resident daemon
     * needs.
     */
    @Test
    fun aRegistrationMadeWhileCollectingReachesTheList() = runBlocking {
        val client = startAppIntegrationKoin()
        val registry = TenshuAppRegistry(client, BundledAppManifestSource())

        coroutineScope {
            // Subscribed with nothing registered — the daemon's list, already read.
            val appears = async {
                withTimeout(15.seconds) {
                    registry.installedApps().first { apps -> apps.any { it.appId == appId } }
                }
            }
            registry.installedApps().first()

            client.appService.registerApp(appId, deviceId = "device-1", verified = true)

            val apps = appears.await()
            assertTrue(
                apps.any { it.appId == appId },
                "a registration written while the list was being collected must reach the collector",
            )
        }
    }

}
