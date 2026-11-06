package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.dataIdIdentityString
import org.tenshu.core.entity.getDataIdHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * EI-1's primitive (`sync-entity-identity.md` §2.1): the relay-visible name of an entity.
 *
 * What each test here is actually defending is named in its own comment; together they pin the four
 * properties the design rests on — convergence, unguessability, per-realm unlinkability, and width.
 */
class WireEntityIdTest {

    private val proxy = TestProxy(createSyntheticTestRegistry())
    private fun id(key: String): DataId =
        DataId.StringDataId.create(proxy.tContext, "tenshu/file/file", key)

    private val volumeKeyA = ByteArray(32) { it.toByte() }
    private val volumeKeyB = ByteArray(32) { (it + 7).toByte() }

    /**
     * ⭐⭐ The property the whole "hash, not a random id" decision rests on: two devices that create
     * the same entity **offline** derive the same wire id, so their blocks arrive as
     * `existing` + `incoming` for one entity and `SemanticMerger` reconciles them. A random id
     * would produce two entities and no conflict to resolve.
     */
    @Test
    fun twoDevicesInOneRealmConvergeOnTheSameWireId() {
        val deviceA = WireEntityId.keyFor(volumeKeyA)
        val deviceB = WireEntityId.keyFor(volumeKeyA)

        assertEquals(
            WireEntityId.of(id("/Photos"), deviceA),
            WireEntityId.of(id("/Photos"), deviceB),
            "same realm key + same identity ⇒ same wire id, with no coordination",
        )
    }

    /**
     * ⚠⚠ The defect EI-1 exists to close. The relay holds no `K_entity`, so a *guessed* path hashes
     * to nothing it can test — unlike `getDataIdHash`, which it could compute for any path it cared
     * to try.
     */
    @Test
    fun aGuessedPathIsNotComputableWithoutTheRealmKey() {
        // ⚠ The LOCAL hash is still derived from plaintext — widened by EI-1b, but still a pure
        // function of the identity string with no key in it, which is exactly why it may not be the
        // relay-visible name. Asserted as *derivability*, not as a literal: pinning the constant is
        // what made this test stale the moment the width changed.
        val guessableToday = id("/Photos/holiday.jpg").getDataIdHash()
        assertEquals(
            guessableToday,
            id("/Photos/holiday.jpg").getDataIdHash(),
            "anyone who can guess the path can recompute it — no key involved",
        )

        val wire = WireEntityId.of(id("/Photos/holiday.jpg"), WireEntityId.keyFor(volumeKeyA))
        assertNotEquals(guessableToday.toString(), wire)
        assertTrue(
            wire != WireEntityId.of(id("/Photos/holiday.jpg"), WireEntityId.keyFor(volumeKeyB)),
            "a different realm key gives a different name for the same identity",
        )
    }

    /**
     * ⭐ Per-realm derivation means the relay cannot link one identity across realms — the same
     * denial the content plane's per-owner keyspace already gives (ADR 0017 D5).
     */
    @Test
    fun theSameIdentityIsUnlinkableAcrossRealms() {
        assertNotEquals(
            WireEntityId.of(id("/shared.txt"), WireEntityId.keyFor(volumeKeyA)),
            WireEntityId.of(id("/shared.txt"), WireEntityId.keyFor(volumeKeyB)),
        )
    }

    /**
     * ⚠ 128 bits, against `p ≈ n²/2³³` for the 32-bit predecessor — about 2.3 % of realms at 14 208
     * rows and 29 % at 50 000.
     */
    @Test
    fun theWireIdIs128BitsOfHex() {
        val wire = WireEntityId.of(id("/a"), WireEntityId.keyFor(volumeKeyA))
        assertEquals(32, wire.length, "16 bytes as lowercase hex")
        assertTrue(wire.all { it in "0123456789abcdef" }, "hex only, got $wire")
    }

    /**
     * ⚠⚠ The wire id and the local hash must name the *same* entity and differ only in how — which
     * is what sharing one identity string gives them, and what lets a caller move between them
     * without changing meaning.
     */
    @Test
    fun bothFunctionsReadTheSameIdentity() {
        assertEquals("tenshu/file/file_/a", id("/a").dataIdIdentityString())
        assertNotEquals(id("/a").dataIdIdentityString(), id("/b").dataIdIdentityString())
        assertNotEquals(
            WireEntityId.of(id("/a"), WireEntityId.keyFor(volumeKeyA)),
            WireEntityId.of(id("/b"), WireEntityId.keyFor(volumeKeyA)),
        )
    }
}
