import { describe, expect, it } from 'vitest'
import { HOSTED_APPS } from '../apps/registry'
import { findHostedApp } from '../apps/registry'
import {
  effectiveIds,
  hiddenApps,
  shownApps,
  usesDrive,
  withAdded,
  withMovedBy,
  withMovedTo,
  withRemoved,
  withRestored,
} from './shownApps'

const ids = (apps: readonly { id: string }[]) => apps.map(a => a.id)

describe('the shown set', () => {
  it('D1: never customised (nothing stored) shows Drive, then Photos', () => {
    expect(ids(shownApps([], HOSTED_APPS))).toEqual(['drive', 'photos'])
  })

  it('keeps the stored order', () => {
    expect(ids(shownApps(['drive', 'fit', 'photos'], HOSTED_APPS))).toEqual(['drive', 'fit', 'photos'])
  })

  // ⚠⚠ Enforced where the list is READ: a stored row without Drive (old client, bug) still shows it.
  it('always shows Drive, first, even when the stored list omits it', () => {
    expect(ids(shownApps(['photos', 'fit'], HOSTED_APPS))).toEqual(['drive', 'photos', 'fit'])
  })

  it('cannot remove Drive', () => {
    expect(withRemoved(['drive', 'photos'], 'drive')).toEqual(['drive', 'photos'])
  })

  it('adds at the end and removes in place, starting from the default when nothing is stored', () => {
    expect(withAdded([], 'fit')).toEqual(['drive', 'photos', 'fit'])
    expect(withRemoved([], 'photos')).toEqual(['drive'])
    expect(withAdded(['drive', 'fit'], 'fit')).toEqual(['drive', 'fit'])
  })

  // ⚠ An app a newer client added must survive an edit made here, or browsers fight over the list.
  it('skips unknown ids when rendering but keeps them when writing', () => {
    const stored = ['drive', 'futureApp', 'photos']
    expect(ids(shownApps(stored, HOSTED_APPS))).toEqual(['drive', 'photos'])
    expect(withAdded(stored, 'fit')).toEqual(['drive', 'futureApp', 'photos', 'fit'])
    expect(withRemoved(stored, 'photos')).toEqual(['drive', 'futureApp'])
  })

  it('lists every registered app not shown as "more", in registry order', () => {
    const more = ids(hiddenApps(['drive'], HOSTED_APPS))
    expect(more).not.toContain('drive')
    expect(more).toEqual(ids(HOSTED_APPS).filter(id => id !== 'drive'))
  })

  it('drops repeats', () => {
    expect(effectiveIds(['drive', 'photos', 'photos'])).toEqual(['drive', 'photos'])
  })
})

describe('usesDrive', () => {
  it('is derived from the allowlist: Photos calls fs.*, Drive is Drive, Contacts does not', () => {
    expect(usesDrive(findHostedApp('photos')!)).toBe(true)
    expect(usesDrive(findHostedApp('drive')!)).toBe(false)
    expect(usesDrive(findHostedApp('contacts')!)).toBe(false)
  })
})

describe('reordering (Customize mode)', () => {
  it('moves an app one place among the visible apps', () => {
    expect(withMovedBy(['drive', 'photos', 'fit'], HOSTED_APPS, 'fit', -1)).toEqual(['drive', 'fit', 'photos'])
    expect(withMovedBy(['drive', 'photos', 'fit'], HOSTED_APPS, 'drive', 1)).toEqual(['photos', 'drive', 'fit'])
  })

  it('does nothing past either end', () => {
    expect(withMovedBy(['drive', 'photos'], HOSTED_APPS, 'drive', -1)).toEqual(['drive', 'photos'])
    expect(withMovedBy(['drive', 'photos'], HOSTED_APPS, 'photos', 1)).toEqual(['drive', 'photos'])
  })

  it('drops an app onto another app\'s place', () => {
    expect(withMovedTo(['drive', 'photos', 'fit', 'maps'], HOSTED_APPS, 'maps', 'photos')).toEqual([
      'drive', 'maps', 'photos', 'fit',
    ])
    expect(withMovedTo(['drive', 'photos', 'fit'], HOSTED_APPS, 'drive', 'fit')).toEqual(['photos', 'fit', 'drive'])
  })

  // ⚠ An unknown id (a newer client's app) keeps its slot: only the known apps are reshuffled.
  it('keeps unknown ids in their slots', () => {
    const stored = ['drive', 'futureApp', 'photos', 'fit']
    expect(withMovedBy(stored, HOSTED_APPS, 'fit', -1)).toEqual(['drive', 'futureApp', 'fit', 'photos'])
    expect(withMovedTo(stored, HOSTED_APPS, 'fit', 'drive')).toEqual(['fit', 'futureApp', 'drive', 'photos'])
  })

  // ⚠ Undo restores into the list as it is NOW, so a change made since is not reverted with it.
  it('undo puts the app back at its old index of the current list', () => {
    expect(withRestored(['drive', 'fit'], 'photos', 1)).toEqual(['drive', 'photos', 'fit'])
    expect(withRestored(['drive', 'fit', 'maps'], 'photos', 1)).toEqual(['drive', 'photos', 'fit', 'maps'])
    expect(withRestored(['drive'], 'photos', 9)).toEqual(['drive', 'photos'])
  })
})
