// The app's one thumbnail cache and the hook a tile reads it through (see `thumbnailCache.ts` for
// the bound). A tile asks for the `LARGE` rung: `fs.getPreview` reaches LIST and LARGE only, never
// XLARGE (`drive-web-parity.md` §1.2i), so a bigger request would be answered by the same class.
import { createContext, useContext, useEffect, useState, type RefObject } from 'react'
import { getPreview } from './proxy'
import { sniffImageMime } from './imageMime'
import { ThumbnailCache } from './thumbnailCache'

// `GetStoredThumbnail.LARGE_PX`.
export const TILE_PREVIEW_PX = 256

export async function loadTile(path: string): Promise<Blob | null> {
  const result = await getPreview(path, TILE_PREVIEW_PX, TILE_PREVIEW_PX)
  if (!result.ok) throw new Error(`${result.error.kind}`)
  const bytes = result.bytes
  if (!bytes) return null
  return bytesToBlob(bytes, 'image/jpeg')
}

// Copy out of the Kotlin/JS heap's buffer and label by the bytes, not the file name: a stored class
// is not the file (a `photo.heic` has a JPEG rung).
export function bytesToBlob(bytes: Int8Array, fallback: string): Blob {
  const copy = new Uint8Array(bytes.length)
  copy.set(bytes)
  return new Blob([copy], { type: sniffImageMime(copy, fallback) })
}

// A test (or a later shell) provides its own cache; the app uses one module-wide.
export const ThumbnailCacheContext = createContext<ThumbnailCache | null>(null)

let appCache: ThumbnailCache | null = null
function defaultCache(): ThumbnailCache {
  if (!appCache) appCache = new ThumbnailCache(loadTile)
  return appCache
}

/**
 * Whether [ref]'s element is on screen. ⚠ NOT sticky, unlike Drive's `useInView`: leaving the
 * screen is what releases a tile's URL. Without `IntersectionObserver` (jsdom) it reports visible,
 * so the virtualised grid alone bounds what is mounted.
 */
export function useOnScreen<T extends Element>(ref: RefObject<T | null>, rootMargin = '200px'): boolean {
  const [onScreen, setOnScreen] = useState(typeof IntersectionObserver === 'undefined')
  useEffect(() => {
    const el = ref.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      const last = entries[entries.length - 1]
      if (last) setOnScreen(last.isIntersecting)
    }, { rootMargin })
    observer.observe(el)
    return () => observer.disconnect()
  }, [ref, rootMargin])
  return onScreen
}

/** [path]'s tile URL while [active], else `null` — and the reference is released. */
export function useThumbnail(path: string | null, active: boolean): string | null {
  const cache = useContext(ThumbnailCacheContext) ?? defaultCache()
  const [state, setState] = useState<{ path: string; url: string | null } | null>(null)
  useEffect(() => {
    if (!path || !active) return
    let live = true
    const release = cache.acquire(path, (url) => {
      if (live) setState({ path, url })
    })
    return () => {
      live = false
      release()
    }
  }, [cache, path, active])
  // ⚠ Derived, so a tile that left the screen renders no `src` at once (its bitmap can be dropped)
  // and a recycled tile never flashes its previous photograph.
  return active && state?.path === path ? state.url : null
}
