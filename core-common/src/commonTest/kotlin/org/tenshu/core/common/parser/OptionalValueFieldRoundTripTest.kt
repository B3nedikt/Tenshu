package org.tenshu.core.common.parser

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.createTestContext
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural round-trip coverage for a **null optional fixed-length value field that is NOT
 * the last field of a composite**. Schema + the byte-level contract live in
 * [OptionalFieldFixture]; the byte-exact serializer/parser assertions live in
 * [BinarySerializerTest] / [BinaryParserTest].
 *
 * The serializer writes an 8-byte zero marker for a null optional `int`/`boolean`. A parser that
 * consumes `dataSchema.length` (8) bytes for an optional fixed-length value field regardless of
 * that marker shifts every following field by 8 bytes, eventually misframing a value as a
 * 16-byte type id ("No type id registered for bytes: 00000008…"). This is the
 * [org.tenshu.core.ingest.IngestSource] shape (`lastRunAt`/`pendingCount` null between
 * populated fields).
 */
class OptionalValueFieldRoundTripTest {

    private val registry = OptionalFieldFixture.registry()
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer(registry)
    private val parser = BinaryParser(registry, registry)

    private val expectedType = Type.create(baseSchema = OptionalFieldFixture.Schema)

    /** Round-trips via the NON-optimized path (expectedType = null), as the stream-push path does. */
    private fun roundTripNonOptimized(data: Composite): Composite {
        val bytes = serializer.serialize(data, isTopLevel = true, expectedType = null)
        return parser.parse(bytes, expectedType = null) as Composite
    }

    /** Round-trips via the optimized path (expectedType carried). */
    private fun roundTripOptimized(data: Composite): Composite {
        val bytes = serializer.serialize(data, isTopLevel = true, expectedType = expectedType)
        return parser.parse(bytes, expectedType = expectedType) as Composite
    }

    private fun dto(a: String, b: Long?, c: String, d: Boolean?, e: Long?) =
        OptionalFieldFixture.dto(context, a, b, c, d, e)

    private fun assertSurvives(
        parsed: Composite,
        a: String,
        b: Long?,
        c: String,
        d: Boolean?,
        e: Long?,
    ) {
        assertEquals(a, parsed.getField<TString>(OptionalFieldFixture.F.A).get())
        assertEquals(b, parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.B)?.get())
        assertEquals(c, parsed.getField<TString>(OptionalFieldFixture.F.C).get())
        assertEquals(d, parsed.getOptionalField<TBoolean>(OptionalFieldFixture.F.D)?.get())
        assertEquals(e, parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.E)?.get())
    }

    // ---- The exact failing case (null optional int in the MIDDLE) -------------------------

    @Test
    fun nullOptionalIntInTheMiddleRoundTrips_nonOptimized() {
        val parsed = roundTripNonOptimized(dto(a = "before", b = null, c = "after", d = null, e = null))
        assertSurvives(parsed, a = "before", b = null, c = "after", d = null, e = null)
    }

    @Test
    fun nullOptionalIntInTheMiddleRoundTrips_optimized() {
        val parsed = roundTripOptimized(dto(a = "before", b = null, c = "after", d = null, e = null))
        assertSurvives(parsed, a = "before", b = null, c = "after", d = null, e = null)
    }

    // ---- Present optional int == 0 / boolean == false must NOT be read as null ------------

    @Test
    fun presentOptionalIntZeroIsNotNull_nonOptimized() {
        val parsed = roundTripNonOptimized(dto(a = "x", b = 0L, c = "y", d = false, e = 0L))
        assertSurvives(parsed, a = "x", b = 0L, c = "y", d = false, e = 0L)
        assertTrue(parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.B) != null, "present int 0 must survive, not become null")
        assertTrue(parsed.getOptionalField<TBoolean>(OptionalFieldFixture.F.D) != null, "present boolean false must survive, not become null")
    }

    @Test
    fun presentOptionalIntZeroIsNotNull_optimized() {
        val parsed = roundTripOptimized(dto(a = "x", b = 0L, c = "y", d = false, e = 0L))
        assertSurvives(parsed, a = "x", b = 0L, c = "y", d = false, e = 0L)
    }

    // ---- Mixed present/null + trailing nulls + present strings ----------------------------

    @Test
    fun mixedPresentAndNullFields_nonOptimized() {
        val parsed = roundTripNonOptimized(dto(a = "a", b = 42L, c = "c", d = null, e = 7L))
        assertSurvives(parsed, a = "a", b = 42L, c = "c", d = null, e = 7L)
    }

    @Test
    fun mixedPresentAndNullFields_optimized() {
        val parsed = roundTripOptimized(dto(a = "a", b = 42L, c = "c", d = null, e = 7L))
        assertSurvives(parsed, a = "a", b = 42L, c = "c", d = null, e = 7L)
    }

    @Test
    fun allOptionalsPresent_nonOptimized() {
        val parsed = roundTripNonOptimized(dto(a = "a", b = 1L, c = "c", d = true, e = 9L))
        assertSurvives(parsed, a = "a", b = 1L, c = "c", d = true, e = 9L)
    }

    @Test
    fun trailingNullOptional_stillRoundTrips_nonOptimized() {
        val parsed = roundTripNonOptimized(dto(a = "a", b = 5L, c = "c", d = true, e = null))
        assertSurvives(parsed, a = "a", b = 5L, c = "c", d = true, e = null)
        assertNull(parsed.getOptionalField<TInteger>(OptionalFieldFixture.F.E)?.get())
    }
}
