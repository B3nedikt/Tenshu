// Data → Files, review finding #1's second half: the inventory read cannot be cancelled, so a page
// unmounted before it answers must drop the answer — no state set, no React warning.
//
// ⚠ React 19 no longer warns on a set-after-unmount, so a warning alone cannot fail here. The
// observable is the setter itself: `useState` is wrapped so every setter call made after the
// unmount is recorded. Kept in its own file so the wrap touches no other suite.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { FileInventoryResult } from '@tenshu/sdk/files'

const setterCalls = vi.hoisted(() => ({ recording: false, count: 0 }))

vi.mock('react', async importOriginal => {
  const react = await importOriginal<typeof import('react')>()
  const useState = ((initial: unknown) => {
    const [value, set] = react.useState(initial)
    const recorded = react.useCallback(
      (next: unknown) => {
        if (setterCalls.recording) setterCalls.count++
        set(next)
      },
      [set],
    )
    return [value, recorded]
  }) as typeof react.useState
  return { ...react, default: { ...react, useState }, useState }
})

const inventoryMock = vi.fn<() => Promise<FileInventoryResult>>()

vi.mock('@tenshu/sdk/files', () => ({
  FileSystem: { inventory: () => inventoryMock() },
}))

import { FilesInventoryPage } from './FilesInventoryPage'
import { resetInventoryReadForTests } from './fileInventoryReader'

beforeEach(() => {
  inventoryMock.mockReset()
  resetInventoryReadForTests()
  setterCalls.recording = false
  setterCalls.count = 0
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('FilesInventoryPage unmounted before the read answers', () => {
  it('sets no state and logs no React warning', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    let resolve: (r: FileInventoryResult) => void = () => {}
    inventoryMock.mockReturnValue(new Promise(r => (resolve = r)))

    const { unmount } = render(
      <MemoryRouter>
        <FilesInventoryPage />
      </MemoryRouter>,
    )
    unmount()
    setterCalls.recording = true
    await act(async () =>
      resolve({
        ok: true,
        inventory: { fileCount: 1, folderCount: 0, totalBytes: 1, newestModifiedMillis: 1, byExtension: [] },
      }),
    )

    expect(inventoryMock).toHaveBeenCalledTimes(1)
    expect(setterCalls.count).toBe(0)
    expect(consoleError).not.toHaveBeenCalled()
  })
})
