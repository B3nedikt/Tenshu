// The push half of Photos: `photo.albums.observe`, `photo.favourites.observe` and
// `photo.trash.observe` are genuine streams (run A). Each is subscribed ONCE, at the app root, and
// written into the query cache, so every screen reads one copy and no view refetches after a write
// — the write's own emission updates it. The mechanism is the app-kit's (`useLive`).
//
// ⚠⚠ A terminal stream error is surfaced as `streamError` (rendered with `StreamNotice`), never
// swallowed. ⚠ The feed has no push form (its only Flow is a whole-realm stream): it is snapshot +
// refetch, `feed.tsx`.
import type { ReactNode } from 'react'
import type { AlbumsResult, PhotosListResult, TrashResult } from '@tenshu/sdk/photos'
import { createLiveContext, useLive, type Live } from '@tenshu/app-kit/live'
import {
  listAlbums,
  listFavourites,
  listTrash,
  subscribeAlbums,
  subscribeFavourites,
  subscribeTrash,
} from './proxy'
import { queryKeys } from './queryKeys'

interface LiveState {
  readonly albums: Live<AlbumsResult>
  readonly favourites: Live<PhotosListResult>
  readonly trash: Live<TrashResult>
}

const [LiveContext, useLiveState] = createLiveContext<LiveState>('LiveProvider')

export function LiveProvider({ children }: { children: ReactNode }) {
  const albums = useLive(queryKeys.albums(), listAlbums, subscribeAlbums)
  const favourites = useLive(queryKeys.favourites(), listFavourites, subscribeFavourites)
  const trash = useLive(queryKeys.trash(), listTrash, subscribeTrash)
  return <LiveContext.Provider value={{ albums, favourites, trash }}>{children}</LiveContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAlbums(): Live<AlbumsResult> {
  return useLiveState().albums
}

// eslint-disable-next-line react-refresh/only-export-components
export function useFavourites(): Live<PhotosListResult> {
  return useLiveState().favourites
}

// eslint-disable-next-line react-refresh/only-export-components
export function useTrash(): Live<TrashResult> {
  return useLiveState().trash
}
