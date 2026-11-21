package org.tenshu.core.common.format

/**
 * `Intl.NumberFormat` over the decimal *string* (exact since Intl.NumberFormat v3), with
 * `halfExpand` — ties away from zero, the same rule as `RoundingMode.HALF_UP` and
 * `NSNumberFormatterRoundHalfUp`.
 */
internal actual fun formatPlatformNumber(
    value: String,
    minFractionDigits: Int,
    maxFractionDigits: Int,
    languageTag: String,
): String {
    val options: dynamic = js("({})")
    options.useGrouping = true
    options.minimumFractionDigits = minFractionDigits
    options.maximumFractionDigits = maxFractionDigits
    options.roundingMode = "halfExpand"
    return Intl.NumberFormat(languageTag, options).format(value)
}

internal actual fun platformLanguageTag(): String {
    val navigatorLanguage: dynamic = js("(typeof navigator !== 'undefined' && navigator.language) || null")
    if (navigatorLanguage != null) return navigatorLanguage as String
    return Intl.NumberFormat().resolvedOptions().locale as String
}

private external object Intl {
    class NumberFormat(locales: String = definedExternally, options: dynamic = definedExternally) {
        fun format(value: String): String
        fun resolvedOptions(): dynamic
    }
}
