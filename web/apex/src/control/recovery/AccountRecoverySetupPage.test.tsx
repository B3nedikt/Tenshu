// §7.4 "Set up account recovery" page (web-roadmap 6.6). Covers the
// reveal → show → confirm → done happy path (recording the capture via
// setRecoveryVerified) and the "coming soon" branch the live SDK returns
// today (revealPhrase → unavailable).
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import type { Mock } from 'vitest'
import { OnboardingSdkContext } from '../../lib/onboardingSdkContext'
import type { OnboardingSdk } from '../../lib/mockOnboarding'

// Stub the IDB-backed verified mark so the test doesn't touch IndexedDB.
vi.mock('../recoveryStatus', () => ({
  setRecoveryVerified: vi.fn().mockResolvedValue(undefined),
}))
import { setRecoveryVerified } from '../recoveryStatus'
import { AccountRecoverySetupPage } from './AccountRecoverySetupPage'

const MNEMONIC = [
  'abandon', 'ability', 'able', 'about', 'above', 'absent',
  'absorb', 'abstract', 'absurd', 'abuse', 'access', 'accident',
  'account', 'accuse', 'achieve', 'acid', 'acoustic', 'acquire',
  'across', 'act', 'action', 'actor', 'actress', 'actual',
]

function renderPage(revealPhrase: OnboardingSdk['Recovery']['revealPhrase']) {
  const sdk = { Recovery: { revealPhrase } } as unknown as OnboardingSdk
  return render(
    <OnboardingSdkContext.Provider value={sdk}>
      <MemoryRouter>
        <AccountRecoverySetupPage />
      </MemoryRouter>
    </OnboardingSdkContext.Provider>,
  )
}

function askedPositions(): number[] {
  return screen
    .getAllByText(/^Word \d+$/)
    .map(el => Number(el.textContent!.replace(/^Word /, '')))
}

describe('AccountRecoverySetupPage', () => {
  it('reveals → shows → confirms → marks recovery verified', async () => {
    const user = userEvent.setup()
    const revealPhrase = vi.fn().mockResolvedValue({ ok: true, value: MNEMONIC.join(' ') })
    renderPage(revealPhrase as OnboardingSdk['Recovery']['revealPhrase'])

    await user.click(screen.getByRole('button', { name: 'Show my recovery phrase' }))

    // Show screen: gate Continue behind the saved-it checkbox.
    await screen.findByRole('heading', { name: 'Save your recovery phrase' })
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    // Confirm the words the screen asks for.
    await screen.findByRole('heading', { name: "Let's check you saved it" })
    for (const pos of askedPositions()) {
      await user.type(screen.getByLabelText(`Word ${pos}`), MNEMONIC[pos - 1]!)
    }
    await user.click(screen.getByRole('button', { name: 'Verify' }))

    await screen.findByText('Recovery is set up')
    expect(setRecoveryVerified as Mock).toHaveBeenCalled()
  })

  it('surfaces a "coming soon" notice when the SDK reveal is unavailable', async () => {
    const user = userEvent.setup()
    const revealPhrase = vi.fn().mockResolvedValue({ ok: false, error: { kind: 'unavailable' } })
    renderPage(revealPhrase as OnboardingSdk['Recovery']['revealPhrase'])

    await user.click(screen.getByRole('button', { name: 'Show my recovery phrase' }))
    expect(await screen.findByText(/coming soon/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Recovery' })).toBeInTheDocument()
  })
})
