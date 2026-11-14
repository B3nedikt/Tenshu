// Which photograph the viewer shows, as the URL carries it (web-apps-layout.md §3.4, D6): the
// view's fragment gains `open=<index>` — the index into the list that view shows (the feed, an
// album, the favourites). An index, never a name or a path: ⚠⚠ user data stays out of the URL's
// path, and an index is all a view needs to find the photograph again.
//
// D6: opening the viewer takes over the content, so it PUSHES (browser Back closes it); stepping
// through photographs inside it REPLACES; its own close is `back` (history.back() when this app
// pushed the entry, else a replace — never out of the app from a deep link).
import { useEffect, useMemo, useState } from 'react'

export interface ViewerRoute {
  // The open photograph's index, or null when the viewer is closed.
  readonly index: number | null
  readonly open: (index: number) => void
  readonly move: (index: number) => void
  readonly close: () => void
}

export const OPEN_PARAM = 'open'

/** The `open=` index of [fragment] (a `URLSearchParams` string), or null. */
export function openIndexOf(fragment: string): number | null {
  const raw = new URLSearchParams(fragment).get(OPEN_PARAM)
  if (raw === null || !/^\d+$/.test(raw)) return null
  const index = Number.parseInt(raw, 10)
  return Number.isSafeInteger(index) ? index : null
}

/** [fragment] with `open=` set to [index], or removed for null; the other params are kept. */
export function withOpen(fragment: string, index: number | null): string {
  const params = new URLSearchParams(fragment)
  if (index === null) params.delete(OPEN_PARAM)
  else params.set(OPEN_PARAM, String(index))
  return params.toString()
}

/** A route over the app's URL: [fragment] is the view's current one. */
export function urlViewer(
  fragment: string,
  nav: { open: (fragment: string) => void; setFragment: (fragment: string) => void; back: (fallback?: string) => void },
): ViewerRoute {
  return {
    index: openIndexOf(fragment),
    open: (index) => nav.open(withOpen(fragment, index)),
    move: (index) => nav.setFragment(withOpen(fragment, index)),
    close: () => nav.back(withOpen(fragment, null)),
  }
}

/** [route] when the view is mounted under the app's URL; otherwise the viewer's place is local state. */
export function useViewerRoute(route?: ViewerRoute): ViewerRoute {
  const [index, setIndex] = useState<number | null>(null)
  const local = useMemo<ViewerRoute>(
    () => ({ index, open: setIndex, move: setIndex, close: () => setIndex(null) }),
    [index],
  )
  return route ?? local
}

/**
 * Closes [route]'s viewer when its list became empty. A view shows its empty state INSTEAD of the
 * grid, which unmounts the viewer with it — so the viewer's own "emptied → close" never runs, and
 * `open=` would stay in the URL on a pushed entry (Back lands on the same empty view, and a photo
 * arriving later pops the viewer open by itself).
 */
export function useCloseViewerWhenEmpty(route: ViewerRoute | undefined, empty: boolean): void {
  const open = route?.index !== null && route?.index !== undefined
  useEffect(() => {
    if (empty && open) route?.close()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- on the transition, not per route object
  }, [empty, open])
}
