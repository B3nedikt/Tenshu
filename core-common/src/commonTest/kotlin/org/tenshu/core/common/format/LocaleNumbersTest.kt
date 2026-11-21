package org.tenshu.core.common.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs on every target, so Android/desktop (`java.text`), iOS (`NSNumberFormatter`) and JS (`Intl`) agree. */
class LocaleNumbersTest {

    @Test
    fun englishGroupsWithCommasAndDecimalsWithAPoint() {
        assertEquals("4,315", LocaleNumbers.integer(4315, "en"))
        assertEquals("1,234,567", LocaleNumbers.integer(1_234_567L, "en-US"))
        assertEquals("3.2", LocaleNumbers.decimal(3.2, 1, "en"))
        assertEquals("1,003.0", LocaleNumbers.decimal(1003.0, 1, "en"))
    }

    @Test
    fun germanGroupsWithPointsAndDecimalsWithAComma() {
        assertEquals("4.315", LocaleNumbers.integer(4315, "de"))
        assertEquals("1.234.567", LocaleNumbers.integer(1_234_567L, "de-DE"))
        assertEquals("3,2", LocaleNumbers.decimal(3.2, 1, "de"))
        assertEquals("1,05", LocaleNumbers.decimal(1.05, 2, "de"))
    }

    @Test
    fun frenchGroupsWithASpaceCharacterAndDecimalsWithAComma() {
        val grouped = LocaleNumbers.integer(4315, "fr-FR")
        // CLDR's French group separator is a (narrow) no-break space; which one is CLDR-version
        // data, so pin the shape, not the code point.
        assertEquals(5, grouped.length, grouped)
        assertTrue(grouped[1].isWhitespace() || grouped[1] == ' ' || grouped[1] == ' ', grouped)
        assertEquals("4315", grouped.filter { it.isDigit() })
        assertEquals("3,2", LocaleNumbers.decimal(3.2, 1, "fr"))
    }

    @Test
    fun roundsHalfUpAsWrittenOnEveryTarget() {
        assertEquals("72.5", LocaleNumbers.decimal(72.45, 1, "en"))
        assertEquals("72.5", LocaleNumbers.decimal(72.46, 1, "en"))
        assertEquals("-0.4", LocaleNumbers.decimal(-0.44, 1, "en"))
        assertEquals("0", LocaleNumbers.integer(0, "de"))
        assertEquals("-4.315", LocaleNumbers.integer(-4315, "de"))
    }

    @Test
    fun aLongPastDoublePrecisionKeepsEveryDigit() {
        assertEquals("9,007,199,254,740,993", LocaleNumbers.integer(9_007_199_254_740_993L, "en-US"))
        assertEquals("-9.223.372.036.854.775.808", LocaleNumbers.integer(Long.MIN_VALUE, "de"))
    }

    @Test
    fun upToDropsATrailingZeroButKeepsARealDecimal() {
        assertEquals("5", LocaleNumbers.upTo(5.0, 1, "en"))
        assertEquals("5", LocaleNumbers.upTo(5.0, 1, "de"))
        assertEquals("8.3", LocaleNumbers.upTo(8.25, 1, "en"))
        assertEquals("8,3", LocaleNumbers.upTo(8.25, 1, "de"))
        assertEquals("1,023.9", LocaleNumbers.upTo(1023.94, 1, "en"))
        assertEquals("1.023,9", LocaleNumbers.upTo(1023.94, 1, "de"))
        assertEquals("2", LocaleNumbers.upTo(1.96, 0, "de"))
    }

    @Test
    fun aNonFiniteValueIsNotHandedToThePlatform() {
        assertEquals("NaN", LocaleNumbers.decimal(Double.NaN, 1, "de"))
        assertEquals("Infinity", LocaleNumbers.upTo(Double.POSITIVE_INFINITY, 1, "de"))
    }

    @Test
    fun theDefaultTagIsAUsableLanguageTag() {
        val tag = LocaleNumbers.defaultLanguageTag()
        assertTrue(tag.isNotBlank(), tag)
        assertEquals("4", LocaleNumbers.integer(4, tag).filter { it.isDigit() })
    }
}
