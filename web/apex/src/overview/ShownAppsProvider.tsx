// Provider half of the shown-apps seam; the context, types and hook live in `shownAppsContext.ts`
// (a module mixing components with plain exports breaks fast refresh).
//
// ⚠ `source` is required and never defaulted to the SDK — see `shownAppsContext.ts`.
//
// ⚠⚠ ONE overlay for every consumer. A write resolves BEFORE the store re-emits it (the new list
// comes back through the realm relation's flow), so for that window the store's list is stale and a
// change built from it drops the previous one. The overlay is the list we last wrote; it holds while
// the store still shows the list it was written against (a stale or late re-seed), and goes when the
// store shows the written list or anything else (another browser's change, which then wins). It lives
// here, not per editor: two editors with their own overlays (Customize + the dialog opened from it)
// each built from the other's stale list and dropped its change.
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ShownAppsContext, type ShownAppsSource, type ShownAppsState, type ShownAppsValue } from './shownAppsContext'

interface Overlay {
  readonly list: readonly string[]
  readonly against: readonly string[]
}

const same = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((id, i) => id === b[i])

export function ShownAppsProvider({
  source,
  children,
}: {
  readonly source: ShownAppsSource
  readonly children: ReactNode
}) {
  const [state, setState] = useState<ShownAppsState>({ kind: 'loading' })
  const [overlay, setOverlay] = useState<Overlay | null>(null)
  const [inFlight, setInFlight] = useState(0)

  useEffect(
    () =>
      source.observe(
        stored => {
          setState({ kind: 'ready', stored })
          setOverlay(o => (o === null || same(stored, o.list) || !same(stored, o.against) ? null : o))
        },
        message => setState({ kind: 'unavailable', message }),
      ),
    [source],
  )

  // Memoised: a fresh object every render would re-render every consumer (both launchers, the dock).
  const effective = useMemo<ShownAppsState>(
    () => (state.kind === 'ready' && overlay !== null ? { kind: 'ready', stored: overlay.list } : state),
    [state, overlay],
  )

  const apply = useCallback(
    async (next: readonly string[]): Promise<boolean> => {
      if (effective.kind !== 'ready') return false
      const against = effective.stored
      setInFlight(n => n + 1)
      const result = await source.write(next)
      if (result.ok) setOverlay({ list: next, against })
      setInFlight(n => n - 1)
      return result.ok
    },
    [effective, source],
  )

  const value = useMemo<ShownAppsValue>(
    () => ({ state: effective, pending: inFlight > 0, apply }),
    [effective, inFlight, apply],
  )
  return <ShownAppsContext.Provider value={value}>{children}</ShownAppsContext.Provider>
}
