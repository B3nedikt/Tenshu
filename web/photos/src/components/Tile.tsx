import { useRef, useState, type MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import type { Photo } from '@tenshu/sdk/photos'
import { ContextMenu, Menu, MenuItem, type MenuEntry } from '@tenshu/web-tokens/ui'
import { useOnScreen, useThumbnail } from '../lib/thumbnails'
import { useMotionClip } from '../lib/motion'
import { formatDuration, previewPathOf } from '../lib/media'

// One grid cell. Its URL is held only while it is on screen (`useOnScreen` → `useThumbnail`), so a
// tile scrolled past gives its reference back and its `<img>` loses the `src`.
// A video tile plays its `MOTION` loop while the pointer is on it — one tile at a time.
//
// Pointer rules (web-apps-layout.md §3.2): a click selects (the grid decides how — ⌘, Shift),
// a double-click opens, a right-click opens the item menu and selects the photo it points at. The
// check circle (shown on hover / focus, or while anything is selected) toggles, for touch and the
// keyboard; the ⋯ opens the same menu as the right-click — one `MenuEntry[]` feeds both.
export function Tile({
  photo,
  index,
  size,
  selecting,
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
  // Anything is selected: the check circles show on every tile.
  selecting: boolean
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
  const url = useThumbnail(photo ? previewPathOf(photo) : null, onScreen)
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

  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' }).format(photo.capturedAtMs)
  const label = photo.isVideo ? t('tile.videoLabel', { name: photo.name, date }) : t('tile.photoLabel', { name: photo.name, date })

  return (
    <ContextMenu items={menu}>
      <div
        ref={ref}
        data-selected={selected || undefined}
        onContextMenu={onContextSelect}
        onMouseEnter={() => {
          setHovered(true)
          setArmed(true)
        }}
        onMouseLeave={() => setHovered(false)}
        onFocus={() => setArmed(true)}
        className={`group relative overflow-hidden rounded-sm bg-surface-3 ${selected ? 'ring-2 ring-primary' : ''}`}
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
          className="block size-full select-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
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
        </button>
        <span
          className={`absolute left-1 top-1 flex size-5 items-center justify-center ${
            selecting || selected ? '' : 'opacity-0 group-hover:opacity-100 focus-within:opacity-100'
          }`}
        >
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
