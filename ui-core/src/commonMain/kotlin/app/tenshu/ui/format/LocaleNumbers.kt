package app.tenshu.ui.format

import androidx.compose.ui.text.intl.Locale
import org.tenshu.core.common.format.LocaleNumbers as CoreLocaleNumbers
import org.tenshu.storage.common.formatStorageBytes

/**
 * Figures in the user's language — `4,315` / `3.2` in English, `4.315` / `3,2` in German — for
 * Compose code. The formatting itself is `:core-common`'s `org.tenshu.core.common.format.LocaleNumbers`
 * (the platform's CLDR data, half-up rounding); this facade only defaults the locale to Compose's
 * [Locale.current], the same one `stringResource` resolves against, so a figure reads in the
 * language of the label beside it.
 *
 * A Compose-free `*-shared-logic` module calls the `:core-common` object directly. ⚠ Never
 * hand-roll a separator (`"$whole.$tenth"`): `docs/proposals/locale-number-formatting.md`.
 */
object LocaleNumbers {

    /** A whole number with the locale's grouping: `1,234,567` / `1.234.567`. */
    fun integer(value: Long, languageTag: String = currentTag()): String =
        CoreLocaleNumbers.integer(value, languageTag)

    fun integer(value: Int, languageTag: String = currentTag()): String = CoreLocaleNumbers.integer(value, languageTag)

    /**
     * [value] with exactly [fractionDigits] decimals (rounded half-up) and the locale's
     * separators: `decimal(3.2, 1)` → `3.2` / `3,2`.
     */
    fun decimal(value: Double, fractionDigits: Int, languageTag: String = currentTag()): String =
        CoreLocaleNumbers.decimal(value, fractionDigits, languageTag)

    /** [value] with at most [maxFractionDigits] decimals, trailing zeros dropped: `5` / `8,3`. */
    fun upTo(value: Double, maxFractionDigits: Int, languageTag: String = currentTag()): String =
        CoreLocaleNumbers.upTo(value, maxFractionDigits, languageTag)

    /**
     * A size — `8.3 MB` / `8,3 MB`, binary steps — through the one byte formatter,
     * `formatStorageBytes` (`:storage-service-common`; its KDoc says why binary).
     */
    fun bytes(value: Long, languageTag: String = currentTag()): String = formatStorageBytes(value, languageTag)

    private fun currentTag(): String = Locale.current.toLanguageTag()
}
