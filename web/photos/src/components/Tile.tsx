import { useRef, useState, type MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { Photo } from '@tenshu/sdk/photos'
import { ContextMenu, Menu, MenuItem, type MenuEntry } from '@tenshu/web-tokens/ui'
import { useOnScreen, useTileUrl } from '../lib/thumbnails'
import { planTile, useDevicePixelRatio } from '../lib/tileSize'
import { useMotionClip } from '../lib/motion'
import { formatDuration, previewPathOf } from '../lib/media'

// One grid cell. Its URL is held only while it is on screen (`useOnScreen` → `useThumbnail`), so a
// tile scrolled past gives its reference back and its `<img>` loses the `src`.
// A video tile plays its `MOTION` loop while the pointer is on it — one tile at a time.
//
// Pointer rules (web-apps-layout.md §3.2): a click selects (the grid decides how — ⌘, Shift),
// a double-click opens, a right-click opens the item menu and selects the photo it points at; the
// ⋯ opens the same menu as the right-click — one `MenuEntry[]` feeds both.
// ⚠ A selected tile is a ring and nothing else (iCloud Photos / Finder, §4.4 "Selection model
// revised"): no grid-wide selecting state, no check circles under a mouse. The circle exists for
// touch only (`pointer-coarse:`), where there is no ⌘-click to add one more.
// A whole-photo tile draws its picture in this share of the square, centred.
const FIT_SCALE = 0.9

export function Tile({
  photo,
  index,
  size,
  fit = false,
  selected,
  menu,
  onClick,
  onOpen,
  onToggle,
  onContextSelect,
}: {
  photo: Photo | undefined
  index: number
  size: number
  // The whole photograph inside the square (`object-contain`, inset), not the square cropped from it.
  fit?: boolean
  selected: boolean
  menu: readonly MenuEntry[]
  onClick: (event: MouseEvent) => void
  onOpen: () => void
  onToggle: () => void
  onContextSelect: () => void
}) {
  const { t, i18n } = useTranslation()
  const ref = useRef<HTMLDivElement>(null)
  const onScreen = useOnScreen(ref)
  const ratio = useDevicePixelRatio()
  const plan = planTile(fit ? size * FIT_SCALE : size, ratio, photo?.pixelWidth ?? null, photo?.pixelHeight ?? null, fit)
  const url = useTileUrl(photo ? previewPathOf(photo) : null, onScreen, plan)
  const [hovered, setHovered] = useState(false)
  // The ⋯ is mounted once the tile has been hovered or focused: a screen of tiles would otherwise
  // mount a dropdown each, and their buttons crowd the accessibility tree. ⚠ It stays mounted from
  // then on — unmounting it on mouse-leave would close its own open menu (the pointer moves onto
  // the portalled menu). The grid's virtualisation unmounts the tile when it scrolls away.
  const [armed, setArmed] = useState(false)
  const clip = useMotionClip(photo?.isVideo && hovered ? photo.path : null)

  if (!photo) {
    // A row of a page not loaded yet: sized, so the scroll height never jumps.
    return <div aria-hidden className="rounded-sm bg-surface-3" style={{ width: size, height: size }} />
  }

  const media = fit ? 'size-full object-contain' : 'size-full object-cover'
  // ⚠ The inset is what makes whole photographs read as separate pictures (iCloud's aspect mode):
  // with the grid's 2 px seam, two landscape photos side by side would otherwise touch.
  const inset = fit ? { padding: (size * (1 - FIT_SCALE)) / 2 } : undefined
  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' }).format(photo.capturedAtMs)
  const label = photo.isVideo ? t('tile.videoLabel', { name: photo.name, date }) : t('tile.photoLabel', { name: photo.name, date })

  return (
    <ContextMenu items={menu}>
      <div
        ref={ref}
        data-tile
        data-selected={selected || undefined}
        onContextMenu={onContextSelect}
        onMouseEnter={() => {
          setHovered(true)
          setArmed(true)
        }}
        onMouseLeave={() => setHovered(false)}
        onFocus={() => setArmed(true)}
        className={`group relative overflow-hidden rounded-sm ${fit ? '' : 'bg-surface-3'}`}
        style={{ width: size, height: size }}
      >
        <button
          type="button"
          onClick={onClick}
          onDoubleClick={onOpen}
          aria-label={label}
          // Selection is the button's pressed state (as Drive's rows): the grid is not a listbox.
          aria-pressed={selected}
          data-tile-index={index}
          data-testid="tile"
          // Focus is a white outline INSIDE the selected ring (the ring is painted over the button), so a
          // selected, focused tile still shows where the keyboard is (WCAG 2.4.7).
          className="block size-full select-none focus-visible:outline-2 focus-visible:outline-white focus-visible:-outline-offset-[7px]"
        >
          {clip ? (
            <video src={clip} autoPlay loop muted playsInline className={media} style={inset} />
          ) : (
            url && <img src={url} alt="" draggable={false} className={media} style={inset} />
          )}
          {photo.isVideo && (
            <span className="absolute bottom-1 right-1 rounded bg-black/60 px-1 text-xs text-white">
              {photo.durationMs !== null ? formatDuration(photo.durationMs) : '▶'}
            </span>
          )}
        </button>
        {selected && (
          // Over the image, inset: a box-shadow ring on the tile itself would be painted beneath it,
          // and an outset one would shift nothing but overlap the neighbours.
          <span aria-hidden data-testid="selected-ring" className="pointer-events-none absolute inset-0 rounded-sm ring-3 ring-inset ring-primary shadow-[inset_0_0_0_5px_rgb(255_255_255/0.85)]" />
        )}
        <span data-testid="check-circle" className="absolute left-1 top-1 hidden size-5 items-center justify-center pointer-coarse:flex">
          <input
            type="checkbox"
            aria-label={t('tile.select', { name: photo.name })}
            checked={selected}
            onChange={onToggle}
            className="peer size-5 cursor-pointer appearance-none rounded-full border-2 border-white bg-black/30 checked:bg-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          />
          <span aria-hidden className="pointer-events-none absolute text-xs text-on-primary opacity-0 peer-checked:opacity-100">
            ✓
          </span>
        </span>
        {armed && (
          <span className={`absolute right-1 top-1 ${hovered ? '' : 'opacity-0 focus-within:opacity-100 group-focus-within:opacity-100'}`}>
            <Menu
              trigger={
                <button
                  type="button"
                  aria-label={t('tile.actions', { name: photo.name })}
                  className="rounded-full bg-black/50 px-1.5 text-sm leading-5 text-white hover:bg-black/70"
                >
                  ⋯
                </button>
              }
            >
              {menu.map((item) => (
                <MenuItem key={item.label} danger={item.danger} onSelect={item.onSelect}>
                  {item.label}
                </MenuItem>
              ))}
            </Menu>
          </span>
        )}
      </div>
    </ContextMenu>
  )
}
