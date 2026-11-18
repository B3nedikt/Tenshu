// §4b new-user signup screens (roadmap 6.4, reordered in 6.6). Component-
// level coverage for the generate → commit chain and the failure screen. The phrase show + confirm screens moved to Settings →
// Set up account recovery (§7.4); their coverage moved with them to
// `control/recovery/RecoveryCaptureScreens.test.tsx`. The generate screen
// runs the real Kotlin `Bip39` bundle unmocked in jsdom.
import type { ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { OnboardingSdkContext } from '../../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../../lib/mockOnboarding'
import {
  CommittingRealmScreen,
  GenerateRealmScreen,
  RealmCreateFailedScreen,
} from './NewUserScreens'

// First 24 BIP-39 English words — all valid; used here to drive the
// CommittingRealmScreen `Realm.create` assertion.
const MNEMONIC = [
  'abandon', 'ability', 'able', 'about', 'above', 'absent',
  'absorb', 'abstract', 'absurd', 'abuse', 'access', 'accident',
  'account', 'accuse', 'achieve', 'acid', 'acoustic', 'acquire',
  'across', 'act', 'action', 'actor', 'actress', 'actual',
]

function sdkWith(create: OnboardingSdk['Realm']['create']): OnboardingSdk {
  return { Realm: { create } } as unknown as OnboardingSdk
}

function withSdk(sdk: OnboardingSdk, ui: ReactElement) {
  return render(
    <OnboardingSdkContext.Provider value={sdk}>{ui}</OnboardingSdkContext.Provider>,
  )
}

describe('GenerateRealmScreen', () => {
  it('auto-advances with a freshly generated 24-word mnemonic', async () => {
    const onReady = vi.fn()
    render(<GenerateRealmScreen onReady={onReady} />)
    expect(screen.getByText('Generating identity…')).toBeInTheDocument()
    await waitFor(() => expect(onReady).toHaveBeenCalled(), { timeout: 2500 })
    const mnemonic = onReady.mock.calls[0]![0] as string
    expect(mnemonic.split(' ')).toHaveLength(24)
  })
})

describe('CommittingRealmScreen', () => {
  it('calls Realm.create and reports success', async () => {
    const create = vi
      .fn<OnboardingSdk['Realm']['create']>()
      .mockResolvedValue({ ok: true, value: undefined })
    const onCommitted = vi.fn()
    const onFailed = vi.fn()
    withSdk(
      sdkWith(create),
      <CommittingRealmScreen
        mnemonic={MNEMONIC.join(' ')}
        deviceName="Work laptop"
        onCommitted={onCommitted}
        onFailed={onFailed}
      />,
    )
    await waitFor(() => expect(onCommitted).toHaveBeenCalled())
    expect(create).toHaveBeenCalledWith({ mnemonic: MNEMONIC.join(' '), deviceName: 'Work laptop' })
    expect(onFailed).not.toHaveBeenCalled()
  })

  it('reports the SDK error message on failure', async () => {
    const create = vi
      .fn<OnboardingSdk['Realm']['create']>()
      .mockResolvedValue({ ok: false, error: 'relay unreachable' })
    const onFailed = vi.fn()
    withSdk(
      sdkWith(create),
      <CommittingRealmScreen
        mnemonic={MNEMONIC.join(' ')}
        deviceName="Work laptop"
        onCommitted={vi.fn()}
        onFailed={onFailed}
      />,
    )
    await waitFor(() => expect(onFailed).toHaveBeenCalledWith('relay unreachable'))
  })
})

describe('RealmCreateFailedScreen', () => {
  it('renders the error + retain-phrase note and wires retry/cancel', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    const onCancel = vi.fn()
    render(
      <RealmCreateFailedScreen message="relay unreachable" onRetry={onRetry} onCancel={onCancel} />,
    )
    expect(screen.getByText('relay unreachable')).toBeInTheDocument()
    expect(screen.getByText(/still in this tab/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onRetry).toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Cancel and start over' }))
    expect(onCancel).toHaveBeenCalled()
  })
})
