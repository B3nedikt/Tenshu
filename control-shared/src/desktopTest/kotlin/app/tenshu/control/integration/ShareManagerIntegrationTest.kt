package app.tenshu.control.integration

import app.tenshu.control.data.ShareManager
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.share.shareLink
import org.tenshu.file.client.fileService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [app.tenshu.control.data.TenshuShareManager] over the
 * in-process proxy chain: a share minted through the file client surfaces on the
 * realm-wide `FileService.shares` projection the Control inventory reads, and
 * `revoke` flips the row (kept in the list, with the badge) via the
 * `RevokeShareLink` op. The integration harness wires the proxy *without* a
 * `shareRelayClient`, so `shareLink` runs in local-only mode (no relay upload) —
 * exactly the projection + revoke path this manager depends on.
 */
class ShareManagerIntegrationTest {

    @Before
    fun setup() {
        startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
    }

    @After
    fun tearDown() {
        stopAppIntegrationKoin()
    }

    @Test
    fun mintedShareSurfacesOnInventory_andRevokeFlipsIt() = runTest {
        val client = GlobalContext.get().get<TenshuClient>()
        val shareManager = GlobalContext.get().get<ShareManager>()

        client.fileService.defaultFileSystem.shareLink(
            path = "/report.pdf",
            expiresAtMillis = 0L,
            maxOpens = 7L,
        )

        // The minted share appears on the inventory, mapped to a summary.
        val rows = shareManager.shares().first { it.isNotEmpty() }
        val row = rows.single()
        assertEquals("report.pdf", row.fileName)
        assertEquals("/report.pdf", row.filePath)
        assertEquals(7, row.maxOpens)
        assertEquals(null, row.expiresAtMillis, "0L sentinel normalises to no-expiry")
        assertTrue(!row.revoked)

        // Revoke flips the row — and the row stays in the inventory (badge).
        shareManager.revoke(row.shareId)
        val afterRevoke = shareManager.shares().first { list -> list.any { it.revoked } }
        assertEquals(1, afterRevoke.size, "revoked rows stay in the Control inventory")
        assertTrue(afterRevoke.single().revoked)
    }
}
