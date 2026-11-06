// Browser-family detection for UX §4a.6 (per-browser sync opt-out
// instructions). Only used to pick which copy block to show; the apex
// always renders a chooser when detection is ambiguous so an incorrect
// guess never traps the user.
export type BrowserFamily = 'chrome' | 'edge' | 'firefox' | 'safari' | 'other'

export function detectBrowserFamily(userAgent: string = navigator.userAgent): BrowserFamily {
  const ua = userAgent.toLowerCase()
  // Edge is identified by `edg/` (post-Chromium); the legacy `edge/` token
  // is irrelevant in 2026 but matched for completeness.
  if (ua.includes('edg/') || ua.includes('edge/')) return 'edge'
  if (ua.includes('firefox/')) return 'firefox'
  if (ua.includes('chrome/') && !ua.includes('chromium/')) return 'chrome'
  // Safari's UA contains `safari/` but so does Chrome on macOS — order
  // matters: Chrome's check above eliminates that overlap.
  if (ua.includes('safari/') && ua.includes('version/')) return 'safari'
  return 'other'
}

export function browserFamilyLabel(family: BrowserFamily): string {
  switch (family) {
    case 'chrome':
      return 'Chrome'
    case 'edge':
      return 'Edge'
    case 'firefox':
      return 'Firefox'
    case 'safari':
      return 'Safari'
    case 'other':
      return 'Other'
  }
}
