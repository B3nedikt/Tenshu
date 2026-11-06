// §8.2 Try-mode banner: persistent while this browser's data really is local-only.
//
// ⚠ The load-bearing cases here are the last three. The banner used to gate on the
// commerce account alone, so it told a paired, funded, actively-syncing browser that
// "your data lives only in this browser" — a claim about data location derived from a
// signal about identity, which by House Rule #1 can never sync. If `entitledRealm_*`
// or `unsettled_*` go red, that lie is back; fix the gate rather than the test.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import {
  MockPublisherClient,
  type PublisherClient,
} from './services/publisherClient'
import { PublisherClientProvider } from './services/PublisherClientProvider'
import { StorageStatusProvider } from './services/StorageStatusProvider'
import { useStorageStatus, type StorageStatusReader } from './services/storageStatusContext'
import { TryModeBanner } from './TryModeBanner'

/** No storage source configured — settles immediately as "no storage". */
const noStorage: StorageStatusReader = () => Promise.resolve(null)

/** A funded realm, as a paired browser sees it once the owner's Entitlement syncs. */
const fundedStorage: StorageStatusReader = () =>
  Promise.resolve({ hasStorage: true, usedBytes: 5, limitBytes: 100 })

/** Never resolves: the "have not heard back yet" window. */
const pendingStorage: StorageStatusReader = () => new Promise(() => {})

/**
 * Renders a marker once the storage read has settled.
 *
 * ⚠ Needed because the banner renders *nothing* in the cases under test, and "absent
 * because the entitlement says so" is indistinguishable from "absent because the read
 * had not resolved yet". Without this the funded-realm assertion would pass even if the
 * gate were deleted — it would just be observing the first paint.
 */
function SettleProbe() {
  const { settled } = useStorageStatus()
  return settled ? <span data-testid="settled" /> : null
}

function renderWith(client: PublisherClient, read: StorageStatusReader = noStorage) {
  return render(
    <PublisherClientProvider client={client}>
      <StorageStatusProvider read={read}>
        <MemoryRouter>
          <TryModeBanner />
          <SettleProbe />
        </MemoryRouter>
      </StorageStatusProvider>
    </PublisherClientProvider>,
  )
}

const banner = () => screen.queryByText("You're trying Tenshu locally")

describe('TryModeBanner', () => {
  it('renders the §8.2 framing when there is no account and no storage', async () => {
    renderWith(new MockPublisherClient(null))
    expect(await screen.findByText("You're trying Tenshu locally")).toBeInTheDocument()
    expect(
      screen.getByText(/Your data lives only in this browser/),
    ).toBeInTheDocument()
  })

  it('links the Sign-up CTA to the account picker', async () => {
    renderWith(new MockPublisherClient(null))
    const link = await screen.findByRole('link', { name: /Sign up free/ })
    expect(link).toHaveAttribute('href', '/control/services/picker')
  })

  it('exposes itself as a status region for assistive tech', async () => {
    renderWith(new MockPublisherClient(null))
    expect(await screen.findByRole('status')).toBeInTheDocument()
  })

  it('disappears once an account is bound', async () => {
    const client = new MockPublisherClient(null)
    await client.register('test@example.com', null)
    await client.verify('123456')
    renderWith(client)
    expect(banner()).toBeNull()
  })

  it('entitledRealm_staysHidden: a funded realm hides it even with no account here', async () => {
    // The regression. A browser paired into someone's funded realm holds no commerce
    // session — that binding is device-local PII and cannot sync — but its files DO
    // reach the relay. Claiming otherwise is the defect this gate exists to prevent.
    renderWith(new MockPublisherClient(null), fundedStorage)
    // Wait for the read to actually settle, so absence means "the gate hid it",
    // not "the first paint had not resolved".
    await screen.findByTestId('settled')
    expect(banner()).toBeNull()
  })

  it('unsettled_showsNothing: asserts nothing before the storage read resolves', () => {
    // `SyncBannerState`'s `storage == null -> null`: with the signal absent, every
    // statement below it is a guess presented as fact. Better to render nothing than
    // to flash "your data is local-only" at a device that is about to prove otherwise.
    renderWith(new MockPublisherClient(null), pendingStorage)
    expect(banner()).toBeNull()
  })

  it('unfundedRealm_showsBanner: settled with no storage is the honest local-only case', async () => {
    renderWith(new MockPublisherClient(null), noStorage)
    expect(await screen.findByText("You're trying Tenshu locally")).toBeInTheDocument()
  })
})
