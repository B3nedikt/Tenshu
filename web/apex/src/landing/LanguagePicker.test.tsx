// The language picker, as the landing footer mounts it: a <details> listing the landing page in
// each language, the current one marked, and a pick remembered for `/` (src/i18n.ts reads it
// first). The static, never-hydrated copies on the support pages (header and footer) are covered
// by support-pages/generate.test.ts.
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import i18n, { supportedLanguages } from '../i18n'
import enLanding from '../../locales/en/landing.json'
import deLanding from '../../locales/de/landing.json'
import frLanding from '../../locales/fr/landing.json'
import { MarketingFooter } from './MarketingFooter'
import { LANGUAGE_STORAGE_KEY, SITE_LANGUAGES, storeLanguageChoice } from './languages'

// Under Node ≥ 25 the runtime's own `localStorage` global shadows jsdom's (src/theme/theme.test.ts).
function installMemoryLocalStorage() {
  const map = new Map<string, string>()
  const storage: Storage = {
    get length() {
      return map.size
    },
    clear: () => map.clear(),
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: (k) => void map.delete(k),
    key: (i) => Array.from(map.keys())[i] ?? null,
  }
  Object.defineProperty(window, 'localStorage', { value: storage, configurable: true })
  Object.defineProperty(globalThis, 'localStorage', { value: storage, configurable: true })
}

beforeEach(() => installMemoryLocalStorage())
afterEach(async () => {
  await i18n.changeLanguage('en')
})

function renderFooter() {
  return render(
    <MemoryRouter>
      <MarketingFooter />
    </MemoryRouter>,
  )
}

describe('the landing footer language picker', () => {
  it('links the landing page in every site language, marking the current one', () => {
    renderFooter()
    const nav = screen.getByRole('navigation', { name: 'Language' })
    const links = within(nav).getAllByRole('link', { hidden: true })
    expect(links.map((a) => [a.textContent, a.getAttribute('href'), a.getAttribute('lang'), a.getAttribute('hreflang')])).toEqual([
      ['English', '/en', 'en', 'en'],
      ['Deutsch', '/de', 'de', 'de'],
      ['Français', '/fr', 'fr', 'fr'],
    ])
    expect(within(nav).getByRole('link', { name: 'English', hidden: true })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Deutsch', hidden: true })).not.toHaveAttribute('aria-current')
  })

  it('is a labelled disclosure showing the current language in its own name', () => {
    renderFooter()
    const nav = screen.getByRole('navigation', { name: 'Language' })
    const summary = nav.querySelector('summary')!
    expect(nav.querySelector('details')).not.toHaveAttribute('open')
    // "Language English": the label, then the current language, read in its own language.
    const name = summary.getAttribute('aria-labelledby')!.split(' ').map((id) => document.getElementById(id)!.textContent)
    expect(name).toEqual(['Language', 'English'])
    expect(summary.querySelector('[lang="en"]')).toHaveTextContent('English')
    expect(nav.querySelector('select')).toBeNull()
  })

  it('opens and closes like the phone menu: aria-expanded, Escape, a click outside', () => {
    renderFooter()
    const nav = screen.getByRole('navigation', { name: 'Language' })
    const details = nav.querySelector('details')!
    const summary = nav.querySelector('summary')!
    expect(summary).toHaveAttribute('aria-expanded', 'false')
    const open = () => {
      details.open = true
      fireEvent(details, new Event('toggle'))
    }
    open()
    expect(summary).toHaveAttribute('aria-expanded', 'true')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(details).not.toHaveAttribute('open')
    expect(summary).toHaveFocus()
    open()
    fireEvent.mouseDown(document.body)
    expect(details).not.toHaveAttribute('open')
    expect(summary).toHaveAttribute('aria-expanded', 'false')
  })

  it('sits beside the theme switch and follows the page language', async () => {
    await i18n.changeLanguage('fr')
    renderFooter()
    const nav = screen.getByRole('navigation', { name: 'Langue' })
    expect(within(nav).getByRole('link', { name: 'Français', hidden: true })).toHaveAttribute('aria-current', 'page')
    expect(nav.querySelector('summary [lang="fr"]')).toHaveTextContent('Français')
    expect(screen.getByRole('group', { name: 'Thème' })).toBeInTheDocument()
    expect(document.documentElement.lang).toBe('fr')
  })

  it('remembers a pick, and survives blocked storage', () => {
    renderFooter()
    fireEvent.click(screen.getByRole('link', { name: 'Deutsch', hidden: true }))
    expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('de')
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('SecurityError')
      },
    })
    expect(() => storeLanguageChoice('fr')).not.toThrow()
  })

  it('a stored pick decides the language of an address without one, ahead of the browser', async () => {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, 'de')
    const detector = i18n.services.languageDetector as { detect: () => string | string[] | undefined }
    const detected = detector.detect()
    expect(Array.isArray(detected) ? detected[0] : detected).toBe('de')
  })
})

describe('the site languages', () => {
  it('are exactly the SPA\'s supported languages, English first', () => {
    expect(SITE_LANGUAGES.map((l) => l.lang)).toEqual([...supportedLanguages])
    // Machine translations stay flagged until a native speaker has read them.
    expect(SITE_LANGUAGES.filter((l) => l.lang !== 'en').every((l) => !l.reviewed)).toBe(true)
  })

  it('every landing catalogue has exactly the English keys', () => {
    const keys = (o: object, prefix = ''): string[] =>
      Object.entries(o).flatMap(([k, v]) => (typeof v === 'object' && v !== null ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`]))
    const en = keys(enLanding).sort()
    expect(keys(deLanding).sort()).toEqual(en)
    expect(keys(frLanding).sort()).toEqual(en)
  })
})
