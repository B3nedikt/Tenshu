// The selection over a grid (web-apps-layout.md §3.2, L4): a set of photographs keyed by path, the
// desktop pointer rules that change it, and the batch writes it runs. There is no separate "Select"
// mode — a click selects, as in Drive (L3).
import { useCallback, useMemo, useState } from 'react'
import type { Photo } from '@tenshu/sdk/photos'
import { attempt, type Outcome } from './errors'

export interface PointerModifiers {
  readonly shiftKey: boolean
  readonly metaKey: boolean
  readonly ctrlKey: boolean
}

export interface SelectionState {
  readonly photos: readonly Photo[]
  readonly size: number
  readonly has: (photo: Photo) => boolean
  // A click on [photo] at grid [index]: select just it; ⌘/Ctrl toggles it; Shift extends from the
  // anchor, in grid order ([photoAt] reads the grid — rows not loaded yet are skipped).
  readonly click: (photo: Photo, index: number, event: PointerModifiers, photoAt: (index: number) => Photo | undefined) => void
  // The check circle (touch / keyboard): toggles, and moves the anchor.
  readonly toggle: (photo: Photo, index: number) => void
  // Right-click: select the photo pointed at unless it is already part of the selection.
  readonly point: (photo: Photo, index: number) => void
  readonly selectAll: (photos: readonly Photo[]) => void
  readonly drop: (photo: Photo) => void
  readonly clear: () => void
}

/**
 * [within], when given, is the whole list the grid shows (an album, the favourites): a photograph
 * that left it (removed, unfavourited elsewhere) drops out of the count, the bar and every batch.
 */
export function useSelection(within?: readonly Photo[]): SelectionState {
  const [picked, setPicked] = useState<ReadonlyMap<string, Photo>>(new Map())
  // Where a Shift-click range starts: the index and the path that was there, so an anchor whose
  // row has since moved (a refetch) is recognised as stale and the click is a plain one.
  const [anchor, setAnchor] = useState<{ index: number; path: string } | null>(null)

  const visible = useMemo(() => {
    if (!within) return picked
    const present = new Set(within.map((p) => p.path))
    const next = new Map<string, Photo>()
    for (const [path, photo] of picked) if (present.has(path)) next.set(path, photo)
    return next.size === picked.size ? picked : next
  }, [picked, within])

  const toggle = useCallback((photo: Photo, index: number) => {
    setPicked((prev) => {
      const next = new Map(prev)
      if (next.has(photo.path)) next.delete(photo.path)
      else next.set(photo.path, photo)
      return next
    })
    setAnchor({ index, path: photo.path })
  }, [])

  const click = useCallback<SelectionState['click']>(
    (photo, index, event, photoAt) => {
      const from = anchor && photoAt(anchor.index)?.path === anchor.path ? anchor.index : -1
      if (event.shiftKey && from >= 0) {
        const [lo, hi] = from < index ? [from, index] : [index, from]
        const next = new Map<string, Photo>()
        for (let i = lo; i <= hi; i += 1) {
          const p = photoAt(i)
          if (p) next.set(p.path, p)
        }
        setPicked(next)
        return
      }
      if (event.metaKey || event.ctrlKey) {
        toggle(photo, index)
        return
      }
      setPicked(new Map([[photo.path, photo]]))
      setAnchor({ index, path: photo.path })
    },
    [anchor, toggle],
  )

  const point = useCallback((photo: Photo, index: number) => {
    setPicked((prev) => (prev.has(photo.path) ? prev : new Map([[photo.path, photo]])))
    setAnchor((prev) => (prev?.path === photo.path ? prev : { index, path: photo.path }))
  }, [])

  const selectAll = useCallback((photos: readonly Photo[]) => {
    setPicked(new Map(photos.map((p) => [p.path, p])))
  }, [])

  const drop = useCallback((photo: Photo) => {
    setPicked((prev) => {
      if (!prev.has(photo.path)) return prev
      const next = new Map(prev)
      next.delete(photo.path)
      return next
    })
  }, [])

  const clear = useCallback(() => {
    setPicked(new Map())
    setAnchor(null)
  }, [])

  const has = useCallback((photo: Photo) => visible.has(photo.path), [visible])
  return useMemo(
    () => ({ photos: [...visible.values()], size: visible.size, has, click, toggle, point, selectAll, drop, clear }),
    [visible, has, click, toggle, point, selectAll, drop, clear],
  )
}

export interface BatchOutcome {
  readonly done: number
  readonly total: number
  // The first failure, rendered by kind; the rest of the batch still ran.
  readonly first: Outcome
}

/** Run [write] for each of [photos] in turn; every one runs, and the first failure is kept. */
export async function runBatch(
  photos: readonly Photo[],
  write: (photo: Photo) => Promise<{ ok: true } | { ok: false; error: { kind: string } }>,
): Promise<BatchOutcome> {
  let done = 0
  let first: Outcome = null
  for (const photo of photos) {
    const outcome = await attempt(() => write(photo))
    if (outcome) first ??= outcome
    else done += 1
  }
  return { done, total: photos.length, first }
}
