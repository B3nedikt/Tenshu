// Where Tenshu's privacy policy lives, and which translation a reader gets: German for a German
// language, French for French, English otherwise. The apex's Control (Settings, the account
// sign-up) and web Drive's Settings link it in-app.
//
// ⚠⚠ The TypeScript copy of `org.tenshu.core.common.legal.PrivacyPolicy` (`:core-common`), which is
// the source of truth; `PrivacyPolicyParityTest` (`:ui-core`) pins this file's base URL and
// languages to it. Change both together. The page itself is static, built by
// `web/apex/support-pages/privacy.ts`; the store listings carry the language-neutral `/legal/privacy`,
// which Caddy redirects by Accept-Language.

export const PRIVACY_POLICY_BASE_URL = 'https://tenshu.app/legal/privacy'
export const PRIVACY_POLICY_LANGUAGES: readonly string[] = ['de', 'en', 'fr']
export const PRIVACY_POLICY_FALLBACK_LANGUAGE = 'en'

/** The published language for a BCP 47 tag (`de-AT`), a POSIX locale (`de_CH`) or a bare code. */
export function privacyPolicyLanguage(tag: string | null | undefined): string {
  const code = (tag ?? '').trim().split(/[-_]/)[0]!.toLowerCase()
  return PRIVACY_POLICY_LANGUAGES.includes(code) ? code : PRIVACY_POLICY_FALLBACK_LANGUAGE
}

/** The policy's URL for a reader whose UI (or browser) language is [tag]. */
export function privacyPolicyUrl(tag: string | null | undefined): string {
  return `${PRIVACY_POLICY_BASE_URL}/${privacyPolicyLanguage(tag)}`
}

/**
 * The link's label in the policy's language, for an app with no translations of its own (web
 * Drive): it follows the same rule as the URL, so the words and the page always agree. Apps with
 * i18n (the apex) use their own catalogue instead.
 */
const PRIVACY_POLICY_LABELS: Readonly<Record<string, string>> = {
  de: 'Datenschutzerklärung',
  en: 'Privacy policy',
  fr: 'Politique de confidentialité',
}

export function privacyPolicyLabel(tag: string | null | undefined): string {
  return PRIVACY_POLICY_LABELS[privacyPolicyLanguage(tag)]!
}

/**
 * Where Tenshu's terms of use live: the same languages and the same choice as the privacy policy
 * (`web/apex/support-pages/terms.ts` publishes de/en/fr). The apex's sign-up screen links both.
 *
 * ⚠⚠ The TypeScript copy of `org.tenshu.core.common.legal.TermsOfUse`; `PrivacyPolicyParityTest`
 * (`:ui-core`) pins the base URL. Change both together.
 */
export const TERMS_OF_USE_BASE_URL = 'https://tenshu.app/legal/terms'

/** The terms' URL for a reader whose UI (or browser) language is [tag]. */
export function termsOfUseUrl(tag: string | null | undefined): string {
  return `${TERMS_OF_USE_BASE_URL}/${privacyPolicyLanguage(tag)}`
}

/**
 * The terms' withdrawal notice and model form (`#withdrawal`), which the checkout links: without
 * it the period does not start (§ 356 Abs. 3 BGB) and no compensation is owed (§ 357a Abs. 2).
 */
export function termsWithdrawalUrl(tag: string | null | undefined): string {
  return `${termsOfUseUrl(tag)}#withdrawal`
}
