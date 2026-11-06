package app.tenshu.timeline.data.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [NetworkTag] — the opaque identifier that rides in the realm in place of a raw BSSID
 * (`timeline-recognition-and-place-naming.md` §9.3 / F4).
 */
class NetworkTagTest {

    private val bssid = "a4:2b:8c:10:00:01"

    @Test
    fun theRawBssidNeverAppearsInTheTag() {
        // The whole point: what syncs must not contain the MAC, in any casing or separator form.
        val tag = NetworkTag.of(bssid)
        assertFalse(tag.contains("a4:2b:8c", ignoreCase = true))
        assertFalse(tag.contains("a42b8c", ignoreCase = true))
    }

    @Test
    fun casingDoesNotChangeTheTag() {
        // ⚠ Load-bearing: BSSIDs come back with mixed casing across reads, and two devices must
        // derive the SAME tag or the binding never converges.
        assertEquals(NetworkTag.of(bssid), NetworkTag.of(bssid.uppercase()))
    }

    @Test
    fun differentNetworksGetDifferentTags() {
        assertNotEquals(NetworkTag.of(bssid), NetworkTag.of("a4:2b:8c:10:00:02"))
    }

    @Test
    fun theTagIsAStableHexDigest() {
        // Deterministic across devices and runs — there is no salt to diverge on (see the KDoc).
        val tag = NetworkTag.of(bssid)
        assertEquals(64, tag.length)
        assertTrue(tag.all { it in "0123456789abcdef" })
        assertEquals(tag, NetworkTag.of(bssid))
    }
}
