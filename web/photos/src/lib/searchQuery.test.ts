import { describe, expect, it } from 'vitest'
import { parseSearch } from './searchQuery'

const local = (y: number, m: number, d = 1) => new Date(y, m, d).getTime()

describe('parseSearch', () => {
  it('is nothing for an empty query', () => {
    expect(parseSearch('   ', 'en')).toBeNull()
  })

  it('reads a year, a month and a day as LOCAL ranges, both ends inclusive', () => {
    expect(parseSearch('2025', 'en')).toEqual({ name: null, fromMs: local(2025, 0), toMs: local(2026, 0) - 1, isDate: true })
    expect(parseSearch('2025-05', 'en')).toMatchObject({ fromMs: local(2025, 4), toMs: local(2025, 5) - 1 })
    expect(parseSearch('2025-05-03', 'en')).toMatchObject({ fromMs: local(2025, 4, 3), toMs: local(2025, 4, 4) - 1 })
  })

  it('reads a month name with a year, in the UI language or English, either order', () => {
    const may = { fromMs: local(2025, 4), toMs: local(2025, 5) - 1, isDate: true }
    expect(parseSearch('May 2025', 'en')).toMatchObject(may)
    expect(parseSearch('2025 may', 'en')).toMatchObject(may)
    expect(parseSearch('Mai 2025', 'de')).toMatchObject(may)
    expect(parseSearch('mai 2025', 'fr')).toMatchObject(may)
    expect(parseSearch('März 2024', 'de')).toMatchObject({ fromMs: local(2024, 2) })
    expect(parseSearch('Sept. 2024', 'en')).toMatchObject({ fromMs: local(2024, 8) })
    expect(parseSearch('May 2025', 'de')).toMatchObject(may) // English always works
  })

  it('anything else is a name search — including impossible dates', () => {
    expect(parseSearch('beach', 'en')).toEqual({ name: 'beach', fromMs: null, toMs: null, isDate: false })
    expect(parseSearch('IMG_2025', 'en')).toMatchObject({ name: 'IMG_2025' })
    expect(parseSearch('2025-02-30', 'en')).toMatchObject({ name: '2025-02-30', isDate: false })
    expect(parseSearch('3025', 'en')).toMatchObject({ name: '3025' })
    expect(parseSearch('trip 2025', 'en')).toMatchObject({ name: 'trip 2025' })
  })
})
