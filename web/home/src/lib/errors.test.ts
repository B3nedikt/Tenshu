/// <reference types="node" />
// The kinds this app has copy for must be exactly the kinds the SDK mints (`HomeErrorKind` in
// `@tenshu/sdk/home`). Read as text: a value import would drag the Kotlin runtime in.
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import en from '../../locales/en/home.json'
import { HOME_ERROR_KINDS, KNOWN_ERROR_KINDS, streamErrorKind } from './errors'

const SDK = resolve(dirname(fileURLToPath(import.meta.url)), '../../../sdk/src/home.ts')

describe('error kinds', () => {
  it('match HomeErrorKind in the SDK', () => {
    const source = readFileSync(SDK, 'utf8')
    const block = source.slice(source.indexOf('export const HomeErrorKind = {'))
    const body = block.slice(0, block.indexOf('} as const'))
    const kinds = [...body.matchAll(/'(error_home_[a-z_]+)'/g)].map((m) => m[1])
    expect([...HOME_ERROR_KINDS].sort()).toEqual(kinds.sort())
  })

  it('every known kind has copy', () => {
    for (const kind of KNOWN_ERROR_KINDS) expect(en.errors, kind).toHaveProperty(kind)
  })

  it.each([
    ['error_home_stream_ended: collector completed', 'error_home_stream_ended'],
    ['error_home_service_unavailable', 'error_home_service_unavailable'],
    ['apex proxy frame is not available', null],
    ['not permitted: home.devices', null],
  ])('streamErrorKind(%s) = %s', (message, kind) => expect(streamErrorKind(new Error(message))).toBe(kind))
})
