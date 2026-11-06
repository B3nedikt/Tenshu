import { describe, expect, it } from 'vitest'
import { RegionErrorKind } from '@tenshu/map-kit'
import en from '../../locales/en/maps.json'
import i18n from '../i18n'
import { failureMessage, KNOWN_ERROR_KINDS, MAP_ERROR_KINDS, SOURCE_SILENT } from './errors'

const t = i18n.getFixedT('en')

describe('error copy', () => {
  it('the map kinds spelled here are the map-kit\'s', () => {
    expect([...MAP_ERROR_KINDS].sort()).toEqual(Object.values(RegionErrorKind).sort())
  })

  it('every known kind has copy', () => {
    for (const kind of KNOWN_ERROR_KINDS) expect(Object.keys(en.errors)).toContain(kind)
    expect(SOURCE_SILENT).toBe('error_timeline_source_silent')
  })

  it('renders by kind, never by message, and an unknown kind is the generic sentence', () => {
    expect(failureMessage(t, { kind: 'error_timeline_blank_name', message: 'boom' })).toBe('Enter a name.')
    expect(failureMessage(t, { kind: 'error_from_a_newer_peer', message: 'boom' })).toBe(en.errors.error_generic)
  })
})
