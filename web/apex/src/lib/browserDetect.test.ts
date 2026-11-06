import { describe, expect, it } from 'vitest'
import { browserFamilyLabel, detectBrowserFamily } from './browserDetect'

describe('detectBrowserFamily', () => {
  // Real (or representative) UA strings from the major 2026-era browsers.
  // Detection is best-effort per the §4a.6 chooser fallback — wrong calls
  // are recoverable via the manual chooser, not via this function.
  const cases: ReadonlyArray<{ family: ReturnType<typeof detectBrowserFamily>; ua: string }> = [
    {
      family: 'chrome',
      ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    },
    {
      family: 'edge',
      ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0',
    },
    {
      family: 'firefox',
      ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:121.0) Gecko/20100101 Firefox/121.0',
    },
    {
      family: 'safari',
      ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15',
    },
    {
      family: 'safari',
      ua: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1',
    },
    {
      family: 'other',
      ua: 'Mozilla/5.0 (X11; Linux x86_64) curl/8.0.0',
    },
    {
      family: 'other',
      ua: '',
    },
  ]

  it.each(cases)('classifies $family from "$ua"', ({ family, ua }) => {
    expect(detectBrowserFamily(ua)).toBe(family)
  })

  it('Chrome on macOS is not misclassified as Safari', () => {
    // Both UAs include `Safari/`; the order of checks in the implementation
    // is what makes Chrome-detection win.
    const ua = 'Mozilla/5.0 (Macintosh) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'
    expect(detectBrowserFamily(ua)).toBe('chrome')
  })
})

describe('browserFamilyLabel', () => {
  it('produces a human label for every family', () => {
    expect(browserFamilyLabel('chrome')).toBe('Chrome')
    expect(browserFamilyLabel('edge')).toBe('Edge')
    expect(browserFamilyLabel('firefox')).toBe('Firefox')
    expect(browserFamilyLabel('safari')).toBe('Safari')
    expect(browserFamilyLabel('other')).toBe('Other')
  })
})
