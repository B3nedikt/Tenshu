package org.tenshu.core.common.proxy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Surface tests for the in-memory [PreviewCache] impl that backs desktop
 * + test fixtures. The semantics asserted here apply equally to the
 * persistent Android impl (`AndroidPreviewCache`) — that backend's
 * filesystem-level tests would live in `androidHostTest`, gated on the
 * Android SDK being present (not in this build env).
 */
class PreviewCacheTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun get_missingEntry_returnsNull() {
        val cache = InMemoryPreviewCache()
        assertNull(cache.get(hashA, 100, 100))
    }

    @Test
    fun put_thenGet_returnsStoredBytes() {
        val cache = InMemoryPreviewCache()
        val bytes = byteArrayOf(1, 2, 3, 4)

        assertTrue(cache.put(hashA, 100, 100, bytes))

        val read = cache.get(hashA, 100, 100)
        assertContentEquals(bytes, read)
    }

    @Test
    fun differentDimensionsForSameContentAreDistinctEntries() {
        val cache = InMemoryPreviewCache()
        val small = byteArrayOf(1, 2, 3)
        val large = byteArrayOf(4, 5, 6, 7, 8)

        cache.put(hashA, 100, 100, small)
        cache.put(hashA, 200, 200, large)

        assertContentEquals(small, cache.get(hashA, 100, 100))
        assertContentEquals(large, cache.get(hashA, 200, 200))
    }

    @Test
    fun differentContentForSameDimensionsAreDistinctEntries() {
        val cache = InMemoryPreviewCache()
        val bytesA = byteArrayOf(1, 2, 3)
        val bytesB = byteArrayOf(9, 9, 9)

        cache.put(hashA, 100, 100, bytesA)
        cache.put(hashB, 100, 100, bytesB)

        assertContentEquals(bytesA, cache.get(hashA, 100, 100))
        assertContentEquals(bytesB, cache.get(hashB, 100, 100))
    }

    @Test
    fun put_overwritesExistingEntry() {
        val cache = InMemoryPreviewCache()
        val v1 = byteArrayOf(1)
        val v2 = byteArrayOf(2, 2)

        cache.put(hashA, 100, 100, v1)
        cache.put(hashA, 100, 100, v2)

        assertContentEquals(v2, cache.get(hashA, 100, 100))
    }

    @Test
    fun put_rejectsOversizedEntry() {
        val cache = InMemoryPreviewCache(maxEntryBytes = 16)
        val tooBig = ByteArray(17)

        assertFalse(cache.put(hashA, 100, 100, tooBig))
        assertNull(cache.get(hashA, 100, 100))
    }

    @Test
    fun put_acceptsEntryExactlyAtCap() {
        val cache = InMemoryPreviewCache(maxEntryBytes = 16)
        val atCap = ByteArray(16) { 1 }

        assertTrue(cache.put(hashA, 100, 100, atCap))
        assertContentEquals(atCap, cache.get(hashA, 100, 100))
    }

    @Test
    fun noOpCache_alwaysMisses() {
        val cache: PreviewCache = NoOpPreviewCache
        // put returns false and never persists; subsequent get is null.
        assertFalse(cache.put(hashA, 100, 100, byteArrayOf(1, 2, 3)))
        assertNull(cache.get(hashA, 100, 100))
    }
}
