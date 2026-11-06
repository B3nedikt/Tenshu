// en / de / fr carry the same keys — a missing key would render English (or the raw key) silently.
import { describe, expect, it } from 'vitest'
import en from '../locales/en/contacts.json'
import de from '../locales/de/contacts.json'
import fr from '../locales/fr/contacts.json'
import { catalogueKeys as keys } from '@tenshu/app-kit/testing'
import { KNOWN_ERROR_KINDS } from './lib/errors'

describe('catalogues', () => {
  it.each([
    ['de', de],
    ['fr', fr],
  ])('%s has exactly the English keys', (_lang, catalogue) => {
    expect(keys(catalogue).sort()).toEqual(keys(en).sort())
  })

  it('has copy for every error kind the app knows', () => {
    for (const kind of KNOWN_ERROR_KINDS) expect(keys(en)).toContain(`errors.${kind}`)
  })
})
