package org.tenshu.core.common.legal

/**
 * Where Tenshu's terms of use live: `https://tenshu.app/legal/terms/<lang>`, a static page the
 * apex's support-page build generates (`web/apex/support-pages/terms.ts`) beside the privacy
 * policy, in the same languages and with the same choice of translation ([PrivacyPolicy.languageFor]).
 * Control's sign-up screen links both.
 *
 * ⚠⚠ Copied as `TermsOfUse` in `ios-release/Sources/PrivacyPolicy.swift` and `termsOfUseUrl` in
 * `web/shared/legal.ts`; `PrivacyPolicyParityTest` (`:ui-core`) pins both base URLs to [BASE_URL].
 * ⚠ The per-language URLs are linked from shipped apps: never move them. ⚖️ The German text is
 * the binding one; the others are translations.
 */
object TermsOfUse {
    /** The terms' path on tenshu.app, without the language segment. */
    const val BASE_URL: String = "https://tenshu.app/legal/terms"

    /** The terms' URL for a reader whose device or app language is [languageTag]. */
    fun url(languageTag: String?): String = "$BASE_URL/${PrivacyPolicy.languageFor(languageTag)}"

    /** The page's withdrawal section (`<h2 id="withdrawal">` in terms.ts). */
    const val WITHDRAWAL_ANCHOR: String = "withdrawal"

    /**
     * The withdrawal notice and model form, which both checkout screens link: without it the
     * period does not start (§ 356 Abs. 3 BGB) and no compensation is owed (§ 357a Abs. 2).
     */
    fun withdrawalUrl(languageTag: String?): String = "${url(languageTag)}#$WITHDRAWAL_ANCHOR"
}
