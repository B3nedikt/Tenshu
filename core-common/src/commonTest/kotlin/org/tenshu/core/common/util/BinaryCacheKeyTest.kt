package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the `SharedMemory`/`ParcelFileDescriptor` cache-key
 * collision that crashed the Drive process during a background folder upload
 * (`"... not found in cache"`). The root cause was keying the per-process
 * Binder caches by `String(idBytes)` — a lossy, non-injective decode for
 * arbitrary binary ids. [toBinaryCacheKey] replaces it; these tests pin the
 * properties the fix depends on.
 */
class BinaryCacheKeyTest {

    @Test
    fun roundTripsStablyForArbitraryBytes() {
        val id = byteArrayOf(0, 1, 2, 0xFE.toByte(), 0xFF.toByte(), 0x7F, 0x80.toByte())

        val key1 = id.toBinaryCacheKey()
        val key2 = id.copyOf().toBinaryCacheKey()

        // Stable: equal content → equal key, on every call (caches `put` with
        // one instance and `get` with another that has equal bytes).
        assertEquals(key1, key2, "Equal byte content must produce an equal key")
        assertTrue(key1.isNotEmpty(), "Non-empty input must produce a non-empty key")
    }

    @Test
    fun emptyInputIsStable() {
        assertEquals(
            ByteArray(0).toBinaryCacheKey(),
            ByteArray(0).toBinaryCacheKey(),
            "Empty input must encode deterministically",
        )
    }

    /**
     * The load-bearing property. `0xFF` and `0xFE` are each, on their own, an
     * invalid UTF-8 sequence: the UTF-8 decoder (Android `String(byte[])` /
     * Kotlin `decodeToString()`) replaces each with the replacement character
     * `U+FFFD`, so the two distinct ids collapse to the SAME `String` key —
     * which is exactly what closed a live `SharedMemory` handle on `put`
     * overwrite and crashed the next lazy `get`.
     *
     * The new encoding MUST keep them distinct.
     */
    @Test
    fun doesNotCollideForBytesThatCollideUnderStringDecode() {
        val a = byteArrayOf(0xFF.toByte())
        val b = byteArrayOf(0xFE.toByte())

        // Precondition: prove the *old* keying really did collide, so this
        // test stays meaningful if the encoding is ever changed.
        assertEquals(
            a.decodeToString(),
            b.decodeToString(),
            "Sanity: the lossy String() decode is expected to collide these",
        )

        assertNotEquals(
            a.toBinaryCacheKey(),
            b.toBinaryCacheKey(),
            "Distinct ids must map to distinct cache keys",
        )
    }

    @Test
    fun doesNotCollideForMultiByteInvalidSequences() {
        // Two different md5-length (16-byte) ids whose bytes are all in the
        // 0x80..0xFF continuation/invalid range: `String()` maps long runs of
        // these to a string of `U+FFFD`s, collapsing many distinct 16-byte
        // ids (the real shape of `InMemoryByteArrayDto.uniqueId = md5Hash`).
        val a = ByteArray(16) { 0xFF.toByte() }
        val b = ByteArray(16) { if (it == 7) 0xFE.toByte() else 0xFF.toByte() }

        assertEquals(
            a.decodeToString(),
            b.decodeToString(),
            "Sanity: the lossy String() decode is expected to collide these",
        )

        assertNotEquals(
            a.toBinaryCacheKey(),
            b.toBinaryCacheKey(),
            "Distinct 16-byte ids must map to distinct cache keys",
        )
    }
}
