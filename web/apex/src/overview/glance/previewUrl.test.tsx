// usePreviewUrl (web-apex-home.md §4.11): a row reused for another file never keeps the old picture.
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { usePreviewUrl } from './previewUrl'

describe('usePreviewUrl', () => {
  it('a new path with no preview drops the old (revoked) picture at once', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:a')
    URL.revokeObjectURL = vi.fn()
    const call = vi.fn(async (_m: string, p?: unknown) =>
      ({ ok: true, bytes: (p as { path: string }).path === '/a.jpg' ? new Int8Array([1]) : null }) as unknown,
    )
    const { result, rerender } = renderHook(({ path }) => usePreviewUrl(path, call, 96), { initialProps: { path: '/a.jpg' } })
    await waitFor(() => expect(result.current).toBe('blob:a'))
    rerender({ path: '/b.txt' })
    expect(result.current).toBeNull()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:a')
  })

  // ⚠⚠ LARGE is 256 px on the long edge — a card tile stretches it ~2× on Retina (the blur).
  describe('sharp grid tiles', () => {
    function setup(sharp: Record<string, unknown> | Error) {
      let n = 0
      URL.createObjectURL = vi.fn(() => `blob:${++n}`)
      URL.revokeObjectURL = vi.fn()
      return vi.fn(async (method: string) => {
        if (method === 'fs.getPreview') return { ok: true, bytes: new Int8Array([1]) }
        if (sharp instanceof Error) throw sharp
        return sharp
      })
    }

    it('swaps LARGE for XLARGE once it arrives, and revokes both on unmount', async () => {
      const call = setup({ ok: true, bytes: new Int8Array([2]), thumbnailClass: 'xlarge' })
      const { result, unmount } = renderHook(() => usePreviewUrl('/a.jpg', call, 256, true))
      await waitFor(() => expect(result.current).toBe('blob:2'))
      expect(call).toHaveBeenCalledWith('fs.getTilePreview', { path: '/a.jpg' })
      unmount()
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:1')
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:2')
    })

    it('keeps LARGE for a stand-in answer or a failure', async () => {
      for (const answer of [{ ok: true, bytes: new Int8Array([2]), thumbnailClass: 'large' }, { ok: false }, new Error('refused')]) {
        const call = setup(answer)
        const { result, unmount } = renderHook(() => usePreviewUrl('/a.jpg', call, 256, true))
        await waitFor(() => expect(call).toHaveBeenCalledTimes(2))
        await new Promise(r => setTimeout(r, 0))
        expect(result.current).toBe('blob:1')
        unmount()
      }
    })

    it('a list row never asks for the sharp rung', async () => {
      const call = setup({ ok: true, bytes: new Int8Array([2]), thumbnailClass: 'xlarge' })
      const { result } = renderHook(() => usePreviewUrl('/a.jpg', call, 96))
      await waitFor(() => expect(result.current).toBe('blob:1'))
      expect(call).toHaveBeenCalledTimes(1)
    })
  })
})
