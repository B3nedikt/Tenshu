// `PairNewDeviceDialog` orchestrates four phases:
//   - starting: spinner while `Pairing.show()` is pending
//   - failed: `Pairing.show()` rejected (network / other)
//   - active.showing: pairing code rendered, waiting for the peer
//   - active.request: peer submitted a request; SAS rendered when
//     present, deviceName fallback when null; Approve drives
//     `session.approve(deviceId)`
//   - active.paired: success copy + onPaired callback fired
//
// We expose a tiny `HostPairingSession` test harness that lets the
// test push status emissions into the component synchronously.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, act, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {
  ApprovePairingError,
  HostPairingSession,
  HostPairingStatus,
  PairingApi,
  PairingShowError,
  TResult,
} from '@tenshu/sdk/onboarding'

const showMock = vi.fn<PairingApi['show']>()

vi.mock('@tenshu/sdk/onboarding', () => ({
  Pairing: { show: () => showMock() },
}))

import { PairNewDeviceDialog } from './PairNewDeviceDialog'
import { AUTO_CONTINUE_DURATION_MS } from './useAutoContinue'

interface SessionHarness {
  readonly session: HostPairingSession
  readonly emit: (status: HostPairingStatus) => void
  readonly approveCalls: readonly string[]
  readonly cancelCount: { current: number }
}

function makeSession(
  approveResult: TResult<void, ApprovePairingError> = { ok: true, value: undefined },
): SessionHarness {
  const callbacks: ((status: HostPairingStatus) => void)[] = []
  const approveCalls: string[] = []
  const cancelCount = { current: 0 }
  const session: HostPairingSession = {
    observe: cb => {
      callbacks.push(cb)
      return () => {
        const idx = callbacks.indexOf(cb)
        if (idx >= 0) callbacks.splice(idx, 1)
      }
    },
    approve: async deviceId => {
      approveCalls.push(deviceId)
      return approveResult
    },
    cancel: async () => {
      cancelCount.current += 1
    },
  }
  return {
    session,
    emit: status => act(() => callbacks.forEach(cb => cb(status))),
    approveCalls,
    cancelCount,
  }
}

describe('PairNewDeviceDialog', () => {
  it('shows the starting spinner before Pairing.show resolves', () => {
    showMock.mockReturnValue(new Promise<TResult<HostPairingSession, PairingShowError>>(() => {}))
    render(<PairNewDeviceDialog onClose={() => {}} onPaired={() => {}} />)
    expect(screen.getByText('Generating pairing code…')).toBeInTheDocument()
  })

  it('renders the friendly network-error copy when Pairing.show rejects', async () => {
    showMock.mockResolvedValue({ ok: false, error: { kind: 'network' } })
    render(<PairNewDeviceDialog onClose={() => {}} onPaired={() => {}} />)
    expect(
      await screen.findByText(/Couldn't reach the relay\./i),
    ).toBeInTheDocument()
  })

  it("renders the pairing URI verbatim when the session emits 'showing'", async () => {
    const harness = makeSession()
    showMock.mockResolvedValue({ ok: true, value: harness.session })
    render(<PairNewDeviceDialog onClose={() => {}} onPaired={() => {}} />)
    // Wait for the observer to be wired up.
    await waitFor(() => expect(showMock).toHaveBeenCalled())
    harness.emit({ kind: 'showing', pairingUri: 'tenshu://join?o=o&d=MQYQ&k=AAAAAAAAAAAA&s=OM' })
    expect(
      await screen.findByText('tenshu://join?o=o&d=MQYQ&k=AAAAAAAAAAAA&s=OM'),
    ).toBeInTheDocument()
    expect(screen.getByText(/Waiting for the other device…/i)).toBeInTheDocument()
  })

  it("on 'request' renders SAS + deviceName, then Approve drives session.approve(deviceId)", async () => {
    const user = userEvent.setup()
    const harness = makeSession()
    showMock.mockResolvedValue({ ok: true, value: harness.session })
    render(<PairNewDeviceDialog onClose={() => {}} onPaired={() => {}} />)
    await waitFor(() => expect(showMock).toHaveBeenCalled())
    harness.emit({
      kind: 'request',
      deviceId: 'peer-9',
      deviceName: 'Pixel 9',
      sas: '123 456',
    })
    expect(await screen.findByText('Pixel 9')).toBeInTheDocument()
    expect(screen.getByText('123 456')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(harness.approveCalls).toEqual(['peer-9']))
  })

  it("'paired' shows the success copy and fires onPaired", async () => {
    const onPaired = vi.fn()
    const harness = makeSession()
    showMock.mockResolvedValue({ ok: true, value: harness.session })
    render(<PairNewDeviceDialog onClose={() => {}} onPaired={onPaired} />)
    await waitFor(() => expect(showMock).toHaveBeenCalled())
    harness.emit({ kind: 'paired' })
    expect(
      await screen.findByText(/Device paired\. It'll appear in your devices list shortly\./i),
    ).toBeInTheDocument()
    expect(onPaired).toHaveBeenCalledTimes(1)
  })

  it("approve failure surfaces the error inline without leaving the request step", async () => {
    const user = userEvent.setup()
    const harness = makeSession({ ok: false, error: { kind: 'network' } })
    showMock.mockResolvedValue({ ok: true, value: harness.session })
    render(<PairNewDeviceDialog onClose={() => {}} onPaired={() => {}} />)
    await waitFor(() => expect(showMock).toHaveBeenCalled())
    harness.emit({
      kind: 'request',
      deviceId: 'peer-9',
      deviceName: 'Pixel 9',
      sas: null,
    })
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    expect(
      await screen.findByText(/Couldn't reach the relay\./i),
    ).toBeInTheDocument()
    // No SAS panel for sas:null branch — confirm the deviceName-only
    // fallback copy is what the user sees.
    expect(
      screen.getByText(/Confirm the device name above looks right/i),
    ).toBeInTheDocument()
  })
})

// control-pairing-ux.md §9: the host's terminal auto-continues after 3 s; "Stay" or a tap keeps it
// open; a hidden tab pauses the countdown and restarts it on return.
describe('PairNewDeviceDialog auto-continue', () => {
  let visibility: DocumentVisibilityState = 'visible'

  beforeEach(() => {
    vi.useFakeTimers()
    visibility = 'visible'
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function renderPaired(onClose: () => void) {
    const harness = makeSession()
    showMock.mockResolvedValue({ ok: true, value: harness.session })
    render(<PairNewDeviceDialog onClose={onClose} onPaired={() => {}} />)
    // Flush Pairing.show()'s promise so the observer is wired (no waitFor: its timers are faked).
    await act(async () => {})
    harness.emit({ kind: 'paired' })
  }

  function advance(ms: number) {
    act(() => {
      vi.advanceTimersByTime(ms)
    })
  }

  it('closes by itself 3 s after pairing', async () => {
    const onClose = vi.fn()
    await renderPaired(onClose)
    expect(screen.getByRole('button', { name: 'Stay' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')

    advance(AUTO_CONTINUE_DURATION_MS - 500)
    expect(onClose).not.toHaveBeenCalled()
    advance(1000)
    expect(onClose).toHaveBeenCalledTimes(1)
    advance(10_000)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('"Stay" cancels the countdown and leaves a plain Done', async () => {
    const onClose = vi.fn()
    await renderPaired(onClose)
    advance(1000)
    act(() => {
      fireEvent.pointerDown(screen.getByRole('button', { name: 'Stay' }))
      fireEvent.click(screen.getByRole('button', { name: 'Stay' }))
    })
    advance(10_000)
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Stay' })).not.toBeInTheDocument()
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('a tap anywhere on the step cancels', async () => {
    const onClose = vi.fn()
    await renderPaired(onClose)
    act(() => {
      fireEvent.pointerDown(screen.getByText(/Device paired\./))
    })
    advance(10_000)
    expect(onClose).not.toHaveBeenCalled()
  })

  it('pauses while the tab is hidden and restarts the full countdown on return', async () => {
    const onClose = vi.fn()
    await renderPaired(onClose)
    advance(2000)
    visibility = 'hidden'
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    advance(10_000)
    expect(onClose).not.toHaveBeenCalled()

    visibility = 'visible'
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    advance(AUTO_CONTINUE_DURATION_MS - 500)
    expect(onClose).not.toHaveBeenCalled()
    advance(1000)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
