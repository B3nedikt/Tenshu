import { describe, expect, it } from 'vitest'
import { asOfText, byteSizeText, deciCText } from './format'

// The same outputs as native's `formatByteSize` / `formatDeciC` / `formatAsOfTime` (`HomeModel.kt`).
describe('format', () => {
  it.each([
    [-5, '0 B'],
    [999, '999 B'],
    [1_000, '1 KB'],
    [1_550_000_000, '1.5 GB'],
    [12_400_000_000, '12 GB'],
    [2_000_000_000_000, '2 TB'],
  ])('byteSizeText(%i) = %s', (bytes, text) => expect(byteSizeText(bytes)).toBe(text))

  it.each([
    [215, '21.5°'],
    [210, '21°'],
    [-35, '-3.5°'],
    [0, '0°'],
  ])('deciCText(%i) = %s', (deciC, text) => expect(deciCText(deciC)).toBe(text))

  it('asOfText is the time on the same day and the date + time otherwise', () => {
    const at = new Date(2026, 6, 23, 14, 5).getTime()
    expect(asOfText(at, new Date(2026, 6, 23, 23, 59).getTime())).toBe('14:05')
    expect(asOfText(at, new Date(2026, 6, 24, 0, 1).getTime())).toBe('2026-07-23 14:05')
  })
})
