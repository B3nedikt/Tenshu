// The app's thumbnail caches and the hook a tile reads them through (see `thumbnailCache.ts` for
// the bound). Every tile shows the `LARGE` rung first: `fs.getPreview` reaches LIST and LARGE only,
// never XLARGE (`drive-web-parity.md` §1.2i). A tile `LARGE` cannot cover on this screen (`planTile`:
// a Retina screen, Days or Months) then asks `fs.getTilePreview` for `XLARGE` — pulled and dropped
// again by the runtime — scales it to the tile in a worker (`scaleTile`) and swaps it in, so
// scrolling never shows an empty tile.
import { createContext, useContext, useEffect, useState, type RefObject } from 'react'
import { getPreview, getTilePreview } from './proxy'
import { sniffImageMime } from './imageMime'
import { ThumbnailCache } from './thumbnailCache'
import { scaleTile } from './downscale'
import type { TilePlan } from './tileSize'

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

// Paths whose `XLARGE` the relay confirmed ABSENT, and when: their tiles stay on `LARGE` without
// asking again on every scroll past — for a while. ⚠ Absent is often a wait (a byte holder backfills
// the class this browser declares wanted), so it is forgotten after [ABSENT_FOR_MS]. ⚠ `unknown`
// (a failed read or pull) is never remembered.
const ABSENT_FOR_MS = 10 * 60 * 1000
const largeAbsent = new Map<string, number>()

// The sharp tile's cache key: the path, the square it is scaled to (a density switch is a new one)
// and whether it is cropped to it or fit within it (`f`, the whole-photo layout).
export function sharpKey(path: string, devicePx: number, fit = false): string {
  return `${fit ? 'f' : 'c'}${devicePx}\u0000${path}`
}

export async function loadSharpTile(key: string): Promise<Blob | null> {
  const split = key.indexOf('\u0000')
  const fit = key.startsWith('f')
  const devicePx = Number.parseInt(key.slice(1, split), 10)
  const path = key.slice(split + 1)
  const absentAt = largeAbsent.get(path)
  if (absentAt !== undefined && Date.now() - absentAt < ABSENT_FOR_MS) return null
  // ⚠ Never kept by the runtime: a pulled rung is dropped once read (`fs.getTilePreview`), because
  // the browser's store holds every byte in memory. Only the scaled copy below survives, in this
  // cache's bounded LRU.
  const result = await getTilePreview(path)
  if (!result.ok) throw new Error(`${result.error.kind}`)
  if (result.coveringRung === 'absent') largeAbsent.set(path, Date.now())
  else largeAbsent.delete(path)
  // A stand-in (`large` / `list`) is what the tile already shows: no upgrade.
  if (!result.bytes || (result.thumbnailClass !== 'xlarge' && result.thumbnailClass !== 'xxlarge')) return null
  return scaleTile(bytesToBlob(result.bytes, 'image/jpeg'), devicePx, fit)
}

/** Forget the confirmed-absent paths (tests). */
export function resetSharpTiles(): void {
  largeAbsent.clear()
}

// A test (or a later shell) provides its own caches; the app uses one pair module-wide.
export const ThumbnailCacheContext = createContext<ThumbnailCache | null>(null)
export const SharpTileCacheContext = createContext<ThumbnailCache | null>(null)

let appCache: ThumbnailCache | null = null
function defaultCache(): ThumbnailCache {
  if (!appCache) appCache = new ThumbnailCache(loadTile)
  return appCache
}
// ⚠ Fewer at once than the small tiles: each is a pull of ~140 KB and a decode.
let appSharpCache: ThumbnailCache | null = null
function defaultSharpCache(): ThumbnailCache {
  if (!appSharpCache) appSharpCache = new ThumbnailCache(loadSharpTile, { maxInFlight: 3 })
  return appSharpCache
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

/**
 * [path]'s tile URL while [active]: the sharp one once it is ready when [plan] asks for `XLARGE`,
 * else (and until then) `LARGE`.
 */
export function useTileUrl(path: string | null, active: boolean, plan: TilePlan): string | null {
  const large = useThumbnail(path, active)
  const sharpCache = useContext(SharpTileCacheContext) ?? defaultSharpCache()
  const key = path && plan.rung === 'xlarge' ? sharpKey(path, plan.devicePx, plan.fit) : null
  const sharp = useCachedUrl(sharpCache, key, active)
  return sharp ?? large
}

/** [path]'s tile URL while [active], else `null` — and the reference is released. */
export function useThumbnail(path: string | null, active: boolean): string | null {
  const cache = useContext(ThumbnailCacheContext) ?? defaultCache()
  return useCachedUrl(cache, path, active)
}

function useCachedUrl(cache: ThumbnailCache, path: string | null, active: boolean): string | null {
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
