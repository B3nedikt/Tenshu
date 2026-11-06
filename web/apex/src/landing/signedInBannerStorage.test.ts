// Confirms the dismiss flag round-trips through sessionStorage so a
// returning-active visitor sees the banner on first scroll, not after
// dismissing it on the same tab. (web-apex-landing.md §4 — banner stays
// dismissed for the tab session via sessionStorage.)
import { afterEach, describe, expect, it } from 'vitest'
import { dismissBanner, readBannerDismissed } from './signedInBannerStorage'

afterEach(() => {
  window.sessionStorage.clear()
})

describe('signedInBannerStorage', () => {
  it('reports not-dismissed when no flag is set', () => {
    expect(readBannerDismissed()).toBe(false)
  })

  it('reports dismissed after dismissBanner() is called', () => {
    dismissBanner()
    expect(readBannerDismissed()).toBe(true)
  })

  it('persists the flag across reads (tab-session lifetime)', () => {
    dismissBanner()
    expect(readBannerDismissed()).toBe(true)
    expect(readBannerDismissed()).toBe(true)
  })
})
