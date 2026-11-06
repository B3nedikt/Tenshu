package app.tenshu.sdk

import org.tenshu.sync.common.deviceIdForPublicKey
import org.tenshu.sync.common.deviceIdMatchesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Workstream B on the **browser** runtime — the one target where `:sync-service-common`'s own
 * `DeviceIdDerivationTest` does not run (its JS test compilation is disabled). The web runtime mints
 * its device id through the same `SyncManagerImpl`, and the relay refuses any id that is not its key's,
 * so JS must derive the same 32 characters as the JVM, iOS and the relay.
 *
 * ⚠ The vectors are copied from `DeviceIdDerivationTest`, which were computed outside Kotlin (Python
 * `hashlib` over `SHA-256(LP("tenshu-device-id-v1") ‖ LP(key))[0:32]`).
 */
class DeviceIdDerivationJsTest {

    @Test
    fun vectorsMatchTheOtherPlatforms() {
        assertEquals("aace78e37fb541c44dab3c2b2c2a5002", deviceIdForPublicKey(ByteArray(32)))
        assertEquals("3b6c5d22de2801912158d40b9ea8b688", deviceIdForPublicKey(ByteArray(32) { it.toByte() }))
        assertEquals("50364e8e9cecd3b5a5f875e3c8c94604", deviceIdForPublicKey(ByteArray(32) { 0xFF.toByte() }))
        assertEquals(
            "3dc65f69655c4620f3db42b86c55cf4e",
            deviceIdForPublicKey("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray()),
        )
    }

    @Test
    fun matchesOnlyItsOwnKey() {
        val a = ByteArray(32) { 1 }
        assertTrue(deviceIdMatchesKey(deviceIdForPublicKey(a), a))
        assertFalse(deviceIdMatchesKey(deviceIdForPublicKey(a), ByteArray(32) { 2 }))
    }
}
