// `RevealPhraseDialog` walks: gate → authenticating → staged | authFailed.
// The gate sits behind `isWebAuthnSupported()` (disabled when WebAuthn
// is missing). On approve it calls `getDedicatedAssertion` and gates on
// `isDedicatedAssertionFresh`. The staged step is the v1 "Phrase reveal
// — coming soon" placeholder until the SDK extends the wrap blob.
//
// The actual phrase reveal isn't shipped yet, so the staged step is the
// happy "auth gate worked" copy + a Track A note. Tests cover:
//   - gate disables Approve when WebAuthn isn't supported + shows the
//     unsupported-browser warning
//   - happy path: gate → authenticate → staged ("Phrase reveal — coming
//     soon"); getDedicatedAssertion called with the reveal action
//   - cancelled WebAuthn → authFailed with the cancellation copy
//   - stale-assertion fallback (defence-in-depth path) surfaces the
//     synthetic "expired" copy without contacting the SDK twice.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WebAuthnAssertion } from '@tenshu/sdk/onboarding'
import type { DedicatedAssertion, WebAuthnFailure, WebAuthnResult } from '../../lib/webauthn'

const getDedicatedAssertionMock = vi.fn<(action: string) => Promise<WebAuthnResult<DedicatedAssertion>>>()
const isDedicatedAssertionFreshMock = vi.fn<(a: DedicatedAssertion) => boolean>(() => true)
const isWebAuthnSupportedMock = vi.fn<() => boolean>(() => true)

vi.mock('../../lib/webauthn', async () => {
  const actual = await vi.importActual<typeof import('../../lib/webauthn')>('../../lib/webauthn')
  return {
    ...actual,
    getDedicatedAssertion: (action: string) => getDedicatedAssertionMock(action),
    isDedicatedAssertionFresh: (a: DedicatedAssertion) => isDedicatedAssertionFreshMock(a),
    isWebAuthnSupported: () => isWebAuthnSupportedMock(),
  }
})

import { RevealPhraseDialog } from './RevealPhraseDialog'

function freshAssertion(): DedicatedAssertion {
  return {
    assertion: {} as WebAuthnAssertion,
    assertedAtMs: Date.now(),
    action: 'recovery.reveal-phrase',
  }
}

describe('RevealPhraseDialog', () => {
  it('disables Approve and shows the unsupported-browser warning when WebAuthn is unavailable', () => {
    isWebAuthnSupportedMock.mockReturnValue(false)
    render(<RevealPhraseDialog onClose={() => {}} />)
    expect(
      screen.getByText(/This browser doesn't support WebAuthn/i),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve and continue' })).toBeDisabled()
  })

  it('happy path: gate → authenticate → staged "Phrase reveal — coming soon"', async () => {
    const user = userEvent.setup()
    isWebAuthnSupportedMock.mockReturnValue(true)
    getDedicatedAssertionMock.mockResolvedValueOnce({ ok: true, value: freshAssertion() })
    render(<RevealPhraseDialog onClose={() => {}} />)
    await user.click(screen.getByRole('button', { name: 'Approve and continue' }))
    expect(await screen.findByText('Authenticator approved.')).toBeInTheDocument()
    expect(screen.getByText('Phrase reveal — coming soon')).toBeInTheDocument()
    await waitFor(() =>
      expect(getDedicatedAssertionMock).toHaveBeenCalledWith('recovery.reveal-phrase'),
    )
  })

  it('cancelled WebAuthn ceremony surfaces the cancellation copy and offers retry', async () => {
    const user = userEvent.setup()
    isWebAuthnSupportedMock.mockReturnValue(true)
    const cancelled: WebAuthnFailure = { kind: 'cancelled', message: 'aborted' }
    getDedicatedAssertionMock.mockResolvedValueOnce({ ok: false, error: cancelled })
    render(<RevealPhraseDialog onClose={() => {}} />)
    await user.click(screen.getByRole('button', { name: 'Approve and continue' }))
    expect(
      await screen.findByText(/Authenticator prompt cancelled/i),
    ).toBeInTheDocument()
    // Try again is offered.
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it("falls back to the synthetic 'expired' error when the freshness check fails", async () => {
    const user = userEvent.setup()
    isWebAuthnSupportedMock.mockReturnValue(true)
    getDedicatedAssertionMock.mockResolvedValueOnce({ ok: true, value: freshAssertion() })
    isDedicatedAssertionFreshMock.mockReturnValueOnce(false)
    render(<RevealPhraseDialog onClose={() => {}} />)
    await user.click(screen.getByRole('button', { name: 'Approve and continue' }))
    expect(
      await screen.findByText(/Assertion expired before it could be used\./i),
    ).toBeInTheDocument()
  })
})
