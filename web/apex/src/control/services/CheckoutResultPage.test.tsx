// Where Stripe drops a browser customer back — and where the purchase is fetched.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { CheckoutResultPage } from './CheckoutResultPage'
import { PRODUCT_BASIC, PRODUCT_FREE } from './commerceApi'
import { MockPublisherClient, type PlanSyncResult } from './publisherClient'
import { PublisherClientProvider } from './PublisherClientProvider'

async function signedIn(last: PlanSyncResult, ...later: PlanSyncResult[]) {
  const answers = [last, ...later]
  const client = new MockPublisherClient(null)
  await client.register('buyer@example.com', null)
  await client.verify('123456')
  const syncPlan = vi.fn<() => Promise<PlanSyncResult>>()
  answers.forEach(answer => syncPlan.mockResolvedValueOnce(answer))
  // Whatever commerce says last, it keeps saying.
  syncPlan.mockResolvedValue(later[later.length - 1] ?? last)
  client.syncPlan = syncPlan
  return { client, syncPlan }
}

function renderWith(query: string, client = new MockPublisherClient(null)) {
  render(
    <PublisherClientProvider client={client}>
      <MemoryRouter initialEntries={[`/control/services/checkout-result${query}`]}>
        <CheckoutResultPage attempts={3} intervalMs={0} />
      </MemoryRouter>
    </PublisherClientProvider>,
  )
}

const FREE: PlanSyncResult = { kind: 'synced', productId: PRODUCT_FREE }
const BASIC: PlanSyncResult = { kind: 'synced', productId: PRODUCT_BASIC }

describe('CheckoutResultPage', () => {
  // ⚠⚠ A buyer who never signed in here has no session to fetch the plan with:
  // the account was created by the webhook from the email typed into Stripe.
  it('asks a buyer with no session to confirm the checkout email', () => {
    renderWith('?status=success')
    expect(screen.getByText(/confirm your email/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Confirm email' })).toHaveAttribute(
      'href',
      '/control/services/signup?return=checkout',
    )
    expect(screen.queryByText(/is active|payment succeeded/i)).not.toBeInTheDocument()
  })

  it('reports the plan commerce signed, not the query parameter', async () => {
    const { client } = await signedIn(BASIC)
    renderWith('?status=success', client)
    expect(await screen.findByText('Your plan is active')).toBeInTheDocument()
    expect(screen.getByText(/Basic storage is now active/)).toBeInTheDocument()
  })

  // The redirect and the webhook race: Free first means "not yet".
  it('keeps asking while commerce still answers Free', async () => {
    const { client, syncPlan } = await signedIn(FREE, FREE, BASIC)
    renderWith('?status=success', client)
    expect(await screen.findByText('Your plan is active')).toBeInTheDocument()
    expect(syncPlan).toHaveBeenCalledTimes(3)
  })

  it('stops waiting, and says which email it looked under', async () => {
    const { client, syncPlan } = await signedIn(FREE)
    renderWith('?status=success', client)
    expect(await screen.findByText(/on its way/)).toBeInTheDocument()
    expect(screen.getByText(/buyer@example\.com/)).toBeInTheDocument()
    expect(screen.queryByText('Your plan is active')).not.toBeInTheDocument()
    expect(syncPlan).toHaveBeenCalledTimes(3)
  })

  it('is unambiguous that a cancel charged nothing, and fetches nothing', async () => {
    const { client, syncPlan } = await signedIn(BASIC)
    renderWith('?status=cancel', client)
    expect(screen.getByText(/Nothing was charged/)).toBeInTheDocument()
    expect(syncPlan).not.toHaveBeenCalled()
  })

  it('handles the portal return', () => {
    renderWith('?status=portal')
    expect(screen.getByText(/Back from billing/)).toBeInTheDocument()
  })

  it('always offers the way back to Services', () => {
    renderWith('')
    expect(screen.getByRole('link', { name: 'Back to Services' })).toBeInTheDocument()
  })
})
