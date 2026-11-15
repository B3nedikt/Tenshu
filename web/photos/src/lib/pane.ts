import { createContext, useContext } from 'react'
import type { Photo } from '@tenshu/sdk/photos'

// The app's Info pane (web-apps-layout.md §3.2, D2), as the grids see it. The pane belongs to App
// (AppLayout's `pane`); every `PhotoBrowser` under it — the library, Videos, an album, the
// favourites — gets its ⓘ / ⌘I toggle and reports what the pane should describe through this.
// No provider (a view rendered on its own) = no ⓘ, nothing reported.

// The browser's own actions on a photograph, so a Favourite or a trash from the pane runs exactly
// as the item menu's does (its notice, its refresh, the selection).
export interface PhotoActions {
  readonly download: (photo: Photo) => void
  readonly setFavourite: (photo: Photo, on: boolean) => void
  readonly addToAlbum: (photo: Photo) => void
  readonly trash: (photo: Photo) => void
}

// The ONE selected photograph (null: none, or several), how many are selected, and the actions.
export interface PhotoFocus {
  readonly photo: Photo | null
  readonly count: number
  // Stable for the reporting browser's lifetime.
  readonly actions: PhotoActions
}

export interface PaneControl {
  readonly open: boolean
  readonly toggle: () => void
  // Reported by path and count (a refetch's fresh Photo object is not a new report); null when the
  // reporting grid unmounts.
  readonly onFocusChange: (focus: PhotoFocus | null) => void
}

export const PaneContext = createContext<PaneControl | null>(null)

export function usePane(): PaneControl | null {
  return useContext(PaneContext)
}
