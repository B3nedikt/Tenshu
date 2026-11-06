// The purchase step. The acknowledgement is the whole reason this screen exists.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import i18n from '../../i18n'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { CheckoutPage } from './CheckoutPage'
import { PublisherClientProvider } from './PublisherClientProvider'
import { MockPublisherClient } from './publisherClient'
import { PRODUCT_BASIC, PRODUCT_PREMIUM } from './commerceApi'
import type { PublisherClient } from './publisherClient'

const SIGNED_IN = {
  identityMethod: { kind: 'email', address: 'a@example.com' },
  displayName: null,
} as const

function renderAt(productId: string, client: PublisherClient = new MockPublisherClient(SIGNED_IN)) {
  render(
    <PublisherClientProvider client={client}>
      <MemoryRouter initialEntries={[`/control/services/checkout/${productId}`]}>
        <Routes>
          <Route path="/control/services/checkout/:productId" element={<CheckoutPage />} />
          <Route path="/control/services/signup" element={<SignupProbe />} />
        </Routes>
      </MemoryRouter>
    </PublisherClientProvider>,
  )
  return client
}

function SignupProbe() {
  return <p>sign-in step{useLocation().search}</p>
}

describe('CheckoutPage', () => {
  // ⚠⚠ Paying is not reachable signed out, by any route in: the email Stripe
  // charges has to be the one this device holds a session for.
  it('sends a signed-out buyer to sign in, and remembers what they were buying', () => {
    renderAt(PRODUCT_BASIC, new MockPublisherClient())
    expect(
      screen.getByText(`sign-in step?return=checkout&product=${PRODUCT_BASIC}`),
    ).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('names the tier being bought', () => {
    renderAt(PRODUCT_PREMIUM)
    expect(screen.getByText('Premium — 2 TB')).toBeInTheDocument()
  })

  // ⚠⚠ The gate. `/commerce/checkout` answers 400 without the acknowledgement, so
  // a screen that let you past it would just fail at the service — after the
  // customer thought they were buying something.
  it('will not start a checkout until the acknowledgement is ticked', async () => {
    const user = userEvent.setup()
    const client = new MockPublisherClient(SIGNED_IN)
    const spy = vi.spyOn(client, 'startCheckout')
    renderAt(PRODUCT_BASIC, client)

    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox'))
    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeEnabled()
    expect(spy).not.toHaveBeenCalled()
  })

  it('passes the acknowledgement through when it starts', async () => {
    const user = userEvent.setup()
    const client = new MockPublisherClient(SIGNED_IN)
    const spy = vi.spyOn(client, 'startCheckout').mockResolvedValue({ kind: 'unavailable' })
    renderAt(PRODUCT_BASIC, client)

    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }))
    expect(spy).toHaveBeenCalledWith(PRODUCT_BASIC, true)
  })

  // ⚠⚠ The tick is the express request to start early, NOT a waiver: a storage plan is a digital
  // service, so a withdrawal owes pro-rata value (§ 357a Abs. 2 BGB) and the right survives. The
  // linked notice is what starts the period (§ 356 Abs. 3). Pinned so neither drifts back.
  it('asks for an early start with pro-rata payment, links the withdrawal notice, and never says the right is lost', () => {
    renderAt(PRODUCT_BASIC)
    expect(
      screen.getByText('I expressly ask Tenshu to start before the withdrawal period ends. I understand that if I withdraw, I pay for the time up to my withdrawal.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Right of withdrawal and model form' })).toHaveAttribute(
      'href',
      'https://tenshu.app/legal/terms/en#withdrawal',
    )
    expect(screen.queryByText(/give up|lose|verliere|aufgebe/i)).not.toBeInTheDocument()
  })

  it('says the same in German', async () => {
    await i18n.changeLanguage('de')
    try {
      renderAt(PRODUCT_BASIC)
      expect(screen.getByText(/Ich verlange ausdrücklich, dass Tenshu vor Ablauf der Widerrufsfrist mit der Leistung beginnt/)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Widerrufsbelehrung und Muster-Widerrufsformular' })).toHaveAttribute(
        'href',
        'https://tenshu.app/legal/terms/de#withdrawal',
      )
    } finally {
      await i18n.changeLanguage('en')
    }
  })

  // ⚠ The acknowledgement is the INITIAL step, not something reachable only by a
  // particular route in.
  it('opens on the acknowledgement, not on a payment hand-off', () => {
    renderAt(PRODUCT_BASIC)
    expect(screen.getByRole('checkbox')).toBeInTheDocument()
    expect(screen.queryByText(/Taking you to/)).not.toBeInTheDocument()
  })

  it('says so when the deployment sells nothing', async () => {
    const user = userEvent.setup()
    const client = new MockPublisherClient(SIGNED_IN)
    vi.spyOn(client, 'startCheckout').mockResolvedValue({ kind: 'unavailable' })
    renderAt(PRODUCT_BASIC, client)

    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }))
    expect(await screen.findByText(/not available on this deployment/)).toBeInTheDocument()
  })

  it('refuses an unknown product rather than offering to buy it', () => {
    renderAt('tenshu.drive.nonsense')
    expect(screen.getByText(/not available/)).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  // ⚠ The price is deliberately not restated beside the acknowledgement — the
  // payment page states it authoritatively, and a client-computed figure next to a
  // legal waiver is a way to be wrong twice.
  it('does not restate the price on the acknowledgement', () => {
    renderAt(PRODUCT_PREMIUM)
    expect(screen.queryByText(/€/)).not.toBeInTheDocument()
  })

  // ⚠⚠ A hosted checkout always starts a NEW subscription, so the service refuses
  // one for a customer who is already billing. Neither answer may read as an error
  // to retry — retrying is how a second subscription would get bought.
  it('says so, and charges nothing, when the plan is already active', async () => {
    const user = userEvent.setup()
    const client = new MockPublisherClient(SIGNED_IN)
    vi.spyOn(client, 'startCheckout').mockResolvedValue({ kind: 'alreadySubscribed' })
    renderAt(PRODUCT_BASIC, client)

    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }))
    expect(await screen.findByText(/already on this plan/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  it('sends a plan change to billing instead of a second purchase', async () => {
    const user = userEvent.setup()
    const client = new MockPublisherClient(SIGNED_IN)
    vi.spyOn(client, 'startCheckout').mockResolvedValue({ kind: 'planChange' })
    const portal = vi.spyOn(client, 'openBillingPortal').mockResolvedValue({ kind: 'unavailable' })
    renderAt(PRODUCT_PREMIUM, client)

    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }))
    await user.click(await screen.findByRole('button', { name: 'Change plan in billing' }))
    expect(portal).toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  // The realm already pays under another email. Offering "change plan in billing"
  // would be a dead end: this session's billing portal has no subscription in it.
  it('says the realm pays under another email, and offers no billing shortcut', async () => {
    const user = userEvent.setup()
    const client = new MockPublisherClient(SIGNED_IN)
    vi.spyOn(client, 'startCheckout').mockResolvedValue({ kind: 'subscribedElsewhere' })
    renderAt(PRODUCT_BASIC, client)

    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: 'Continue to payment' }))
    expect(await screen.findByText(/under a different email address/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change plan in billing' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })
})
