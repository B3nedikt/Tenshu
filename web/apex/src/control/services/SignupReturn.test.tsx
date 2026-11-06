// Where the email step sends a buyer afterwards.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { FreeAccountSignupPage } from './FreeAccountSignupPage'
import { PRODUCT_BASIC } from './commerceApi'
import { MockPublisherClient } from './publisherClient'
import { PublisherClientProvider } from './PublisherClientProvider'

function Landed() {
  const location = useLocation()
  return <p>landed {location.pathname + location.search}</p>
}

async function signInFrom(query: string) {
  const user = userEvent.setup()
  render(
    <PublisherClientProvider client={new MockPublisherClient()}>
      <MemoryRouter initialEntries={[`/control/services/signup${query}`]}>
        <Routes>
          <Route path="/control/services/signup" element={<FreeAccountSignupPage />} />
          <Route path="*" element={<Landed />} />
        </Routes>
      </MemoryRouter>
    </PublisherClientProvider>,
  )
  await user.type(screen.getByLabelText('Email address'), 'buyer@example.com')
  await user.click(screen.getByRole('button', { name: 'Send code' }))
  await user.type(await screen.findByLabelText('6-digit code'), '123456')
  await user.click(screen.getByRole('button', { name: 'Continue' }))
}

describe('FreeAccountSignupPage, on the way to a purchase', () => {
  it('carries on to the tier the buyer picked', async () => {
    await signInFrom(`?return=checkout&product=${PRODUCT_BASIC}`)
    expect(
      await screen.findByText(`landed /control/services/checkout/${PRODUCT_BASIC}`),
    ).toBeInTheDocument()
  })

  // The product comes from the URL, so it is checked against the real tiers.
  it('does not carry on to a product that is not sold', async () => {
    await signInFrom('?return=checkout&product=tenshu.drive.nonsense')
    expect(
      await screen.findByText('landed /control/services/checkout-result?status=success'),
    ).toBeInTheDocument()
  })

  it('goes to Services when it was an ordinary sign-in', async () => {
    await signInFrom('')
    expect(await screen.findByText('landed /control/services')).toBeInTheDocument()
  })
})
