// glanceHref (web-apex-home.md §4.10): a card's click target is a place inside its OWN app.
import { describe, expect, it } from 'vitest'
import { findHostedApp } from './registry'
import { glanceHref } from './appNav'

const drive = findHostedApp('drive')!
const photos = findHostedApp('photos')!

describe('glanceHref', () => {
  it('builds the app path from its registered section, the fragment as the hash', () => {
    expect(glanceHref(drive, { section: 'files', fragment: 'view:/Docs/a b.pdf' })).toBe('/drive/files#view:/Docs/a%20b.pdf')
    expect(glanceHref(photos, { section: '', fragment: 'open=3' })).toBe('/photos#open=3')
    expect(glanceHref(drive, { section: '', fragment: '' })).toBe('/drive')
  })

  it('draws no link for a section the app did not register, or a path posing as one', () => {
    expect(glanceHref(drive, { section: 'admin', fragment: '' })).toBeNull()
    expect(glanceHref(drive, { section: '../photos', fragment: '' })).toBeNull()
    expect(glanceHref(drive, { section: 'files/x', fragment: '' })).toBeNull()
  })

  it('draws no link for a fragment it cannot carry', () => {
    expect(glanceHref(drive, { section: 'files', fragment: 'view:/bad\uD800' })).toBeNull()
    expect(glanceHref(drive, { section: 'files', fragment: 'x'.repeat(9000) })).toBeNull()
  })
})
