// §7.4 phrase-capture screens, moved out of the new-user onboarding path
// (web-roadmap 6.6). These tests followed `ShowMnemonicScreen` +
// `ConfirmMnemonicScreen` from `NewUserScreens.test.tsx` when the screens
// moved here; the screens are unchanged, so the coverage is too.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfirmMnemonicScreen, ShowMnemonicScreen } from './RecoveryCaptureScreens'

// First 24 BIP-39 English words — all valid so WordlistInput doesn't flag
// them; ConfirmMnemonicScreen compares typed values against these.
const MNEMONIC = [
  'abandon', 'ability', 'able', 'about', 'above', 'absent',
  'absorb', 'abstract', 'absurd', 'abuse', 'access', 'accident',
  'account', 'accuse', 'achieve', 'acid', 'acoustic', 'acquire',
  'across', 'act', 'action', 'actor', 'actress', 'actual',
]

// Read which 1-based positions the confirm screen is asking for, from the
// rendered "Word N" labels.
function askedPositions(): number[] {
  return screen
    .getAllByText(/^Word \d+$/)
    .map(el => Number(el.textContent!.replace(/^Word /, '')))
}

describe('ShowMnemonicScreen', () => {
  it('gates Continue behind the saved-it checkbox', async () => {
    const user = userEvent.setup()
    const onContinue = vi.fn()
    render(<ShowMnemonicScreen mnemonic={MNEMONIC} onContinue={onContinue} />)
    // All 24 words render in the grid.
    for (const word of MNEMONIC) expect(screen.getByText(word)).toBeInTheDocument()
    const cont = screen.getByRole('button', { name: 'Continue' })
    expect(cont).toBeDisabled()
    await user.click(screen.getByRole('checkbox'))
    expect(cont).toBeEnabled()
    await user.click(cont)
    expect(onContinue).toHaveBeenCalled()
  })
})

describe('ConfirmMnemonicScreen', () => {
  it('confirms when the asked-for words are entered correctly', async () => {
    const user = userEvent.setup()
    const onConfirmed = vi.fn()
    render(
      <ConfirmMnemonicScreen
        mnemonic={MNEMONIC}
        onConfirmed={onConfirmed}
        onRedisplay={vi.fn()}
      />,
    )
    for (const pos of askedPositions()) {
      await user.type(screen.getByLabelText(`Word ${pos}`), MNEMONIC[pos - 1]!)
    }
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(onConfirmed).toHaveBeenCalled()
  })

  it('shows a mismatch error and forces re-display after three misses', async () => {
    const user = userEvent.setup()
    const onConfirmed = vi.fn()
    const onRedisplay = vi.fn()
    render(
      <ConfirmMnemonicScreen
        mnemonic={MNEMONIC}
        onConfirmed={onConfirmed}
        onRedisplay={onRedisplay}
      />,
    )
    const positions = askedPositions()
    // Enter a deliberately wrong (but valid) word in every field.
    async function fillWrong() {
      for (const pos of positions) {
        const wrong = MNEMONIC[pos - 1] === 'zone' ? 'zoo' : 'zone'
        const field = screen.getByLabelText(`Word ${pos}`)
        await user.clear(field)
        await user.type(field, wrong)
      }
    }
    await fillWrong()
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/don't match/i)
    await fillWrong()
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    await fillWrong()
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(screen.getByRole('alert')).toHaveTextContent(/three misses/i)
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled()
    expect(onConfirmed).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Re-display phrase' }))
    expect(onRedisplay).toHaveBeenCalled()
  })
})
