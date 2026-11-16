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
})
