import { useEffect, useRef, type ReactNode } from 'react'
import type { File as FsEntry } from '@tenshu/sdk/files'
import { usePreview } from '../lib/usePreview'
import { previewKind, type Preview } from '../lib/preview'
import { basename, formatDate, parentDir } from '../lib/paths'
import { FileThumbnail } from './FileThumbnail'

// The full-screen file viewer (web-apps-layout.md §3.2, L3): `FilePreview` grown into the native
// shape — back button, name · "n of N" · date, Share · Download · Delete, prev/next arrows, a
// filmstrip of the folder's files, and the details pane beside it when ⓘ is on.
//
// ⚠ It owns no navigation state: which file is open is the caller's URL (D6 — opening the viewer
// pushes, so browser Back closes it), and prev/next/filmstrip only ask for a new `path`.
export function FileViewer({
  files,
  path,
  onNavigate,
  onClose,
  onShare,
  onDelete,
  detailsOpen,
  onToggleDetails,
  details,
}: {
  files: readonly FsEntry[]
  path: string
  onNavigate: (path: string) => void
  onClose: () => void
  onShare: (path: string) => void
  onDelete: (path: string) => void
  detailsOpen: boolean
  onToggleDetails: () => void
  details?: ReactNode
}) {
  const name = basename(path)
  const index = files.findIndex((f) => f.path === path)
  const entry = index >= 0 ? files[index] : undefined
  const prev = index > 0 ? files[index - 1] : undefined
  const next = index >= 0 && index < files.length - 1 ? files[index + 1] : undefined
  const isVideo = previewKind(name).kind === 'video'
  const date = entry ? formatDate(entry.lastModified) : ''
  const folder = basename(parentDir(path))

  const handle = usePreview(path)
  const { preview } = handle

  const dialogRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    dialogRef.current?.focus()
    // ⚠ Twice: a Radix menu whose "Open" mounted this viewer hands focus back to its trigger in a
    // setTimeout(0) after it closes — later than this effect — which would leave every viewer key dead
    // (focus behind the viewer, where neither the viewer nor the grid takes keys). The second focus
    // runs after that timer (scheduled later), and only if focus is not already inside.
    const retake = window.setTimeout(() => {
      const el = dialogRef.current
      if (el && !el.contains(document.activeElement)) el.focus()
    }, 0)
    return () => window.clearTimeout(retake)
  }, [])

  // The keys read the latest props through a ref, so the listener is installed once.
  const keys = useRef({ prev, next, onNavigate, onClose })
  useEffect(() => {
    keys.current = { prev, next, onNavigate, onClose }
  })
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      // Already handled — by a Radix dialog or menu, or by the list whose Space/Enter just opened
      // this viewer (React can install this listener before that same event reaches the window).
      if (event.defaultPrevented) return
      const target = event.target as HTMLElement | null
      // A key typed in a dialog the viewer opened (Share, the delete confirm) is that dialog's:
      // they are portalled outside the viewer, so anything outside it (bar the page itself) is not ours.
      const root = dialogRef.current
      if (target instanceof Node && target !== document.body && root && !root.contains(target)) return
      const tag = target?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target?.isContentEditable) {
        return
      }
      // Space plays/pauses and ←/→ seek on a focused video — leave them to the player.
      if (tag === 'VIDEO' && (event.key === ' ' || event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
        return
      }
      const k = keys.current
      if (event.key === 'Escape') {
        event.preventDefault()
        k.onClose()
      } else if (event.key === ' ') {
        // Space toggles the viewer (§3.2 Quick Look). ⚠ preventDefault, or a focused button
        // would also be clicked and the page scroll.
        event.preventDefault()
        k.onClose()
      } else if (event.key === 'ArrowLeft' && k.prev) {
        event.preventDefault()
        k.onNavigate(k.prev.path)
      } else if (event.key === 'ArrowRight' && k.next) {
        event.preventDefault()
        k.onNavigate(k.next.path)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const barButton =
    'shrink-0 rounded-lg px-3 py-1.5 text-sm font-medium text-white/90 hover:bg-white/10 disabled:opacity-50'

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-label={name}
      tabIndex={-1}
      className="fixed inset-0 z-30 flex flex-col bg-neutral-950 text-white outline-none"
    >
      <header className="flex items-center gap-2 border-b border-white/10 px-3 py-2">
        <button
          type="button"
          onClick={onClose}
          aria-label={`Back to ${folder}`}
          className={`${barButton} max-w-[30%] truncate`}
        >
          ‹ {folder}
        </button>
        <div className="min-w-0 flex-1 text-center">
          <h2 className="truncate text-sm font-medium">{name}</h2>
          <p className="truncate text-xs text-white/60">
            {[index >= 0 ? `${index + 1} of ${files.length}` : '', date].filter(Boolean).join(' · ')}
          </p>
        </div>
        <button
          type="button"
          onClick={onToggleDetails}
          aria-pressed={detailsOpen}
          aria-label="Details"
          className={`${barButton} ${detailsOpen ? 'bg-white/15' : ''}`}
        >
          ⓘ
        </button>
        <button type="button" onClick={() => onShare(path)} className={barButton}>
          Share
        </button>
        {/* The video kind streams a virtual URL — a plain download would get only one window's
            206 — so it has no Download. */}
        {!isVideo && (
          <DownloadControl
            preview={preview}
            downloading={handle.downloading}
            onDownloadOriginal={() => void handle.downloadOriginal()}
            className={barButton}
          />
        )}
        <button type="button" onClick={() => onDelete(path)} className={barButton}>
          Delete
        </button>
      </header>

      <div className="flex min-h-0 flex-1">
        <div className="relative flex min-w-0 flex-1 items-center justify-center p-4">
          <ViewerBody
            name={name}
            preview={preview}
            loading={handle.loading}
            error={handle.error}
            unpreviewable={handle.unpreviewable}
            downloading={handle.downloading}
            onDownloadOriginal={() => void handle.downloadOriginal()}
          />
          {handle.downloadError && (
            <p role="alert" className="absolute inset-x-0 bottom-4 text-center text-sm text-red-300">
              {handle.downloadError}
            </p>
          )}
          <ArrowButton side="left" label="Previous file" target={prev} onNavigate={onNavigate} />
          <ArrowButton side="right" label="Next file" target={next} onNavigate={onNavigate} />
        </div>
        {detailsOpen && details !== undefined && (
          <aside
            aria-label="File details"
            className="w-80 shrink-0 overflow-y-auto border-l border-line bg-surface-2 p-5 text-text"
          >
            {details}
          </aside>
        )}
      </div>

      <Filmstrip files={files} current={path} onNavigate={onNavigate} />
    </div>
  )
}

// ⚠⚠ X-5: a stand-in preview is a thumbnail class, not the file, so its Download fetches the
// original (`downloadOriginal`); only a preview of the file's own bytes is linked directly. With
// nothing loaded yet (or nothing previewable) the original is fetched too.
function DownloadControl({
  preview,
  downloading,
  onDownloadOriginal,
  className,
}: {
  preview: Preview | null
  downloading: boolean
  onDownloadOriginal: () => void
  className: string
}) {
  if (preview && !preview.standIn) {
    return (
      <a href={preview.url} download={preview.name} className={className}>
        Download
      </a>
    )
  }
  return (
    <button type="button" onClick={onDownloadOriginal} disabled={downloading} className={className}>
      {downloading ? 'Downloading…' : 'Download'}
    </button>
  )
}

function ViewerBody({
  name,
  preview,
  loading,
  error,
  unpreviewable,
  downloading,
  onDownloadOriginal,
}: {
  name: string
  preview: Preview | null
  loading: boolean
  error: string | null
  unpreviewable: boolean
  downloading: boolean
  onDownloadOriginal: () => void
}) {
  if (loading) return <p className="text-sm text-white/60">Opening “{name}”…</p>
  if (error) return <p role="alert" className="text-sm text-red-300">{error}</p>
  if (unpreviewable || !preview) {
    return (
      <div className="flex flex-col items-center gap-3 text-sm text-white/70">
        <p>No preview</p>
        <button
          type="button"
          onClick={onDownloadOriginal}
          disabled={downloading}
          className="rounded-lg border border-white/20 px-3 py-1.5 font-medium text-white hover:bg-white/10 disabled:opacity-50"
        >
          {downloading ? 'Downloading…' : 'Download'}
        </button>
      </div>
    )
  }
  switch (preview.kind) {
    case 'image':
      return <img src={preview.url} alt={preview.name} draggable={false} className="max-h-full max-w-full object-contain" />
    case 'video':
      // Streamed on demand through the Service Worker (D2b).
      return <video src={preview.url} controls autoPlay className="max-h-full max-w-full" />
    case 'pdf':
      return <iframe src={preview.url} title={preview.name} className="h-full w-full rounded bg-white" />
    default:
      return (
        <pre className="h-full w-full max-w-4xl overflow-auto whitespace-pre-wrap break-words rounded bg-surface-2 p-4 font-mono text-xs text-text">
          {preview.text}
        </pre>
      )
  }
}

function ArrowButton({
  side,
  label,
  target,
  onNavigate,
}: {
  side: 'left' | 'right'
  label: string
  target: FsEntry | undefined
  onNavigate: (path: string) => void
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={!target}
      onClick={() => target && onNavigate(target.path)}
      className={`absolute top-1/2 ${side === 'left' ? 'left-3' : 'right-3'} -translate-y-1/2 rounded-full bg-black/50 px-3 py-2 text-xl text-white hover:bg-black/70 disabled:opacity-20`}
    >
      {side === 'left' ? '‹' : '›'}
    </button>
  )
}

function Filmstrip({
  files,
  current,
  onNavigate,
}: {
  files: readonly FsEntry[]
  current: string
  onNavigate: (path: string) => void
}) {
  const currentRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    // ⚠ jsdom has no scrollIntoView.
    const el = currentRef.current
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' })
    }
  }, [current])

  return (
    <nav aria-label="Files in this folder" className="border-t border-white/10 px-3 py-2">
      <ul className="flex items-center gap-1.5 overflow-x-auto">
        {files.map((file) => {
          const isCurrent = file.path === current
          const fileName = basename(file.path)
          return (
            <li key={file.path} className="shrink-0">
              <button
                ref={isCurrent ? currentRef : undefined}
                type="button"
                aria-label={fileName}
                aria-current={isCurrent ? 'true' : undefined}
                onClick={() => onNavigate(file.path)}
                // FileThumbnail is a 20 px row icon; the strip stretches it to the tile.
                className={`flex h-12 items-center justify-center overflow-hidden rounded bg-white/5 [&>span]:size-full [&_img]:size-full [&_img]:rounded-none ${
                  isCurrent ? 'w-16 ring-2 ring-white' : 'w-10 opacity-70 hover:opacity-100'
                }`}
              >
                <FileThumbnail path={file.path} name={fileName} />
              </button>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
