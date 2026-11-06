package app.tenshu.control.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `MockAppRegistry` is the proxy-free fake for previews / screenshot tests.
 * These lock its seeded states + write behaviour so UI work (A2/A3) can rely on
 * it without a running proxy.
 */
class MockAppRegistryTest {

    @Test
    fun seedsDriveAndDevModeApp_newestFirst() = runBlocking {
        val apps = MockAppRegistry().installedApps().first()
        assertEquals(2, apps.size)
        assertEquals("dev.example.notes", apps.first().appId, "newest install sorts first")
        assertTrue(apps.any { it.appId == BundledAppManifestSource.DRIVE_APP_ID && it.verified })
        assertTrue(apps.any { it.appId == "dev.example.notes" && !it.verified }, "dev build is unverified")
    }

    @Test
    fun emptyConstruction_givesEmptyState() = runBlocking {
        assertTrue(MockAppRegistry(apps = emptyList()).installedApps().first().isEmpty())
    }

    @Test
    fun setGrant_mutatesDetail() = runBlocking {
        val registry = MockAppRegistry()
        val driveId = BundledAppManifestSource.DRIVE_APP_ID
        registry.setGrant(driveId, "tenshu/file/system", "tenshu/file/system/uploadFiles", GrantState.Granted)
        val detail = registry.appDetail(driveId).first()!!
        assertEquals(
            GrantState.Granted,
            detail.permissions.first { it.operationId == "tenshu/file/system/uploadFiles" }.grant,
        )
    }

    @Test
    fun revokeAll_resetsEveryGrantToAskEachTime() = runBlocking {
        val registry = MockAppRegistry()
        val driveId = BundledAppManifestSource.DRIVE_APP_ID
        registry.revokeAllPermissions(driveId)
        val detail = registry.appDetail(driveId).first()!!
        assertTrue(detail.permissions.all { it.grant == GrantState.AskEachTime })
    }

    @Test
    fun uninstall_dropsAppAndDetail() = runBlocking {
        val registry = MockAppRegistry()
        val driveId = BundledAppManifestSource.DRIVE_APP_ID
        registry.uninstall(driveId, UninstallScope.AllDevices)
        assertTrue(registry.installedApps().first().none { it.appId == driveId })
        assertNull(registry.appDetail(driveId).first())
    }
}
