// House style for the apex's user-facing copy: no dashes as punctuation. An em dash (or the
// spaced en dash German typography uses for the same job) reads as machine-written, so prose
// says it with a full stop, a comma, a colon or parentheses instead. A string that is ONLY a
// dash is a placeholder glyph (an empty value, an empty field) and is allowed.
import { describe, expect, it } from 'vitest'

const catalogues = import.meta.glob<Record<string, unknown>>('../locales/*/*.json', {
  eager: true,
  import: 'default',
})

function strings(value: unknown, path: string): [string, string][] {
  if (typeof value === 'string') return [[path, value]]
  if (typeof value !== 'object' || value === null) return []
  return Object.entries(value).flatMap(([k, v]) => strings(v, path ? `${path}.${k}` : k))
}

const DASH_AS_PUNCTUATION = /—| – /

describe('apex copy style', () => {
  it('uses no dash as punctuation in any locale string', () => {
    const offenders = Object.entries(catalogues)
      .flatMap(([file, catalogue]) => strings(catalogue, file.replace('../locales/', '')))
      .filter(([, text]) => DASH_AS_PUNCTUATION.test(text) && !/^[\s—–]+$/.test(text))
      .map(([path, text]) => `${path}: ${text}`)
    expect(offenders).toEqual([])
  })
})
