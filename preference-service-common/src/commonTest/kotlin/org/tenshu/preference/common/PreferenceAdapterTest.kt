package org.tenshu.preference.common

import org.tenshu.core.common.createTestContext
import org.tenshu.core.toTString
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.ByteArrayAdapter
import org.tenshu.preference.IntAdapter
import org.tenshu.preference.LongAdapter
import org.tenshu.preference.StringAdapter
import org.tenshu.preference.StringListAdapter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Each built-in adapter maps its Kotlin type to a concrete, self-describing
 * `Data` ([BooleanAdapter] → `TBoolean`, [StringListAdapter] → `TList<TString>`,
 * ...) and back. A type mismatch (or `null`) decodes to `null` so the upstream
 * `PreferenceService.read(pref)` falls back to `Preference.default`
 * (`core-preference-service.md` §6.6).
 */
class PreferenceAdapterTest {

    private val ctx = createTestContext()

    @Test
    fun booleanAdapter_roundTrips_bothValues() {
        listOf(true, false).forEach { value ->
            assertEquals(value, BooleanAdapter.fromData(BooleanAdapter.toData(value, ctx)))
        }
    }

    @Test
    fun longAdapter_roundTrips_includingExtremes() {
        listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE).forEach { value ->
            assertEquals(value, LongAdapter.fromData(LongAdapter.toData(value, ctx)))
        }
    }

    @Test
    fun intAdapter_roundTrips() {
        listOf(0, 42, -7, Int.MAX_VALUE, Int.MIN_VALUE).forEach { value ->
            assertEquals(value, IntAdapter.fromData(IntAdapter.toData(value, ctx)))
        }
    }

    @Test
    fun stringAdapter_roundTrips_includingEmpty() {
        listOf("", "hello", "πœ◊ unicode", "/A/B/file.txt").forEach { value ->
            assertEquals(value, StringAdapter.fromData(StringAdapter.toData(value, ctx)))
        }
    }

    @Test
    fun byteArrayAdapter_roundTrips_includingEmpty() {
        listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(1, 2, 3, 4, 5)).forEach { value ->
            assertContentEquals(value, ByteArrayAdapter.fromData(ByteArrayAdapter.toData(value, ctx)))
        }
    }

    @Test
    fun stringListAdapter_roundTrips_includingEmpty() {
        listOf(emptyList(), listOf("a"), listOf("/A", "/B", "/C/D"), listOf("", "")).forEach { value ->
            assertEquals(value, StringListAdapter.fromData(StringListAdapter.toData(value, ctx)))
        }
    }

    @Test
    fun fromData_typeMismatchOrNull_returnsNull() {
        // null → default fallback upstream
        assertNull(BooleanAdapter.fromData(null))
        assertNull(LongAdapter.fromData(null))
        assertNull(StringListAdapter.fromData(null))
        // wrong concrete Data type → null (a String where a Boolean/list was expected)
        assertNull(BooleanAdapter.fromData("nope".toTString(ctx)))
        assertNull(LongAdapter.fromData("nope".toTString(ctx)))
        assertNull(StringListAdapter.fromData("nope".toTString(ctx)))
    }
}
