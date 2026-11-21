package app.tenshu.home.model

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeModelTest {

    @Test
    fun byteSizesFormatAcrossUnits() {
        assertEquals("0 B", formatByteSize(0, "en"))
        assertEquals("512 B", formatByteSize(512, "en"))
        assertEquals("1.5 KB", formatByteSize(1_500, "en"))
        assertEquals("412 GB", formatByteSize(412_000_000_000, "en"))
        assertEquals("1 TB", formatByteSize(1_000_000_000_000, "en"))
        assertEquals("2.5 TB", formatByteSize(2_500_000_000_000, "en"))
    }

    @Test
    fun storageDerivesFreeBytesAndUsedFraction() {
        val storage = DeviceState.Storage(usedBytes = 250, totalBytes = 1000)
        assertEquals(750, storage.freeBytes)
        assertEquals(0.25f, storage.usedFraction)
        // Degenerate capacity never divides by zero or goes negative.
        val empty = DeviceState.Storage(usedBytes = 10, totalBytes = 0)
        assertEquals(0, empty.freeBytes)
        assertEquals(0f, empty.usedFraction)
    }

    @Test
    fun temperaturesFormatFromTenths() {
        assertEquals("21°", formatDeciC(210, "en"))
        assertEquals("21.3°", formatDeciC(213, "en"))
        assertEquals("-5°", formatDeciC(-50, "en"))
        // The sign survives a reading between 0 and -1 (integer division used to drop it: "0.5°").
        assertEquals("-0.5°", formatDeciC(-5, "en"))
    }

    @Test
    fun germanReadsTheDecimalWithAComma() {
        assertEquals("21,3°", formatDeciC(213, "de"))
        assertEquals("-0,5°", formatDeciC(-5, "de"))
        assertEquals("2,5 TB", formatByteSize(2_500_000_000_000, "de"))
        assertEquals("1,5 KB", formatByteSize(1_500, "de"))
        assertEquals("412 GB", formatByteSize(412_000_000_000, "de"))
    }
}
