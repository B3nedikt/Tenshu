package app.tenshu.drive.ui.common

import org.tenshu.core.file.sync.SyncPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The offline glyph decision (`docs/proposals/drive-offline-online.md` §3–§4):
 * presence (v2) refining the effective sync policy (v1/v3).
 */
class OfflineRowGlyphTest {

    @Test
    fun `present bytes show no glyph regardless of policy`() {
        assertNull(offlineRowGlyph(hasLocalData = true, effectivePolicy = SyncPolicy.ONLINE_ONLY))
        assertNull(offlineRowGlyph(hasLocalData = true, effectivePolicy = SyncPolicy.KEEP_OFFLINE))
    }

    @Test
    fun `absent and kept-offline is pending download`() {
        assertEquals(
            OfflineRowGlyph.OfflinePending,
            offlineRowGlyph(hasLocalData = false, effectivePolicy = SyncPolicy.KEEP_OFFLINE),
        )
    }

    @Test
    fun `absent and online-only is online only`() {
        assertEquals(
            OfflineRowGlyph.OnlineOnly,
            offlineRowGlyph(hasLocalData = false, effectivePolicy = SyncPolicy.ONLINE_ONLY),
        )
    }

    @Test
    fun `unknown presence renders neutral`() {
        // §4.5: while the async probe is in flight the row shows no glyph at all,
        // regardless of intent — the cloud appears only once presence is *known*
        // to be absent. This kills the cloud-flash on freshly-created rows.
        assertNull(offlineRowGlyph(hasLocalData = null, effectivePolicy = SyncPolicy.ONLINE_ONLY))
        assertNull(offlineRowGlyph(hasLocalData = null, effectivePolicy = SyncPolicy.KEEP_OFFLINE))
    }
}
