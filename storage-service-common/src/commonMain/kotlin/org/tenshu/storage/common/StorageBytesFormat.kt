package org.tenshu.storage.common

import org.tenshu.core.common.format.LocaleNumbers
import kotlin.math.roundToLong

/**
 * A byte count as the apps show it — `"221 B"`, `"8.3 MB"`, `"5 GB"`, `"2 TB"`.
 *
 * The **one** byte formatter for every Kotlin and Swift surface that shows a size: the Services
 * storage card (Compose and SwiftUI), every app's realm-storage banner (Compose and the iOS
 * `RealmStorageSurface`), Drive's file sizes (`formatByteSize` delegates here), offline-map region
 * bundles (Control, Maps, Timeline), on-device models (Control, Photos) and Fit's import archives. Swift
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
 * symbols are the same in every shipped language, so they stay code-side (ADR 0016 n=1) — but the
 * **number** follows the locale: "8,3 MB" in German, "1,023.9 KB" / "1.023,9 KB" grouped
 * (`LocaleNumbers`, `docs/proposals/locale-number-formatting.md`).
 *
 * The one-argument form uses the platform's current language — the overload Swift reaches, since a
 * Kotlin default argument does not cross into Objective-C. Compose callers pass
 * `Locale.current.toLanguageTag()` so the figure matches the `stringResource` label beside it.
 */
fun formatStorageBytes(bytes: Long): String = formatStorageBytes(bytes, LocaleNumbers.defaultLanguageTag())

/** [formatStorageBytes] in [languageTag] — `"8.3 MB"` (`en`), `"8,3 MB"` (`de`). */
fun formatStorageBytes(bytes: Long, languageTag: String): String {
    if (bytes < 1024) return "${LocaleNumbers.integer(bytes.coerceAtLeast(0), languageTag)} B"

    var value = bytes.toDouble()
    var unit = 0
    while (unit < UNITS.lastIndex && (value * 10).roundToLong() >= 1024 * 10) {
        value /= 1024.0
        unit++
    }
    return "${LocaleNumbers.upTo(value, 1, languageTag)} ${UNITS[unit]}"
}

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
