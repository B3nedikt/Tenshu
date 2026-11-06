// Central registry of TanStack Query keys — the app-kit's `defineQueryKeys`, which also merges in
// its session keys (`runtimeLease`): every query, every `setQueryData` from a push stream and every
// invalidation names its key through this factory, so a key typed slightly differently at one end
// cannot silently detach the cache from its updates.
import { defineQueryKeys } from '@tenshu/app-kit/query'

export const queryKeys = defineQueryKeys({
  captureTimes: () => ['captureTimes'] as const,
  albums: () => ['albums'] as const,
  albumPhotos: (albumId: string) => ['albumPhotos', albumId] as const,
  albumsFor: (contentId: string) => ['albumsFor', contentId] as const,
  favourites: () => ['favourites'] as const,
  isFavourite: (contentId: string) => ['isFavourite', contentId] as const,
  trash: () => ['trash'] as const,
  details: (path: string) => ['details', path] as const,
  largePreview: (path: string, px: number) => ['largePreview', path, px] as const,
})
