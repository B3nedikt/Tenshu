// en / de / fr carry the same keys — a missing key would render English (or the raw key) silently.
import { describe, expect, it } from 'vitest'
import { catalogueKeys } from '@tenshu/app-kit/testing'
import en from '../locales/en/fit.json'
import de from '../locales/de/fit.json'
import fr from '../locales/fr/fit.json'

describe('catalogues', () => {
  it.each([
    ['de', de],
    ['fr', fr],
  ])('%s has exactly the English keys', (_lang, catalogue) => {
    expect(catalogueKeys(catalogue).sort()).toEqual(catalogueKeys(en).sort())
  })
})
