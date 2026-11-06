import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const completeHandshake = vi.fn()
vi.mock('../lib/proxy', () => ({
  completeHandshake: (args: unknown) => completeHandshake(args),
}))

const { ContactCompletionDialog } = await import('./ContactCompletionDialog')

function invite(over: Record<string, unknown> = {}) {
  return {
    inviteId: 'inv-1',
    status: 'REDEEMED',
    intentKind: '',
    intentLabel: '',
    createdAtMillis: 0,
    expiresAtMillis: 0,
    claimedName: 'Bob',
    claimedFingerprint: 'feedface00112233',
    claimedSas: '482913',
    ...over,
  }
}

afterEach(() => completeHandshake.mockReset())

// contact-service v2 / threat model T-CI-10: the dialog sends the raw fingerprint it displayed,
// and a tick made against one code never survives the claim moving under the open dialog (a
// second inviter device's write arriving by merge).
describe('ContactCompletionDialog', () => {
  it('clears the codes-match tick when the claim under it changes, and sends what is shown', async () => {
    completeHandshake.mockResolvedValue({ outcome: 'completed', message: null })
    const view = render(<ContactCompletionDialog invite={invite()} onClose={() => {}} onDone={() => {}} />)
    const tick = () => screen.getByRole('checkbox', { name: /The codes match/ }) as HTMLInputElement

    await userEvent.click(tick())
    expect(tick().checked).toBe(true)

    view.rerender(
      <ContactCompletionDialog
        invite={invite({ claimedFingerprint: 'ffff0000ffff0000', claimedSas: '111222' })}
        onClose={() => {}}
        onDone={() => {}}
      />,
    )
    expect(tick().checked).toBe(false)

    await userEvent.type(screen.getByLabelText(/Your name/i), 'Alice')
    await userEvent.click(screen.getByRole('button', { name: 'Add contact' }))

    expect(completeHandshake).toHaveBeenCalledWith(
      expect.objectContaining({ shownFingerprint: 'ffff0000ffff0000', sasConfirmed: false }),
    )
  })
})
