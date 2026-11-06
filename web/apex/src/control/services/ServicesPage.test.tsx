// §6.1 Services tab: the storage half (synced, always) and the identity half
// (session-only). See `servicesCardState.ts` for why they are separate.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { MockPublisherClient } from './publisherClient'
import { PublisherClientProvider } from './PublisherClientProvider'
import { StorageStatusProvider } from './StorageStatusProvider'
import { ServicesPage } from './ServicesPage'
import {
  BASIC_LIMIT_BYTES,
  FREE_LIMIT_BYTES,
  PREMIUM_LIMIT_BYTES,
  type StorageStatusSnapshot,
} from './servicesCardState'

function renderWith(client: MockPublisherClient, status: StorageStatusSnapshot | null) {
  return render(
    <PublisherClientProvider client={client}>
      <StorageStatusProvider read={() => Promise.resolve(status)}>
        <MemoryRouter>
          <ServicesPage />
        </MemoryRouter>
      </StorageStatusProvider>
    </PublisherClientProvider>,
  )
}

const FREE = { hasStorage: true, usedBytes: 0, limitBytes: FREE_LIMIT_BYTES }

async function signedIn(): Promise<MockPublisherClient> {
  const client = new MockPublisherClient(null)
  await client.register('test@example.com', null)
  await client.verify('123456')
  return client
}

describe('ServicesPage', () => {
  it('shows the empty state with a Sign up CTA when there is no account and no storage', async () => {
    renderWith(new MockPublisherClient(null), null)
    expect(screen.getByText('Tenshu Relay')).toBeInTheDocument()
    expect(await screen.findByText(/No account yet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign up' })).toHaveAttribute(
      'href',
      '/control/services/picker',
    )
  })

  it('shows the synced plan + storage + identity when signed in here', async () => {
    renderWith(await signedIn(), { ...FREE, usedBytes: 512 * 1024 * 1024 })

    expect(await screen.findByText('Free')).toBeInTheDocument()
    expect(screen.getByText('512 MB of 5 GB used')).toBeInTheDocument()
    expect(screen.getByText('Verified with test@example.com')).toBeInTheDocument()
  })

  // ⚠⚠ The defect this change exists to fix. The card used to be gated on
  // `account === null` and read literals off the mock, so a device with a real
  // Basic entitlement but no local session showed nothing — and signing in
  // showed "0 B of 1 GB" over live figures.
  it('shows real figures on a paired device that never signed in here', async () => {
    renderWith(new MockPublisherClient(null), {
      hasStorage: true,
      usedBytes: 10 * 1024 * 1024 * 1024,
      limitBytes: BASIC_LIMIT_BYTES,
    })

    expect(await screen.findByText('Basic')).toBeInTheDocument()
    expect(screen.getByText('10 GB of 50 GB used')).toBeInTheDocument()
    // No session ⇒ no identity row and no billing actions, but the plan is real.
    expect(screen.queryByText(/Verified with/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Disconnect' })).not.toBeInTheDocument()
  })

  it('does not lose the figures when a session appears', async () => {
    const withSession = await signedIn()
    const { unmount } = renderWith(withSession, { ...FREE, usedBytes: 1024 * 1024 })
    expect(await screen.findByText('1 MB of 5 GB used')).toBeInTheDocument()
    unmount()

    renderWith(new MockPublisherClient(null), { ...FREE, usedBytes: 1024 * 1024 })
    expect(await screen.findByText('1 MB of 5 GB used')).toBeInTheDocument()
  })

  // ⚠ The exact CE-6 defect, on the exact screen we ask people to buy from.
  it('renders the flagship tier as TB, not "2048 GB"', async () => {
    renderWith(new MockPublisherClient(null), {
      hasStorage: true,
      usedBytes: 0,
      limitBytes: PREMIUM_LIMIT_BYTES,
    })

    expect(await screen.findByText('0 B of 2 TB used')).toBeInTheDocument()
    expect(screen.queryByText(/2048 GB/)).not.toBeInTheDocument()
  })

  it('says storage is being set up rather than showing 0 B of 0 B', async () => {
    renderWith(await signedIn(), { hasStorage: true, usedBytes: 0, limitBytes: 0 })

    expect(await screen.findByText(/Setting up your storage/)).toBeInTheDocument()
    expect(screen.queryByText(/0 B of 0 B/)).not.toBeInTheDocument()
  })

  it('reports no storage plan when signed in but the entitlement has not arrived', async () => {
    renderWith(await signedIn(), null)

    expect(await screen.findByText(/No storage plan on this device/)).toBeInTheDocument()
    expect(screen.getByText('Verified with test@example.com')).toBeInTheDocument()
  })

  it('exposes management CTAs only with a session', async () => {
    renderWith(await signedIn(), FREE)

    expect(await screen.findByRole('link', { name: 'Get more storage' })).toBeInTheDocument()
    // Billing management is a real action against Stripe's portal, so it is a
    // button that calls the service, not a link to another apex route.
    expect(screen.getByRole('button', { name: 'Manage billing' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Disconnect' })).toHaveAttribute(
      'href',
      '/control/services/disconnect',
    )
  })
})
