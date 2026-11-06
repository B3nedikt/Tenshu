import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import { renderWithQueryClient } from '../testing/render'
import {
  AppErrorBoundary,
  RuntimeLeaseBanner,
  STILL_CONNECTING_AFTER_MS,
  StreamNotice,
  SyncHealthNotice,
  type LeaseCopy,
} from './index'

const LEASE_COPY: LeaseCopy = {
  connectingTitle: 'Still connecting',
  connectingBody: 'Busy',
  elsewhereTitle: 'Open in another tab',
  elsewhereBody: 'Use it here to move it.',
  moving: 'Waiting for the other tab',
  movingShort: 'Moving…',
  useHere: 'Use here',
}

const reload = vi.fn()
beforeEach(() => {
  reload.mockReset()
  vi.stubGlobal('location', { ...window.location, reload })
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('RuntimeLeaseBanner', () => {
  it('says nothing while this tab holds the runtime', async () => {
    const lease = vi.fn(async () => 'held' as const)
    const { container } = renderWithQueryClient(
      <RuntimeLeaseBanner lease={lease} useHere={vi.fn()} copy={LEASE_COPY} />,
    )
    await waitFor(() => expect(lease).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it.each(['elsewhere', 'displaced'] as const)('offers "Use here" when the runtime is %s, and reloads after', async (state) => {
    const useHere = vi.fn(async () => {})
    renderWithQueryClient(<RuntimeLeaseBanner lease={async () => state} useHere={useHere} copy={LEASE_COPY} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Use here' }))
    expect(useHere).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(reload).toHaveBeenCalled())
  })

  it('says it is still connecting once the lease has not answered for a while', async () => {
    vi.useFakeTimers()
    renderWithQueryClient(
      <RuntimeLeaseBanner lease={() => new Promise(() => {})} useHere={vi.fn()} copy={LEASE_COPY} />,
    )
    expect(screen.queryByText('Still connecting')).toBeNull()
    act(() => {
      vi.advanceTimersByTime(STILL_CONNECTING_AFTER_MS)
    })
    expect(screen.getByText('Still connecting')).toBeInTheDocument()
  })
})

describe('SyncHealthNotice', () => {
  it('shows the sentence for a state that has one, nothing for one that does not, and unsubscribes', () => {
    let push: (v: unknown) => void = () => {}
    const stop = vi.fn()
    const subscribe = vi.fn((onValue: (v: never) => void) => {
      push = onValue as (v: unknown) => void
      return stop
    })
    const message = (c: string) => (c === 'offline' ? 'You are offline' : null)
    const { unmount } = render(<SyncHealthNotice subscribe={subscribe} message={message} />)
    act(() => push({ connectivity: 'online' }))
    expect(screen.queryByRole('status')).toBeNull()
    act(() => push({ connectivity: 'offline' }))
    expect(screen.getByRole('status')).toHaveTextContent('You are offline')
    unmount()
    expect(stop).toHaveBeenCalled()
  })
})

describe('StreamNotice', () => {
  it('renders only for an ended stream, and reconnects', async () => {
    const retry = vi.fn()
    const copy = { stopped: 'Live updates stopped', reconnect: 'Reconnect' }
    const { rerender } = render(<StreamNotice live={{ streamError: null, retry }} copy={copy} />)
    expect(screen.queryByRole('alert')).toBeNull()
    rerender(<StreamNotice live={{ streamError: new Error('gone'), retry }} copy={copy} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Live updates stopped')
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }))
    expect(retry).toHaveBeenCalled()
  })
})

describe('AppErrorBoundary', () => {
  it('catches a render throw, names the app in the console, and recovers on retry', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    let crash = true
    function Maybe() {
      if (crash) throw new Error('kaboom')
      return <p>recovered</p>
    }
    const copy = () => ({ title: 'Something went wrong', body: 'Safe.', retry: 'Try again', reload: 'Reload' })
    render(
      <AppErrorBoundary appName="Demo" copy={copy}>
        <Maybe />
      </AppErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('kaboom')
    expect(log).toHaveBeenCalledWith('Demo UI crashed:', expect.any(Error), expect.anything())
    crash = false
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(screen.getByText('recovered')).toBeInTheDocument()
  })
})
