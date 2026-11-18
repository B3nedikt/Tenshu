// Coverage for the §8.1 first-time orchestrator. The chooser has three
// actions — [Create an account] and [Use without an account] (both the
// new-user realm-creation path) and [I already use Tenshu] (existing-realm-
// device pairing). This slice asserts the chooser-level dispatch, that no step
// asks for a device name (the browser is named for what it is), the new-user
// happy path (no in-flow phrase ceremony), and the §8.1/§8.2 intent routing:
// signup → get the apps → plan step → the chosen plan's page; try-mode → get
// the apps → control home; joiner → control home. No path shows a browser-sync screen.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { PairingSession } from '@tenshu/sdk/onboarding'
import { OnboardingSdkContext } from '../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../lib/mockOnboarding'
import { OnboardingFlow } from './OnboardingFlow'

// The shallow tests below never reach an SDK call — the chooser is pure UI. We still need a non-null context
// because `useOnboardingSdk` throws otherwise; deeper screens that
// would consume it (`GenerateRealmScreen`, `CommittingRealmScreen`,
// `PairingSubmitScreen`) are out of scope here.
const EMPTY_SDK = {} as unknown as OnboardingSdk

function renderFlow(sdk: OnboardingSdk = EMPTY_SDK) {
  return render(
    <OnboardingSdkContext.Provider value={sdk}>
      <MemoryRouter initialEntries={['/onboarding']}>
        <Routes>
          <Route path="/onboarding" element={<OnboardingFlow />} />
          {/* Landing probes so the intent-routing tests can assert where
              the flow navigates on completion. */}
          <Route path="/control/services/signup" element={<div>FREE SIGNUP</div>} />
          <Route path="/overview" element={<div>HOME</div>} />
        </Routes>
      </MemoryRouter>
    </OnboardingSdkContext.Provider>,
  )
}

const CHROME_ON_MAC =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

// Walk a new-user run from the chooser through passkey to the "get the apps"
// screen. jsdom has no WebAuthn, so the passkey screen
// renders its passphrase fallback throughout.
async function walkNewUserToGetApps(
  user: ReturnType<typeof userEvent.setup>,
  chooserCta: RegExp,
) {
  await user.click(screen.getByRole('button', { name: chooserCta }))
  // generate (~1.2s) → commit → passkey fallback
  await screen.findByRole('heading', { name: 'Set up a passphrase' }, { timeout: 2500 })
  await user.click(screen.getByRole('button', { name: 'Use recovery phrase only' }))
  await screen.findByRole('heading', { name: 'Get Tenshu on your devices' })
}

function newUserSdk() {
  return {
    Realm: { create: vi.fn().mockResolvedValue({ ok: true, value: undefined }) },
    Session: { persist: vi.fn().mockResolvedValue(undefined) },
  } as unknown as OnboardingSdk
}

describe('OnboardingFlow', () => {
  beforeEach(() => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(CHROME_ON_MAC)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the §8.1 chooser: two equal actions, try-mode as a quiet link, no body copy', () => {
    renderFlow()
    expect(screen.getByRole('heading', { name: 'Welcome to Tenshu' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Create an account/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /I already use Tenshu/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Use without an account' })).toBeInTheDocument()
    expect(screen.queryByText(/encrypted on this device/)).toBeNull()
  })

  it('new-user happy path: straight to generate → commit, named for the browser, no phrase ceremony', async () => {
    const user = userEvent.setup()
    const create = vi.fn().mockResolvedValue({ ok: true, value: undefined })
    const sdk = { Realm: { create } } as unknown as OnboardingSdk
    renderFlow(sdk)

    await user.click(screen.getByRole('button', { name: /Create an account/ }))
    // No intro and no device-name step: generate auto-advances (~1.2s) and
    // commits via Realm.create with a freshly generated 24-word phrase.
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1), { timeout: 2500 })
    const arg = create.mock.calls[0]![0] as { readonly mnemonic: string; readonly deviceName: string }
    expect(arg.deviceName).toBe('Chrome on macOS')
    expect(arg.mnemonic.split(' ')).toHaveLength(24)

    expect(
      await screen.findByRole('heading', { name: 'Set up a passphrase' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Save your recovery phrase' })).toBeNull()
  })

  it('the apps screen links the Play Store listing and the Mac download page', async () => {
    const user = userEvent.setup()
    renderFlow(newUserSdk())
    await walkNewUserToGetApps(user, /Create an account/)
    expect(screen.getByRole('link', { name: /Get it on Google Play/ })).toHaveAttribute(
      'href',
      'https://play.google.com/store/apps/details?id=app.tenshu.control.android',
    )
    expect(screen.getByRole('link', { name: /Download for Mac/ })).toHaveAttribute(
      'href',
      'https://tenshu.app/apps/control/download/macos',
    )
  })

  it('signup intent ends on the in-flow plan step, and choosing Free goes to its signup', async () => {
    const user = userEvent.setup()
    const sdk = newUserSdk()
    renderFlow(sdk)
    await walkNewUserToGetApps(user, /Create an account/)
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByRole('heading', { name: 'Choose a plan' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sign up free' }))
    expect(await screen.findByText('FREE SIGNUP')).toBeInTheDocument()
    expect(sdk.Session.persist).toHaveBeenCalled()
  })

  it('"Not now" on the plan step lands on the control home', async () => {
    const user = userEvent.setup()
    renderFlow(newUserSdk())
    await walkNewUserToGetApps(user, /Create an account/)
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await user.click(await screen.findByRole('button', { name: 'Not now' }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })

  it('try-mode skips the plan step and lands on the control home', async () => {
    const user = userEvent.setup()
    renderFlow(newUserSdk())
    await walkNewUserToGetApps(user, /Use without an account/)
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByText('HOME')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Choose a plan' })).toBeNull()
  })

  it('chooser → "I already use Tenshu" goes straight to pairing, and Cancel returns to the chooser', async () => {
    const user = userEvent.setup()
    renderFlow()
    await user.click(screen.getByRole('button', { name: /I already use Tenshu/ }))
    expect(
      await screen.findByRole('heading', { name: 'Add this browser to your realm' }),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(await screen.findByRole('heading', { name: 'Welcome to Tenshu' })).toBeInTheDocument()
  })

  it('joiner happy path: pair → paired → passkey → control home', async () => {
    const user = userEvent.setup()
    const session: PairingSession = {
      observe: cb => {
        cb({ kind: 'awaiting', sas: '482 913' })
        cb({ kind: 'paired' })
        return () => {}
      },
      cancel: () => Promise.resolve(),
    }
    const submit = vi.fn().mockResolvedValue({ ok: true, value: session })
    const sdk = {
      Pairing: { submit },
      Session: { persist: vi.fn().mockResolvedValue(undefined) },
    } as unknown as OnboardingSdk
    renderFlow(sdk)

    await user.click(screen.getByRole('button', { name: /I already use Tenshu/ }))
    await user.type(
      await screen.findByPlaceholderText(/tenshu:\/\/join/i),
      'tenshu://join?o=o&d=MQYQ&k=AAAAAAAAAAAA&s=OM',
    )
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(submit).toHaveBeenCalled())
    await user.click(await screen.findByRole('button', { name: 'Set up passkey' }))
    await user.click(await screen.findByRole('button', { name: 'Use recovery phrase only' }))
    // A joiner already has the apps and an account: no apps or plan step.
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })
})
