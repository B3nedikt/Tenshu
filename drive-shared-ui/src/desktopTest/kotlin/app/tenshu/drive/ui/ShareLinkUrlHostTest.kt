package app.tenshu.drive.ui

import app.tenshu.drive.ui.share.ShareLinkUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Mode-A share-link host: the apex by default (the deployed viewer origin), with
 * a per-deployment override via the `-Dtenshu.shareHost` system property (the JVM half of
 * [app.tenshu.drive.ui.share.platformShareHostOverride], shared by desktop + Android).
 */
class ShareLinkUrlHostTest {

    @Test fun defaultHostIsApex() {
        assertEquals(
            "https://tenshu.app/s/abc#k=xyz",
            ShareLinkUrl(shareId = "abc", urlFragment = "xyz").toCopyableString(),
        )
    }

    @Test fun systemPropertyOverridesHost() {
        System.setProperty("tenshu.shareHost", "drive.example.test")
        try {
            assertTrue(
                ShareLinkUrl(shareId = "abc", urlFragment = "xyz")
                    .toCopyableString()
                    .startsWith("https://drive.example.test/s/abc#k="),
                "an explicit tenshu.shareHost should drive the minted URL host",
            )
            // The companion default-host accessor reflects the override too (the Copy-ID
            // path in SharedScreen reads ShareLinkUrl.DEFAULT_HOST directly).
            assertEquals("drive.example.test", ShareLinkUrl.DEFAULT_HOST)
        } finally {
            System.clearProperty("tenshu.shareHost")
        }
    }

    @Test fun blankOverrideFallsBackToApex() {
        System.setProperty("tenshu.shareHost", "   ")
        try {
            assertEquals("tenshu.app", ShareLinkUrl.DEFAULT_HOST)
        } finally {
            System.clearProperty("tenshu.shareHost")
        }
    }
}
