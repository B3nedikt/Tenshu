// The name a browser gets in the realm's device list when onboarding creates or
// joins it. Onboarding no longer asks for one, so this default has to be
// something a person recognises — notably on the *other* device, which shows it
// in the pairing approval prompt. "Chrome on macOS" reads better there than any
// generic label.
import { browserFamilyLabel, detectBrowserFamily } from './browserDetect'

export type OsFamily = 'iphone' | 'ipad' | 'android' | 'macos' | 'windows' | 'chromeos' | 'linux' | 'other'

export function detectOsFamily(
  userAgent: string = navigator.userAgent,
  maxTouchPoints: number = typeof navigator === 'undefined' ? 0 : navigator.maxTouchPoints,
): OsFamily {
  const ua = userAgent.toLowerCase()
  if (ua.includes('iphone') || ua.includes('ipod')) return 'iphone'
  if (ua.includes('ipad')) return 'ipad'
  // iPadOS Safari reports a desktop macOS user agent; a Mac has no touch screen.
  if (ua.includes('macintosh') && maxTouchPoints > 1) return 'ipad'
  if (ua.includes('android')) return 'android'
  if (ua.includes('cros')) return 'chromeos'
  if (ua.includes('mac os x') || ua.includes('macintosh')) return 'macos'
  if (ua.includes('windows')) return 'windows'
  if (ua.includes('linux')) return 'linux'
  return 'other'
}

const OS_LABEL: Record<Exclude<OsFamily, 'other'>, string> = {
  iphone: 'iPhone',
  ipad: 'iPad',
  android: 'Android',
  macos: 'macOS',
  windows: 'Windows',
  chromeos: 'ChromeOS',
  linux: 'Linux',
}

/**
 * "Chrome on macOS", "Safari on iPhone". Browser and OS names are proper nouns
 * and stay untranslated; the joining word comes from the catalogue via
 * [format]. Falls back to [fallback] when neither the browser nor the OS is
 * recognised, and to whichever half is known when only one is.
 */
export function defaultDeviceName({
  format,
  fallback,
  userAgent = navigator.userAgent,
  maxTouchPoints,
}: {
  readonly format: (browser: string, os: string) => string
  readonly fallback: string
  readonly userAgent?: string
  readonly maxTouchPoints?: number
}): string {
  const browser = detectBrowserFamily(userAgent)
  const os = detectOsFamily(userAgent, maxTouchPoints)
  const browserLabel = browser === 'other' ? null : browserFamilyLabel(browser)
  const osLabel = os === 'other' ? null : OS_LABEL[os]
  if (browserLabel !== null && osLabel !== null) return format(browserLabel, osLabel)
  return browserLabel ?? osLabel ?? fallback
}
