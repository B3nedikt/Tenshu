package org.tenshu.core.common.legal

import kotlin.test.Test
import kotlin.test.assertEquals

class PrivacyPolicyTest {

    @Test
    fun germanReadersGetTheBindingGermanText() {
        for (tag in listOf("de", "de-DE", "de-AT", "de_CH", "DE", " de-de ")) {
            assertEquals("https://tenshu.app/legal/privacy/de", PrivacyPolicy.url(tag), tag)
        }
    }

    @Test
    fun frenchReadersGetTheFrenchTranslation() {
        for (tag in listOf("fr", "fr-FR", "fr-CA", "fr_BE")) {
            assertEquals("https://tenshu.app/legal/privacy/fr", PrivacyPolicy.url(tag), tag)
        }
    }

    @Test
    fun everyoneElseGetsEnglish() {
        for (tag in listOf("en", "en-US", "en-GB", "es-ES", "it", "ja-JP", "zh-Hant-TW", "", "  ", null)) {
            assertEquals("https://tenshu.app/legal/privacy/en", PrivacyPolicy.url(tag), "$tag")
        }
    }

    /** A language whose code merely *starts* like a published one must not be mistaken for it. */
    @Test
    fun aLanguageCodeIsMatchedWholeNotByPrefix() {
        assertEquals("en", PrivacyPolicy.languageFor("dsb")) // Lower Sorbian, not German
        assertEquals("en", PrivacyPolicy.languageFor("fur")) // Friulian, not French
        assertEquals("en", PrivacyPolicy.languageFor("deu")) // ISO 639-2 is not what platforms report
    }

    @Test
    fun theFallbackIsItselfPublished() {
        assertEquals(true, PrivacyPolicy.FALLBACK_LANGUAGE in PrivacyPolicy.LANGUAGES)
    }
}
