// Redeeming a code. A spent code whose plan was not saved must never read as
// success, nor as a failure that invites redeeming it again.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { RedeemPage } from './RedeemPage'
import { PublisherClientProvider } from './PublisherClientProvider'
import { MockPublisherClient } from './publisherClient'
import type { PublisherClient } from './publisherClient'

function renderPage(client: PublisherClient) {
  render(
    <PublisherClientProvider client={client}>
      <MemoryRouter initialEntries={['/control/services/redeem']}>
        <Routes>
          <Route path="/control/services/redeem" element={<RedeemPage />} />
          <Route path="/control/services" element={<p>services</p>} />
        </Routes>
      </MemoryRouter>
    </PublisherClientProvider>,
  )
}

async function redeem(code: string) {
  const user = userEvent.setup()
  await user.type(screen.getByRole('textbox'), code)
  await user.click(screen.getByRole('button', { name: 'Redeem' }))
}

describe('RedeemPage', () => {
  it('returns to Services once the plan is saved', async () => {
    const client = new MockPublisherClient()
    const spy = vi.spyOn(client, 'redeem')
    renderPage(client)
    await redeem('CODE-1')
    expect(spy).toHaveBeenCalledWith('CODE-1')
    expect(await screen.findByText('services')).toBeInTheDocument()
  })

  it('says an invalid code is invalid', async () => {
    const client = new MockPublisherClient()
    vi.spyOn(client, 'redeem').mockResolvedValue({ kind: 'invalid' })
    renderPage(client)
    await redeem('NOPE')
    expect(await screen.findByText("That code isn't valid.")).toBeInTheDocument()
  })

  // ⚠⚠ The code is spent: the screen stays put and says not to redeem it again.
  it('says an accepted code whose plan was not saved is used up', async () => {
    const client = new MockPublisherClient()
    vi.spyOn(client, 'redeem').mockResolvedValue({ kind: 'notSaved', signedIn: true })
    renderPage(client)
    await redeem('CODE-1')
    expect(
      await screen.findByText(/accepted, but the plan couldn't be added on this device/),
    ).toBeInTheDocument()
    expect(screen.getByText(/don't redeem it again — open Services/)).toBeInTheDocument()
    expect(screen.queryByText('services')).not.toBeInTheDocument()
  })

  // Signed out, the tier is on no account, so Services cannot fetch it.
  it('does not send a signed-out redeemer to Services for a plan it cannot fetch', async () => {
    const client = new MockPublisherClient()
    vi.spyOn(client, 'redeem').mockResolvedValue({ kind: 'notSaved', signedIn: false })
    renderPage(client)
    await redeem('CODE-1')
    expect(await screen.findByText(/don't redeem it again — keep it/)).toBeInTheDocument()
    expect(screen.queryByText(/open Services/)).not.toBeInTheDocument()
  })
})
