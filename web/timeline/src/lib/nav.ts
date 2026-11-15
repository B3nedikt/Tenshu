// Where a tap goes. The app has two tabs (the day list and the places list) and three detail
// screens; a detail opened from a detail stacks, so Back returns to where the user was.
export type Detail =
  | { readonly kind: 'moment'; readonly id: string }
  | { readonly kind: 'trip'; readonly id: string }
  | { readonly kind: 'place'; readonly id: string }

export type Open = (detail: Detail) => void

const KINDS: readonly Detail['kind'][] = ['moment', 'trip', 'place']

// The innermost open detail lives in the URL fragment as `<kind>=<id>` (`place=…`, `moment=…`,
// `trip=…`; web-apps-layout.md §3.4), so a reload or a deep link reopens it. Only the top of the
// stack round-trips: a reloaded detail's Back returns to its section, not to the detail under it.
// ⚠⚠ The opaque id only — never a place name, and never in the path.
export function fragmentOf(detail: Detail | null): string {
  return detail ? `${detail.kind}=${encodeURIComponent(detail.id)}` : ''
}

/** The detail a fragment names, or null (no fragment, or one this app does not know). */
export function detailOf(fragment: string): Detail | null {
  const at = fragment.indexOf('=')
  if (at < 0) return null
  const kind = fragment.slice(0, at) as Detail['kind']
  if (!KINDS.includes(kind)) return null
  let id = fragment.slice(at + 1)
  try {
    id = decodeURIComponent(id)
  } catch {
    // A malformed escape: the raw text is still the best guess at the id.
  }
  return id ? { kind, id } : null
}
