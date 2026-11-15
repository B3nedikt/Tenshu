// This browser's remembered Drive display (web-apps-layout.md §3.6, L6): DEVICE-scoped rows, the
// sort and view being native Drive's rows for this device. Read once per session (one cached query
// shared by the listing and the pane).
//
// The rules (each pinned by AppFiles.test):
// - Per field, a STORED row wins; a row this browser has never stored falls back to the local copy —
//   ⚠⚠ a missing row reads as the default, and taking it would override (and erase) the choice kept
//   in localStorage before L6 — and that local value is written up once, so the row exists from then on.
// - ⚠ Every write updates the cached settings at once: the listing remounts on each section switch,
//   and a cache still holding the pre-write value would bring the old choice back.
// - `localStorage` stays the first paint and the fallback (`no_device` before a sync identity, or a
//   failed read): it is kept in step with whatever is shown.
import { useEffect, useState } from 'react'
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { driveSettings, setDriveDetailsPane, setDriveListing, type DriveSettingsResult } from './proxy'
import { queryKeys } from './queryKeys'
import { loadPrefs, savePrefs, type ListingPrefs } from './listingPrefs'
import { loadPaneOpen, savePaneOpen } from './paneState'

type Stored = Extract<DriveSettingsResult, { ok: true }>

export function useDriveSettings(): Stored | null {
  const { data } = useQuery({ queryKey: queryKeys.driveSettings(), queryFn: driveSettings, staleTime: Infinity })
  return data?.ok ? data : null
}

function patchCache(queryClient: QueryClient, patch: Partial<Stored>) {
  queryClient.setQueryData<DriveSettingsResult>(queryKeys.driveSettings(), (old) => (old?.ok ? { ...old, ...patch } : old))
}

/** The listing's sort + layout, and its setter. */
export function useListingPrefs(): { prefs: ListingPrefs; setPrefs: (next: ListingPrefs) => void } {
  const queryClient = useQueryClient()
  const [local, setLocal] = useState<ListingPrefs>(loadPrefs)
  const device = useDriveSettings()
  const prefs: ListingPrefs = {
    key: device?.sortStored ? device.key : local.key,
    descending: device?.sortStored ? device.descending : local.descending,
    layout: device?.layoutStored ? device.layout : local.layout,
  }

  const persist = (next: ListingPrefs) => {
    patchCache(queryClient, { key: next.key, descending: next.descending, layout: next.layout, sortStored: true, layoutStored: true })
    // ⚠ Best effort: no sync identity yet (`no_device`) or a failed write keeps the local copy.
    void setDriveListing(next.key, next.descending, next.layout).catch(() => undefined)
  }

  // First L6 load on this browser: adopt the local choice for the rows not stored, and store it.
  const missing = !!device && (!device.sortStored || !device.layoutStored)
  useEffect(() => {
    if (missing) persist(prefs)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once, when the read shows a row missing
  }, [missing])

  // Keep the local fallback in step with what is shown.
  useEffect(() => {
    savePrefs(prefs)
  }, [prefs.key, prefs.descending, prefs.layout]) // eslint-disable-line react-hooks/exhaustive-deps

  return {
    prefs,
    setPrefs: (next) => {
      setLocal(next)
      persist(next)
    },
  }
}

/** Whether the details pane is open (D2), and its toggle. */
export function useDetailsPane(): { open: boolean; toggle: () => void } {
  const queryClient = useQueryClient()
  const [local, setLocal] = useState(loadPaneOpen)
  const device = useDriveSettings()
  const open = device?.paneStored ? device.detailsPane : local

  const persist = (next: boolean) => {
    patchCache(queryClient, { detailsPane: next, paneStored: true })
    void setDriveDetailsPane(next).catch(() => undefined)
  }

  const missing = !!device && !device.paneStored
  useEffect(() => {
    if (missing) persist(open)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once, when the read shows the row missing
  }, [missing])

  useEffect(() => {
    savePaneOpen(open)
  }, [open])

  return {
    open,
    toggle: () => {
      const next = !open
      setLocal(next)
      persist(next)
    },
  }
}
