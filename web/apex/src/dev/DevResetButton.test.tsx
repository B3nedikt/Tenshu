import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DevResetButton } from './DevResetButton'

// `import.meta.env.DEV` is true under vitest, so the button renders. We
// only smoke-test the UI gesture (collapsed → confirm controls); the
// actual IndexedDB wipe + reload isn't exercised in jsdom.
describe('DevResetButton', () => {
  it('reveals the confirm controls after the first click', async () => {
    const user = userEvent.setup()
    render(<DevResetButton />)
    await user.click(screen.getByRole('button', { name: /reset local state/i }))
    expect(screen.getByText(/wipe local realm/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Wipe' })).toBeInTheDocument()
    // Cancel collapses back to the single trigger.
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('button', { name: /reset local state/i })).toBeInTheDocument()
    expect(screen.queryByText(/wipe local realm/i)).toBeNull()
  })
})
