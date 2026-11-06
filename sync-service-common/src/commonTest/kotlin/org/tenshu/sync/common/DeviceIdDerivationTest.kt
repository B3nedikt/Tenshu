package org.tenshu.sync.common

import org.koin.dsl.koinApplication
import org.tenshu.core.common.security.Ed25519Backend
import org.tenshu.core.common.security.cryptoBackendsModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Workstream B: `deviceId = lowerhex(SHA-256(LP("tenshu-device-id-v1") ‖ LP(publicKey)))[0:32]`.
 *
 * ⚠⚠ The vectors are the contract between every client and the relay, which recomputes the id on
 * every registering route. This `commonTest` runs on the JVM and iOS; this module's JS test
 * compilation is disabled, so the browser copy is `DeviceIdDerivationJsTest` in `:tenshu-sdk-js`.
 * The vectors were computed **outside Kotlin** (Python `hashlib`, the formula above), so a matching
 * pair of Kotlin bugs on both sides cannot make them pass.
 */
class DeviceIdDerivationTest {

    private val ed25519: Ed25519Backend =
        koinApplication { modules(cryptoBackendsModule()) }.koin.get()

    @Test
    fun theVectors() {
        assertEquals("aace78e37fb541c44dab3c2b2c2a5002", deviceIdForPublicKey(ByteArray(32)))
        assertEquals("3b6c5d22de2801912158d40b9ea8b688", deviceIdForPublicKey(ByteArray(32) { it.toByte() }))
        assertEquals("50364e8e9cecd3b5a5f875e3c8c94604", deviceIdForPublicKey(ByteArray(32) { 0xFF.toByte() }))
    }

    /** A real Ed25519 key: RFC 8032 §7.1 TEST 1, whose public key this backend must derive first. */
    @Test
    fun aRealEd25519Key_fromRfc8032() {
        val publicKey = ed25519.derivePublicKey(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", publicKey.toHexString())
        assertEquals("3dc65f69655c4620f3db42b86c55cf4e", deviceIdForPublicKey(publicKey))
    }

    /** Tagged: not the untagged `SHA-256(key)` the per-realm ids used to be (nor any prefix of it). */
    @Test
    fun theTagSeparatesItFromABareKeyDigest() {
        val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        assertFalse(
            "21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9".startsWith(deviceIdForPublicKey(publicKey)),
        )
    }

    @Test
    fun theShape_fitsEveryRegisteringRoutesAlphabet() {
        repeat(32) { i ->
            val id = deviceIdForPublicKey(ByteArray(32) { (it * 7 + i).toByte() })
            assertEquals(DEVICE_ID_HEX_CHARS, id.length)
            assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' }, id)
        }
    }

    @Test
    fun matches_onlyItsOwnKey_andOnlyA32ByteOne() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertTrue(deviceIdMatchesKey(deviceIdForPublicKey(a), a))
        assertFalse(deviceIdMatchesKey(deviceIdForPublicKey(a), b), "another key's id")
        assertFalse(deviceIdMatchesKey(deviceIdForPublicKey(a).uppercase(), a), "case is part of the id")
        assertFalse(deviceIdMatchesKey(deviceIdForPublicKey(a) + "0", a))
        assertFalse(deviceIdMatchesKey("3b6c5d22-de28-0191-2158-d40b9ea8b688", ByteArray(32) { it.toByte() }))
        // A 31-byte "key" derives an id, but never matches one: no route registers it.
        val short = ByteArray(31) { 1 }
        assertFalse(deviceIdMatchesKey(deviceIdForPublicKey(short), short))
        assertNotEquals(deviceIdForPublicKey(a), deviceIdForPublicKey(b))
    }

    private fun hex(s: String): ByteArray = s.hexToByteArray()
}
