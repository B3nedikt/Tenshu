// Multi-select over a grid: a set of photographs keyed by path, and the batch writes it runs.
import { useCallback, useMemo, useState } from 'react'
import type { Photo } from '@tenshu/sdk/photos'
import type { Selection } from '../components/PhotoGrid'
import { attempt, type Outcome } from './errors'

export interface SelectionState extends Selection {
  readonly photos: readonly Photo[]
  readonly start: () => void
  readonly stop: () => void
}

export function useSelection(): SelectionState {
  const [active, setActive] = useState(false)
  const [picked, setPicked] = useState<ReadonlyMap<string, Photo>>(new Map())
  const toggle = useCallback((photo: Photo) => {
    setPicked((prev) => {
      const next = new Map(prev)
      if (next.has(photo.path)) next.delete(photo.path)
      else next.set(photo.path, photo)
      return next
    })
  }, [])
  const has = useCallback((photo: Photo) => picked.has(photo.path), [picked])
  const start = useCallback(() => setActive(true), [])
  const stop = useCallback(() => {
    setActive(false)
    setPicked(new Map())
  }, [])
  return useMemo(
    () => ({ active, has, toggle, start, stop, photos: [...picked.values()] }),
    [active, has, toggle, start, stop, picked],
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
