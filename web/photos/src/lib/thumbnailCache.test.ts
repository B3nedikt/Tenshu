import { describe, expect, it, vi } from 'vitest'
import { ThumbnailCache } from './thumbnailCache'

function harness(maxRetained = 150) {
  let minted = 0
  let revoked = 0
  const load = vi.fn(async (key: string) => new Blob([key]))
  const cache = new ThumbnailCache(load, {
    maxRetained,
    createUrl: () => `blob:${++minted}`,
    revokeUrl: () => {
      revoked += 1
    },
  })
  return { cache, load, live: () => minted - revoked, minted: () => minted }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

describe('ThumbnailCache — the memory bound', () => {
  it('a 10k-photo library scrolled end to end never holds more than the visible tiles + the retained set', async () => {
    const { cache, live } = harness(150)
    const VISIBLE = 60
    const released: (() => void)[] = []
    let peak = 0
    // Scroll a screen of 60 tiles at a time through 10 000 photographs, letting each screen load.
    for (let screen = 0; screen < 10_000 / VISIBLE; screen++) {
      while (released.length) released.pop()!()
      for (let i = 0; i < VISIBLE; i++) released.push(cache.acquire(`/p${screen * VISIBLE + i}.jpg`, () => {}))
      await flush()
      await flush()
      peak = Math.max(peak, live())
    }
    expect(peak).toBeLessThanOrEqual(VISIBLE + 150)
    expect(cache.stats().live).toBeLessThanOrEqual(VISIBLE + 150)
    while (released.length) released.pop()!()
    expect(cache.stats().live).toBeLessThanOrEqual(150)
    expect(cache.stats().inUse).toBe(0)
  })

  it('a tile scrolled away before its turn is never fetched (a fling costs what the user stopped on)', async () => {
    const { cache, load } = harness()
    for (let i = 0; i < 1000; i++) cache.acquire(`/fling${i}.jpg`, () => {})()
    const stay = cache.acquire('/stopped.jpg', () => {})
    await flush()
    await flush()
    // At most the first in-flight batch went out before its tiles were released, plus the one kept.
    expect(load.mock.calls.length).toBeLessThanOrEqual(7)
    expect(load).toHaveBeenCalledWith('/stopped.jpg')
    stay()
  })

  it('serves a retained URL again without refetching, and shares one read between two tiles', async () => {
    const { cache, load } = harness()
    const urls: (string | null)[] = []
    const a = cache.acquire('/x.jpg', (u) => urls.push(u))
    const b = cache.acquire('/x.jpg', (u) => urls.push(u))
    await flush()
    a()
    b()
    cache.acquire('/x.jpg', (u) => urls.push(u))()
    expect(load).toHaveBeenCalledTimes(1)
    expect(urls).toEqual(['blob:1', 'blob:1', 'blob:1'])
  })

  it('does not remember a null (no rung yet is a wait): the next acquire asks again', async () => {
    const load = vi.fn(async () => null)
    const cache = new ThumbnailCache(load, { createUrl: () => 'blob:x', revokeUrl: () => {} })
    const seen: (string | null)[] = []
    const release = cache.acquire('/heic.heic', (u) => seen.push(u))
    await flush()
    release()
    cache.acquire('/heic.heic', () => {})
    await flush()
    expect(seen).toEqual([null])
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('a failed read becomes a blank tile and is logged, not thrown', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const cache = new ThumbnailCache(async () => {
      throw new Error('error_generic')
    })
    const seen: (string | null)[] = []
    cache.acquire('/bad.jpg', (u) => seen.push(u))
    await flush()
    await flush()
    expect(seen).toEqual([null])
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })
})
