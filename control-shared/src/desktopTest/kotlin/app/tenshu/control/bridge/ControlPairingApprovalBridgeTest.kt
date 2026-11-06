package app.tenshu.control.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JSON-shaping tests for the `pairingApproval` tray bridge
 * (`docs/proposals/control-pairing-ux.md` §3.6). The socket/registration path mirrors the
 * pending-requests bridge and is exercised there; here we pin the payload contract — most
 * importantly the SAS/PII-free shape, which is load-bearing for §3.3.
 */
class ControlPairingApprovalBridgeTest {

    @Test
    fun encodesPendingWithOpaqueIdWhenADeviceIsAsking() {
        val result = pairingApprovalResult(deviceId = "joiner-device-7")

        assertEquals(true, result["pending"])
        assertEquals("joiner-device-7", result["id"])
    }

    @Test
    fun encodesNotPendingWhenNoDeviceIsAsking() {
        val result = pairingApprovalResult(deviceId = null)

        assertFalse(result["pending"] as Boolean)
        assertNull(result["id"])
    }

    @Test
    fun payloadCarriesOnlyPendingAndId() {
        // §3.3/§3.6: the notification must never carry the SAS (else it could approve
        // without the two-device comparison), nor the joiner's unverified device name.
        // The whole payload is exactly {pending, id} — assert no other keys leak in.
        val result = pairingApprovalResult(deviceId = "joiner-device-7")

        assertEquals(setOf("pending", "id"), result.keys)
        // The only string value is the opaque id; nothing SAS- or name-shaped rides along.
        assertTrue(result.values.none { it is String && it != "joiner-device-7" })
    }
}
