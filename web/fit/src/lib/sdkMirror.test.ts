/// <reference types="node" />
// The values this app spells out instead of importing (a value import from `@tenshu/sdk/fit` ships
// the Kotlin runtime): each must equal the SDK's own, read here from its source text.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import * as bounds from './bounds'
import { FIT_ERROR_KINDS, KNOWN_ERROR_KINDS } from './errors'
import en from '../../locales/en/fit.json'

const SDK = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../../sdk/src/fit.ts'), 'utf8')

// `export const NAME = <expr>` → the expression's value. Only numeric literals, `a * b`, `{ min, max }`
// and string literals occur.
function sdkConst(name: string): unknown {
  const m = new RegExp(`export const ${name} = ([^\\n]+?)(?: as const)?\\n`).exec(SDK)
  if (!m) throw new Error(`${name} is not in sdk/src/fit.ts`)
  return Function(`"use strict"; return (${m[1]!.replace(/_/g, '')})`)()
}

describe('mirrored SDK values', () => {
  it.each([
    'GOAL_ACTIVE_MINUTES',
    'GOAL_STEPS',
    'MAX_HEIGHT_CM',
    'MAX_WEIGHT_KG',
    'EARLIEST_BIRTH_DATE',
    'MAX_WORKOUT_PAGE_SIZE',
    'MAX_AGGREGATE_WINDOW_DAYS',
    'MAX_ACTIVITY_FILE_BYTES',
  ] as const)('%s equals the SDK’s', (name) => {
    expect(bounds[name]).toEqual(sdkConst(name))
  })

  it('FIT_ERROR_KINDS is exactly FitErrorKind’s values', () => {
    const block = /export const FitErrorKind = \{([\s\S]*?)\} as const/.exec(SDK)![1]!
    const kinds = [...block.matchAll(/'(error_fit_[a-z_]+)'/g)].map((m) => m[1])
    expect([...FIT_ERROR_KINDS].sort()).toEqual(kinds.sort())
  })

  it('every known kind and every bridge kind has English copy', () => {
    const errors = en.errors as Record<string, string>
    for (const kind of [...KNOWN_ERROR_KINDS, 'bridge', 'bridge_timeout', 'bridge_not_permitted']) {
      expect(errors[kind], kind).toBeTruthy()
    }
  })
})
