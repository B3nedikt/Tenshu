// §5.1 tier picker: the tiers commerce actually sells.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { AccountPickerPage } from './AccountPickerPage'
import { PublisherClientProvider } from './PublisherClientProvider'
import { MockPublisherClient } from './publisherClient'
import { PRODUCT_BASIC, PRODUCT_PREMIUM } from './commerceApi'

function renderPage(client = new MockPublisherClient()) {
  return render(
    <PublisherClientProvider client={client}>
      <MemoryRouter>
        <AccountPickerPage />
      </MemoryRouter>
    </PublisherClientProvider>,
  )
}

describe('AccountPickerPage', () => {
  it('offers Free, Basic and Premium with their quotas and prices', () => {
    renderPage()
    expect(screen.getByText('Free', { selector: 'h2' })).toBeInTheDocument()
    expect(screen.getByText('Basic')).toBeInTheDocument()
    expect(screen.getByText('Premium')).toBeInTheDocument()
    expect(screen.getByText(/5 GB of encrypted storage/)).toBeInTheDocument()
    expect(screen.getByText(/50 GB of encrypted storage/)).toBeInTheDocument()
    // ⚠ The flagship tier reads as TB — the CE-6 defect was "2048 GB" on the
    // screen people buy from.
    expect(screen.getByText(/2 TB of encrypted storage/)).toBeInTheDocument()
    expect(screen.getByText('€2.99 / month')).toBeInTheDocument()
    expect(screen.getByText('€9.99 / month')).toBeInTheDocument()
  })

  // ⚠⚠ A paid tier must route through the acknowledgement step, never straight to
  // a payment page: the service refuses a checkout without it, and the customer
  // has to have actually seen it.
  it('routes paid tiers to the acknowledgement step', () => {
    renderPage(
      new MockPublisherClient({
        identityMethod: { kind: 'email', address: 'a@example.com' },
        displayName: null,
      }),
    )
    const links = screen.getAllByRole('link', { name: 'Choose' })
    expect(links.map(l => l.getAttribute('href'))).toEqual([
      `/control/services/checkout/${PRODUCT_BASIC}`,
      `/control/services/checkout/${PRODUCT_PREMIUM}`,
    ])
  })

  // ⚠⚠ Signed out, the email step comes first — and comes back to the purchase.
  it('routes a signed-out buyer through sign-in', () => {
    renderPage()
    const links = screen.getAllByRole('link', { name: 'Choose' })
    expect(links.map(l => l.getAttribute('href'))).toEqual([
      `/control/services/signup?return=checkout&product=${PRODUCT_BASIC}`,
      `/control/services/signup?return=checkout&product=${PRODUCT_PREMIUM}`,
    ])
  })

  it('routes Free to the email flow and offers redeem', () => {
    renderPage()
    expect(screen.getByRole('link', { name: 'Sign up free' })).toHaveAttribute(
      'href',
      '/control/services/signup',
    )
    expect(screen.getByRole('link', { name: /Redeem/ })).toHaveAttribute(
      'href',
      '/control/services/redeem',
    )
  })

  it('offers no Lifetime or Rei Key card', () => {
    renderPage()
    expect(screen.queryByText(/Lifetime/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Rei/)).not.toBeInTheDocument()
  })
})
