import { describe, expect, it, vi } from 'vitest'
import type { FeedPageResult, Photo } from '@tenshu/sdk/photos'
import { FeedPager, FEED_PAGE_SIZE } from './feedPager'
import { keysetFeed, library } from '../test/proxyMock'

const paths = (rows: readonly Photo[]) => rows.map((p) => p.path)

describe('FeedPager', () => {
  it('asks for the head, then exactly the nextCursor each page returned, and stops at null', async () => {
    const { photos } = library(25)
    const fetch = vi.fn(keysetFeed(photos))
    const pager = new FeedPager(fetch, 10)

    await pager.ensure(24)

    expect(fetch.mock.calls.map(([cursor]) => cursor)).toEqual([
      null,
      `v2.${photos[9]!.capturedAtMs}.${photos[9]!.path}`,
      `v2.${photos[19]!.capturedAtMs}.${photos[19]!.path}`,
    ])
    expect(paths(pager.getState().rows)).toEqual(paths(photos))
    expect(pager.getState().done).toBe(true)

    // Exhausted: asking again sends nothing.
    await pager.loadNext()
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('never repeats a row, even when a page overlaps the last one', async () => {
    const { photos } = library(6)
    const pages: FeedPageResult[] = [
      { ok: true, photos: photos.slice(0, 3), nextCursor: 'c1' },
      // A row synced in at the boundary millisecond re-serves photos[2].
      { ok: true, photos: photos.slice(2, 6), nextCursor: null },
    ]
    const pager = new FeedPager(async () => pages.shift()!, 3)
    await pager.ensure(10)
    expect(paths(pager.getState().rows)).toEqual(paths(photos))
  })

  it('loads only as far as asked: one page for the first screen, never the library', async () => {
    const { photos } = library(10_000)
    const fetch = vi.fn(keysetFeed(photos))
    const pager = new FeedPager(fetch)
    await pager.ensure(40)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0]![1]).toBe(FEED_PAGE_SIZE)
    expect(FEED_PAGE_SIZE).toBeLessThanOrEqual(500)
    expect(pager.getState().rows).toHaveLength(FEED_PAGE_SIZE)
  })

  it('shares one request between concurrent asks', async () => {
    const { photos } = library(30)
    const fetch = vi.fn(keysetFeed(photos))
    const pager = new FeedPager(fetch, 10)
    await Promise.all([pager.ensure(5), pager.ensure(5), pager.loadNext()])
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('treats a cursor that did not move as the end, rather than asking forever', async () => {
    const { photos } = library(4)
    const fetch = vi.fn(async (cursor: string | null) => ({ ok: true as const, photos: photos.slice(0, 2), nextCursor: cursor ?? 'same' }))
    const pager = new FeedPager(fetch, 2)
    await pager.ensure(100)
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(pager.getState().done).toBe(true)
  })

  it('keeps a typed failure, and retries from the same cursor with the rows it held', async () => {
    const { photos } = library(6)
    const feed = keysetFeed(photos)
    const fetch = vi
      .fn<(cursor: string | null, size: number) => Promise<FeedPageResult>>()
      .mockImplementationOnce(feed)
      .mockResolvedValueOnce({ ok: false, error: { kind: 'error_photos_bad_cursor' } })
      .mockImplementation(feed)
    const pager = new FeedPager(fetch, 3)

    await pager.ensure(5)
    expect(pager.getState().failure).toEqual({ failure: { kind: 'error_photos_bad_cursor' } })
    expect(pager.getState().rows).toHaveLength(3)
    // Failed: `ensure` does not hammer it.
    await pager.ensure(5)
    expect(fetch).toHaveBeenCalledTimes(2)

    await pager.retry()
    expect(fetch.mock.calls[2]![0]).toBe(fetch.mock.calls[1]![0])
    expect(paths(pager.getState().rows)).toEqual(paths(photos))
  })

  it('reports a rejected bridge call as an error, not as the end of the feed', async () => {
    const pager = new FeedPager(async () => {
      throw new Error('not permitted: photo.feedPage')
    })
    await pager.ensure(0)
    const state = pager.getState()
    expect(state.done).toBe(false)
    expect(state.failure && 'error' in state.failure).toBe(true)
  })
})
