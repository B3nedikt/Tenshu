package org.tenshu.core.common.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OwnerIdTest {

    @Test
    fun deterministic_sameInputProducesSameOutput() {
        val pubKey = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(
            ownerIdForMasterPublicKey(pubKey),
            ownerIdForMasterPublicKey(pubKey)
        )
    }

    @Test
    fun differentKeys_produceDifferentOwnerIds() {
        val keyA = byteArrayOf(1, 2, 3)
        val keyB = byteArrayOf(4, 5, 6)
        assertNotEquals(
            ownerIdForMasterPublicKey(keyA),
            ownerIdForMasterPublicKey(keyB)
        )
    }

    @Test
    fun emptyKey_doesNotCrashAndIsDeterministic() {
        val empty = ByteArray(0)
        // Stub-level edge case: even the empty key produces a deterministic ownerId.
        // Real production never hits this (master keys are 32 bytes), but the stub
        // behaves predictably so test fixtures don't have to special-case.
        assertEquals(
            ownerIdForMasterPublicKey(empty),
            ownerIdForMasterPublicKey(empty)
        )
    }

    @Test
    fun ownerIdShape_isHexString() {
        val pubKey = byteArrayOf(1, 2, 3, 4)
        val ownerId = ownerIdForMasterPublicKey(pubKey)
        // sha256 → 32 bytes → 64 hex chars. When we swap to blake2b+base32 this
        // assertion will need updating — that's a feature, not a bug, since it
        // forces a deliberate review of the wire-shape change.
        assertEquals(64, ownerId.length)
        assertEquals(true, ownerId.all { it in "0123456789abcdef" })
    }
}
