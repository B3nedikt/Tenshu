package org.tenshu.core.common.format

/**
 * Figures in the user's language — `4,315` / `3.2` in English, `4.315` / `3,2` in German,
 * `4 315` / `3,2` in French (a narrow no-break space), `4’315` in Swiss German — from the
 * platform's own CLDR data (`java.text` on Android and desktop, `NSNumberFormatter` on iOS,
 * `Intl.NumberFormat` on JS).
 *
 * Compose-free on purpose, so a `*-shared-logic` module that builds a display string can reach it;
 * Compose code calls `app.tenshu.ui.format.LocaleNumbers` instead, which defaults the locale to
 * Compose's `Locale.current`. Here the default is [defaultLanguageTag], read from the same platform
 * source Compose reads (`java.util.Locale.getDefault()`, `NSLocale.preferredLanguages.first`), so
 * a figure built in a view model and a label resolved by `stringResource` agree.
 *
 * ⚠ Never hand-roll a separator (`"$whole.$tenth"`, a literal `,` between thousands): that is
 * English everywhere. ⚠ Desktop relies on the packaged runtime carrying `jdk.localedata` (root
 * `build.gradle.kts`); without it `java.text` silently answers every locale in English.
 * Rounding is half-up (away from zero on a tie) on every target, so two platforms never disagree
 * about `72.45`. The rule and its call sites: `docs/proposals/locale-number-formatting.md`.
 */
object LocaleNumbers {

    /** A whole number with the locale's grouping: `1,234,567` / `1.234.567`. */
    fun integer(value: Long, languageTag: String = defaultLanguageTag()): String =
        formatPlatformNumber(value.toString(), 0, 0, languageTag)

    fun integer(value: Int, languageTag: String = defaultLanguageTag()): String = integer(value.toLong(), languageTag)

    /**
     * [value] with exactly [fractionDigits] decimals (rounded half-up) and the locale's
     * separators: `decimal(3.2, 1)` → `3.2` / `3,2`.
     */
    fun decimal(value: Double, fractionDigits: Int, languageTag: String = defaultLanguageTag()): String {
        require(fractionDigits in 0..MAX_FRACTION_DIGITS) { "fractionDigits out of range: $fractionDigits" }
        if (!value.isFinite()) return value.toString()
        return formatPlatformNumber(value.toString(), fractionDigits, fractionDigits, languageTag)
    }

    /**
     * [value] with at most [maxFractionDigits] decimals, trailing zeros dropped: `upTo(5.0, 1)` →
     * `5`, `upTo(8.25, 1)` → `8.3` / `8,3`. The shape of a size or a reading where `5.0 GB` would
     * be noise.
     */
    fun upTo(value: Double, maxFractionDigits: Int, languageTag: String = defaultLanguageTag()): String {
        require(maxFractionDigits in 0..MAX_FRACTION_DIGITS) { "maxFractionDigits out of range: $maxFractionDigits" }
        if (!value.isFinite()) return value.toString()
        return formatPlatformNumber(value.toString(), 0, maxFractionDigits, languageTag)
    }

    /** The platform's current language tag — what Compose's `Locale.current` reads on that target. */
    fun defaultLanguageTag(): String = platformLanguageTag()

    private const val MAX_FRACTION_DIGITS = 6
}

/**
 * Formats the decimal string [value] with grouping and between [minFractionDigits] and
 * [maxFractionDigits] decimals, half-up, in [languageTag]. ⚠ A string, not a `Double`: a `Long`
 * past 2^53 would lose digits on the way, and a `Double` rounds as its shortest decimal form
 * (72.45 → 72.5), not its binary neighbour.
 */
internal expect fun formatPlatformNumber(
    value: String,
    minFractionDigits: Int,
    maxFractionDigits: Int,
    languageTag: String,
): String

internal expect fun platformLanguageTag(): String
