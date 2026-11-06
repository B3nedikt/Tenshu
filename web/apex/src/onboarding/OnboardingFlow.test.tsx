// Coverage for the §8.1 first-time orchestrator. The state machine has
// three CTAs off the chooser — [Sign up free] and [Just try it locally]
// (both the new-user realm-creation path) and [I have Tenshu already]
// (existing-realm-device pairing) — plus a long shared tail. This slice
// asserts the chooser-level dispatch, the back-navigation invariant that
// distinguishes the two device-name screens (new-user back → intro;
// pairing back → chooser), the post-6.6 new-user happy path (no in-flow
// phrase ceremony anymore), and the §8.1/§8.2 intent routing (signup →
// account picker, try-mode → control home).
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { PairingSession } from '@tenshu/sdk/onboarding'
import { OnboardingSdkContext } from '../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../lib/mockOnboarding'
import { OnboardingFlow } from './OnboardingFlow'

// The shallow tests below never reach an SDK call — chooser + intro +
// device-name screens are pure UI. We still need a non-null context
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
          <Route path="/control/services/picker" element={<div>PICKER</div>} />
          <Route path="/control/home" element={<div>HOME</div>} />
        </Routes>
      </MemoryRouter>
    </OnboardingSdkContext.Provider>,
  )
}

// Walk a new-user run from the chooser through the shared tail to the
// completion screen, which persists a session and navigates. jsdom has no
// WebAuthn, so the passkey screen renders its passphrase fallback throughout.
async function walkNewUserToCompletion(
  user: ReturnType<typeof userEvent.setup>,
  chooserCta: string,
) {
  await user.click(screen.getByRole('button', { name: chooserCta }))
  await user.click(await screen.findByRole('button', { name: "Got it, let's start" }))
  await user.click(await screen.findByRole('button', { name: 'Continue' })) // device name (default)
  // generate (~1.2s) → commit → passkey fallback
  await screen.findByRole('heading', { name: 'Set up a passphrase' }, { timeout: 2500 })
  await user.click(screen.getByRole('button', { name: 'Use recovery phrase only' }))
  await user.click(await screen.findByRole('button', { name: "Skip — I'll decide later" }))
  await user.click(await screen.findByRole('button', { name: 'Continue to control plane' }))
  await user.click(await screen.findByRole('button', { name: 'Continue' })) // storage tier
}

describe('OnboardingFlow', () => {
  it('renders the §8.1 chooser with all three CTAs', () => {
    renderFlow()
    expect(screen.getByRole('heading', { name: 'Welcome to Tenshu' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign up free' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'I have Tenshu already' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Just try it locally' })).toBeInTheDocument()
  })

  it('chooser → "Sign up free" advances to the new-user intro', async () => {
    const user = userEvent.setup()
    renderFlow()
    await user.click(screen.getByRole('button', { name: 'Sign up free' }))
    expect(await screen.findByRole('heading', { name: 'How Tenshu works' })).toBeInTheDocument()
  })

  it('new-user happy path: generate → commit reaches the shared passkey tail (no in-flow phrase ceremony)', async () => {
    const user = userEvent.setup()
    const create = vi.fn().mockResolvedValue({ ok: true, value: undefined })
    const sdk = { Realm: { create } } as unknown as OnboardingSdk
    renderFlow(sdk)

    await user.click(screen.getByRole('button', { name: 'Sign up free' }))
    await user.click(await screen.findByRole('button', { name: "Got it, let's start" }))
    await user.click(await screen.findByRole('button', { name: 'Continue' })) // device name (default)

    // Generate auto-advances (~1.2s) and commits via Realm.create with a
    // freshly generated 24-word phrase — show + confirm moved to Settings.
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1), { timeout: 2500 })
    const arg = create.mock.calls[0]![0] as { readonly mnemonic: string; readonly deviceName: string }
    expect(arg.deviceName).toBe('This Browser')
    expect(arg.mnemonic.split(' ')).toHaveLength(24)

    // Commit → shared tail. jsdom lacks WebAuthn so the passkey screen
    // renders its passphrase fallback.
    expect(
      await screen.findByRole('heading', { name: 'Set up a passphrase' }),
    ).toBeInTheDocument()
    // The phrase grid never renders in onboarding now.
    expect(screen.queryByRole('heading', { name: 'Save your recovery phrase' })).toBeNull()
  })

  it('signup intent completes into the account picker (§5.1)', async () => {
    const user = userEvent.setup()
    const sdk = {
      Realm: { create: vi.fn().mockResolvedValue({ ok: true, value: undefined }) },
      Session: { persist: vi.fn().mockResolvedValue(undefined) },
    } as unknown as OnboardingSdk
    renderFlow(sdk)
    await walkNewUserToCompletion(user, 'Sign up free')
    expect(await screen.findByText('PICKER')).toBeInTheDocument()
  })

  it('try-locally intent completes onto the control home (Try-mode)', async () => {
    const user = userEvent.setup()
    const sdk = {
      Realm: { create: vi.fn().mockResolvedValue({ ok: true, value: undefined }) },
      Session: { persist: vi.fn().mockResolvedValue(undefined) },
    } as unknown as OnboardingSdk
    renderFlow(sdk)
    await walkNewUserToCompletion(user, 'Just try it locally')
    expect(await screen.findByText('HOME')).toBeInTheDocument()
  })

  it('intro → "Got it, let\'s start" reaches the new-user device-name screen', async () => {
    const user = userEvent.setup()
    renderFlow()
    await user.click(screen.getByRole('button', { name: 'Sign up free' }))
    await user.click(await screen.findByRole('button', { name: "Got it, let's start" }))
    expect(await screen.findByRole('heading', { name: 'Name this browser' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument()
  })

  it('new-user device-name "Back" returns to the intro screen, not the chooser', async () => {
    const user = userEvent.setup()
    renderFlow()
    await user.click(screen.getByRole('button', { name: 'Sign up free' }))
    await user.click(await screen.findByRole('button', { name: "Got it, let's start" }))
    await user.click(await screen.findByRole('button', { name: 'Back' }))
    // Back → intro (NOT chooser). The `How Tenshu works` title is the
    // intro screen's giveaway.
    expect(await screen.findByRole('heading', { name: 'How Tenshu works' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Welcome to Tenshu' })).toBeNull()
  })

  it('chooser → "I have Tenshu already" reaches the pairing device-name screen', async () => {
    const user = userEvent.setup()
    renderFlow()
    await user.click(screen.getByRole('button', { name: 'I have Tenshu already' }))
    expect(await screen.findByRole('heading', { name: 'Name this browser' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument()
  })

  it('joiner happy path: pair → SAS → paired reaches the shared passkey tail', async () => {
    const user = userEvent.setup()
    // A session whose observer reports `awaiting` then `paired`, so the
    // orchestrator walks await → terminal('paired') → goToTailPasskey.
    const session: PairingSession = {
      observe: cb => {
        cb({ kind: 'awaiting', sas: '482 913' })
        cb({ kind: 'paired' })
        return () => {}
      },
      cancel: () => Promise.resolve(),
    }
    const submit = vi.fn().mockResolvedValue({ ok: true, value: session })
    const sdk = { Pairing: { submit } } as unknown as OnboardingSdk
    renderFlow(sdk)

    await user.click(screen.getByRole('button', { name: 'I have Tenshu already' }))
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    // pairing.submit screen: paste a canonical Control URI, then continue.
    await user.type(
      await screen.findByPlaceholderText(/tenshu:\/\/join/i),
      'tenshu://join?o=o&d=MQYQ&k=AAAAAAAAAAAA&s=OM',
    )
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(submit).toHaveBeenCalled())
    // terminal('paired') → "Set up passkey" → shared tail. jsdom has no
    // WebAuthn, so the passkey screen renders its passphrase fallback.
    await user.click(await screen.findByRole('button', { name: 'Set up passkey' }))
    expect(
      await screen.findByRole('heading', { name: 'Set up a passphrase' }),
    ).toBeInTheDocument()
  })

  it('pairing device-name "Back" returns straight to the chooser, not an intro', async () => {
    const user = userEvent.setup()
    renderFlow()
    await user.click(screen.getByRole('button', { name: 'I have Tenshu already' }))
    await user.click(await screen.findByRole('button', { name: 'Back' }))
    // Back → chooser (NOT intro). The pairing branch has no intro
    // step in the orchestrator's state machine.
    expect(await screen.findByRole('heading', { name: 'Welcome to Tenshu' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'How Tenshu works' })).toBeNull()
  })
})
