package org.tenshu.storage.common

import org.tenshu.core.common.entitlement.StorageTiers
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageBytesFormatTest {

    private val gib = 1024L * 1024 * 1024

    @Test
    fun freePlanReadsAsFiveGb_notFivePointThreeSeven() {
        // The 2026-10-14 screenshot defect: 5 GiB rendered with decimal steps as "5.37 GB".
        assertEquals("5 GB", formatStorageBytes(StorageTiers.FREE_BYTES, "en"))
        assertEquals("5 GB", formatStorageBytes(5 * gib, "en"))
    }

    @Test
    fun everyTierReadsAsItsName() {
        assertEquals("1 GB", formatStorageBytes(gib, "en"))
        assertEquals("50 GB", formatStorageBytes(StorageTiers.BASIC_BYTES, "en"))
        // Not "2048 GB" (the CE-6 defect) and not "2.2 TB" (decimal steps).
        assertEquals("2 TB", formatStorageBytes(StorageTiers.PREMIUM_BYTES, "en"))
    }

    @Test
    fun zeroAndNegative() {
        assertEquals("0 B", formatStorageBytes(0, "en"))
        assertEquals("0 B", formatStorageBytes(-5, "en"))
        assertEquals("1,023 B", formatStorageBytes(1023, "en"))
    }

    @Test
    fun mixedUsageLine_sharesOneBase() {
        val used = (8.3 * 1024 * 1024).toLong()
        val line = "${formatStorageBytes(used, "en")} of ${formatStorageBytes(StorageTiers.FREE_BYTES, "en")} used"
        assertEquals("8.3 MB of 5 GB used", line)
        // A nearly-full plan: both figures in GiB, so the usage never reads larger than the limit.
        assertEquals("4.9 GB of 5 GB used", "${formatStorageBytes(5 * gib - gib / 10 - 1, "en")} of ${formatStorageBytes(5 * gib, "en")} used")
    }

    @Test
    fun stepsUpAtTheDisplayedBoundary() {
        assertEquals("1 KB", formatStorageBytes(1024, "en"))
        assertEquals("1.5 MB", formatStorageBytes(1024L * 1024 * 3 / 2, "en"))
        assertEquals("1 MB", formatStorageBytes(1024L * 1024 - 40, "en"))
        assertEquals("8 EB", formatStorageBytes(Long.MAX_VALUE, "en"))
    }

    @Test
    fun germanSeparatesTheDecimalWithACommaAndKeepsTheUnitSymbols() {
        val used = (8.3 * 1024 * 1024).toLong()
        assertEquals("8,3 MB von 5 GB", "${formatStorageBytes(used, "de")} von ${formatStorageBytes(StorageTiers.FREE_BYTES, "de")}")
        assertEquals("1,5 GB", formatStorageBytes(gib * 3 / 2, "de"))
        assertEquals("1.023 B", formatStorageBytes(1023, "de"))
        assertEquals("1.023,5 KB", formatStorageBytes(1023L * 1024 + 512, "de"))
    }
}
