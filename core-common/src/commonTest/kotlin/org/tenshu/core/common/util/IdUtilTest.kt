package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class UuidGenerationTest {

    @Test
    fun testIdToUuidStructure() {
        val uuid = idToUuid("example")
        assertEquals(16, uuid.size)

        val version = (uuid[6].toInt() shr 4) and 0x0F
        assertEquals(3, version)

        val variant = (uuid[8].toInt() shr 6) and 0x03
        assertEquals(2, variant)
    }

    @Test
    fun testIdToUuidDeterminism() {
        val uuid1 = idToUuid("example")
        val uuid2 = idToUuid("example")
        assertEquals(uuid1.toList(), uuid2.toList())
    }

    @Test
    fun testIdToUuidUniqueness() {
        val uuid1 = idToUuid("example")
        val uuid2 = idToUuid("different")
        assertNotEquals(uuid1.toList(), uuid2.toList())
    }

    @Test
    fun testIdToKotlinUuidStructure() {
        val uuid = idToKotlinUuid("example")
        val bytes = uuid.toByteArray()
        assertEquals(16, bytes.size)

        val version = (bytes[6].toInt() shr 4) and 0x0F
        assertEquals(3, version)

        val variant = (bytes[8].toInt() shr 6) and 0x03
        assertEquals(2, variant)
    }

    @Test
    fun testIdToKotlinUuidDeterminism() {
        val uuid1 = idToKotlinUuid("example")
        val uuid2 = idToKotlinUuid("example")
        assertEquals(uuid1, uuid2)
    }

    @Test
    fun testIdToKotlinUuidUniqueness() {
        val uuid1 = idToKotlinUuid("example")
        val uuid2 = idToKotlinUuid("different")
        assertNotEquals(uuid1, uuid2)
    }
}
