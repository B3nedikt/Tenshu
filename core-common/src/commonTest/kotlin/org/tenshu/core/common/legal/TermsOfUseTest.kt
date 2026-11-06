package org.tenshu.core.common.legal

import kotlin.test.Test
import kotlin.test.assertEquals

class TermsOfUseTest {

    /** The terms pick their translation exactly as the privacy policy does. */
    @Test
    fun theTermsFollowThePolicysLanguageChoice() {
        assertEquals("https://tenshu.app/legal/terms/de", TermsOfUse.url("de-AT"))
        assertEquals("https://tenshu.app/legal/terms/fr", TermsOfUse.url("fr_BE"))
        assertEquals("https://tenshu.app/legal/terms/en", TermsOfUse.url("es-ES"))
        assertEquals("https://tenshu.app/legal/terms/en", TermsOfUse.url(null))
    }

    @Test
    fun theWithdrawalLinkPointsAtTheSection() {
        assertEquals("https://tenshu.app/legal/terms/de#withdrawal", TermsOfUse.withdrawalUrl("de-DE"))
    }
}
