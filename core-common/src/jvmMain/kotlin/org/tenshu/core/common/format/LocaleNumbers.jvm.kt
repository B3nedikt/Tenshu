package org.tenshu.core.common.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

internal actual fun formatPlatformNumber(
    value: String,
    minFractionDigits: Int,
    maxFractionDigits: Int,
    languageTag: String,
): String {
    val format = NumberFormat.getNumberInstance(Locale.forLanguageTag(languageTag))
    format.isGroupingUsed = true
    format.minimumFractionDigits = minFractionDigits
    format.maximumFractionDigits = maxFractionDigits
    format.roundingMode = RoundingMode.HALF_UP
    return format.format(BigDecimal(value))
}

/** Compose's `Locale.current` on Android and desktop is `java.util.Locale.getDefault()`. */
internal actual fun platformLanguageTag(): String = Locale.getDefault().toLanguageTag()
