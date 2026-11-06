package app.tenshu.drive.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteSizeFormatTest {

    @Test
    fun belowOneKilobyteIsUpperCaseB() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("1 B", formatByteSize(1))
        assertEquals("221 B", formatByteSize(221))
        assertEquals("999 B", formatByteSize(999))
        assertEquals("1000 B", formatByteSize(1000))
        assertEquals("1023 B", formatByteSize(1023))
    }

    @Test
    fun negativeIsZero() {
        assertEquals("0 B", formatByteSize(-5))
    }

    @Test
    fun binaryStepsWithUpperCaseUnits() {
        assertEquals("1 KB", formatByteSize(1024))
        assertEquals("1.8 KB", formatByteSize(1843))
        assertEquals("181.6 KB", formatByteSize(185_958))
        assertEquals("1 MB", formatByteSize(1024L * 1024))
        assertEquals("1.5 MB", formatByteSize(1024L * 1024 * 3 / 2))
        assertEquals("1 GB", formatByteSize(1024L * 1024 * 1024))
        assertEquals("2.5 TB", formatByteSize(1024L * 1024 * 1024 * 1024 * 5 / 2))
    }

    @Test
    fun aValueThatRoundsUpToTheNextUnitShowsTheNextUnit() {
        // 1023.96 KB would otherwise print as "1024 KB".
        assertEquals("1 MB", formatByteSize(1024L * 1024 - 40))
        assertEquals("1023.9 KB", formatByteSize(1024L * 1024 - 100))
    }

    @Test
    fun theLargestLongStaysInRange() {
        assertEquals("8 EB", formatByteSize(Long.MAX_VALUE))
    }
}
