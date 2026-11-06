// Coverage for the §6 Recovery flow. Walks the state machine far enough
// to verify the four interesting transitions:
//   - entry → deviceName (after a 24-valid-word submit)
//   - deviceName → processing → tail.passkey on Recovery.claim success
//   - processing → failed (with a representative `describeRecoveryError`
//     branch — `noBackupOnRelay`)
//   - failed → processing on retry (Recovery.claim re-invoked)
//
// The tail screens (`PasskeySetupScreen`, `BrowserSyncWarningScreen`,
// etc.) are shared with `OnboardingFlow` and have their own tests in a
// later T.2 slice. Here we only confirm we *land* on the tail's first
// screen, not that the tail is fully wired.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import type {
  RecoveryApi,
  RecoveryError,
  TResult,
} from '@tenshu/sdk/onboarding'
import { OnboardingSdkContext } from '../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../lib/mockOnboarding'
import { RecoveryFlow } from './RecoveryFlow'

const TWENTY_FOUR_ABANDONS = Array<string>(24).fill('abandon').join(' ')

function stubSdk(claim: ReturnType<typeof vi.fn>): OnboardingSdk {
  const recovery: Partial<RecoveryApi> = { claim: claim as RecoveryApi['claim'] }
  return { Recovery: recovery } as unknown as OnboardingSdk
}

function renderFlow(sdk: OnboardingSdk) {
  return render(
    <OnboardingSdkContext.Provider value={sdk}>
      <MemoryRouter initialEntries={['/recovery']}>
        <Routes>
          <Route path="/recovery" element={<RecoveryFlow />} />
          <Route path="/" element={<div data-testid="home">home</div>} />
        </Routes>
      </MemoryRouter>
    </OnboardingSdkContext.Provider>,
  )
}

describe('RecoveryFlow', () => {
  it('keeps Recover disabled until 24 valid BIP-39 words are typed', async () => {
    const user = userEvent.setup()
    const claim = vi.fn()
    renderFlow(stubSdk(claim))
    const submit = screen.getByRole('button', { name: 'Recover' })
    expect(submit).toBeDisabled()
    await user.type(screen.getByLabelText('Recovery phrase'), TWENTY_FOUR_ABANDONS)
    expect(screen.getByText('24/24')).toBeInTheDocument()
    expect(submit).not.toBeDisabled()
    expect(claim).not.toHaveBeenCalled()
  })

  it('advances to the device-name step after a valid Recover submit', async () => {
    const user = userEvent.setup()
    renderFlow(stubSdk(vi.fn()))
    await user.type(screen.getByLabelText('Recovery phrase'), TWENTY_FOUR_ABANDONS)
    await user.click(screen.getByRole('button', { name: 'Recover' }))
    expect(await screen.findByText('Name this browser')).toBeInTheDocument()
  })

  it('runs Recovery.claim with the entered values and lands on the passkey-setup tail on success', async () => {
    const user = userEvent.setup()
    const claim = vi.fn(
      async (): Promise<TResult<void, RecoveryError>> => ({ ok: true, value: undefined }),
    )
    renderFlow(stubSdk(claim))
    await user.type(screen.getByLabelText('Recovery phrase'), TWENTY_FOUR_ABANDONS)
    await user.click(screen.getByRole('button', { name: 'Recover' }))
    // Accept the default device-name "This Browser".
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    await waitFor(() => {
      expect(claim).toHaveBeenCalledWith({
        mnemonic: TWENTY_FOUR_ABANDONS,
        deviceName: 'This Browser',
      })
    })
    // The first tail screen is `PasskeySetupScreen`, which renders the
    // passkey-setup heading when WebAuthn is supported and the
    // passphrase-fallback heading otherwise. jsdom has no WebAuthn so
    // we expect the fallback in this environment.
    expect(
      await screen.findByRole('heading', { name: /Set up (a passphrase|your passkey)/i }),
    ).toBeInTheDocument()
  })

  it('shows the noBackupOnRelay failure card and retries Recovery.claim on click', async () => {
    const user = userEvent.setup()
    const failOnce: TResult<void, RecoveryError> = {
      ok: false,
      error: { kind: 'noBackupOnRelay' },
    }
    const succeedOnRetry: TResult<void, RecoveryError> = {
      ok: true,
      value: undefined,
    }
    const claim = vi
      .fn<RecoveryApi['claim']>()
      .mockResolvedValueOnce(failOnce)
      .mockResolvedValueOnce(succeedOnRetry)
    renderFlow(stubSdk(claim))
    await user.type(screen.getByLabelText('Recovery phrase'), TWENTY_FOUR_ABANDONS)
    await user.click(screen.getByRole('button', { name: 'Recover' }))
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    expect(await screen.findByText("Couldn't find a backup")).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(claim).toHaveBeenCalledTimes(2))
  })

  it('tells a browser already set up for another realm to disconnect first', async () => {
    const user = userEvent.setup()
    const refused: TResult<void, RecoveryError> = {
      ok: false,
      error: { kind: 'deviceAlreadySetUp' },
    }
    const claim = vi.fn<RecoveryApi['claim']>().mockResolvedValue(refused)
    renderFlow(stubSdk(claim))
    await user.type(screen.getByLabelText('Recovery phrase'), TWENTY_FOUR_ABANDONS)
    await user.click(screen.getByRole('button', { name: 'Recover' }))
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    expect(await screen.findByText('This browser is already set up')).toBeInTheDocument()
    expect(screen.getByText(/disconnect this browser first/)).toBeInTheDocument()
  })

  it('Cancel link routes back to /', async () => {
    const user = userEvent.setup()
    renderFlow(stubSdk(vi.fn()))
    await user.click(screen.getByRole('link', { name: 'Cancel' }))
    expect(screen.getByTestId('home')).toBeInTheDocument()
  })
})
