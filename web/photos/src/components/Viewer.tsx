import { useEffect, useMemo, useRef, useState, type Ref } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { Photo } from '@tenshu/sdk/photos'
import {
  addToAlbum,
  albumsFor,
  getLargePreview,
  isFavourite,
  photoDetails,
  removeFromAlbum,
  setFavourite,
  trashPhoto,
} from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { attempt, type Outcome } from '../lib/errors'
import { formatCoordinate, formatDuration, heroPreviewRequestPx, previewPathOf, stillPlan, videoMime } from '../lib/media'
import { bytesToBlob, useOnScreen, useThumbnail } from '../lib/thumbnails'
import { useMotionClip } from '../lib/motion'
import { openVideoStream, type VideoStream } from '../lib/videoStream'
import { downloadOriginal, type DownloadOutcome } from '../lib/download'
import { AlbumPicker } from './AlbumPicker'
import { Button, ErrorText, OutcomeText } from './ui'

// A horizontal drag this long (CSS px) is a swipe to the neighbour.
const SWIPE_PX = 50
// The filmstrip draws this many neighbours each side of the open photograph — never the whole
// list, which in the feed can be 10k rows.
export const FILMSTRIP_RADIUS = 30

export interface ViewerProps {
  readonly total: number
  readonly photoAt: (index: number) => Photo | undefined
  // Page the list in when the neighbour is not loaded yet (the feed).
  readonly need?: (index: number) => void
  // Uncontrolled: where to start; the viewer then keeps its own place.
  readonly startIndex?: number
  // Controlled (the app's URL, D6): the open index, and [onMove] when the viewer steps to another.
  readonly index?: number
  readonly onMove?: (index: number) => void
  // Opened from an album: offers "Remove from album".
  readonly albumId?: string
  readonly onClose: (index: number) => void
  // After a trash: the list it was in has changed (the feed refetches).
  // With the trashed photograph, so the grid can drop it from its selection.
  readonly onTrashed?: (photo: Photo) => void
}

// The full-screen viewer: the largest stored rung (never a decoded original), keyboard ← → Esc
// Space and swipe navigation, a filmstrip of the neighbours, the stored details, favourite, albums,
// trash, and for a video its MOTION loop then playback through Drive's streaming path.
export function Viewer({ total, photoAt, need, startIndex = 0, index: controlled, onMove, albumId, onClose, onTrashed }: ViewerProps) {
  const { t } = useTranslation()
  const [own, setOwn] = useState(startIndex)
  const requested = controlled ?? own
  // ⚠ The list can shrink under the viewer (unfavourite the last favourite; remove from an album):
  // the index follows it in, and an emptied list closes the viewer.
  const index = Math.max(0, Math.min(requested, total - 1))
  const [showDetails, setShowDetails] = useState(false)
  const [picking, setPicking] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)
  const swipeFrom = useRef<number | null>(null)
  const root = useRef<HTMLDivElement>(null)
  const photo = photoAt(index)

  // The filmstrip's far end: asking for it pages the feed forward (keyset: always in order).
  useEffect(() => {
    if (total > 0) need?.(Math.min(total - 1, index + FILMSTRIP_RADIUS))
  }, [need, index, total])

  // Focus moves into the viewer, so its keys are its own and the grid's stay with the grid.
  useEffect(() => {
    root.current?.focus({ preventScroll: true })
    // ⚠ Twice: a Radix menu whose "Open" mounted this viewer hands focus back to its trigger in a
    // setTimeout(0) after it closes — later than this effect — which would leave every viewer key dead
    // (focus behind the viewer, where neither the viewer nor the grid takes keys). The second focus
    // runs after that timer (scheduled later), and only if focus is not already inside.
    const retake = window.setTimeout(() => {
      const el = root.current
      if (el && !el.contains(document.activeElement)) el.focus({ preventScroll: true })
    }, 0)
    return () => window.clearTimeout(retake)
  }, [])

  const closeEmpty = useRef(onClose)
  useEffect(() => {
    closeEmpty.current = onClose
  })
  useEffect(() => {
    if (total === 0) closeEmpty.current(0)
  }, [total])

  function jump(next: number) {
    if (next < 0 || next >= total || next === index) return
    if (onMove) onMove(next)
    else setOwn(next)
    setOutcome(null)
  }
  const go = (delta: number) => jump(index + delta)

  // Latest `go`/`onClose` for the one keydown listener.
  // ⚠ Not while the album picker is open: its Escape closes the picker, not the viewer.
  const keys = useRef({ go, close: () => onClose(index), blocked: picking })
  useEffect(() => {
    keys.current = { go, close: () => onClose(index), blocked: picking }
  })
  // ⚠⚠ Drive's FileViewer rules (web-apps-layout.md §4.3): skip a key already handled (a Radix
  // dialog or menu, or the grid whose Enter/Space just opened this viewer — React can install this
  // listener before that same event reaches the window), and a key from outside the viewer (the
  // album picker is portalled out of it). Space and ←/→ on a focused video are the player's.
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.defaultPrevented || keys.current.blocked) return
      const target = event.target as HTMLElement | null
      const el = root.current
      if (target instanceof Node && target !== document.body && el && !el.contains(target)) return
      const tag = target?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) return
      if (tag === 'VIDEO' && (event.key === ' ' || event.key === 'ArrowLeft' || event.key === 'ArrowRight')) return
      if (event.key === 'ArrowRight') keys.current.go(1)
      else if (event.key === 'ArrowLeft') keys.current.go(-1)
      // Space toggles the viewer (§3.2 Quick Look); preventDefault, or a focused button is clicked.
      else if (event.key === 'Escape' || event.key === ' ') keys.current.close()
      else return
      event.preventDefault()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  async function trash(target: Photo) {
    const result = await attempt(() => trashPhoto(target.path))
    if (result) {
      setOutcome(result)
      return
    }
    onTrashed?.(target)
    onClose(index)
  }

  return (
    <div
      ref={root}
      role="dialog"
      aria-modal="true"
      aria-label={photo?.name ?? t('viewer.title')}
      tabIndex={-1}
      className="fixed inset-0 z-20 flex bg-black text-white focus:outline-none"
      onPointerDown={(e) => {
        swipeFrom.current = e.clientX
      }}
      onPointerUp={(e) => {
        const from = swipeFrom.current
        swipeFrom.current = null
        if (from === null) return
        const dx = e.clientX - from
        if (dx <= -SWIPE_PX) go(1)
        else if (dx >= SWIPE_PX) go(-1)
      }}
    >
      <div className="relative flex min-w-0 flex-1 flex-col">
        <div className="flex items-center gap-2 p-3">
          <Button variant="ghost" className="text-white hover:bg-white/10" onClick={() => onClose(index)}>
            {t('viewer.back')}
          </Button>
          <span className="min-w-0 flex-1 truncate text-sm text-white/80">{photo?.name}</span>
          <span className="shrink-0 text-xs text-white/50">{t('viewer.position', { index: index + 1, total })}</span>
          {photo && <ViewerActions photo={photo} albumId={albumId} onAdd={() => setPicking(true)} onTrash={() => void trash(photo)} onOutcome={setOutcome} />}
          <Button variant="ghost" className="text-white hover:bg-white/10" aria-pressed={showDetails} onClick={() => setShowDetails((v) => !v)}>
            {t('viewer.details')}
          </Button>
        </div>
        {outcome && (
          <div className="px-4">
            <OutcomeText outcome={outcome} />
          </div>
        )}
        <div className="relative flex min-h-0 flex-1 items-center justify-center">
          {photo ? (
            photo.isVideo ? <VideoStage key={photo.path} photo={photo} /> : <StillStage key={photo.path} photo={photo} />
          ) : (
            <p className="text-sm text-white/60">{t('common.loading')}</p>
          )}
          <button
            type="button"
            aria-label={t('viewer.previous')}
            disabled={index <= 0}
            onClick={() => go(-1)}
            className="absolute left-2 top-1/2 -translate-y-1/2 rounded-full bg-white/10 px-3 py-2 text-xl disabled:opacity-20"
          >
            ‹
          </button>
          <button
            type="button"
            aria-label={t('viewer.next')}
            disabled={index >= total - 1}
            onClick={() => go(1)}
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded-full bg-white/10 px-3 py-2 text-xl disabled:opacity-20"
          >
            ›
          </button>
        </div>
        <Filmstrip total={total} photoAt={photoAt} current={index} onNavigate={jump} />
      </div>
      {showDetails && photo && <DetailsPanel photo={photo} />}
      {picking && photo && (
        <AlbumPicker
          onClose={() => setPicking(false)}
          onPick={(id) => attempt(() => addToAlbum(id, photo.path))}
        />
      )}
    </div>
  )
}

// The neighbours along the bottom (§3.2): a window of ±FILMSTRIP_RADIUS around the open photograph,
// each a button named by the file, the current one ringed and `aria-current`. A neighbour not
// loaded yet is a placeholder; the viewer's `need` pages it in.
function Filmstrip({
  total,
  photoAt,
  current,
  onNavigate,
}: {
  total: number
  photoAt: (index: number) => Photo | undefined
  current: number
  onNavigate: (index: number) => void
}) {
  const { t } = useTranslation()
  const currentRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    // ⚠ jsdom has no scrollIntoView.
    const el = currentRef.current
    if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest', inline: 'center' })
  }, [current])
  if (total <= 1) return null
  const from = Math.max(0, current - FILMSTRIP_RADIUS)
  const to = Math.min(total - 1, current + FILMSTRIP_RADIUS)
  const indices = Array.from({ length: to - from + 1 }, (_, k) => from + k)
  return (
    <nav
      aria-label={t('viewer.filmstrip')}
      className="border-t border-white/10 px-3 py-2"
      // A drag along the strip scrolls it; it is not a swipe to the neighbour.
      onPointerDown={(e) => e.stopPropagation()}
      onPointerUp={(e) => e.stopPropagation()}
    >
      <ul className="flex items-center gap-1.5 overflow-x-auto">
        {indices.map((i) => {
          const neighbour = photoAt(i)
          return (
            <li key={neighbour?.path ?? `i${i}`} className="shrink-0">
              {neighbour ? (
                <FilmstripItem
                  ref={i === current ? currentRef : undefined}
                  photo={neighbour}
                  current={i === current}
                  onClick={() => onNavigate(i)}
                />
              ) : (
                <span aria-hidden className="block h-12 w-10 rounded bg-white/5" />
              )}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

function FilmstripItem({
  ref,
  photo,
  current,
  onClick,
}: {
  ref?: Ref<HTMLButtonElement>
  photo: Photo
  current: boolean
  onClick: () => void
}) {
  const own = useRef<HTMLButtonElement>(null)
  const onScreen = useOnScreen(own)
  const url = useThumbnail(previewPathOf(photo), onScreen)
  return (
    <button
      ref={(el) => {
        own.current = el
        if (typeof ref === 'function') ref(el)
        else if (ref) ref.current = el
      }}
      type="button"
      aria-label={photo.name}
      aria-current={current ? 'true' : undefined}
      onClick={onClick}
      className={`block h-12 overflow-hidden rounded bg-white/5 ${current ? 'w-16 ring-2 ring-white' : 'w-10 opacity-70 hover:opacity-100'}`}
    >
      {url && <img src={url} alt="" draggable={false} className="size-full object-cover" />}
    </button>
  )
}

function ViewerActions({
  photo,
  albumId,
  onAdd,
  onTrash,
  onOutcome,
}: {
  photo: Photo
  albumId?: string
  onAdd: () => void
  onTrash: () => void
  onOutcome: (outcome: Outcome) => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  // ⚠ A file predating content addressing has no content id: it is shown, but cannot be filed.
  const fileable = photo.contentId !== ''
  const favourite = useQuery({
    queryKey: queryKeys.isFavourite(photo.contentId),
    queryFn: () => isFavourite(photo.contentId),
    enabled: fileable,
  })
  const isFav = favourite.data?.ok ? favourite.data.favourite : false
  const ghost = 'text-white hover:bg-white/10'

  async function toggleFavourite() {
    const result = await attempt(() => setFavourite(photo.contentId, !isFav))
    onOutcome(result)
    if (!result) await queryClient.invalidateQueries({ queryKey: queryKeys.isFavourite(photo.contentId) })
  }

  async function removeHere(id: string) {
    const result = await attempt(() => removeFromAlbum(id, photo.contentId))
    onOutcome(result)
    if (!result) await queryClient.invalidateQueries({ queryKey: queryKeys.albumPhotos(id) })
  }

  return (
    <>
      {favourite.data && !favourite.data.ok && <ErrorText failure={favourite.data.error} />}
      <Button
        variant="ghost"
        className={ghost}
        disabled={!fileable || !favourite.data?.ok}
        aria-pressed={isFav}
        title={fileable ? undefined : t('viewer.notFileable')}
        onClick={() => void toggleFavourite()}
      >
        {isFav ? t('viewer.unfavourite') : t('viewer.favourite')}
      </Button>
      <Button variant="ghost" className={ghost} disabled={!fileable} title={fileable ? undefined : t('viewer.notFileable')} onClick={onAdd}>
        {t('viewer.addToAlbum')}
      </Button>
      {albumId && fileable && (
        <Button variant="ghost" className={ghost} onClick={() => void removeHere(albumId)}>
          {t('viewer.removeFromAlbum')}
        </Button>
      )}
      <Button variant="ghost" className={ghost} onClick={onTrash}>
        {t('viewer.trash')}
      </Button>
    </>
  )
}

function useViewportPx(): { width: number; height: number } {
  const ratio = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1
  const width = typeof window !== 'undefined' ? window.innerWidth : 1024
  const height = typeof window !== 'undefined' ? window.innerHeight : 768
  return { width: Math.round(width * ratio), height: Math.round(height * ratio) }
}

// A photograph: the largest stored rung `getLargePreview` finds for the viewport. ⚠⚠ The original
// is never fetched to be drawn; when the covering rung is absent, "Download original" is offered.
function StillStage({ photo, offerDownload = true }: { photo: Photo; offerDownload?: boolean }) {
  const { t } = useTranslation()
  const viewport = useViewportPx()
  const px = heroPreviewRequestPx(viewport.width, viewport.height, photo.pixelWidth, photo.pixelHeight)
  const preview = useQuery({
    // ⚠ ED-3: the edit's rendition when there is one — never the original (§12.4).
    queryKey: queryKeys.largePreview(previewPathOf(photo), px),
    queryFn: () => getLargePreview(previewPathOf(photo), px),
    // ⚠ An absent rung is often a wait (it syncs from the minting device): not cached for the
    // session, so reopening asks again.
    staleTime: 0,
    gcTime: 0,
  })
  const plan = preview.data ? stillPlan(preview.data) : null
  const url = useMemo(
    () => (plan?.kind === 'rung' ? URL.createObjectURL(bytesToBlob(plan.bytes, 'image/jpeg')) : null),
    // `plan` is derived from `preview.data`; the data's identity is the change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [preview.data],
  )
  useEffect(() => {
    if (!url) return
    return () => URL.revokeObjectURL(url)
  }, [url])

  if (preview.isPending) return <p className="text-sm text-white/60">{t('common.loading')}</p>
  if (preview.error) return <ErrorText error={preview.error} />
  if (!plan) return null
  if (plan.kind === 'failed') return <ErrorText failure={plan.error} />

  return (
    <div className="flex size-full flex-col items-center justify-center gap-3">
      {url ? (
        <img src={url} alt={photo.name} draggable={false} className="max-h-full max-w-full object-contain" />
      ) : (
        <p className="text-sm text-white/70">{t('viewer.noPreview')}</p>
      )}
      {offerDownload && plan.offerOriginal && <DownloadOriginal photo={photo} />}
    </div>
  )
}

function DownloadOriginal({ photo }: { photo: Photo }) {
  const { t } = useTranslation()
  const [state, setState] = useState<DownloadOutcome | 'working' | null>(null)
  return (
    <div className="flex items-center gap-3 rounded-lg bg-white/10 px-3 py-2 text-sm">
      <span className="text-white/80">{t('viewer.originalNotShown')}</span>
      <Button
        size="sm"
        variant="outline"
        className="border-white/30 text-white hover:bg-white/10"
        disabled={state === 'working'}
        onClick={async () => {
          setState('working')
          // ⚠⚠ ED-3: an edited photo hands out its RENDITION, never the original as a fallback — the
          // original of a redacted edit is what the edit hides (photos-editing.md §12.4).
          setState(await downloadOriginal(previewPathOf(photo), photo.name))
        }}
      >
        {state === 'working' ? t('common.working') : t('viewer.downloadOriginal')}
      </Button>
      {state !== null && state !== 'working' && state.kind === 'saved' && <span role="status">{t('viewer.downloaded')}</span>}
      {state !== null && state !== 'working' && state.kind === 'failed' && <ErrorText failure={{ kind: state.errorKind }} />}
    </div>
  )
}

// A video: its MOTION loop (or poster rung) until Play, then Drive's streaming path — the Service
// Worker answers the `<video>`'s Range requests a window at a time, so the file is never in memory.
function VideoStage({ photo }: { photo: Photo }) {
  const { t } = useTranslation()
  const [stream, setStream] = useState<VideoStream | null>(null)
  const [state, setState] = useState<'idle' | 'opening' | 'unavailable'>('idle')
  const clip = useMotionClip(stream ? null : photo.path)

  useEffect(() => {
    if (!stream) return
    return () => {
      void stream.close()
    }
  }, [stream])

  async function play() {
    setState('opening')
    try {
      const opened = await openVideoStream(photo.path, videoMime(photo.name))
      if (opened) setStream(opened)
      else setState('unavailable')
    } catch {
      setState('unavailable')
    }
  }

  if (stream) {
    // Focused once it mounts: Play's button is gone, and with focus on <body> Space would close the
    // viewer and ←/→ leave the video, instead of pausing and seeking it.
    return (
      <video
        ref={(el) => el?.focus({ preventScroll: true })}
        src={stream.url}
        controls
        autoPlay
        playsInline
        className="max-h-full max-w-full"
        aria-label={photo.name}
      />
    )
  }
  return (
    <div className="flex flex-col items-center gap-3">
      {clip ? (
        <video src={clip} autoPlay loop muted playsInline className="max-h-[60vh] max-w-full" data-testid="motion-loop" />
      ) : (
        // A video's original is played, not downloaded: no "Download original" under its poster.
        <StillStage photo={photo} offerDownload={false} />
      )}
      <Button disabled={state === 'opening'} onClick={() => void play()}>
        {state === 'opening' ? t('common.working') : t('viewer.play')}
      </Button>
      {state === 'unavailable' && <p role="alert" className="text-sm text-danger">{t('viewer.cannotPlay')}</p>}
    </div>
  )
}

function DetailsPanel({ photo }: { photo: Photo }) {
  const { t, i18n } = useTranslation()
  const details = useQuery({ queryKey: queryKeys.details(photo.path), queryFn: () => photoDetails(photo.path) })
  const albums = useQuery({
    queryKey: queryKeys.albumsFor(photo.contentId),
    queryFn: () => albumsFor(photo.contentId),
    enabled: photo.contentId !== '',
  })
  const data = details.data
  const rows: [string, string][] = []
  if (data?.ok && data.details) {
    const d = data.details
    if (d.capturedAtMs !== null)
      rows.push([t('details.taken'), new Intl.DateTimeFormat(i18n.language, { dateStyle: 'full', timeStyle: 'short' }).format(d.capturedAtMs)])
    if (d.pixelWidth !== null && d.pixelHeight !== null) rows.push([t('details.dimensions'), `${d.pixelWidth} × ${d.pixelHeight}`])
    if (d.sizeBytes !== null) rows.push([t('details.size'), formatBytes(d.sizeBytes, i18n.language)])
    const camera = [d.cameraMake, d.cameraModel].filter(Boolean).join(' ')
    if (camera) rows.push([t('details.camera'), camera])
    if (d.durationMs !== null) rows.push([t('details.duration'), formatDuration(d.durationMs)])
    if (d.latE7 !== null && d.lngE7 !== null) rows.push([t('details.location'), formatCoordinate(d.latE7, d.lngE7)])
  }
  return (
    <aside aria-label={t('viewer.details')} className="w-72 shrink-0 overflow-y-auto border-l border-white/10 bg-neutral-900 p-4 text-sm">
      <h2 className="mb-3 font-semibold">{t('viewer.details')}</h2>
      {details.isPending && <p className="text-white/60">{t('common.loading')}</p>}
      {details.error && <ErrorText error={details.error} />}
      {data && !data.ok && <ErrorText failure={data.error} />}
      {data?.ok && !data.details && <p className="text-white/60">{t('details.gone')}</p>}
      <dl className="space-y-2">
        <div>
          <dt className="text-white/50">{t('details.name')}</dt>
          <dd className="break-all">{photo.name}</dd>
        </div>
        {rows.map(([label, value]) => (
          <div key={label}>
            <dt className="text-white/50">{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
        {albums.data?.ok && albums.data.albums.length > 0 && (
          <div>
            <dt className="text-white/50">{t('details.albums')}</dt>
            <dd>{albums.data.albums.map((a) => a.name).join(', ')}</dd>
          </div>
        )}
      </dl>
      {albums.error && <ErrorText error={albums.error} />}
      {albums.data && !albums.data.ok && <ErrorText failure={albums.data.error} />}
    </aside>
  )
}

function formatBytes(bytes: number, locale: string): string {
  const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte'] as const
  let value = bytes
  let unit = 0
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000
    unit += 1
  }
  return new Intl.NumberFormat(locale, { style: 'unit', unit: units[unit], maximumFractionDigits: 1 }).format(value)
}
