// The background colour (docs/proposals/web-apex-home.md §4.14): the shared tint helpers, the boot
// script, and the picker over a fake stored row.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  customTintToken,
  parseTint,
  presetOf,
  tintFromHex,
  tintToHex,
  TINT_BOOT_SCRIPT,
  TINT_PRESETS,
  TINT_STORAGE_KEY,
} from '@tenshu/web-tokens/tint'
import { BackgroundProvider } from '../overview/ShownAppsProvider'
import { fakeShownApps, type FakeShownApps } from '../test/fakeShownApps'
import { applyTint } from './background'
import { BackgroundPicker } from './BackgroundPicker'
import { TintSync } from './TintSync'

const root = document.documentElement

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
  applyTint(null)
})
afterEach(() => {
  vi.useRealTimers()
  applyTint(null)
})

describe('tokens', () => {
  it('parses presets, custom tokens and garbage', () => {
    expect(parseTint('teal')).toEqual(TINT_PRESETS.teal)
    // Nothing stored, or a token this client does not know, is the default: blue.
    expect(parseTint('')).toEqual(TINT_PRESETS.blue)
    expect(parseTint('classic')).toBeNull()
    expect(parseTint('h212c094')).toEqual({ hue: 212, chroma: 0.094 })
    // Chroma is capped: a stored value can never push the accent out of gamut.
    expect(parseTint('h10c900')).toEqual({ hue: 10, chroma: 0.16 })
    expect(parseTint('#ff0000')).toEqual(TINT_PRESETS.blue)
    expect(parseTint(undefined)).toEqual(TINT_PRESETS.blue)
  })

  it('round-trips a custom tint through its token', () => {
    expect(customTintToken({ hue: 212.4, chroma: 0.0937 })).toBe('h212c094')
    expect(presetOf('')).toBe('blue')
    expect(presetOf('rose')).toBe('rose')
    expect(presetOf('h212c094')).toBeNull()
  })

  it('keeps a picked colour’s hue, whatever its lightness', () => {
    const dark = tintFromHex('#0b3d91')!
    const light = tintFromHex('#9ec1ff')!
    expect(Math.abs(dark.hue - light.hue)).toBeLessThan(12)
    const back = tintFromHex(tintToHex(dark))!
    expect(Math.abs(back.hue - dark.hue)).toBeLessThan(2)
    // A grey has (almost) no chroma: the tint is graphite-like, never a guessed hue.
    expect(tintFromHex('#808080')!.chroma).toBeLessThan(0.001)
  })
})

describe('boot script', () => {
  const run = () => new Function(TINT_BOOT_SCRIPT)()

  it('paints the mirrored tint before the runtime exists, and nothing without one', () => {
    root.removeAttribute('data-tint')
    run()
    expect(root.hasAttribute('data-tint')).toBe(false)
    localStorage.setItem(TINT_STORAGE_KEY, 'h195c100')
    run()
    expect(root.hasAttribute('data-tint')).toBe(true)
    expect(root.style.getPropertyValue('--tint-h')).toBe('195')
    expect(root.style.getPropertyValue('--tint-c')).toBe('0.1')
  })

  it('ignores a mangled mirror', () => {
    root.removeAttribute('data-tint')
    localStorage.setItem(TINT_STORAGE_KEY, 'teal')
    run()
    expect(root.hasAttribute('data-tint')).toBe(false)
  })
})

describe('picker', () => {
  function renderPicker(source: FakeShownApps) {
    return render(
      <BackgroundProvider source={source}>
        <TintSync />
        <BackgroundPicker />
      </BackgroundProvider>,
    )
  }

  it('a stored colour is drawn, checked and mirrored', async () => {
    renderPicker(fakeShownApps(['teal']))
    expect(await screen.findByRole('radio', { name: 'Teal' })).toBeChecked()
    await waitFor(() => expect(root.style.getPropertyValue('--tint-h')).toBe('195'))
    expect(localStorage.getItem(TINT_STORAGE_KEY)).toBe('h195c100')
  })

  // The default: a realm that never picked draws blue, and its mirror paints blue on the next load.
  it('nothing stored is blue, checked, drawn and mirrored', async () => {
    renderPicker(fakeShownApps([]))
    expect(await screen.findByRole('radio', { name: 'Blue' })).toBeChecked()
    await waitFor(() => expect(root.style.getPropertyValue('--tint-h')).toBe('252'))
    expect(localStorage.getItem(TINT_STORAGE_KEY)).toBe('h252c120')
  })

  it('a preset writes its name; Blue writes nothing; Classic clears the tint', async () => {
    const source = fakeShownApps([])
    renderPicker(source)
    await userEvent.click(await screen.findByRole('radio', { name: 'Rose' }))
    expect(source.writes.at(-1)).toEqual(['rose'])
    await userEvent.click(screen.getByRole('radio', { name: 'Blue' }))
    expect(source.writes.at(-1)).toEqual([])
    await userEvent.click(screen.getByRole('radio', { name: 'Classic' }))
    expect(source.writes.at(-1)).toEqual(['classic'])
    await waitFor(() => expect(root.hasAttribute('data-tint')).toBe(false))
    expect(localStorage.getItem(TINT_STORAGE_KEY)).toBeNull()
  })

  // One stored row per pick, not one per pointer move.
  it('a custom colour previews at once and writes once it settles', async () => {
    const source = fakeShownApps([])
    renderPicker(source)
    const input = await screen.findByLabelText('Custom colour')
    vi.useFakeTimers()
    fireEvent.change(input, { target: { value: '#2e8b57' } })
    fireEvent.change(input, { target: { value: '#1e90ff' } })
    expect(root.hasAttribute('data-tint')).toBe(true)
    expect(source.writes).toEqual([])
    await act(async () => {
      vi.advanceTimersByTime(500)
    })
    expect(source.writes).toHaveLength(1)
    expect(source.writes[0]![0]).toMatch(/^h\d+c\d{3}$/)
  })

  it('a failed write says so and puts the stored colour back', async () => {
    const source = fakeShownApps(['teal'])
    renderPicker(source)
    await screen.findByRole('radio', { name: 'Teal' })
    source.failWrites(true)
    await userEvent.click(screen.getByRole('radio', { name: 'Amber' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't save that change")
    expect(root.style.getPropertyValue('--tint-h')).toBe('195')
  })

  it('offers nothing without a readable row', () => {
    const { container } = render(<BackgroundPicker />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('review fixes', () => {
  function renderPicker(source: FakeShownApps) {
    return render(
      <BackgroundProvider source={source}>
        <TintSync />
        <BackgroundPicker />
      </BackgroundProvider>,
    )
  }

  it('an inherited key is not a preset', () => {
    expect(parseTint('constructor')).toEqual(TINT_PRESETS.blue)
    expect(presetOf('toString')).toBeNull()
  })

  // Done within the settle time: the preview must be saved, not left painted over the old colour.
  it('leaving with a custom pick waiting writes it', async () => {
    const source = fakeShownApps([])
    const view = renderPicker(source)
    const input = await screen.findByLabelText('Custom colour')
    fireEvent.change(input, { target: { value: '#1e90ff' } })
    view.unmount()
    await waitFor(() => expect(source.writes).toHaveLength(1))
    expect(source.writes[0]![0]).toMatch(/^h\d+c\d{3}$/)
  })

  it('a preset clicked while a custom pick waits wins', async () => {
    const source = fakeShownApps([])
    renderPicker(source)
    const input = await screen.findByLabelText('Custom colour')
    vi.useFakeTimers()
    fireEvent.change(input, { target: { value: '#1e90ff' } })
    fireEvent.click(screen.getByRole('radio', { name: 'Teal' }))
    // Several: a queued write waits out the in-flight one (and its re-emit) before it would fire.
    for (let i = 0; i < 3; i++) {
      await act(async () => {
        vi.advanceTimersByTime(1000)
      })
    }
    expect(source.writes).toEqual([['teal']])
  })

  it('leaving the session takes the colour and its mirror away', async () => {
    const view = renderPicker(fakeShownApps(['teal']))
    await screen.findByRole('radio', { name: 'Teal' })
    expect(root.hasAttribute('data-tint')).toBe(true)
    view.unmount()
    expect(root.hasAttribute('data-tint')).toBe(false)
    expect(localStorage.getItem(TINT_STORAGE_KEY)).toBeNull()
  })

  it('a write that throws fails cleanly and does not wedge later edits', async () => {
    const source = fakeShownApps(['teal'])
    const throwing = { ...source, write: () => Promise.reject(new Error('boom')) }
    render(
      <BackgroundProvider source={throwing}>
        <BackgroundPicker />
      </BackgroundProvider>,
    )
    await userEvent.click(await screen.findByRole('radio', { name: 'Amber' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't save that change")
    expect(screen.getByRole('radio', { name: 'Rose' })).not.toHaveAttribute('aria-disabled')
  })
})
