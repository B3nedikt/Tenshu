package app.tenshu.drive.ui.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards [ShareLinkUrl.pathOnly] — the *My shares* "Copy ID" form.
 *
 * The load-bearing property is the **absence** of the `#k=` fragment: the per-share key is
 * the capability (A11), so a share listed after the mint sheet closed must be identifiable
 * without being re-shareable. Both platforms' My-shares row menus call this one function
 * (Android `MyShareRowItem`, iOS `SharedView.shareMenu`), so this test is the single place
 * that ties the rule down — previously each platform hand-rolled the string.
 */
class ShareLinkUrlPathOnlyTest {

    @Test
    fun `pathOnly omits the secret fragment`() {
        val url = ShareLinkUrl.pathOnly("share-123")

        assertFalse(url.contains("#"), "path-only URL must carry no fragment: $url")
        assertFalse(url.contains("k="), "path-only URL must not leak the per-share key: $url")
        assertTrue(url.endsWith("/s/share-123"), "expected the viewer route, got: $url")
    }

    @Test
    fun `pathOnly is the capability URL minus the fragment`() {
        val link = ShareLinkUrl(shareId = "share-123", urlFragment = "SECRET")

        val full = link.toCopyableString(host = ShareLinkUrl.DEFAULT_HOST)
        val pathOnly = ShareLinkUrl.pathOnly("share-123")

        // The two forms agree on everything up to the fragment delimiter — so a future host
        // or route change cannot silently desynchronise the mint sheet from the listing.
        assertEquals(full.substringBefore("#"), pathOnly)
        assertTrue(full.contains("#k=SECRET"), "sanity: the mint form still carries the key")
    }
}
