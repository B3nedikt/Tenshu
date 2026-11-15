import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import type { Photo } from '@tenshu/sdk/photos'
import type { MenuEntry } from '@tenshu/web-tokens/ui'
import { addToAlbum, setFavourite, trashPhoto } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { attempt, outcomeMessage, type Outcome } from '../lib/errors'
import { downloadOriginal } from '../lib/download'
import { previewPathOf } from '../lib/media'
import { useFavourites } from '../lib/live'
import { useSelection, type BatchOutcome } from '../lib/selection'
import { usePane, type PhotoActions } from '../lib/pane'
import { useViewerRoute, type ViewerRoute } from '../lib/viewerRoute'
import type { MonthSection } from '../lib/layout'
import { AlbumPicker } from './AlbumPicker'
import { PhotoGrid } from './PhotoGrid'
import { IconButton, SelectionBar } from './SelectionBar'
import { InfoIcon } from './icons'
import { Viewer } from './Viewer'
import { OutcomeText } from './ui'

// A grid with Drive's desktop interaction model (web-apps-layout.md §3.2/§3.3, L4): the selection
// and its bar, the item menu (right-click and ⋯), and the viewer over the same rows — the library,
// an album and the favourites all render through this.
//
// The item menu is the native per-photo sheet: Open · Download · Favourite · Add to album · Move to
// trash. ⚠ No "Hide": photos-app.md §42.6 keeps the flag but never sets it.
export function PhotoBrowser({
  total,
  sections,
  photoAt,
  loaded,
  need,
  times,
  scrollToIndex = null,
  onTopIndex,
  headers,
  tileTarget,
  headerFormat,
  mosaic,
  fit,
  columns,
  onColumnsDrawn,
  albumId,
  onTrashed,
  viewer: route,
  within,
  toolbar,
}: {
  total: number
  sections: readonly MonthSection[]
  photoAt: (index: number) => Photo | undefined
  // The photographs held now — what ⌘A selects.
  loaded: readonly Photo[]
  need?: (index: number) => void
  times?: readonly number[]
  scrollToIndex?: number | null
  onTopIndex?: (index: number) => void
  headers?: boolean
  tileTarget?: number
  headerFormat?: Intl.DateTimeFormatOptions
  mosaic?: boolean
  fit?: boolean
  columns?: number
  onColumnsDrawn?: (columns: number) => void
  albumId?: string
  onTrashed?: () => void
  // The viewer's place in the app's URL; omitted = local state (a view rendered on its own).
  viewer?: ViewerRoute
  // The whole list (an album, the favourites): the selection is pruned against it.
  within?: readonly Photo[]
  // The view's own controls, left of the selection's count and actions.
  toolbar?: ReactNode
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const selection = useSelection(within)
  const viewer = useViewerRoute(route)
  const [picking, setPicking] = useState<Photo | null>(null)
  const [notice, setNotice] = useState<Outcome>(null)
  const [batch, setBatch] = useState<BatchOutcome | null>(null)
  // A cleared selection drops its last batch report too — adjusted during render, React's pattern.
  if (selection.size === 0 && batch) setBatch(null)

  const favourites = useFavourites().data
  const favourite = useMemo(
    () => new Set(favourites?.ok ? favourites.photos.map((p) => p.contentId) : []),
    [favourites],
  )

  // Closing the viewer — its own Back, Escape, or browser Back — puts focus on the tile of the
  // photograph it last showed, scrolled into view.
  const lastShown = useRef<number | null>(null)
  const [focusIndex, setFocusIndex] = useState<{ index: number } | null>(null)
  useEffect(() => {
    if (viewer.index !== null) {
      lastShown.current = viewer.index
      return
    }
    const index = lastShown.current
    lastShown.current = null
    if (index !== null) setFocusIndex({ index: Math.min(index, Math.max(0, total - 1)) })
    // On the transition only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewer.index])

  async function trash(photo: Photo) {
    const outcome = await attempt(() => trashPhoto(photo.path))
    setNotice(outcome)
    if (outcome) return
    selection.drop(photo)
    onTrashed?.()
  }

  async function toggleFavourite(photo: Photo, on: boolean) {
    const outcome = await attempt(() => setFavourite(photo.contentId, on))
    setNotice(outcome)
    if (!outcome) await queryClient.invalidateQueries({ queryKey: queryKeys.isFavourite(photo.contentId) })
  }

  async function download(photo: Photo) {
    // ⚠⚠ ED-3: an edited photo hands out its rendition, never the original (photos-editing.md §12.4).
    const outcome = await downloadOriginal(previewPathOf(photo), photo.name)
    setNotice(outcome.kind === 'failed' ? { failure: { kind: outcome.errorKind } } : null)
  }

  const menuFor = (photo: Photo, index: number): MenuEntry[] => {
    // ⚠ A file predating content addressing has no content id: it cannot be filed, so the filing
    // entries are left out rather than failing.
    const fileable = photo.contentId !== ''
    const isFav = fileable && favourite.has(photo.contentId)
    return [
      { label: t('menu.open'), shortcut: '↵', onSelect: () => viewer.open(index) },
      { label: t('menu.download'), onSelect: () => void download(photo) },
      ...(fileable
        ? [
            {
              label: isFav ? t('menu.unfavourite') : t('menu.favourite'),
              groupStart: true,
              onSelect: () => void toggleFavourite(photo, !isFav),
            },
            { label: t('menu.addToAlbum'), onSelect: () => setPicking(photo) },
          ]
        : []),
      { label: t('menu.trash'), danger: true, groupStart: true, onSelect: () => void trash(photo) },
    ]
  }

  // The pane's actions call the LATEST render's handlers through a ref, behind one stable object.
  const handlers = useRef<PhotoActions | null>(null)
  useLayoutEffect(() => {
    handlers.current = {
      download: (photo) => void download(photo),
      setFavourite: (photo, on) => void toggleFavourite(photo, on),
      addToAlbum: (photo) => setPicking(photo),
      trash: (photo) => void trash(photo),
    }
  })
  const actions = useMemo<PhotoActions>(
    () => ({
      download: (photo) => handlers.current?.download(photo),
      setFavourite: (photo, on) => handlers.current?.setFavourite(photo, on),
      addToAlbum: (photo) => handlers.current?.addToAlbum(photo),
      trash: (photo) => handlers.current?.trash(photo),
    }),
    [],
  )
  const only = selection.size === 1 ? selection.photos[0]! : null
  const focusKey = `${only?.path ?? ''}\u0000${selection.size}`
  // The app's Info pane (`lib/pane`): the ⓘ and ⌘I toggle it; what it describes is reported here.
  const pane = usePane()
  const onTogglePane = pane?.toggle
  const reportFocus = useRef(pane?.onFocusChange)
  useLayoutEffect(() => {
    reportFocus.current = pane?.onFocusChange
  })
  useEffect(() => {
    reportFocus.current?.({ photo: only, count: selection.size, actions })
    // Keyed by what the pane shows (above), not by the photo object.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusKey, actions])
  useEffect(() => () => reportFocus.current?.(null), [])

  const selectAll = useCallback(() => selection.selectAll(loaded), [selection, loaded])
  const open = viewer.index

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      {/* ⚠ One row, its height reserved and never wrapped: the selection's count and actions
          appearing must not shift the grid (§4.4 "Selection model revised"). */}
      <div className="flex h-9 flex-none items-center gap-2">
        {/* The view's controls give way (scroll) when the row is short — a narrow window, the pane
            open — so the selection's actions and the ⓘ that closes the pane stay reachable. */}
        <div className="flex min-w-0 flex-1 items-center gap-2 overflow-x-auto">{toolbar}</div>
        <SelectionBar selection={selection} albumId={albumId} onReport={setBatch} onTrashed={onTrashed} />
        {onTogglePane && (
          <IconButton label={t('pane.toggle')} pressed={pane?.open ?? false} onClick={onTogglePane}>
            <InfoIcon />
          </IconButton>
        )}
      </div>
      {notice && <OutcomeText outcome={notice} />}
      {batch?.first && (
        <p role="alert" className="text-sm text-danger">
          {t('select.partial', { done: batch.done, total: batch.total })} {outcomeMessage(t, batch.first)}
        </p>
      )}
      <PhotoGrid
        total={total}
        sections={sections}
        photoAt={photoAt}
        need={need}
        onOpen={viewer.open}
        selection={selection}
        menuFor={menuFor}
        onSelectAll={selectAll}
        onTogglePane={onTogglePane}
        keysDisabled={open !== null || picking !== null}
        times={times}
        scrollToIndex={scrollToIndex}
        onTopIndex={onTopIndex}
        focusIndex={focusIndex}
        headers={headers}
        tileTarget={tileTarget}
        headerFormat={headerFormat}
        mosaic={mosaic}
        fit={fit}
        columns={columns}
        onColumnsDrawn={onColumnsDrawn}
      />
      {open !== null && (
        <Viewer
          total={total}
          photoAt={photoAt}
          need={need}
          index={open}
          onMove={viewer.move}
          albumId={albumId}
          onClose={() => viewer.close()}
          onTrashed={(photo) => {
            // A photograph trashed from the viewer leaves the selection too.
            selection.drop(photo)
            onTrashed?.()
          }}
        />
      )}
      {picking && (
        <AlbumPicker
          onClose={() => setPicking(null)}
          onPick={async (id) => {
            const outcome = await attempt(() => addToAlbum(id, picking.path))
            if (!outcome) await queryClient.invalidateQueries({ queryKey: queryKeys.albumPhotos(id) })
            return outcome
          }}
        />
      )}
    </div>
  )
}
