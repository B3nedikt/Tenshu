package org.tenshu.storage.common

import kotlin.math.roundToLong

/**
 * A byte count as the apps show it — `"221 B"`, `"8.3 MB"`, `"5 GB"`, `"2 TB"`.
 *
 * The **one** byte formatter for every Kotlin and Swift surface that shows a storage figure: the
 * Services storage card (Compose and SwiftUI), every app's realm-storage banner (Compose and the
 * iOS `RealmStorageSurface`), and Drive's file sizes (`formatByteSize` delegates here). Swift
 * reaches it through the TenshuSdk framework as `StorageBytesFormatKt.formatStorageBytes(bytes:)`
 * — `:storage-service-common` is exported by every iOS app framework.
 *
 * ⚠⚠ **Binary steps (1 KB = 1024 bytes), because the plans are binary.** Free is
 * `StorageTiers.FREE_BYTES` = 5 GiB = 5 368 709 120 bytes. Formatted with decimal steps — what
 * `ByteCountFormatter` and the banners' old `/ 1_000_000_000` did — the plan the user bought as
 * "5 GB" read back as **"5.37 GB"** (App Store screenshot run, 2026-10-14), and Premium's 2 TiB as
 * "2.2 TB". Never mix bases on one line: "8.3 MB of 5 GB used" must use the same unit for both.
 *
 * Unit names are the conventional upper-case "KB/MB/GB/TB" rather than "KiB…", matching how the
 * tiers are named everywhere else. One decimal place, dropped when it is `.0` ("5 GB", not
 * "5.0 GB"), rounded half-up — truncation understates a usage figure by up to a whole unit. The
 * unit steps up when the value would *display* as 1024 or more, so 1023.96 KB reads "1 MB". The
 * symbols are the same in every shipped language, so they stay code-side (ADR 0016 n=1).
 */
fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"

    var value = bytes.toDouble()
    var unit = 0
    while (unit < UNITS.lastIndex && (value * 10).roundToLong() >= 1024 * 10) {
        value /= 1024.0
        unit++
    }
    return "${upToOneDecimal(value)} ${UNITS[unit]}"
}

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

/** Rounds to at most one decimal place and drops a trailing `.0`. */
private fun upToOneDecimal(value: Double): String {
    val scaled = (value * 10).roundToLong()
    val whole = scaled / 10
    val frac = (scaled % 10).toInt()
    return if (frac == 0) whole.toString() else "$whole.$frac"
}
