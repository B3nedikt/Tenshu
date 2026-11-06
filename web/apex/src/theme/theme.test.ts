// The Light / Dark / Auto switch in a (jsdom) browser: the SPA's helpers and ThemeSwitch, and the
// static support pages' inline scripts run against their own markup — same key, same attribute,
// so a choice made on one surface holds on the other.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { fireEvent, render, screen } from '@testing-library/react'
import { THEME_BOOT_SCRIPT, THEME_STORAGE_KEY } from '@tenshu/web-tokens/theme'
import { THEME_SWITCH_SCRIPT } from '../../support-pages/themeSwitch.ts'
import { StaticThemeSwitch, ThemeSwitch } from './ThemeSwitch'

/** The support pages' switch markup: the footer's StaticThemeSwitch, rendered as the build does. */
const staticSwitchHtml = () => renderToStaticMarkup(createElement(StaticThemeSwitch))
import { applyThemeChoice, readThemeChoice } from './theme'

const root = document.documentElement

// Under Node ≥ 25 the runtime's own experimental `localStorage` global shadows jsdom's and is
// undefined without --localstorage-file (ConstructionGate.test.tsx hit the same). Install an
// in-memory Storage on both names: theme.ts reads `window.localStorage`, the inline scripts the bare
// global.
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

beforeEach(() => {
  installMemoryLocalStorage()
  localStorage.clear()
  root.removeAttribute('data-theme')
  document.body.innerHTML = ''
})
afterEach(() => {
  localStorage.clear()
  root.removeAttribute('data-theme')
})

const run = (script: string) => new Function(script)()

describe('boot script (head, before first paint)', () => {
  it('applies a stored Light or Dark, leaves Auto to the media query', () => {
    run(THEME_BOOT_SCRIPT)
    expect(root.hasAttribute('data-theme')).toBe(false)
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    run(THEME_BOOT_SCRIPT)
    expect(root.getAttribute('data-theme')).toBe('dark')
  })

  it('ignores an unknown value and survives storage that throws', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia')
    run(THEME_BOOT_SCRIPT)
    expect(root.hasAttribute('data-theme')).toBe(false)
    vi.spyOn(localStorage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    expect(() => run(THEME_BOOT_SCRIPT)).not.toThrow()
    expect(root.hasAttribute('data-theme')).toBe(false)
  })
})

describe('support-page switch (static markup + inline script)', () => {
  const mount = () => {
    document.body.innerHTML = staticSwitchHtml()
    run(THEME_BOOT_SCRIPT)
    run(THEME_SWITCH_SCRIPT)
    return document.getElementById('theme-switch') as HTMLFieldSetElement
  }
  const choose = (value: string) => {
    const input = document.querySelector<HTMLInputElement>(`input[name="theme"][value="${value}"]`)!
    input.checked = true
    input.dispatchEvent(new Event('change', { bubbles: true }))
  }
  const checked = () => document.querySelector<HTMLInputElement>('input[name="theme"]:checked')!.value

  it('is hidden until the script runs (JavaScript off: no switch, the OS decides)', () => {
    document.body.innerHTML = staticSwitchHtml()
    expect((document.getElementById('theme-switch') as HTMLFieldSetElement).hidden).toBe(true)
    expect(checked()).toBe('auto')
    expect(root.hasAttribute('data-theme')).toBe(false)
  })

  it('shows Auto by default and the stored choice otherwise', () => {
    expect(mount().hidden).toBe(false)
    expect(checked()).toBe('auto')
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    mount()
    expect(checked()).toBe('light')
    expect(root.getAttribute('data-theme')).toBe('light')
  })

  it('applies and persists a change; Auto clears both', () => {
    mount()
    choose('dark')
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    choose('auto')
    expect(root.hasAttribute('data-theme')).toBe(false)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('still switches when storage throws', () => {
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    mount()
    expect(() => choose('light')).not.toThrow()
    expect(root.getAttribute('data-theme')).toBe('light')
  })
})

describe('SPA helpers and ThemeSwitch', () => {
  it('readThemeChoice: Auto unless Light or Dark is stored; Auto when storage throws', () => {
    expect(readThemeChoice()).toBe('auto')
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    expect(readThemeChoice()).toBe('dark')
    expect(
      readThemeChoice({
        getItem: () => {
          throw new Error('SecurityError')
        },
      }),
    ).toBe('auto')
  })

  it('applyThemeChoice: sets or clears the attribute and the key', () => {
    applyThemeChoice('light')
    expect(root.getAttribute('data-theme')).toBe('light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
    applyThemeChoice('auto')
    expect(root.hasAttribute('data-theme')).toBe(false)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
  })

  it('renders a named radio group with Auto checked, and a click applies the choice', () => {
    render(createElement(ThemeSwitch))
    const group = screen.getByRole('group', { name: 'Theme' })
    expect(group).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Auto' })).toBeChecked()
    fireEvent.click(screen.getByRole('radio', { name: 'Dark' }))
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeChecked()
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
  })

  it('starts on the stored choice', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    render(createElement(ThemeSwitch))
    expect(screen.getByRole('radio', { name: 'Light' })).toBeChecked()
  })
})
