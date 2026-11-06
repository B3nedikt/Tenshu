/// <reference types="node" />
// The values this app spells out instead of importing (a value import from `@tenshu/sdk/mia` ships
// the Kotlin runtime): each must equal the SDK's own, read here from its source text.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { KNOWN_ERROR_KINDS, MIA_ERROR_KINDS } from './errors'
import { MAX_UTTERANCE_CHARS, ProxyMethod, TURN_PAGE_SIZE } from './proxy'
import en from '../../locales/en/mia.json'
import de from '../../locales/de/mia.json'
import fr from '../../locales/fr/mia.json'

const SDK = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../../sdk/src/mia.ts'), 'utf8')

function block(name: string): string {
  const m = new RegExp(`export const ${name} = \\{([\\s\\S]*?)\\} as const`).exec(SDK)
  if (!m) throw new Error(`${name} is not in sdk/src/mia.ts`)
  return m[1]!
}

function sdkNumber(name: string): number {
  const m = new RegExp(`export const ${name} = (\\d+)\\n`).exec(SDK)
  if (!m) throw new Error(`${name} is not in sdk/src/mia.ts`)
  return Number(m[1])
}

describe('mirrored SDK values', () => {
  it('MIA_ERROR_KINDS is exactly MiaErrorKind’s values', () => {
    const kinds = [...block('MiaErrorKind').matchAll(/'(error_mia_[a-z_]+)'/g)].map((m) => m[1])
    expect([...MIA_ERROR_KINDS].sort()).toEqual(kinds.sort())
  })

  it('the mia.* wire names are exactly MiaMethod’s — the app calls every one, and nothing else', () => {
    const names = [...block('MiaMethod').matchAll(/'(mia\.[A-Za-z.]+)'/g)].map((m) => m[1])
    const table = (Object.values(ProxyMethod) as string[]).filter((m) => m.startsWith('mia.'))
    expect(table.sort()).toEqual(names.sort())
  })

  it('the utterance bound is the SDK’s, and a page fits its ceiling', () => {
    expect(MAX_UTTERANCE_CHARS).toBe(sdkNumber('MAX_UTTERANCE_CHARS'))
    expect(TURN_PAGE_SIZE).toBeLessThanOrEqual(sdkNumber('MAX_TURN_PAGE_SIZE'))
  })

  it('no other app’s namespace: Mia reads other sources only THROUGH questions (threat model A39)', () => {
    const foreign = (Object.values(ProxyMethod) as string[]).filter(
      (m) => !m.startsWith('mia.') && !['bridge.ping', 'runtime.lease', 'runtime.useHere'].includes(m),
    )
    expect(foreign).toEqual([])
  })

  it.each([
    ['en', en],
    ['de', de],
    ['fr', fr],
  ])('every known kind and every bridge kind has %s copy', (_lang, catalogue) => {
    const errors = catalogue.errors as Record<string, string>
    for (const kind of [...KNOWN_ERROR_KINDS, 'bridge', 'bridge_timeout', 'bridge_not_permitted']) {
      expect(errors[kind], kind).toBeTruthy()
    }
  })
})
