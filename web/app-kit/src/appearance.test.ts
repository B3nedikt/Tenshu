// A hosted app follows the apex's appearance (docs/proposals/web-apex-home.md §4.15).
import { afterEach, describe, expect, it } from 'vitest'
import {
  APPEARANCE_BOOT_SCRIPT,
  APPEARANCE_MESSAGE,
  FRAME_NAME_PREFIX,
  appearanceFromFrameName,
  applyAppearance,
  installAppearance,
  readAppearance,
  subscribeAppearance,
} from './appearance'

const APEX = 'https://tenshu.example'
const root = document.documentElement

function reset() {
  root.removeAttribute('data-theme')
  root.removeAttribute('data-tint')
  root.style.removeProperty('--tint-h')
  root.style.removeProperty('--tint-c')
  window.name = ''
}
afterEach(reset)

const fromApex = (data: unknown, origin = APEX, source: unknown = window.parent) =>
  window.dispatchEvent(new MessageEvent('message', { data, origin, source: source as Window }))

describe('reading', () => {
  it('accepts a well-formed appearance and drops a malformed tint or language', () => {
    expect(readAppearance({ mode: 'dark', language: 'de', tint: 'h195c100' })).toEqual({
      mode: 'dark',
      language: 'de',
      tint: 'h195c100',
    })
    expect(readAppearance({ mode: 'light', language: '<script>', tint: 'red' })).toEqual({
      mode: 'light',
      language: '',
      tint: null,
    })
    expect(readAppearance({ mode: 'sepia' })).toBeNull()
    expect(readAppearance(null)).toBeNull()
  })

  it('reads the frame name, and nothing from any other name', () => {
    expect(appearanceFromFrameName(`${FRAME_NAME_PREFIX}{"mode":"dark","language":"fr","tint":null}`)?.mode).toBe('dark')
    expect(appearanceFromFrameName('some-other-frame')).toBeNull()
    expect(appearanceFromFrameName(`${FRAME_NAME_PREFIX}{broken`)).toBeNull()
  })
})

describe('applying', () => {
  it('sets mode and tint, and auto / no tint removes them', () => {
    applyAppearance({ mode: 'dark', language: 'en', tint: 'h70c120' })
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(root.hasAttribute('data-tint')).toBe(true)
    expect(root.style.getPropertyValue('--tint-h')).toBe('70')
    expect(root.style.getPropertyValue('--tint-c')).toBe('0.12')
    applyAppearance({ mode: 'auto', language: 'en', tint: null })
    expect(root.hasAttribute('data-theme')).toBe(false)
    expect(root.hasAttribute('data-tint')).toBe(false)
  })

  // The <head> script: before any of the app's own code, from the frame name alone.
  it('boot script paints the frame name’s mode, tint and language', () => {
    window.name = `${FRAME_NAME_PREFIX}{"mode":"dark","language":"de","tint":"h195c100"}`
    new Function(APPEARANCE_BOOT_SCRIPT)()
    expect(root.getAttribute('data-theme')).toBe('dark')
    expect(root.style.getPropertyValue('--tint-h')).toBe('195')
    expect(root.lang).toBe('de')
  })

  it('boot script leaves a page opened on its own alone', () => {
    window.name = ''
    new Function(APPEARANCE_BOOT_SCRIPT)()
    expect(root.hasAttribute('data-theme')).toBe(false)
    expect(root.hasAttribute('data-tint')).toBe(false)
  })
})

describe('following the apex', () => {
  it('applies and forwards the apex’s messages, and only the apex’s', () => {
    const heard: string[] = []
    const stop = subscribeAppearance(a => heard.push(a.language))
    const uninstall = installAppearance(APEX)
    try {
      fromApex({ type: APPEARANCE_MESSAGE, mode: 'dark', language: 'fr', tint: 'h8c120' })
      expect(root.getAttribute('data-theme')).toBe('dark')
      expect(heard).toEqual(['fr'])
      // Another origin, another sender (jsdom's parent is the window itself, so a port stands in),
      // another message type: ignored.
      fromApex({ type: APPEARANCE_MESSAGE, mode: 'light', language: 'de', tint: null }, 'https://evil.example')
      fromApex({ type: APPEARANCE_MESSAGE, mode: 'light', language: 'de', tint: null }, APEX, new MessageChannel().port1)
      fromApex({ type: 'tenshu.route', mode: 'light' })
      expect(root.getAttribute('data-theme')).toBe('dark')
      expect(heard).toEqual(['fr'])
    } finally {
      uninstall()
      stop()
    }
  })
})
