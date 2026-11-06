package org.tenshu.sync.relay.routing

import org.tenshu.core.common.util.generateId
import org.tenshu.core.common.util.sha256
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The device-id rule every registering relay route applies. ⚠⚠ The point of the first test
 * is that the rule never refuses a REAL id: a too-tight alphabet would lock every device out of
 * re-registering, and nothing else would notice before a tester did.
 */
class RegistrationInputRulesTest {

    @Test
    fun everyRealIdShapePasses() {
        repeat(50) {
            val uuid = generateId()
            assertTrue(RegistrationInputRules.isWellFormedDeviceId(uuid), "a generated device id: $uuid")
        }
        val perRealm = sha256(ByteArray(32) { it.toByte() }).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        assertTrue(RegistrationInputRules.isWellFormedDeviceId(perRealm), "a per-realm device id: $perRealm")
    }

    @Test
    fun anythingOutsideTheAlphabetIsRefused() {
        for (bad in listOf("", "a b", "a\u0000b", "\u0000\u0000\u0000\b", "a,b", "a:b", "ä", "x".repeat(257))) {
            assertFalse(RegistrationInputRules.isWellFormedDeviceId(bad), "accepted: ${bad.take(20)}")
        }
    }
}
