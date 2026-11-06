// The TypeScript copy of the policy-link rule (web/shared/legal.ts). Its base URL and languages are
// pinned to the Kotlin source of truth by PrivacyPolicyParityTest (:ui-core); this pins the choice.
import { describe, expect, it } from 'vitest'
import { privacyPolicyLabel, privacyPolicyLanguage, privacyPolicyUrl, termsOfUseUrl, termsWithdrawalUrl } from '@tenshu/web-tokens/legal'

describe('privacyPolicyUrl', () => {
  it('sends German readers to the binding German text', () => {
    for (const tag of ['de', 'de-DE', 'de-AT', 'de_CH', 'DE', ' de-de ']) {
      expect(privacyPolicyUrl(tag), tag).toBe('https://tenshu.app/legal/privacy/de')
    }
  })

  it('sends French readers to the French translation', () => {
    for (const tag of ['fr', 'fr-FR', 'fr-CA', 'fr_BE']) {
      expect(privacyPolicyUrl(tag), tag).toBe('https://tenshu.app/legal/privacy/fr')
    }
  })

  it('sends everyone else to English', () => {
    for (const tag of ['en', 'en-US', 'es-ES', 'ja-JP', 'zh-Hant-TW', '', null, undefined]) {
      expect(privacyPolicyUrl(tag), String(tag)).toBe('https://tenshu.app/legal/privacy/en')
    }
  })

  it('matches a language code whole, never by prefix', () => {
    expect(privacyPolicyLanguage('dsb')).toBe('en')
    expect(privacyPolicyLanguage('fur')).toBe('en')
    expect(privacyPolicyLanguage('deu')).toBe('en')
  })
})

describe('privacyPolicyLabel', () => {
  it('follows the same language as the URL', () => {
    expect(privacyPolicyLabel('de-AT')).toBe('Datenschutzerklärung')
    expect(privacyPolicyLabel('fr-CA')).toBe('Politique de confidentialité')
    expect(privacyPolicyLabel('es')).toBe('Privacy policy')
    expect(privacyPolicyLabel(null)).toBe('Privacy policy')
  })
})

describe('termsOfUseUrl', () => {
  it('picks the language the way the policy link does', () => {
    expect(termsOfUseUrl('de-AT')).toBe('https://tenshu.app/legal/terms/de')
    expect(termsOfUseUrl('fr_BE')).toBe('https://tenshu.app/legal/terms/fr')
    expect(termsOfUseUrl('es')).toBe('https://tenshu.app/legal/terms/en')
    expect(termsOfUseUrl(null)).toBe('https://tenshu.app/legal/terms/en')
  })

  it('points the withdrawal link at the section the terms page carries', () => {
    expect(termsWithdrawalUrl('de')).toBe('https://tenshu.app/legal/terms/de#withdrawal')
  })
})
