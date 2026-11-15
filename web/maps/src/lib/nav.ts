// Where a tap goes: the places list, or one place. A detail replaces the list; Back returns to it.
export type Detail = { readonly kind: 'place'; readonly id: string }

export type Open = (detail: Detail) => void

// The open place lives in the URL fragment as `place=<id>` (web-apps-layout.md §3.4), so a reload
// or Back keeps it. ⚠⚠ The opaque id only — never the place's name, and never in the path.
const PLACE = 'place='

export function fragmentOf(detail: Detail | null): string {
  return detail ? PLACE + encodeURIComponent(detail.id) : ''
}

/** The place a fragment names, or null (no fragment, or one this app does not know). */
export function detailOf(fragment: string): Detail | null {
  if (!fragment.startsWith(PLACE)) return null
  let id = fragment.slice(PLACE.length)
  try {
    id = decodeURIComponent(id)
  } catch {
    // A malformed escape: the raw text is still the best guess at the id.
  }
  return id ? { kind: 'place', id } : null
}
