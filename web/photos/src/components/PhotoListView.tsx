import { useCallback, useMemo, useState } from 'react'
import type { Photo } from '@tenshu/sdk/photos'
import { wholeList } from '../lib/layout'
import { useSelection } from '../lib/selection'
import { PhotoGrid } from './PhotoGrid'
import { SelectionBar } from './SelectionBar'
import { Viewer } from './Viewer'

// A whole list in a grid (an album, the favourites): no paging, no headers, no scrubber — but the
// same virtualised grid and bounded thumbnails, multi-select and viewer as the feed.
export function PhotoListView({
  photos,
  albumId,
  onTrashed,
}: {
  photos: readonly Photo[]
  albumId?: string
  onTrashed?: () => void
}) {
  const selection = useSelection()
  const [open, setOpen] = useState<number | null>(null)
  const [returnTo, setReturnTo] = useState<number | null>(null)
  const sections = useMemo(() => wholeList(photos.length), [photos.length])
  const photoAt = useCallback((index: number) => photos[index], [photos])

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex items-center gap-2">
        <SelectionBar selection={selection} albumId={albumId} />
      </div>
      <PhotoGrid
        total={photos.length}
        sections={sections}
        photoAt={photoAt}
        onOpen={setOpen}
        selection={selection}
        scrollToIndex={returnTo}
        headers={false}
      />
      {open !== null && (
        <Viewer
          total={photos.length}
          photoAt={photoAt}
          startIndex={Math.min(open, photos.length - 1)}
          albumId={albumId}
          onClose={(index) => {
            setOpen(null)
            setReturnTo(index)
          }}
          onTrashed={onTrashed}
        />
      )}
    </div>
  )
}
