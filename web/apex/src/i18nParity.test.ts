// Key parity for the namespaces that ship in all three languages (`settings`, `dataFiles`): a key
// missing from one language renders English — or the raw key — silently, and no component test
// that runs in English notices. `control`, `onboarding` and `files` are en/de only (i18n.ts), so
// they are held to en/de parity.
import { describe, expect, it } from 'vitest'

const catalogues = import.meta.glob<Record<string, unknown>>('../locales/*/*.json', {
  eager: true,
  import: 'default',
})

function keys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix]
  return Object.entries(value).flatMap(([k, v]) => keys(v, prefix ? `${prefix}.${k}` : k))
}

function catalogue(lang: string, ns: string): Record<string, unknown> | undefined {
  return catalogues[`../locales/${lang}/${ns}.json`]
}

describe('locale key parity', () => {
  for (const ns of ['settings', 'dataFiles']) {
    it(`${ns}: en, de and fr carry the same keys`, () => {
      const en = keys(catalogue('en', ns)).sort()
      expect(en.length).toBeGreaterThan(5)
      expect(keys(catalogue('de', ns)).sort()).toEqual(en)
      expect(keys(catalogue('fr', ns)).sort()).toEqual(en)
    })
  }

  for (const ns of ['control', 'onboarding', 'files']) {
    it(`${ns}: en and de carry the same keys`, () => {
      expect(keys(catalogue('de', ns)).sort()).toEqual(keys(catalogue('en', ns)).sort())
    })
  }
})
