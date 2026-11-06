// The list map's anchor: which place picks the region the map draws.
import { describe, expect, it } from 'vitest'
import { placeRow } from '../test/proxyMock'
import { chooseAnchor } from './anchor'

const BERLIN = placeRow({ placeId: 'berlin', name: 'Berlin', latitude: 52.52, longitude: 13.405 })
const POTSDAM = placeRow({ placeId: 'potsdam', name: 'Potsdam', latitude: 52.39, longitude: 13.065 })
const SPANDAU = placeRow({ placeId: 'spandau', name: 'Spandau', latitude: 52.535, longitude: 13.2 })
const NEW_YORK = placeRow({ placeId: 'ny', name: 'New York', latitude: 40.713, longitude: -74.006 })
const MUNICH = placeRow({ placeId: 'munich', name: 'Munich', latitude: 48.137, longitude: 11.575 })
const HAMBURG = placeRow({ placeId: 'hamburg', name: 'Hamburg', latitude: 53.551, longitude: 9.994 })
const FIJI_EAST = placeRow({ placeId: 'fe', name: 'Taveuni', latitude: -16.8, longitude: 179.9 })
const FIJI_WEST = placeRow({ placeId: 'fw', name: 'Lau', latitude: -16.9, longitude: -179.9 })
const SAMOA = placeRow({ placeId: 'ws', name: 'Apia', latitude: -13.83, longitude: -171.76 })

describe('chooseAnchor', () => {
  it('nothing shown: no anchor', () => {
    expect(chooseAnchor([])).toEqual({ anchor: null, needsRecency: false })
  })

  it('the confirmed home wins, wherever the crowd is', () => {
    const home = { ...NEW_YORK, role: 'home' as const }
    expect(chooseAnchor([BERLIN, POTSDAM, SPANDAU, home]).anchor).toBe(home)
  })

  it('Berlin + New York: the denser side — a real place on land, never the Atlantic midpoint', () => {
    expect(chooseAnchor([NEW_YORK, BERLIN, POTSDAM, SPANDAU])).toEqual({ anchor: BERLIN, needsRecency: false })
  })

  it('Berlin + New York, one place each: a tie, broken by the most recent visit', () => {
    const tie = chooseAnchor([BERLIN, NEW_YORK])
    expect(tie.needsRecency).toBe(true)
    expect(tie.anchor).toBe(BERLIN) // first in list order until the recency arrives
    const recency = new Map([
      ['berlin', 1_000],
      ['ny', 2_000],
    ])
    expect(chooseAnchor([BERLIN, NEW_YORK], recency)).toEqual({ anchor: NEW_YORK, needsRecency: false })
  })

  it('Munich + Hamburg: one of them, never the point between (central Germany)', () => {
    const recency = new Map<string, number | null>([
      ['munich', 5_000],
      ['hamburg', null],
    ])
    expect(chooseAnchor([HAMBURG, MUNICH], recency).anchor).toBe(MUNICH)
    // No visits known at all: the first tied cluster in list order, and no further read.
    expect(chooseAnchor([HAMBURG, MUNICH], new Map())).toEqual({ anchor: HAMBURG, needsRecency: false })
  })

  it('±179°: the two sides of the antimeridian are one cluster', () => {
    expect(chooseAnchor([SAMOA, FIJI_EAST, FIJI_WEST])).toEqual({ anchor: FIJI_EAST, needsRecency: false })
  })
})
