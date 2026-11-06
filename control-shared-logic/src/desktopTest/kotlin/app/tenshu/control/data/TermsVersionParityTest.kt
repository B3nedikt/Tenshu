package app.tenshu.control.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The versions a sign-up records must name the pages the sign-up screen links: [CommercePublisherClient.TOS_VERSION]
 * is the published terms' date (`TERMS_DATE`, `web/apex/support-pages/terms.ts`) and
 * [CommercePublisherClient.PRIVACY_VERSION] the published policy's (`PRIVACY_POLICY_DATE`, `privacy.ts`).
 * The web's copies are pinned the same way by `commerceApi.test.ts`; this is the Kotlin half, so a
 * bump to the page that forgets the app fails here instead of recording a version nobody was shown.
 *
 * ⚠ Reads the generator sources by path relative to the module directory (Gradle's test working
 * directory); a moved file fails rather than skips.
 */
class TermsVersionParityTest {

    private val supportPages = File(File("..").canonicalFile, "web/apex/support-pages")

    @Test
    fun theTermsVersionIsThePublishedTermsDate() {
        assertEquals(publishedDate("terms.ts", "TERMS_DATE"), CommercePublisherClient.TOS_VERSION)
    }

    @Test
    fun thePrivacyVersionIsThePublishedPolicysDate() {
        assertEquals(publishedDate("privacy.ts", "PRIVACY_POLICY_DATE"), CommercePublisherClient.PRIVACY_VERSION)
    }

    private fun publishedDate(file: String, constant: String): String {
        val source = File(supportPages, file)
        assertTrue(source.isFile, "Not found: $source — update this test if it moved")
        return Regex("""export const $constant = '([0-9-]+)'""").find(source.readText())?.groupValues?.get(1)
            ?: error("No $constant in $source")
    }
}
