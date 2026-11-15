// What a Photos search box query means (web-apps-layout.md L7). A date is read HERE, not in the SDK:
// "May 2025" is language-specific and its boundaries are local midnights, which only the browser
// knows. Anything that is not a date is a name search (case-insensitive, SDK-side).
//
// Dates understood: `2025`, `2025-05`, `2025-05-03`, and a month name with a year in either order
// (`May 2025`, `2025 mai`, `Mai 2025`) — in the UI language or English, long or short (`Sept.`).

export interface SearchQuery {
  readonly name: string | null
  readonly fromMs: number | null
  readonly toMs: number | null
  // A date range was understood (the heading says "Photos from …", not "… matching …").
  readonly isDate: boolean
}

const YEAR_MIN = 1900
const YEAR_MAX = 2100

function monthRange(year: number, month: number): [number, number] {
  return [new Date(year, month, 1).getTime(), new Date(year, month + 1, 1).getTime() - 1]
}

function validYear(year: number): boolean {
  return year >= YEAR_MIN && year <= YEAR_MAX
}

const normalise = (s: string) => s.toLocaleLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '').replace(/\.$/, '')

/** Month names (long and short, normalised) → 0-based month, for [locale] and English. */
function monthNames(locale: string): Map<string, number> {
  const names = new Map<string, number>()
  for (const lang of [locale, 'en']) {
    for (const style of ['long', 'short'] as const) {
      let format: Intl.DateTimeFormat
      try {
        format = new Intl.DateTimeFormat(lang, { month: style })
      } catch {
        continue
      }
      for (let m = 0; m < 12; m++) names.set(normalise(format.format(new Date(2000, m, 15))), m)
    }
  }
  return names
}

/** An exact name, else an unambiguous prefix of ≥ 3 letters (`sept`, `mär`, `janv`). */
function monthOf(word: string, names: Map<string, number>): number | undefined {
  const exact = names.get(word)
  if (exact !== undefined || word.length < 3) return exact
  const matches = new Set([...names].filter(([name]) => name.startsWith(word)).map(([, m]) => m))
  return matches.size === 1 ? [...matches][0] : undefined
}

export function parseSearch(raw: string, locale: string): SearchQuery | null {
  const query = raw.trim()
  if (!query) return null
  const date = (from: number, to: number): SearchQuery => ({ name: null, fromMs: from, toMs: to, isDate: true })

  let m = /^(\d{4})$/.exec(query)
  if (m) {
    const year = Number(m[1])
    if (validYear(year)) return date(new Date(year, 0, 1).getTime(), new Date(year + 1, 0, 1).getTime() - 1)
  }
  m = /^(\d{4})-(\d{1,2})$/.exec(query)
  if (m) {
    const year = Number(m[1])
    const month = Number(m[2]) - 1
    if (validYear(year) && month >= 0 && month < 12) return date(...monthRange(year, month))
  }
  m = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(query)
  if (m) {
    const [year, month, day] = [Number(m[1]), Number(m[2]) - 1, Number(m[3])]
    const start = new Date(year, month, day)
    // Rejects 2025-02-30 (it would roll into March).
    if (validYear(year) && start.getMonth() === month && start.getDate() === day) {
      return date(start.getTime(), new Date(year, month, day + 1).getTime() - 1)
    }
  }
  const words = query.split(/\s+/)
  if (words.length === 2) {
    const names = monthNames(locale)
    const [a, b] = words as [string, string]
    const pick = /^\d{4}$/.test(a) ? ([b, a] as const) : /^\d{4}$/.test(b) ? ([a, b] as const) : null
    if (pick) {
      const month = monthOf(normalise(pick[0]), names)
      const year = Number(pick[1])
      if (month !== undefined && validYear(year)) return date(...monthRange(year, month))
    }
  }
  return { name: query, fromMs: null, toMs: null, isDate: false }
}
