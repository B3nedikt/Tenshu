// `TestRecoveryDialog` walks: entry → verifying → verified | failed.
// On verified, it stamps the IDB `verifiedAtMs` mark and notifies the
// caller. Tests:
//   - 24-word gate on the Verify button
//   - happy path: entry → verifying → verified, with setRecoveryVerified
//     called and onVerified fired exactly once
//   - failed → Try again returns to entry
//   - error-copy variants (wrongRealm + invalidMnemonic.unknownWord)
//     map through `describeError` correctly.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {
  PhraseUnlockError,
  SessionApi,
  SessionToken,
  TResult,
} from '@tenshu/sdk/onboarding'
import { OnboardingSdkContext } from '../../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../../lib/mockOnboarding'

const setRecoveryVerifiedMock = vi.fn<(verifiedAtMs: number) => Promise<void>>(async () => undefined)

vi.mock('../recoveryStatus', async () => {
  const actual = await vi.importActual<typeof import('../recoveryStatus')>('../recoveryStatus')
  return {
    ...actual,
    setRecoveryVerified: (ts: number) => setRecoveryVerifiedMock(ts),
  }
})

import { TestRecoveryDialog } from './TestRecoveryDialog'

const TWENTY_FOUR_ABANDONS = Array<string>(24).fill('abandon').join(' ')

function stubSdk(unlock: TResult<SessionToken, PhraseUnlockError>): {
  sdk: OnboardingSdk
  unlockWithPhrase: ReturnType<typeof vi.fn>
  persist: ReturnType<typeof vi.fn>
} {
  const unlockWithPhrase = vi.fn(async () => unlock)
  const persist = vi.fn(async () => undefined)
  const session: Partial<SessionApi> = {
    unlockWithPhrase: unlockWithPhrase as SessionApi['unlockWithPhrase'],
    persist: persist as SessionApi['persist'],
  }
  return {
    sdk: { Session: session } as unknown as OnboardingSdk,
    unlockWithPhrase,
    persist,
  }
}

function renderDialog(sdk: OnboardingSdk, onVerified: () => void = () => {}) {
  return render(
    <OnboardingSdkContext.Provider value={sdk}>
      <TestRecoveryDialog onClose={() => {}} onVerified={onVerified} />
    </OnboardingSdkContext.Provider>,
  )
}

function defaultUnlockOk(): TResult<SessionToken, PhraseUnlockError> {
  return {
    ok: true,
    value: { bytes: new Int8Array(32), expiresAtMs: Date.now() + 86_400_000 },
  }
}

describe('TestRecoveryDialog', () => {
  it('keeps Verify disabled until 24 whitespace-separated words are typed', async () => {
    const user = userEvent.setup()
    const { sdk, unlockWithPhrase } = stubSdk(defaultUnlockOk())
    renderDialog(sdk)
    const verify = screen.getByRole('button', { name: 'Verify' })
    expect(verify).toBeDisabled()
    await user.type(screen.getByPlaceholderText(/word1 word2 word3/), TWENTY_FOUR_ABANDONS)
    expect(screen.getByText('24 / 24 words')).toBeInTheDocument()
    expect(verify).not.toBeDisabled()
    expect(unlockWithPhrase).not.toHaveBeenCalled()
  })

  it('happy path: entry → verifying spinner → verified copy + IDB stamp + onVerified', async () => {
    const user = userEvent.setup()
    const onVerified = vi.fn()
    const { sdk, unlockWithPhrase, persist } = stubSdk(defaultUnlockOk())
    renderDialog(sdk, onVerified)
    await user.type(screen.getByPlaceholderText(/word1 word2 word3/), TWENTY_FOUR_ABANDONS)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(
      await screen.findByText(/Your phrase matches this realm\./i),
    ).toBeInTheDocument()
    await waitFor(() => {
      expect(unlockWithPhrase).toHaveBeenCalledWith(TWENTY_FOUR_ABANDONS)
      expect(persist).toHaveBeenCalledTimes(1)
      expect(setRecoveryVerifiedMock).toHaveBeenCalledTimes(1)
      expect(onVerified).toHaveBeenCalledTimes(1)
    })
  })

  it('wrongRealm failure surfaces the realm-mismatch copy with Try again returning to entry', async () => {
    const user = userEvent.setup()
    const { sdk } = stubSdk({ ok: false, error: { kind: 'wrongRealm' } })
    renderDialog(sdk)
    await user.type(screen.getByPlaceholderText(/word1 word2 word3/), TWENTY_FOUR_ABANDONS)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(
      await screen.findByText(/That phrase doesn't match this realm\./i),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    // Back to entry — the textarea + Verify reappear, the previously
    // typed phrase has been preserved by the orchestrator (the parent
    // owns it across step transitions).
    expect(screen.getByPlaceholderText(/word1 word2 word3/)).toHaveValue(TWENTY_FOUR_ABANDONS)
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument()
  })

  it('invalidMnemonic.unknownWord surfaces the typo-word copy', async () => {
    const user = userEvent.setup()
    const { sdk } = stubSdk({
      ok: false,
      error: {
        kind: 'invalidMnemonic',
        reason: { kind: 'unknownWord', word: 'qwerty' },
      },
    })
    renderDialog(sdk)
    await user.type(screen.getByPlaceholderText(/word1 word2 word3/), TWENTY_FOUR_ABANDONS)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(
      await screen.findByText(/"qwerty" isn't in the BIP-39 wordlist/i),
    ).toBeInTheDocument()
  })
})
