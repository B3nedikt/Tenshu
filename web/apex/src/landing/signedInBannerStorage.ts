// SessionStorage-backed dismissal flag for the "you're signed in"
// banner (web-apex-landing.md §4 + §9 a11y note). Extracted from the
// component so the storage contract is unit-testable; the component
// uses these helpers directly and doesn't keep its own ad-hoc keys.
const DISMISS_KEY = 'tenshu-landing:signed-in-banner-dismissed'

export function readBannerDismissed(): boolean {
  if (typeof window === 'undefined') return false
  return window.sessionStorage.getItem(DISMISS_KEY) === '1'
}

export function dismissBanner(): void {
  if (typeof window === 'undefined') return
  window.sessionStorage.setItem(DISMISS_KEY, '1')
}
