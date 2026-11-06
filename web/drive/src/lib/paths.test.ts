import { describe, expect, it } from 'vitest'
import {
  basename,
  breadcrumbs,
  formatBytes,
  formatDate,
  formatDateTime,
  joinPath,
  parentDir,
} from './paths'

describe('basename', () => {
  it('returns Drive for the root', () => {
    expect(basename('/')).toBe('Drive')
    expect(basename('')).toBe('Drive')
  })

  it('returns the last segment', () => {
    expect(basename('/Photos')).toBe('Photos')
    expect(basename('/Photos/2024/a.jpg')).toBe('a.jpg')
  })

  it('ignores a trailing slash', () => {
    expect(basename('/Photos/')).toBe('Photos')
  })
})

describe('breadcrumbs', () => {
  it('is just Drive at the root', () => {
    expect(breadcrumbs('/')).toEqual([{ label: 'Drive', path: '/' }])
  })

  it('accumulates a path to absolute crumbs', () => {
    expect(breadcrumbs('/Photos/2024')).toEqual([
      { label: 'Drive', path: '/' },
      { label: 'Photos', path: '/Photos' },
      { label: '2024', path: '/Photos/2024' },
    ])
  })
})

describe('formatBytes', () => {
  it('handles zero / negatives', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(-5)).toBe('0 B')
  })

  it('scales by binary units', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1024)).toBe('1 KB')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5 MB')
  })

  // Settings' "… of … used" line formats both figures through this. The plans
  // are binary (Free = 5 GiB), so a decimal step would read "5.37 GB".
  it('reads plan sizes as their names, on the same base as usage', () => {
    const GIB = 1024 * 1024 * 1024
    expect(formatBytes(5 * GIB)).toBe('5 GB')
    expect(formatBytes(GIB)).toBe('1 GB')
    expect(formatBytes(2 * 1024 * GIB)).toBe('2 TB')
    const used = Math.trunc(8.3 * 1024 * 1024)
    expect(`${formatBytes(used)} of ${formatBytes(5 * GIB)} used`).toBe('8.3 MB of 5 GB used')
  })
})

describe('formatDate', () => {
  it('is empty for the 0 sentinel', () => {
    expect(formatDate(0)).toBe('')
  })

  it('formats a real epoch', () => {
    // Just assert it produced a non-empty localised string (locale-agnostic).
    expect(formatDate(Date.UTC(2024, 0, 15, 12)).length).toBeGreaterThan(0)
  })
})

describe('joinPath', () => {
  it('joins under the root with a single slash', () => {
    expect(joinPath('/', 'Photos')).toBe('/Photos')
  })

  it('joins under a nested folder', () => {
    expect(joinPath('/Photos', '2024')).toBe('/Photos/2024')
  })

  it('ignores a trailing slash on the parent', () => {
    expect(joinPath('/Photos/', '2024')).toBe('/Photos/2024')
  })
})

describe('parentDir', () => {
  it('returns root for a root-level entry', () => {
    expect(parentDir('/a.txt')).toBe('/')
    expect(parentDir('/Photos')).toBe('/')
  })

  it('returns the containing folder for a nested entry', () => {
    expect(parentDir('/Photos/2024/a.jpg')).toBe('/Photos/2024')
  })

  it('ignores a trailing slash', () => {
    expect(parentDir('/Photos/2024/')).toBe('/Photos')
  })
})

describe('formatDateTime', () => {
  it('is empty for the 0 sentinel', () => {
    expect(formatDateTime(0)).toBe('')
  })

  it('produces a longer string than the date-only form (includes time)', () => {
    const epoch = Date.UTC(2024, 0, 15, 13, 45)
    expect(formatDateTime(epoch).length).toBeGreaterThan(formatDate(epoch).length)
  })
})
