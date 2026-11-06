// The tile bytes: one object URL per visible tile, a bounded set of recently seen ones, and
// nothing else (web-app-suite Wave 2, Photos run B).
//
// ⚠⚠ The bound is the point. A 10k-photo library must not hold 10k blobs, and a grid that minted a
// URL per tile it ever drew would do exactly that while the user scrolled to 2019. So:
// - a tile ACQUIRES its URL while it is on screen (IntersectionObserver, `useThumbnail`) and
//   RELEASES it when it leaves; its `<img>` drops the `src`, so the decoded bitmap can go too;
// - a released URL is kept, unreferenced, in an LRU of at most `maxRetained`, so scrolling back a
//   screen does not refetch; past that it is revoked and forgotten;
// - a queued fetch whose tile left the screen before its turn is never sent, so a fling past a
//   thousand rows costs the few the user stopped on, not the thousand.
// `thumbnailCache.test.ts` drives 10k tiles through it and pins the live count.
//
// ⚠ `null` (no rung yet, not a decodable image) is not remembered: it is often a wait — the rung
// is minted on another device and has not synced — and a remembered null would hide it all session.

export type ThumbnailLoad = (key: string) => Promise<Blob | null>

interface Entry {
  url: string | null
  refs: number
  loading: boolean
  queued: boolean
  readonly listeners: Set<(url: string | null) => void>
}

export interface ThumbnailCacheOptions {
  // Unreferenced URLs kept for a quick scroll back; past this the oldest is revoked.
  readonly maxRetained?: number
  // Tile reads on the bridge at once.
  readonly maxInFlight?: number
  readonly createUrl?: (blob: Blob) => string
  readonly revokeUrl?: (url: string) => void
}

export interface CacheStats {
  // URLs minted and not yet revoked: the number the bound is about.
  readonly live: number
  readonly inUse: number
  readonly inFlight: number
}

export class ThumbnailCache {
  private readonly entries = new Map<string, Entry>()
  // Keys of entries with a URL and no references, least recently released first.
  private readonly idle = new Set<string>()
  private readonly queue: string[] = []
  private inFlight = 0
  private readonly load: ThumbnailLoad
  private readonly options: ThumbnailCacheOptions

  constructor(load: ThumbnailLoad, options: ThumbnailCacheOptions = {}) {
    this.load = load
    this.options = options
  }

  private get maxRetained() {
    return this.options.maxRetained ?? 150
  }

  private get maxInFlight() {
    return this.options.maxInFlight ?? 6
  }

  /**
   * Take a reference to [key]'s URL. [onUrl] runs with it once it exists (synchronously when it
   * already does) and with `null` when there is none. Returns the release; call it exactly once.
   */
  acquire(key: string, onUrl: (url: string | null) => void): () => void {
    let entry = this.entries.get(key)
    if (!entry) {
      entry = { url: null, refs: 0, loading: false, queued: false, listeners: new Set() }
      this.entries.set(key, entry)
    }
    entry.refs += 1
    this.idle.delete(key)
    const listener = (url: string | null) => onUrl(url)
    if (entry.url) {
      onUrl(entry.url)
    } else {
      entry.listeners.add(listener)
      if (!entry.loading && !entry.queued) {
        entry.queued = true
        this.queue.push(key)
        this.pump()
      }
    }
    let released = false
    return () => {
      if (released) return
      released = true
      this.release(key, listener)
    }
  }

  stats(): CacheStats {
    let live = 0
    let inUse = 0
    for (const entry of this.entries.values()) {
      if (entry.url) live += 1
      if (entry.refs > 0) inUse += 1
    }
    return { live, inUse, inFlight: this.inFlight }
  }

  /** Revoke everything (tests, and a feed reset). */
  clear(): void {
    for (const entry of this.entries.values()) if (entry.url) this.revoke(entry.url)
    this.entries.clear()
    this.idle.clear()
    this.queue.length = 0
  }

  private release(key: string, listener: (url: string | null) => void) {
    const entry = this.entries.get(key)
    if (!entry) return
    entry.listeners.delete(listener)
    entry.refs = Math.max(0, entry.refs - 1)
    if (entry.refs > 0) return
    if (entry.url) {
      this.idle.add(key)
      this.evict()
    } else if (!entry.loading) {
      // Never fetched (still queued) or came back empty: nothing worth keeping.
      this.entries.delete(key)
    }
  }

  private pump() {
    while (this.inFlight < this.maxInFlight && this.queue.length > 0) {
      const key = this.queue.shift()!
      const entry = this.entries.get(key)
      if (!entry) continue
      entry.queued = false
      // ⚠ Scrolled away before its turn: never sent.
      if (entry.refs === 0) {
        this.entries.delete(key)
        continue
      }
      entry.loading = true
      this.inFlight += 1
      void this.load(key)
        .catch((error: unknown) => {
          // A tile is not an error surface (thousands of them, and a missing rung is ordinary),
          // but a FAILED read is not ordinary: logged, not rendered as the same blank.
          console.warn(`thumbnail failed for ${key}: ${String(error)}`)
          return null
        })
        .then((blob) => this.settle(key, entry, blob))
        .finally(() => {
          this.inFlight -= 1
          this.pump()
        })
    }
  }

  private settle(key: string, entry: Entry, blob: Blob | null) {
    entry.loading = false
    if (this.entries.get(key) !== entry) return
    if (!blob) {
      for (const listener of entry.listeners) listener(null)
      // Not remembered: the tile's next acquire (it scrolls back, or the view reopens) asks again.
      this.entries.delete(key)
      return
    }
    entry.url = (this.options.createUrl ?? URL.createObjectURL)(blob)
    for (const listener of entry.listeners) listener(entry.url)
    if (entry.refs === 0) {
      this.idle.add(key)
      this.evict()
    }
  }

  private evict() {
    while (this.idle.size > this.maxRetained) {
      const oldest = this.idle.values().next().value as string
      this.idle.delete(oldest)
      const entry = this.entries.get(oldest)
      this.entries.delete(oldest)
      if (entry?.url) this.revoke(entry.url)
    }
  }

  private revoke(url: string) {
    ;(this.options.revokeUrl ?? URL.revokeObjectURL)(url)
  }
}
