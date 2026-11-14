import { useCallback, useEffect, useState } from 'react'
import { getData, getLargePreview } from './proxy'
import { openVideoStream } from './videoStream'
import {
  buildPreview,
  buildStandInPreview,
  download,
  previewKind,
  TEXT_PREVIEW_MAX_BYTES,
  type Preview,
} from './preview'
import { needsOriginal } from './largePreview'
import { basename } from './paths'

// Opening a personal file for display: the one place that decides which bytes a viewer shows.
// Both the lightbox opener (`useFileOpener`) and the full-screen viewer (`FileViewer`) go through
// `loadPreview`; `usePreview` is the viewer's path-keyed hook over it.

// The edge the viewer asks for. ⚠ Above `XLARGE`'s 1024 px, so the SDK names XXLARGE and a
// retina viewport gets the 2048 px rung rather than a 1024 px one stretched to fit.
export const VIEWER_PX = 2048

export type Loaded =
  | { status: 'preview'; preview: Preview }
  // Nothing to show inline (a binary, an oversized text file). The viewer offers Download.
  | { status: 'unpreviewable'; name: string }
  // The opener's mode downloaded the file itself; there is nothing left to show.
  | { status: 'downloaded' }
  | { status: 'error'; message: string }

// Free what a preview holds: its object URL (a no-op for the virtual video stream URL) and any
// `cleanup` (the video kind closes its proxy-side streaming session).
export function disposePreview(preview: Preview) {
  URL.revokeObjectURL(preview.url)
  preview.cleanup?.()
}

/**
 * Read what to show for [path].
 *
 * [autoDownload] is the lightbox's behaviour: a kind that cannot be shown is fetched and saved
 * straight away. Without it (the viewer) such a kind is reported as `unpreviewable` — and a kind
 * that is unpreviewable by NAME is not fetched at all, since the viewer only ever shows a Download
 * button for it and the original is read when that is pressed.
 */
export async function loadPreview(
  path: string,
  { autoDownload }: { autoDownload: boolean },
): Promise<Loaded> {
  const name = basename(path)
  const { kind, mime } = previewKind(name)

  // Video streams on demand (D2b) — open a Service-Worker-backed session instead of loading the
  // whole file into memory. Everything else reads its bytes whole.
  if (kind === 'video') {
    const stream = await openVideoStream(path, mime)
    if (!stream) return { status: 'error', message: `Couldn't open “${name}”.` }
    return {
      status: 'preview',
      preview: { name, url: stream.url, kind: 'video', cleanup: () => void stream.close() },
    }
  }

  if (kind === 'none' && !autoDownload) return { status: 'unpreviewable', name }

  // X-5: for an image, try the stored large classes before the original
  // (`drive-xxlarge-thumbnail-rung.md` §4.8). A 2048 px rung is ~0.5 MB against 6–18 MB, and on a
  // device that holds neither it is one relay read instead of a whole file.
  //
  // ⚠⚠ The original is still fetched when `needsOriginal` says the covering class is CONFIRMED
  // absent and what we got is smaller than the viewport. Nothing on web runs the thumbnail
  // backfill, so in a web-only realm an old file may never have a large class at all — without
  // that fallback this would swap a sharp preview for a permanent blur.
  if (kind === 'image') {
    // ⚠⚠ Guarded, and not merely for tidiness: the apex the browser has cached may predate this
    // bridge method. An unknown method REJECTS rather than answering `{ ok: false }`, so an
    // unguarded call would turn "your apex is a version behind" into "this file will not open".
    // ⚠ try/catch, not `.catch(...)`: an apex that does not know the method can fail
    // synchronously, before any promise exists, and a rejection handler never sees that.
    let large: Awaited<ReturnType<typeof getLargePreview>> | null
    try {
      large = await getLargePreview(path, VIEWER_PX)
    } catch {
      large = null
    }
    if (large?.ok && large.bytes && !needsOriginal(large, VIEWER_PX)) {
      return { status: 'preview', preview: buildStandInPreview(path, name, large.bytes) }
    }
  }

  const read = await getData(path)
  if (!read.ok) return { status: 'error', message: `Couldn't open “${name}” (${read.error.kind}).` }
  const bytes = read.bytes
  if (!bytes) return { status: 'error', message: `“${name}” isn't downloaded to this device yet.` }

  // `buildPreview` downloads a kind it cannot render itself, so the viewer's mode decides that
  // case before handing it the bytes.
  if (!autoDownload && kind === 'text' && bytes.length > TEXT_PREVIEW_MAX_BYTES) {
    return { status: 'unpreviewable', name }
  }
  const built = buildPreview(name, bytes)
  return built ? { status: 'preview', preview: built } : { status: 'downloaded' }
}

/**
 * Fetch the ORIGINAL bytes of [path] and save them as [name]; returns an error message, or null.
 *
 * ⚠⚠ X-5: the only correct Download when the preview on screen is a stand-in (a thumbnail class)
 * — linking that preview's URL would save a 2048 px JPEG under the original's name.
 */
export async function saveOriginal(path: string, name: string): Promise<string | null> {
  const read = await getData(path)
  if (!read.ok) return `Couldn't download “${name}” (${read.error.kind}).`
  if (!read.bytes) return `“${name}” isn't downloaded to this device yet.`
  const copy = new Uint8Array(read.bytes.length)
  copy.set(read.bytes)
  const url = URL.createObjectURL(new Blob([copy], { type: previewKind(name).mime }))
  download(url, name)
  URL.revokeObjectURL(url)
  return null
}

export interface PreviewHandle {
  // What to show for `path`, once loaded; null while loading, on error, or when unpreviewable.
  readonly preview: Preview | null
  // The file loaded but cannot be shown inline — render "No preview" + Download.
  readonly unpreviewable: boolean
  readonly loading: boolean
  readonly error: string | null
  readonly downloading: boolean
  readonly downloadError: string | null
  // Fetch the original and save it (the stand-in-safe Download).
  readonly downloadOriginal: () => Promise<void>
}

type LoadState = { path: string; loaded: Loaded }
type DownloadState = { path: string; busy: boolean; error: string | null }

/**
 * The viewer's file, keyed by [path]. A new path starts a new load; a result for a path that is no
 * longer current is discarded (and freed). The current preview's object URL is revoked and its
 * `cleanup` run when the path changes or the hook unmounts.
 */
export function usePreview(path: string | null): PreviewHandle {
  const [state, setState] = useState<LoadState | null>(null)
  const [dl, setDl] = useState<DownloadState | null>(null)

  useEffect(() => {
    if (path === null) return
    let cancelled = false
    // The preview THIS run produced — freed by this run's cleanup, whichever comes first.
    let mine: Preview | null = null
    loadPreview(path, { autoDownload: false })
      .catch((e: unknown): Loaded => ({
        status: 'error',
        message: `Couldn't open “${basename(path)}” (${e instanceof Error ? e.message : String(e)}).`,
      }))
      .then((loaded) => {
        if (cancelled) {
          // ⚠ Stale: the user moved on while this was loading. Never shown, so free it now.
          if (loaded.status === 'preview') disposePreview(loaded.preview)
          return
        }
        if (loaded.status === 'preview') mine = loaded.preview
        setState({ path, loaded })
      })
    return () => {
      cancelled = true
      const freed = mine
      if (!freed) return
      disposePreview(freed)
      // ⚠ Drop the freed preview from state too: on A → B → A before B loads, the state would
      // otherwise still read "A, loaded" and draw A's revoked URL until the new load lands.
      setState((prev) =>
        prev?.loaded.status === 'preview' && prev.loaded.preview === freed ? null : prev,
      )
    }
  }, [path])

  // Only a state loaded for the CURRENT path counts; anything else is the previous file's.
  const current = state && state.path === path ? state.loaded : null
  const currentDl = dl && dl.path === path ? dl : null

  const downloadOriginal = useCallback(async () => {
    if (path === null) return
    setDl({ path, busy: true, error: null })
    let error: string | null
    try {
      error = await saveOriginal(path, basename(path))
    } catch (e) {
      error = `Couldn't download “${basename(path)}” (${e instanceof Error ? e.message : String(e)}).`
    }
    setDl((prev) => (prev && prev.path === path ? { path, busy: false, error } : prev))
  }, [path])

  return {
    preview: current?.status === 'preview' ? current.preview : null,
    unpreviewable: current?.status === 'unpreviewable',
    loading: path !== null && current === null,
    error: current?.status === 'error' ? current.message : null,
    downloading: currentDl?.busy ?? false,
    downloadError: currentDl?.error ?? null,
    downloadOriginal,
  }
}
