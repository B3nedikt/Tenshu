import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const redeemContactInvite = vi.fn()
const pendingRedemptions = vi.fn()
const awaitInviteAck = vi.fn()
vi.mock('../lib/proxy', () => ({
  redeemContactInvite: (link: string, name: string) => redeemContactInvite(link, name),
  pendingRedemptions: () => pendingRedemptions(),
  awaitInviteAck: (id: string, sas: boolean) => awaitInviteAck(id, sas),
}))

// Stub the camera scanner: a button that fires onResult with a fixed link.
vi.mock('./QrScanner', () => ({
  QrScanner: ({ onResult }: { onResult: (t: string) => void }) => (
    <button type="button" onClick={() => onResult('https://tenshu.app/i#v1.abc')}>
      fake-scan
    </button>
  ),
}))

const { ScanInviteFlow } = await import('./ScanInviteFlow')

function renderFlow() {
  render(<ScanInviteFlow onClose={() => {}} onDone={() => {}} />)
}

afterEach(() => {
  redeemContactInvite.mockReset()
  pendingRedemptions.mockReset()
  awaitInviteAck.mockReset()
})

describe('ScanInviteFlow', () => {
  it('scans → names → redeems → confirms the SAS → verified close', async () => {
    redeemContactInvite.mockResolvedValueOnce({ outcome: 'redeemed', inviteId: 'inv-1', message: null })
    pendingRedemptions.mockResolvedValueOnce([
      { inviteId: 'inv-1', redeemedAtMillis: 0, peerFingerprint: 'feedface00112233', sas: '744595' },
    ])
    awaitInviteAck.mockResolvedValueOnce({
      status: 'ACCEPTED',
      peerName: 'Alice',
      peerFingerprint: '',
      intentKind: '',
      intentLabel: '',
      sas: '744595',
    })
    renderFlow()

    await userEvent.click(screen.getByRole('button', { name: 'fake-scan' }))
    await userEvent.type(screen.getByLabelText('Your name'), 'Bob')
    await userEvent.click(screen.getByRole('button', { name: 'Connect' }))

    await waitFor(() => expect(redeemContactInvite).toHaveBeenCalledWith('https://tenshu.app/i#v1.abc', 'Bob'))
    // SAS panel appears with the formatted code.
    expect(await screen.findByText('744 595')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'They match' }))
    await waitFor(() => expect(awaitInviteAck).toHaveBeenCalledWith('inv-1', true))
    expect(await screen.findByText(/You.re connected/)).toBeInTheDocument()
  })

  it('surfaces an already-used invite loudly', async () => {
    redeemContactInvite.mockResolvedValueOnce({ outcome: 'alreadyUsed', inviteId: null, message: null })
    renderFlow()

    await userEvent.click(screen.getByRole('button', { name: 'fake-scan' }))
    await userEvent.type(screen.getByLabelText('Your name'), 'Bob')
    await userEvent.click(screen.getByRole('button', { name: 'Connect' }))

    expect(await screen.findByText(/already been used/i)).toBeInTheDocument()
  })
})
