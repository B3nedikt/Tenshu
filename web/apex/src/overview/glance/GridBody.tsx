// The `grid` layout: a few thumbnails, each drawn through the CARD's scoped call — so a grid can only
// show what its app may preview (`fs.getPreview` on the app's own allowlist).
//
// ⚠⚠ Thumbnails come from the `fs.getPreview` op, never a projected `File.Preview`: on the web only
// the dispatcher path awaits (drive-web-parity.md §1.2i).
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { GlanceCall, GlanceTile } from '../../apps/registry'

// `GetStoredThumbnail.LARGE_PX`: the rung `fs.getPreview` reaches; a bigger ask gets the same class.
const TILE_PX = 256

type PreviewAnswer = { readonly ok: true; readonly bytes: Int8Array | null } | { readonly ok: false }

export function GridBody({ tiles, call }: { readonly tiles: readonly GlanceTile[]; readonly call: GlanceCall }) {
  return (
    <ul className="grid flex-1 grid-cols-3 content-start gap-1 px-2.5 pb-2.5 [&>li:first-child]:rounded-tl-[14px] [&>li:nth-child(3)]:rounded-tr-[14px] [&>li:last-child:nth-child(-n+3)]:rounded-tr-[14px] [&>li:nth-child(3n+1):nth-last-child(-n+3)]:rounded-bl-[14px] [&>li:last-child]:rounded-br-[14px]">
      {tiles.map(tile => (
        <Tile key={tile.path} tile={tile} call={call} />
      ))}
    </ul>
  )
}

function Tile({ tile, call }: { readonly tile: GlanceTile; readonly call: GlanceCall }) {
  const { t } = useTranslation('control')
  const url = usePreviewUrl(tile.path, call)
  return (
    <li className="relative aspect-[4/3] overflow-hidden rounded-m bg-well">
      {url !== null && <img src={url} alt="" className="h-full w-full object-cover" />}
      {tile.isVideo && (
        <span className="absolute bottom-1.5 right-1.5 rounded-s bg-black/60 px-1.5 text-[11px] text-white">
          {tile.durationMs !== null ? formatDuration(tile.durationMs) : t('glance.video')}
        </span>
      )}
    </li>
  )
}

/** [path]'s thumbnail as an object URL, released on unmount. `null` while loading or without one. */
function usePreviewUrl(path: string, call: GlanceCall): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    let live = true
    let made: string | null = null
    call('fs.getPreview', { path, width: TILE_PX, height: TILE_PX }).then(
      answer => {
        const a = answer as PreviewAnswer
        if (!live || !a.ok || a.bytes === null) return
        // Copied out of the Kotlin/JS heap. Unlabelled on purpose: an <img> sniffs the type, and this
        // URL is only ever drawn, never downloaded (a stored class is not the file's own type).
        const copy = new Uint8Array(a.bytes.length)
        copy.set(a.bytes)
        made = URL.createObjectURL(new Blob([copy]))
        setUrl(made)
      },
      // A tile that cannot be previewed stays a blank square; the card itself loaded fine.
      () => {},
    )
    return () => {
      live = false
      if (made !== null) URL.revokeObjectURL(made)
    }
  }, [path, call])
  return url
}

function formatDuration(ms: number): string {
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = String(total % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}
