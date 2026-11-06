package app.tenshu.ui.legal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.core.common.legal.PrivacyPolicy
import org.tenshu.core.common.legal.TermsOfUse

/**
 * The privacy-policy link is written three times — Kotlin ([PrivacyPolicy]), Swift (every iOS
 * app) and TypeScript (the web apps) — because no one toolchain can call the others. This pins the
 * two copies to the Kotlin one, the way `ErrorCopyParityTest` pins the error catalogue: a policy
 * that moved, or gained a language, in one place would otherwise send each platform's readers to a
 * different page with no compile error anywhere.
 *
 * ⚠ Reads sources by path relative to the module directory (Gradle's test working directory); a
 * moved file fails [bothCopiesExistWhereExpected] rather than passing silently.
 */
class PrivacyPolicyParityTest {

    private val repoRoot = File("..").canonicalFile
    private val swift = File(repoRoot, "ios-release/Sources/PrivacyPolicy.swift")
    private val typeScript = File(repoRoot, "web/shared/legal.ts")

    /** The page generator: which languages the policy is actually PUBLISHED in. */
    private val publishedPages = File(repoRoot, "web/apex/support-pages/privacy.ts")

    /** The terms' generator: they share the policy's languages ([TermsOfUse] reuses its choice). */
    private val publishedTerms = File(repoRoot, "web/apex/support-pages/terms.ts")

    @Test
    fun bothCopiesExistWhereExpected() {
        assertTrue(swift.isFile, "Not found: $swift — update this test if it moved")
        assertTrue(typeScript.isFile, "Not found: $typeScript — update this test if it moved")
    }

    @Test
    fun swiftUsesTheSameUrlAndLanguages() {
        val source = swift.readText()
        assertEquals(PrivacyPolicy.BASE_URL, capture(source, """static let baseURL = "([^"]+)""""))
        assertEquals(PrivacyPolicy.FALLBACK_LANGUAGE, capture(source, """static let fallbackLanguage = "([^"]+)""""))
        assertEquals(PrivacyPolicy.LANGUAGES.toSet(), quoted(capture(source, """static let languages: Set<String> = \[([^\]]*)]""")))
    }

    @Test
    fun typeScriptUsesTheSameUrlAndLanguages() {
        val source = typeScript.readText()
        assertEquals(PrivacyPolicy.BASE_URL, capture(source, """PRIVACY_POLICY_BASE_URL = '([^']+)'"""))
        assertEquals(PrivacyPolicy.FALLBACK_LANGUAGE, capture(source, """PRIVACY_POLICY_FALLBACK_LANGUAGE = '([^']+)'"""))
        assertEquals(PrivacyPolicy.LANGUAGES.toSet(), quoted(capture(source, """PRIVACY_POLICY_LANGUAGES: readonly string\[] = \[([^\]]*)]""")))
    }

    /**
     * The links may only promise languages the site publishes, or a reader lands on a 404.
     *
     * ⚠ A missing `privacy.ts` fails rather than skips: the generator exists, so its absence means
     * it moved, and a skipped check would stop guarding the links without a word.
     */
    @Test
    fun linksPromiseOnlyLanguagesThePageGeneratorPublishes() {
        assertTrue(publishedPages.isFile, "Not found: $publishedPages — update this test if it moved")
        val source = publishedPages.readText()
        assertEquals(
            PrivacyPolicy.LANGUAGES.toSet(),
            quoted(capture(source, """PRIVACY_LANGUAGES\s*(?::[^=]*)?=\s*\[([^\]]*)]""")),
        )
    }

    /**
     * The terms of use ([TermsOfUse]) are linked from the sign-up screens on all three toolchains,
     * with the policy's language rule — so they must be published in the policy's languages too.
     */
    @Test
    fun termsUseTheSameUrlEverywhereAndThePolicysLanguages() {
        assertEquals(TermsOfUse.BASE_URL, capture(swift.readText(), """static let termsBaseURL = "([^"]+)""""))
        assertEquals(TermsOfUse.BASE_URL, capture(typeScript.readText(), """TERMS_OF_USE_BASE_URL = '([^']+)'"""))
        assertTrue(publishedTerms.isFile, "Not found: $publishedTerms — update this test if it moved")
        assertEquals(
            PrivacyPolicy.LANGUAGES.toSet(),
            quoted(capture(publishedTerms.readText(), """TERMS_LANGUAGES\s*(?::[^=]*)?=\s*\[([^\]]*)]""")),
        )
    }

    private fun capture(source: String, pattern: String): String =
        Regex(pattern).find(source)?.groupValues?.get(1) ?: error("No match for $pattern")

    private fun quoted(list: String): Set<String> =
        Regex("""["']([a-z]+)["']""").findAll(list).map { it.groupValues[1] }.toSet()
}
