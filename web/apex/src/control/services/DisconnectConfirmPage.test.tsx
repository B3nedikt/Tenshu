// §6.4 disconnect: Cancel is the safe action; the destructive button is
// gated on a typed confirmation string.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { MockPublisherClient } from './publisherClient'
import { PublisherClientProvider } from './PublisherClientProvider'
import { DisconnectConfirmPage } from './DisconnectConfirmPage'

function renderPage(client: MockPublisherClient = new MockPublisherClient(null)) {
  return render(
    <PublisherClientProvider client={client}>
      <MemoryRouter>
        <DisconnectConfirmPage />
      </MemoryRouter>
    </PublisherClientProvider>,
  )
}

describe('DisconnectConfirmPage', () => {
  it('renders the destructive warning', () => {
    renderPage()
    expect(
      screen.getByText('Disconnect from the Tenshu Relay?'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Your other devices will stop syncing/),
    ).toBeInTheDocument()
  })

  it('disables the destructive CTA until the confirmation string matches', async () => {
    const user = userEvent.setup()
    renderPage()
    const destructive = screen.getByRole('button', { name: 'Yes, disconnect' })
    expect(destructive).toBeDisabled()
    await user.type(screen.getByLabelText(/Disconnect/), 'wrong')
    expect(destructive).toBeDisabled()
    await user.clear(screen.getByLabelText(/Disconnect/))
    await user.type(screen.getByLabelText(/Disconnect/), 'Disconnect')
    expect(destructive).not.toBeDisabled()
  })

  it('clears the account when confirmed', async () => {
    const client = new MockPublisherClient(null)
    await client.register('test@example.com', null)
    await client.verify('123456')
    expect(client.getAccount()).not.toBeNull()

    const user = userEvent.setup()
    renderPage(client)
    await user.type(screen.getByLabelText(/Disconnect/), 'Disconnect')
    await user.click(screen.getByRole('button', { name: 'Yes, disconnect' }))
    expect(client.getAccount()).toBeNull()
  })
})
