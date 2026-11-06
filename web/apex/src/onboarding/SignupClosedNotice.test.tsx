// The closed notice is reachable from a deep link, so the two things worth
// pinning are that it says why and that it is not a dead end.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { SignupClosedNotice } from './SignupClosedNotice'

describe('SignupClosedNotice', () => {
  it('explains the closure and offers a way back to the landing page', () => {
    render(
      <MemoryRouter>
        <SignupClosedNotice />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: "Tenshu isn't open yet" })).toBeInTheDocument()
    // Reassurance for the case that actually worries people: a returning user
    // whose realm is already in this browser.
    expect(screen.getByText(/still unlock and carry on/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Back to tenshu\.app/ })).toHaveAttribute('href', '/')
  })
})
