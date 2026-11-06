// The feed's keyset pager (photos-app.md §43): pages of `photo.feedPage`, each asked with exactly
// the `nextCursor` the previous one returned, held as one growing list of rows.
//
// ⚠⚠ Never the whole library at once. A page is at most `MAX_FEED_PAGE_SIZE` rows (500); the grid
// asks for the next page only when a cell it is about to draw is past the rows held (`ensure`).
// The rows are metadata — paths, times, sizes — and the bytes are the thumbnail cache's, bounded
// on their own (`thumbnailCache.ts`).
//
// ⚠⚠ The cursor is opaque (`v2.<ms>.<lastPath>`): it is passed back verbatim and never built
// here. Keyset paging only walks forward, so a scrubber seek deep into the library walks the pages
// in between (metadata only) — a seek-by-time cursor is listed under "Needs a decision".
//
// ⚠ A row is never shown twice. The Kotlin cursor already guarantees it (its order is total), and
// `seen` holds it here too, so a page that overlaps the last one — a row synced in at the boundary
// millisecond — cannot duplicate a tile.
import type { FeedPageResult, Photo } from '@tenshu/sdk/photos'
import type { Failure } from './errors'

// Rows per request. Under the bridge's 500 cap, and small enough that a cold open draws the first
// screen after one round trip of ~a few hundred kB.
export const FEED_PAGE_SIZE = 250

export type FeedFetch = (cursor: string | null, pageSize: number) => Promise<FeedPageResult>

export type PagerFailure = { failure: Failure } | { error: unknown }

export interface PagerState {
  readonly rows: readonly Photo[]
  readonly done: boolean
  readonly loading: boolean
  readonly failure: PagerFailure | null
}

export class FeedPager {
  private rows: Photo[] = []
  private readonly seen = new Set<string>()
  // The cursor the next request sends: `null` = the head, before the first page.
  private cursor: string | null = null
  private done = false
  private inFlight: Promise<void> | null = null
  private failure: PagerFailure | null = null
  private snapshot: PagerState
  private readonly listeners = new Set<() => void>()
  private readonly fetchPage: FeedFetch
  private readonly pageSize: number

  constructor(fetchPage: FeedFetch, pageSize: number = FEED_PAGE_SIZE) {
    this.fetchPage = fetchPage
    this.pageSize = pageSize
    this.snapshot = this.freeze()
  }

  // `useSyncExternalStore` pair: a stable snapshot that changes identity only on change.
  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  readonly getState = (): PagerState => this.snapshot

  /** Fetch the next page, unless one is in flight, the feed is exhausted, or the last one failed. */
  loadNext(): Promise<void> {
    if (this.inFlight) return this.inFlight
    if (this.done || this.failure) return Promise.resolve()
    const asked = this.cursor
    this.inFlight = this.fetchPage(asked, this.pageSize)
      .then((page) => {
        if (!page.ok) {
          this.failure = { failure: page.error }
          return
        }
        for (const photo of page.photos) {
          if (this.seen.has(photo.path)) continue
          this.seen.add(photo.path)
          this.rows.push(photo)
        }
        // ⚠ A cursor that did not move would ask for the same page forever: treat it as the end.
        if (page.nextCursor === null || page.nextCursor === asked) this.done = true
        else this.cursor = page.nextCursor
      })
      .catch((error: unknown) => {
        this.failure = { error }
      })
      .finally(() => {
        this.inFlight = null
        this.emit()
      })
    this.emit()
    return this.inFlight
  }

  /** Load pages until row [index] is held, the feed ends, or a page fails. */
  async ensure(index: number): Promise<void> {
    while (this.rows.length <= index && !this.done && !this.failure) {
      await this.loadNext()
    }
  }

  /** Clear a failure and carry on from the same cursor: the rows already held stay. */
  retry(): Promise<void> {
    this.failure = null
    this.emit()
    return this.loadNext()
  }

  private emit() {
    this.snapshot = this.freeze()
    for (const listener of this.listeners) listener()
  }

  private freeze(): PagerState {
    return {
      rows: this.rows.slice(),
      done: this.done,
      loading: this.inFlight !== null,
      failure: this.failure,
    }
  }
}
