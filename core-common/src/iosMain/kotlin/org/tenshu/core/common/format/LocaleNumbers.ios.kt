package org.tenshu.core.common.format

import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages

internal actual fun formatPlatformNumber(
    value: String,
    minFractionDigits: Int,
    maxFractionDigits: Int,
    languageTag: String,
): String {
    val format = NSNumberFormatter()
    format.locale = NSLocale(localeIdentifier = languageTag.replace('-', '_'))
    format.numberStyle = NSNumberFormatterDecimalStyle
    format.usesGroupingSeparator = true
    format.minimumFractionDigits = minFractionDigits.toULong()
    format.maximumFractionDigits = maxFractionDigits.toULong()
    format.roundingMode = NSNumberFormatterRoundHalfUp
    return format.stringFromNumber(NSDecimalNumber(string = value)) ?: value
}

/** Compose's `Locale.current` on iOS is the first preferred language. */
internal actual fun platformLanguageTag(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)
        ?: NSLocale.currentLocale.localeIdentifier.replace('_', '-')
