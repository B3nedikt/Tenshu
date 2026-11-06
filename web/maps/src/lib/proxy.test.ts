import { describe, expect, it } from 'vitest'
import { MapRegionMethod } from '@tenshu/map-kit'
import { ProxyMethod } from './proxy'

describe('ProxyMethod', () => {
  it('carries exactly the mapRegion methods <TenshuMap> calls', () => {
    const table = Object.values(ProxyMethod) as string[]
    expect(table.filter((m) => m.startsWith('mapRegion.')).sort()).toEqual(
      [MapRegionMethod.RegionAt, MapRegionMethod.StreamRange].sort(),
    )
  })

  it('of Timeline reads only the visit summary and the place rows — never `timeline.place` (every visit moment)', () => {
    const table = Object.values(ProxyMethod) as string[]
    expect(table.filter((m) => m.startsWith('timeline.')).sort()).toEqual(['timeline.placeVisitSummary', 'timeline.places'])
  })
})
