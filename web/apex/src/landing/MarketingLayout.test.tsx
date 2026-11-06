// MarketingLayout: the landing page's own header and footer around a public page that has no URL
// per language (the share viewer, the invite page, the signup-closed notice). Pins that it is the
// same chrome, that it never probes the realm, and that its language picker switches in place —
// a share being decrypted must not be navigated away from.
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import i18n from '../i18n'
import { MarketingLayout } from './MarketingLayout'
import { LANGUAGE_STORAGE_KEY } from './languages'

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

function Where() {
  const { pathname, hash } = useLocation()
  return <output data-testid="where">{pathname + hash}</output>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="s/:id"
          element={
            <MarketingLayout>
              <main>
                <h1>Tenshu share</h1>
                <Where />
              </main>
            </MarketingLayout>
          }
        />
      </Routes>
    </MemoryRouter>,
  )
}

describe('MarketingLayout', () => {
  it('wraps the page in the landing header and footer, with a logged-out CTA and no probe', () => {
    renderAt('/s/abc#k=secret')
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Tenshu share' })).toBeInTheDocument()
    // The CTA a first-time visitor sees, resolved at once: no Session.detect() ran.
    const ctas = within(screen.getByRole('banner')).getAllByRole('link', { name: /Get started|Coming soon/, hidden: true })
    expect(ctas.map((a) => a.getAttribute('href'))).toEqual(['/start', '/start']) // the bar's and the phone menu's
    expect(screen.getByRole('group', { name: 'Theme' })).toBeInTheDocument()
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Imprint' })).toHaveAttribute('href', '/legal/imprint/en')
    // The footer links the policy in the page's language; the stores carry the neutral /legal/privacy.
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Privacy' })).toHaveAttribute('href', '/legal/privacy/en')
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Terms' })).toHaveAttribute('href', '/legal/terms/en')
  })

  it('switches language in place: no navigation, the fragment stays, the key never reaches a link', async () => {
    renderAt('/s/abc#k=secret')
    const nav = screen.getByRole('navigation', { name: 'Language' })
    const deutsch = within(nav).getByRole('link', { name: 'Deutsch', hidden: true })
    expect(deutsch).toHaveAttribute('href', '/s/abc')
    for (const a of within(nav).getAllByRole('link', { hidden: true })) expect(a.getAttribute('href')).not.toContain('secret')
    fireEvent.click(deutsch)
    expect(await screen.findByRole('navigation', { name: 'Sprache' })).toBeInTheDocument()
    expect(i18n.resolvedLanguage).toBe('de')
    expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('de')
    expect(screen.getByTestId('where')).toHaveTextContent('/s/abc#k=secret')
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Impressum' })).toHaveAttribute('href', '/legal/imprint/de')
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Datenschutz' })).toHaveAttribute('href', '/legal/privacy/de')
    expect(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Nutzungsbedingungen' })).toHaveAttribute('href', '/legal/terms/de')
  })
})
