import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SasConfirmPanel } from './SasConfirmPanel'

describe('SasConfirmPanel', () => {
  it('shows the formatted code + peer fingerprint and relays confirm/reject', async () => {
    const onConfirm = vi.fn()
    const onReject = vi.fn()
    render(
      <SasConfirmPanel
        sas="744595"
        peerFingerprint="0123456789abcdef0123456789"
        peerName="Alice"
        onConfirm={onConfirm}
        onReject={onReject}
      />,
    )

    expect(screen.getByText('744 595')).toBeInTheDocument()
    expect(screen.getByText('01234-56789-ABCDE')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'They match' }))
    expect(onConfirm).toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: /don.t match/i }))
    expect(onReject).toHaveBeenCalled()
  })
})
