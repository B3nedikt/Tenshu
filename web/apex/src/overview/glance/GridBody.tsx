// The `grid` layout: a few thumbnails, each drawn through the CARD's scoped call — so a grid can only
// show what its app may preview (`fs.getPreview` on the app's own allowlist).
//
// ⚠⚠ Thumbnails come from the `fs.getPreview` op, never a projected `File.Preview`: on the web only
// the dispatcher path awaits (drive-web-parity.md §1.2i).
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { GlanceCall, GlanceTile, HostedApp } from '../../apps/registry'
import { glanceHref } from '../../apps/appNav'
import { usePreviewUrl } from './previewUrl'

export function GridBody({
  tiles,
  call,
  app,
}: {
  readonly tiles: readonly GlanceTile[]
  readonly call: GlanceCall
  /** The card's app: a tile's `open` is a place inside it, linked by `glanceHref`. */
  readonly app: HostedApp
}) {
  return (
    <ul className="grid flex-1 grid-cols-3 content-start gap-1 px-2.5 pb-2.5 [&>li:first-child]:rounded-tl-[14px] [&>li:nth-child(3)]:rounded-tr-[14px] [&>li:last-child:nth-child(-n+3)]:rounded-tr-[14px] [&>li:nth-child(3n+1):nth-last-child(-n+3)]:rounded-bl-[14px] [&>li:last-child]:rounded-br-[14px]">
      {tiles.map(tile => (
        <Tile key={tile.path} tile={tile} call={call} app={app} />
      ))}
    </ul>
  )
}

// A clickable tile (iCloud's overview): hovering or focusing it draws a white frame inside its
// corners, and a click opens that photograph in the app (its `open` fragment, under the app's path).
function Tile({ tile, call, app }: { readonly tile: GlanceTile; readonly call: GlanceCall; readonly app: HostedApp }) {
  const { t } = useTranslation('control')
  const url = usePreviewUrl(tile.path, call)
  const name = tile.path.slice(tile.path.lastIndexOf('/') + 1)
  const href = tile.open ? glanceHref(app, tile.open) : null
  return (
    <li className="group relative aspect-[4/3] overflow-hidden rounded-m bg-well">
      {url !== null && (
        <img
          src={url}
          alt=""
          className={`h-full w-full object-cover ${href !== null ? 'transition-[filter] group-hover:brightness-110' : ''}`}
        />
      )}
      {href !== null && (
        <Link
          to={href}
          aria-label={
            tile.isVideo
              ? t('glance.openVideo', { name, duration: tile.durationMs !== null ? formatDuration(tile.durationMs) : '' })
              : t('glance.openPhoto', { name })
          }
          className="absolute inset-0 rounded-[inherit] focus:outline-none"
        >
          {/* The frame: inset, so it sits inside the rounded corners and covers no neighbour. */}
          <span
            aria-hidden
            data-testid="tile-frame"
            className="pointer-events-none absolute inset-0 rounded-[inherit] ring-inset ring-white/90 group-hover:ring-[3px] group-has-[:focus-visible]:ring-[3px]"
          />
        </Link>
      )}
      {tile.isVideo && (
        // ⚠ Click-through: it sits over the link, and a badge that ate the click was a dead spot.
        <span aria-hidden={href !== null || undefined} className="pointer-events-none absolute bottom-1.5 right-1.5 rounded-s bg-black/60 px-1.5 text-[11px] text-white">
          {tile.durationMs !== null ? formatDuration(tile.durationMs) : t('glance.video')}
        </span>
      )}
    </li>
  )
}

function formatDuration(ms: number): string {
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = String(total % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}
