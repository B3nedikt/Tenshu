// Provider half of a stored-list seam — the shown apps, the widgets and the background each use one;
// the contexts, types and hooks live in `shownAppsContext.ts` (a module mixing components with
// plain exports breaks fast refresh).
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
import type { Context } from 'react'
import {
  BackgroundContext,
  ShownAppsContext,
  WidgetsContext,
  type ShownAppsSource,
  type ShownAppsState,
  type ShownAppsValue,
} from './shownAppsContext'

interface Overlay {
  readonly list: readonly string[]
  readonly against: readonly string[]
}

const same = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((id, i) => id === b[i])

type ProviderProps = { readonly source: ShownAppsSource; readonly children: ReactNode }

export function ShownAppsProvider(props: ProviderProps) {
  return <StoredListProvider context={ShownAppsContext} {...props} />
}

export function WidgetsProvider(props: ProviderProps) {
  return <StoredListProvider context={WidgetsContext} {...props} />
}

export function BackgroundProvider(props: ProviderProps) {
  return <StoredListProvider context={BackgroundContext} {...props} />
}

function StoredListProvider({
  context: ListContext,
  source,
  children,
}: ProviderProps & { readonly context: Context<ShownAppsValue> }) {
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
      try {
        const result = await source.write(next)
        if (result.ok) setOverlay({ list: next, against })
        return result.ok
      } catch {
        // A thrown write is a failed one — and must not leave `pending` stuck, refusing every edit.
        return false
      } finally {
        setInFlight(n => n - 1)
      }
    },
    [effective, source],
  )

  const value = useMemo<ShownAppsValue>(
    () => ({ state: effective, pending: inFlight > 0, apply }),
    [effective, inFlight, apply],
  )
  return <ListContext.Provider value={value}>{children}</ListContext.Provider>
}
