// Coverage for the §3 Locked unlock flow. Exercises:
//   - the pre-detect router (active → /control/home, firstTime → /onboarding)
//   - the welcome card with the persisted device name
//   - the happy-path passkey unlock (detect → unlock → persist → route)
//   - the two failure surfaces: WebAuthn-side cancellation and
//     SDK-side wrongCredential
//   - switching to the recovery-phrase entry step + the 24-word
//     submit-button gate.
//
// `getPasskeyAssertion` and `isWebAuthnSupported` are free-function
// imports from `../lib/webauthn`, so the boundary is `vi.mock('../lib/
// webauthn', …)`. The SDK is injected via `OnboardingSdkContext` exactly
// like the production wiring in `onboardingSdk.tsx`.
import { describe, expect, it, vi } from 'vitest'
import { reloadAfterPurge } from '../lib/reloadAfterPurge'
vi.mock('../lib/reloadAfterPurge', () => ({ reloadAfterPurge: vi.fn() }))
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type {
  SessionApi,
  SessionState,
  SessionToken,
  TResult,
  UnlockError,
  WebAuthnAssertion,
} from '@tenshu/sdk/onboarding'
import type { WebAuthnFailure, WebAuthnResult } from '../lib/webauthn'
import { OnboardingSdkContext } from '../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../lib/mockOnboarding'

const getPasskeyAssertionMock = vi.fn<() => Promise<WebAuthnResult<WebAuthnAssertion>>>()
const isWebAuthnSupportedMock = vi.fn<() => boolean>(() => true)

vi.mock('../lib/webauthn', async () => {
  const actual = await vi.importActual<typeof import('../lib/webauthn')>('../lib/webauthn')
  return {
    ...actual,
    getPasskeyAssertion: () => getPasskeyAssertionMock(),
    isWebAuthnSupported: () => isWebAuthnSupportedMock(),
  }
})

import { LockedFlow } from './LockedFlow'

interface SessionStubs {
  readonly detect?: SessionState | Promise<SessionState>
  readonly unlock?: TResult<SessionToken, UnlockError>
}

function stubSdk(overrides: SessionStubs = {}): {
  sdk: OnboardingSdk
  persist: ReturnType<typeof vi.fn>
  unlock: ReturnType<typeof vi.fn>
  clear: ReturnType<typeof vi.fn>
} {
  const persist = vi.fn(async () => undefined)
  const unlock = vi.fn(async () => overrides.unlock ?? defaultUnlockOk())
  const clear = vi.fn(async () => undefined)
  const session: Partial<SessionApi> = {
    detect: () =>
      Promise.resolve(
        overrides.detect ?? { kind: 'locked', deviceName: 'Pixel 9' },
      ),
    unlock: unlock as SessionApi['unlock'],
    persist: persist as SessionApi['persist'],
    clear: clear as SessionApi['clear'],
  }
  return {
    sdk: { Session: session } as unknown as OnboardingSdk,
    persist,
    unlock,
    clear,
  }
}

function defaultUnlockOk(): TResult<SessionToken, UnlockError> {
  return {
    ok: true,
    value: { bytes: new Int8Array(32), expiresAtMs: Date.now() + 86_400_000 },
  }
}

function LocationProbe() {
  const loc = useLocation()
  return <span data-testid="location">{loc.pathname}</span>
}

function renderFlow(sdk: OnboardingSdk) {
  return render(
    <OnboardingSdkContext.Provider value={sdk}>
      <MemoryRouter initialEntries={['/lock']}>
        <Routes>
          <Route path="/lock" element={<LockedFlow />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </OnboardingSdkContext.Provider>,
  )
}

describe('LockedFlow', () => {
  it('renders the welcome card with the persisted device name', async () => {
    const { sdk } = stubSdk()
    renderFlow(sdk)
    expect(await screen.findByText('Welcome back, Pixel 9')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Unlock with passkey' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Lost your passkey?' })).toBeInTheDocument()
  })

  it('redirects to /control/home when Session.detect returns active', async () => {
    const { sdk } = stubSdk({ detect: { kind: 'active', deviceName: 'Pixel 9' } })
    renderFlow(sdk)
    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/control/home'),
    )
  })

  it('redirects to /onboarding when Session.detect returns firstTime', async () => {
    const { sdk } = stubSdk({ detect: { kind: 'firstTime' } })
    renderFlow(sdk)
    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/onboarding'),
    )
  })

  it('completes the happy-path unlock and routes to /control/home', async () => {
    const user = userEvent.setup()
    getPasskeyAssertionMock.mockResolvedValueOnce({
      ok: true,
      value: {} as WebAuthnAssertion,
    })
    const { sdk, persist, unlock } = stubSdk()
    renderFlow(sdk)
    await user.click(await screen.findByRole('button', { name: 'Unlock with passkey' }))
    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/control/home'),
    )
    expect(unlock).toHaveBeenCalledTimes(1)
    expect(persist).toHaveBeenCalledTimes(1)
  })

  it('shows the wrongCredential failure card when Session.unlock rejects the credential', async () => {
    const user = userEvent.setup()
    getPasskeyAssertionMock.mockResolvedValueOnce({
      ok: true,
      value: {} as WebAuthnAssertion,
    })
    const { sdk, persist } = stubSdk({
      unlock: { ok: false, error: { kind: 'wrongCredential' } },
    })
    renderFlow(sdk)
    await user.click(await screen.findByRole('button', { name: 'Unlock with passkey' }))
    expect(
      await screen.findByText(
        /That security key isn't registered for this browser/i,
      ),
    ).toBeInTheDocument()
    expect(persist).not.toHaveBeenCalled()
  })

  it('shows the cancelled-unlock failure when WebAuthn returns cancelled', async () => {
    const user = userEvent.setup()
    const failure: WebAuthnFailure = { kind: 'cancelled', message: 'aborted' }
    getPasskeyAssertionMock.mockResolvedValueOnce({ ok: false, error: failure })
    const { sdk, unlock } = stubSdk()
    renderFlow(sdk)
    await user.click(await screen.findByRole('button', { name: 'Unlock with passkey' }))
    expect(await screen.findByText(/You cancelled the unlock/i)).toBeInTheDocument()
    expect(unlock).not.toHaveBeenCalled()
  })

  it('disconnects from the welcome card: confirm purges the realm and routes to /', async () => {
    const user = userEvent.setup()
    const { sdk, clear } = stubSdk()
    renderFlow(sdk)
    await user.click(
      await screen.findByRole('button', { name: 'Not you? Disconnect this browser' }),
    )
    // Confirmation card — destructive action is gated behind it.
    expect(await screen.findByText('Disconnect this browser?')).toBeInTheDocument()
    expect(clear).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Disconnect this browser' }))
    expect(clear).toHaveBeenCalledWith({ purgeRealm: true })
    // ⚠ NOT a router transition. A purge is only half-done without a real page load —
    // `IndexedDbSecureStorageDelegate.clearAll` says so on itself, and an SPA navigate leaves
    // the JsRuntime singleton alive holding the removed device's identity.
    await waitFor(() => expect(reloadAfterPurge).toHaveBeenCalledWith('/'))
  })

  it('cancelling the disconnect confirmation returns to the welcome card', async () => {
    const user = userEvent.setup()
    const { sdk, clear } = stubSdk()
    renderFlow(sdk)
    await user.click(
      await screen.findByRole('button', { name: 'Not you? Disconnect this browser' }),
    )
    await user.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(await screen.findByText('Welcome back, Pixel 9')).toBeInTheDocument()
    expect(clear).not.toHaveBeenCalled()
  })

  it('offers the same disconnect escape from the unlock-failed card', async () => {
    const user = userEvent.setup()
    getPasskeyAssertionMock.mockResolvedValueOnce({
      ok: false,
      error: { kind: 'cancelled', message: 'aborted' },
    })
    const { sdk } = stubSdk()
    renderFlow(sdk)
    await user.click(await screen.findByRole('button', { name: 'Unlock with passkey' }))
    await screen.findByText(/You cancelled the unlock/i)
    await user.click(screen.getByRole('button', { name: 'Disconnect this browser' }))
    expect(await screen.findByText('Disconnect this browser?')).toBeInTheDocument()
  })

  it('switches to phrase entry and enables Unlock only after 24 valid BIP-39 words', async () => {
    const user = userEvent.setup()
    const { sdk } = stubSdk()
    renderFlow(sdk)
    await user.click(await screen.findByRole('button', { name: 'Lost your passkey?' }))
    const submit = screen.getByRole('button', { name: 'Unlock' })
    expect(submit).toBeDisabled()
    // 24 words from the BIP-39 wordlist; "abandon" repeats are easy to
    // type and unambiguously valid.
    const phrase = Array<string>(24).fill('abandon').join(' ')
    const textarea = screen.getByLabelText('Recovery phrase')
    await user.type(textarea, phrase)
    expect(screen.getByText('24/24')).toBeInTheDocument()
    expect(submit).not.toBeDisabled()
  })
})
