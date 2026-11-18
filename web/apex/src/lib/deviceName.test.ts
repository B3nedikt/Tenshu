// The default device name has to be one a person recognises on the *other*
// device, which shows it in the pairing approval prompt.
import { describe, expect, it } from 'vitest'
import { defaultDeviceName, detectOsFamily } from './deviceName'

const format = (browser: string, os: string) => `${browser} on ${os}`
const name = (userAgent: string, maxTouchPoints = 0) =>
  defaultDeviceName({ format, fallback: 'This Browser', userAgent, maxTouchPoints })

describe('defaultDeviceName', () => {
  it('names the common desktop browsers', () => {
    expect(
      name('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'),
    ).toBe('Chrome on macOS')
    expect(
      name('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15'),
    ).toBe('Safari on macOS')
    expect(
      name('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0'),
    ).toBe('Edge on Windows')
    expect(name('Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0')).toBe(
      'Firefox on Linux',
    )
  })

  it('names phones by device, not by the Linux or macOS underneath', () => {
    expect(
      name('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'),
    ).toBe('Chrome on Android')
    expect(
      name('Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'),
    ).toBe('Safari on iPhone')
  })

  // ⚠ iPadOS Safari sends a desktop macOS user agent; only touch support tells them apart.
  it('recognises an iPad behind its desktop user agent', () => {
    const ua =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15'
    expect(detectOsFamily(ua, 5)).toBe('ipad')
    expect(name(ua, 5)).toBe('Safari on iPad')
  })

  it('uses whichever half it knows, then the fallback', () => {
    // Chrome on iOS identifies as CriOS, which is not the Chrome desktop token.
    expect(
      name('Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0 Mobile/15E148 Safari/604.1'),
    ).toBe('iPhone')
    expect(name('SomethingElse/1.0')).toBe('This Browser')
  })
})
