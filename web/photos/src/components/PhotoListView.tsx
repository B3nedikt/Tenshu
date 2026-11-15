import { useCallback, useMemo } from 'react'
import type { Photo } from '@tenshu/sdk/photos'
import { wholeList } from '../lib/layout'
import type { ViewerRoute } from '../lib/viewerRoute'
import { PhotoBrowser } from './PhotoBrowser'

// A whole list in a grid (an album, the favourites): no paging, no headers, no scrubber — but the
// same virtualised grid and bounded thumbnails, pointer rules, item menu and viewer as the feed.
export function PhotoListView({
  photos,
  albumId,
  onTrashed,
  viewer,
}: {
  photos: readonly Photo[]
  albumId?: string
  onTrashed?: () => void
  // The viewer's place in the URL (`open=<index into this list>`); omitted = local state.
  viewer?: ViewerRoute
}) {
  const sections = useMemo(() => wholeList(photos.length), [photos.length])
  const photoAt = useCallback((index: number) => photos[index], [photos])

  return (
    <PhotoBrowser
      total={photos.length}
      sections={sections}
      photoAt={photoAt}
      loaded={photos}
      within={photos}
      headers={false}
      albumId={albumId}
      onTrashed={onTrashed}
      viewer={viewer}
    />
  )
}
