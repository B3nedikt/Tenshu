import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Photo } from '@tenshu/sdk/photos'
import { useOnScreen, useThumbnail } from '../lib/thumbnails'
import { useMotionClip } from '../lib/motion'
import { formatDuration } from '../lib/media'

// One grid cell. Its URL is held only while it is on screen (`useOnScreen` → `useThumbnail`), so a
// tile scrolled past gives its reference back and its `<img>` loses the `src`.
// A video tile plays its `MOTION` loop while the pointer is on it — one tile at a time.
export function Tile({
  photo,
  size,
  selecting,
  selected,
  onActivate,
}: {
  photo: Photo | undefined
  size: number
  selecting: boolean
  selected: boolean
  onActivate: () => void
}) {
  const { t, i18n } = useTranslation()
  const ref = useRef<HTMLButtonElement>(null)
  const onScreen = useOnScreen(ref)
  const url = useThumbnail(photo?.path ?? null, onScreen)
  const [hovered, setHovered] = useState(false)
  const clip = useMotionClip(photo?.isVideo && hovered && !selecting ? photo.path : null)

  if (!photo) {
    // A row of a page not loaded yet: sized, so the scroll height never jumps.
    return <div aria-hidden className="rounded-sm bg-surface-3" style={{ width: size, height: size }} />
  }

  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' }).format(photo.capturedAtMs)
  const label = photo.isVideo ? t('tile.videoLabel', { name: photo.name, date }) : t('tile.photoLabel', { name: photo.name, date })

  return (
    <button
      ref={ref}
      type="button"
      onClick={onActivate}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      aria-label={label}
      aria-pressed={selecting ? selected : undefined}
      data-testid="tile"
      className={`relative overflow-hidden rounded-sm bg-surface-3 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${
        selected ? 'ring-2 ring-primary' : ''
      }`}
      style={{ width: size, height: size }}
    >
      {clip ? (
        <video src={clip} autoPlay loop muted playsInline className="size-full object-cover" />
      ) : (
        url && <img src={url} alt="" draggable={false} className="size-full object-cover" />
      )}
      {photo.isVideo && (
        <span className="absolute bottom-1 right-1 rounded bg-black/60 px-1 text-xs text-white">
          {photo.durationMs !== null ? formatDuration(photo.durationMs) : '▶'}
        </span>
      )}
      {selecting && (
        <span
          aria-hidden
          className={`absolute left-1 top-1 flex size-5 items-center justify-center rounded-full border-2 border-white text-xs ${
            selected ? 'bg-primary text-on-primary' : 'bg-black/30'
          }`}
        >
          {selected ? '✓' : ''}
        </span>
      )}
    </button>
  )
}
