// ⚠ react-i18next's <Trans> parses catalogue strings as HTML, and a tag named after a VOID
// element (`<link>`, `<br>`, `<img>`, `<input>` …) is parsed as one: it cannot have children,
// so `<link>Go back</link>` renders an empty element with "Go back" beside it, and no test of
// the component's own markup notices. Name wrapper tags after what they wrap instead
// (`<threatModel>`, `<policy>`, `<back>`).
import { describe, expect, it } from 'vitest'

const VOID_ELEMENTS = [
  'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source',
  'track', 'wbr', 'keygen', 'command',
]

const catalogues = import.meta.glob<Record<string, unknown>>('../locales/*/*.json', {
  eager: true,
  import: 'default',
})

function* strings(node: unknown, path: string): Generator<[string, string]> {
  if (typeof node === 'string') yield [path, node]
  else if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) yield* strings(v, path ? `${path}.${k}` : k)
  }
}

describe('locale catalogues', () => {
  it('found the catalogues', () => {
    expect(Object.keys(catalogues).length).toBeGreaterThan(5)
  })

  it('use no void-element name as a <Trans> tag', () => {
    const tag = new RegExp(`</?(${VOID_ELEMENTS.join('|')})\\s*/?>`, 'i')
    const offenders: string[] = []
    for (const [file, catalogue] of Object.entries(catalogues)) {
      for (const [key, value] of strings(catalogue, '')) {
        if (tag.test(value)) offenders.push(`${file}: ${key}`)
      }
    }
    expect(offenders).toEqual([])
  })
})
