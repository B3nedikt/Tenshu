import { describe, expect, it } from 'vitest'
import { initAppI18n } from './i18n'
import { catalogueKeys } from './testing/catalogues'

describe('initAppI18n', () => {
  it('serves each language’s catalogue under the app’s namespace, English as the fallback', async () => {
    const i18n = initAppI18n('demo', {
      en: { greeting: 'Hello {name}', only: { en: 'English only' } },
      de: { greeting: 'Hallo {name}' },
      fr: { greeting: 'Bonjour {name}' },
    })
    await i18n.changeLanguage('de')
    expect(i18n.t('greeting', { name: 'Ada' })).toBe('Hallo Ada')
    expect(i18n.t('only.en')).toBe('English only')
    expect(i18n.options.defaultNS).toBe('demo')
  })
})

describe('catalogueKeys', () => {
  it('lists every leaf as a dotted path', () => {
    expect(catalogueKeys({ a: 'x', b: { c: 'y', d: { e: 'z' } } }).sort()).toEqual(['a', 'b.c', 'b.d.e'])
  })
})
