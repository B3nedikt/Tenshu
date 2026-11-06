// `formatDeviceLabel` — the web twin of `deviceDisplayLabel` in
// `:identity-service` (DeviceDisplayLabelTest pins the same cases).
import { describe, expect, it } from 'vitest'
import { formatDeviceLabel } from './format'

describe('formatDeviceLabel', () => {
  it('follows the name with a known, distinct hardware model', () => {
    expect(formatDeviceLabel('Work Laptop', 'Mac OS X (aarch64)')).toBe('Work Laptop · Mac OS X (aarch64)')
  })

  it('leaves the name alone when the model is unknown', () => {
    expect(formatDeviceLabel('Work Laptop', undefined)).toBe('Work Laptop')
    expect(formatDeviceLabel('Work Laptop', '  ')).toBe('Work Laptop')
  })

  it('does not repeat a model equal to the name', () => {
    expect(formatDeviceLabel('Google Pixel 8', 'google pixel 8')).toBe('Google Pixel 8')
  })

  it('falls back to the model when the name is blank', () => {
    expect(formatDeviceLabel(' ', 'iPhone')).toBe('iPhone')
  })
})
