import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const runtimeLease = vi.fn()
const runtimeUseHere = vi.fn()
vi.mock('../lib/proxy', () => ({
  runtimeLease: () => runtimeLease(),
  runtimeUseHere: () => runtimeUseHere(),
}))

const { RuntimeBanner, STILL_CONNECTING_AFTER_MS } = await import('./RuntimeBanner')

function renderBanner() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <RuntimeBanner />
    </QueryClientProvider>,
  )
}

const reload = vi.fn()

beforeEach(() => {
  runtimeLease.mockReset()
  runtimeUseHere.mockReset().mockResolvedValue(undefined)
  reload.mockReset()
  vi.stubGlobal('location', { ...window.location, reload })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('RuntimeBanner', () => {
  it('says nothing while this tab runs Tenshu', async () => {
    runtimeLease.mockResolvedValue('held')
    renderBanner()
    await waitFor(() => expect(runtimeLease).toHaveBeenCalled())
    expect(screen.queryByText('Tenshu is open in another tab')).toBeNull()
  })

  it.each(['elsewhere', 'displaced'])('offers "Use here" when the runtime is %s, then reloads', async (state) => {
    runtimeLease.mockResolvedValue(state)
    renderBanner()
    await screen.findByText('Tenshu is open in another tab')

    await userEvent.click(screen.getByRole('button', { name: 'Use here' }))

    await waitFor(() => expect(reload).toHaveBeenCalled())
    expect(runtimeUseHere).toHaveBeenCalledTimes(1)
  })

  it('says it is still connecting when the apex has not answered after a while', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      runtimeLease.mockReturnValue(new Promise(() => undefined)) // never answers
      renderBanner()
      expect(screen.queryByText('Still connecting to Tenshu…')).toBeNull()

      await act(async () => {
        vi.advanceTimersByTime(STILL_CONNECTING_AFTER_MS + 100)
      })
      expect(screen.getByText('Still connecting to Tenshu…')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})
