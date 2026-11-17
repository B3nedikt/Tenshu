import { describe, expect, it } from 'vitest'
import { HOSTED_APPS } from '../apps/registry'
import { shownApps } from './shownApps'
import {
  addableWidgets,
  effectiveWidgets,
  withWidgetAdded,
  withWidgetMovedBy,
  withWidgetMovedTo,
  withWidgetRemoved,
} from './widgets'

const installed = (ids: string[]) => shownApps(ids, HOSTED_APPS)
const keys = (stored: readonly string[], apps: string[]) =>
  effectiveWidgets(stored, installed(apps)).map(w => w.key)

describe('the default (never edited)', () => {
  it('is Your apps, then a card per installed app, in the dock order', () => {
    expect(keys([], [])).toEqual(['apps', 'app:drive', 'app:photos'])
    expect(keys([], ['drive', 'fit', 'photos'])).toEqual(['apps', 'app:drive', 'app:fit', 'app:photos'])
  })

  it('has no card for an installed app without a glance', () => {
    expect(keys([], ['drive', 'photos', 'contacts'])).toEqual(['apps', 'app:drive', 'app:photos'])
  })
})

describe('an edited list', () => {
  it('keeps its order', () => {
    expect(keys(['app:photos', 'apps', 'app:drive'], ['drive', 'photos'])).toEqual([
      'app:photos',
      'apps',
      'app:drive',
    ])
  })

  // D-W1: installing still adds the card, at the end, after the user edited.
  it('appends the card of an app installed after the edit', () => {
    expect(keys(['app:photos', 'apps', 'app:drive'], ['drive', 'photos', 'fit'])).toEqual([
      'app:photos',
      'apps',
      'app:drive',
      'app:fit',
    ])
  })

  it('a removed widget stays removed, also when its app is reinstalled', () => {
    const removed = withWidgetRemoved([], installed(['drive', 'photos']), 'app:photos')
    expect(keys(removed, ['drive', 'photos'])).toEqual(['apps', 'app:drive'])
    expect(keys(removed, ['drive'])).toEqual(['apps', 'app:drive'])
    expect(keys(removed, ['drive', 'photos', 'fit'])).toEqual(['apps', 'app:drive', 'app:fit'])
  })

  it('Your apps can be removed too, and does not come back first', () => {
    const removed = withWidgetRemoved([], installed(['drive', 'photos']), 'apps')
    expect(keys(removed, ['drive', 'photos'])).toEqual(['app:drive', 'app:photos'])
  })

  // An uninstalled app's card leaves the page but keeps its place for a reinstall.
  it('hides an uninstalled app and restores it in place', () => {
    const stored = ['app:photos', 'apps', 'app:drive']
    expect(keys(stored, ['drive'])).toEqual(['apps', 'app:drive'])
    expect(keys(stored, ['drive', 'photos'])).toEqual(['app:photos', 'apps', 'app:drive'])
  })

  // ⚠ A newer client's widget kind survives an older client's edit.
  it('keeps unknown tokens through an edit', () => {
    const next = withWidgetMovedBy(['clock', 'apps', 'app:drive'], installed(['drive']), 'app:drive', -1)
    expect(next).toEqual(['clock', 'app:drive', 'apps'])
  })
})

describe('adding', () => {
  it('lists Your apps and installed cards not drawn now', () => {
    const removed = withWidgetRemoved(withWidgetRemoved([], installed(['drive', 'fit']), 'apps'), installed(['drive', 'fit']), 'app:fit')
    expect(addableWidgets(removed, installed(['drive', 'fit'])).map(w => w.key)).toEqual(['apps', 'app:fit'])
    expect(addableWidgets([], installed(['drive', 'fit']))).toEqual([])
  })

  it('adds at the end, or back at its old index (undo)', () => {
    const apps = installed(['drive', 'photos'])
    const removed = withWidgetRemoved([], apps, 'app:drive')
    expect(keys(withWidgetAdded(removed, apps, 'app:drive'), ['drive', 'photos'])).toEqual([
      'apps',
      'app:photos',
      'app:drive',
    ])
    expect(keys(withWidgetAdded(removed, apps, 'app:drive', 1), ['drive', 'photos'])).toEqual([
      'apps',
      'app:drive',
      'app:photos',
    ])
  })
})

describe('moving', () => {
  const apps = installed(['drive', 'photos'])

  it('swaps with the drawn neighbour', () => {
    expect(keys(withWidgetMovedBy([], apps, 'app:photos', -1), ['drive', 'photos'])).toEqual([
      'apps',
      'app:photos',
      'app:drive',
    ])
  })

  it('skips an undrawn token between neighbours', () => {
    const stored = ['apps', 'app:fit', 'app:drive', 'app:photos']
    expect(keys(withWidgetMovedBy(stored, apps, 'app:drive', -1), ['drive', 'photos'])).toEqual([
      'app:drive',
      'apps',
      'app:photos',
    ])
  })

  it('at an end is a no-op', () => {
    expect(keys(withWidgetMovedBy([], apps, 'apps', -1), ['drive', 'photos'])).toEqual([
      'apps',
      'app:drive',
      'app:photos',
    ])
  })

  it('drops onto a target in both directions', () => {
    expect(keys(withWidgetMovedTo([], apps, 'apps', 'app:photos'), ['drive', 'photos'])).toEqual([
      'app:drive',
      'app:photos',
      'apps',
    ])
    expect(keys(withWidgetMovedTo([], apps, 'app:photos', 'apps'), ['drive', 'photos'])).toEqual([
      'app:photos',
      'apps',
      'app:drive',
    ])
  })
})
