import { describe, expect, it } from 'vitest'
import { formatStorageBytes } from './format'

// The same cases as the native `StorageBytesFormatTest` (`:storage-service-common`).
const GIB = 1024 * 1024 * 1024

describe('formatStorageBytes', () => {
  it('reads the Free plan (5 GiB) as "5 GB", not "5.37 GB"', () => {
    expect(formatStorageBytes(5 * GIB)).toBe('5 GB')
  })

  it('reads every tier as its name', () => {
    expect(formatStorageBytes(GIB)).toBe('1 GB')
    expect(formatStorageBytes(50 * GIB)).toBe('50 GB')
    expect(formatStorageBytes(2 * 1024 * GIB)).toBe('2 TB')
  })

  it('handles zero and negatives', () => {
    expect(formatStorageBytes(0)).toBe('0 B')
    expect(formatStorageBytes(-5)).toBe('0 B')
    expect(formatStorageBytes(1023)).toBe('1023 B')
  })

  it('puts usage and limit on one base', () => {
    const used = Math.trunc(8.3 * 1024 * 1024)
    expect(`${formatStorageBytes(used)} of ${formatStorageBytes(5 * GIB)} used`).toBe(
      '8.3 MB of 5 GB used',
    )
    expect(
      `${formatStorageBytes(5 * GIB - GIB / 10 - 1)} of ${formatStorageBytes(5 * GIB)} used`,
    ).toBe('4.9 GB of 5 GB used')
  })

  it('steps up at the displayed boundary', () => {
    expect(formatStorageBytes(1024)).toBe('1 KB')
    expect(formatStorageBytes(1.5 * 1024 * 1024)).toBe('1.5 MB')
    expect(formatStorageBytes(1024 * 1024 - 40)).toBe('1 MB')
  })
})
