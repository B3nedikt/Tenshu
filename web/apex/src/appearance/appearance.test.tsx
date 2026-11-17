// This browser's mode and language (docs/proposals/web-apex-home.md §4.13).
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18n from 'i18next'
import { THEME_STORAGE_KEY } from '@tenshu/web-tokens/theme'
import { LANGUAGE_STORAGE_KEY } from '../landing/languages'
import { AppearanceControls } from './AppearanceControls'
import { getAppearance, setLanguage, setMode } from './appearance'

const root = document.documentElement

// Node ≥ 25 shadows jsdom's localStorage with an undefined global (theme.test.ts has the same).
function installMemoryLocalStorage() {
  const map = new Map<string, string>()
  const storage: Storage = {
    get length() {
      return map.size
    },
    clear: () => map.clear(),
    getItem: k => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: k => void map.delete(k),
    key: i => Array.from(map.keys())[i] ?? null,
  }
  Object.defineProperty(window, 'localStorage', { value: storage, configurable: true })
  Object.defineProperty(globalThis, 'localStorage', { value: storage, configurable: true })
}

beforeEach(() => {
  installMemoryLocalStorage()
  root.removeAttribute('data-theme')
})
afterEach(async () => {
  setMode('auto')
  setLanguage('auto')
  await i18n.changeLanguage('en')
})

describe('the store', () => {
  // The footer's keys: a pick on the landing page holds on the overview, and the reverse.
  it('mode writes the marketing footer’s key and the <html> attribute', () => {
    setMode('dark')
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(getAppearance().mode).toBe('dark')
    setMode('auto')
    expect(root.hasAttribute('data-theme')).toBe(false)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('a language switches at once; Automatic forgets the pick', async () => {
    setLanguage('de')
    expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('de')
    expect(i18n.language).toBe('de')
    expect(getAppearance().language).toBe('de')
    setLanguage('auto')
    expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBeNull()
    expect(getAppearance().language).toBe('auto')
  })
})

describe('the controls', () => {
  it('show the current choice and change it', async () => {
    render(<AppearanceControls />)
    expect(screen.getByRole('radio', { name: 'Auto' })).toBeChecked()
    await userEvent.click(screen.getByRole('radio', { name: 'Dark' }))
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeChecked()
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Language' }), 'de')
    // The page itself re-renders in the new language.
    expect(await screen.findByRole('combobox', { name: 'Sprache' })).toHaveValue('de')
  })
})

describe('blocked storage', () => {
  it('still switches, and the store says what is drawn', () => {
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('blocked')
      },
    })
    setMode('dark')
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(getAppearance().mode).toBe('dark')
  })
})
