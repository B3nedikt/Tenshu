// "Disconnect this browser" — the shared confirm → disconnecting → outcome panel. Each test pins one
// guard the 2026-11-16 investigation found missing: the destructive button must not fire on one
// accidental click, the multi-second wait must say it is working (people reloaded mid-purge), an
// unconfirmed removal must be told before the reload, and the dead passkey must be signalled.
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { reloadAfterPurge } from '../lib/reloadAfterPurge'
vi.mock('../lib/reloadAfterPurge', () => ({ reloadAfterPurge: vi.fn() }))
const signalMock = vi.fn(async (_id: Int8Array) => true)
vi.mock('../lib/webauthn', () => ({ signalPasskeyRemoved: (id: Int8Array) => signalMock(id) }))
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { SessionApi, SessionClearResult } from '@tenshu/sdk/onboarding'
import { DisconnectBrowserPanel } from './DisconnectBrowserPanel'

function renderPanel(clear: SessionApi['clear'], onCancel = vi.fn()) {
  render(
    <DisconnectBrowserPanel
      deviceName="Work laptop"
      session={{ clear }}
      onCancel={onCancel}
      renderTitle={text => <h2>{text}</h2>}
    />,
  )
  return { onCancel }
}

const confirmed: SessionClearResult = { removal: 'confirmed', passkeyCredentialId: null }

async function acknowledgeAndConfirm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('checkbox'))
  await user.click(screen.getByRole('button', { name: 'Disconnect this browser' }))
}

describe('DisconnectBrowserPanel', () => {
  beforeEach(() => {
    vi.mocked(reloadAfterPurge).mockClear()
    signalMock.mockClear()
  })

  it('keeps the destructive button disabled until the acknowledgement is ticked', async () => {
    const user = userEvent.setup()
    const clear = vi.fn<SessionApi['clear']>(async () => confirmed)
    renderPanel(clear)
    const button = screen.getByRole('button', { name: 'Disconnect this browser' })
    expect(button).toBeDisabled()
    await user.click(button)
    expect(clear).not.toHaveBeenCalled()
    await user.click(screen.getByRole('checkbox'))
    expect(button).toBeEnabled()
  })

  it('a confirmed removal purges, then reloads to / — never a router transition', async () => {
    const user = userEvent.setup()
    const clear = vi.fn<SessionApi['clear']>(async () => confirmed)
    renderPanel(clear)
    await acknowledgeAndConfirm(user)
    expect(clear).toHaveBeenCalledWith({ purgeRealm: true })
    await waitFor(() => expect(reloadAfterPurge).toHaveBeenCalledWith('/'))
  })

  it('says it is working while the purge runs, and locks cancel', async () => {
    const user = userEvent.setup()
    let finish: (r: SessionClearResult) => void = () => {}
    const clear = vi.fn<SessionApi['clear']>(() => new Promise(resolve => (finish = resolve)))
    renderPanel(clear)
    await acknowledgeAndConfirm(user)
    expect(await screen.findByRole('status')).toHaveTextContent(/keep this tab open/i)
    expect(screen.getByRole('button', { name: 'Disconnecting…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    expect(reloadAfterPurge).not.toHaveBeenCalled()
    finish(confirmed)
    await waitFor(() => expect(reloadAfterPurge).toHaveBeenCalledWith('/'))
  })

  it('an unconfirmed removal is shown BEFORE the reload, which waits for Continue', async () => {
    const user = userEvent.setup()
    renderPanel(async () => ({ removal: 'unconfirmed', passkeyCredentialId: null }))
    await acknowledgeAndConfirm(user)
    expect(await screen.findByText('Disconnected, with one loose end')).toBeInTheDocument()
    expect(reloadAfterPurge).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(reloadAfterPurge).toHaveBeenCalledWith('/')
  })

  it('a purge that throws offers a retry and does not reload', async () => {
    const user = userEvent.setup()
    const clear = vi
      .fn<SessionApi['clear']>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(confirmed)
    renderPanel(clear)
    await acknowledgeAndConfirm(user)
    expect(await screen.findByText("Couldn't finish disconnecting")).toBeInTheDocument()
    expect(reloadAfterPurge).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(reloadAfterPurge).toHaveBeenCalledWith('/'))
    expect(clear).toHaveBeenCalledTimes(2)
  })

  it('signals the passkey the purge stopped honouring', async () => {
    const user = userEvent.setup()
    const credentialId = new Int8Array([1, 2, 3])
    renderPanel(async () => ({ removal: 'confirmed', passkeyCredentialId: credentialId }))
    await acknowledgeAndConfirm(user)
    await waitFor(() => expect(reloadAfterPurge).toHaveBeenCalled())
    expect(signalMock).toHaveBeenCalledWith(credentialId)
  })

  it('cancel wipes nothing', async () => {
    const user = userEvent.setup()
    const clear = vi.fn<SessionApi['clear']>(async () => confirmed)
    const { onCancel } = renderPanel(clear)
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalled()
    expect(clear).not.toHaveBeenCalled()
  })
})
