// The account-deletion page (/account/delete): what goes out on the wire at each step, and the
// guards on the one irreversible button on the site.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { AccountDeletionPage } from './AccountDeletionPage'
import i18n from '../i18n'

interface Sent {
  path: string
  body: Record<string, unknown>
}

let sent: Sent[] = []
// One queued response per call, in order: [status, json].
let responses: Array<[number, unknown]> = []

function stubFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      sent.push({
        path: new URL(url).pathname,
        body: JSON.parse(String(init.body)) as Record<string, unknown>,
      })
      const [status, json] = responses.shift() ?? [500, null]
      return {
        status,
        json: async () => {
          if (json === null) throw new Error('no body')
          return json
        },
      } as Response
    }),
  )
}

function renderPage() {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  return render(
    <MemoryRouter initialEntries={['/account/delete']}>
      <AccountDeletionPage />
    </MemoryRouter>,
  )
}

async function requestCode(user: ReturnType<typeof userEvent.setup>, email = 'a@example.com') {
  await user.type(screen.getByLabelText('Email address of your Tenshu account'), email)
  await user.click(screen.getByRole('button', { name: 'Send code' }))
  await screen.findByLabelText('6-digit code')
}

const deleteButton = () => screen.getByRole('button', { name: 'Delete my account' })
const ackBox = () =>
  screen.getByRole('checkbox', {
    name: 'I understand that deleting my account is permanent and cannot be undone.',
  })

beforeEach(() => {
  sent = []
  responses = []
  stubFetch()
})
afterEach(async () => {
  vi.unstubAllGlobals()
  await i18n.changeLanguage('en')
})

describe('AccountDeletionPage', () => {
  it('says what deletion does before anything is sent', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: 'Delete your Tenshu account' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'What deleting your account does' })).toBeInTheDocument()
    expect(screen.getByText(/scheduled for permanent deletion after a 30-day grace period/)).toBeInTheDocument()
    expect(screen.getByText(/only be cancelled by you, in your Apple ID settings/)).toBeInTheDocument()
    expect(screen.getByText(/Kept: your invoices/)).toBeInTheDocument()
    expect(sent).toEqual([])
  })

  it('email → code → confirm: posts the right bodies to the right endpoints and ends deleted', async () => {
    const user = userEvent.setup()
    responses = [
      [202, { status: 'code_sent' }],
      [200, { status: 'deleted' }],
    ]
    renderPage()
    await requestCode(user)
    await user.type(screen.getByLabelText('6-digit code'), '123456')
    await user.click(ackBox())
    await user.click(deleteButton())

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your account has been deleted.' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Your account and email address have been erased. Synced data on our servers is scheduled for deletion after a 30-day grace period.')).toBeInTheDocument()
    expect(sent).toEqual([
      { path: '/commerce/account/delete/request', body: { email: 'a@example.com' } },
      { path: '/commerce/account/delete/confirm', body: { email: 'a@example.com', code: '123456' } },
    ])
  })

  // ⚠⚠ The enumeration guard: the service answers 202 for every well-formed address, and the page
  // must say nothing that claims an account exists.
  it('the code step does not claim an account exists', async () => {
    const user = userEvent.setup()
    responses = [[202, { status: 'code_sent' }]]
    renderPage()
    await requestCode(user, 'nobody@example.com')
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent(
      "If a Tenshu account uses nobody@example.com, we've sent it a code to confirm the deletion. Check your inbox.",
    )
    expect(status.textContent).toMatch(/^If a Tenshu account uses/)
  })

  it('a wrong code shows a retry message, keeps the code step and never the done state', async () => {
    const user = userEvent.setup()
    responses = [
      [202, { status: 'code_sent' }],
      [401, { error: 'unauthorized' }],
    ]
    renderPage()
    await requestCode(user)
    await user.type(screen.getByLabelText('6-digit code'), '000000')
    await user.click(ackBox())
    await user.click(deleteButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "That code didn't work. Check it and try again, or send a new code.",
    )
    expect(screen.queryByText('Your account has been deleted.')).not.toBeInTheDocument()
    expect(screen.getByLabelText('6-digit code')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send a new code' })).toBeEnabled()
  })

  it('the delete button stays disabled until the acknowledgement is ticked', async () => {
    const user = userEvent.setup()
    responses = [[202, { status: 'code_sent' }]]
    renderPage()
    await requestCode(user)
    await user.type(screen.getByLabelText('6-digit code'), '123456')
    expect(deleteButton()).toBeDisabled()
    // Submitting the form some other way (Enter in the code field) must not get past it either.
    await user.type(screen.getByLabelText('6-digit code'), '{Enter}')
    expect(sent.map((s) => s.path)).toEqual(['/commerce/account/delete/request'])

    await user.click(ackBox())
    expect(deleteButton()).toBeEnabled()
    await user.click(ackBox())
    expect(deleteButton()).toBeDisabled()
  })

  it('maps request failures to their messages', async () => {
    const user = userEvent.setup()
    responses = [
      [429, { error: 'rate_limited' }],
      [503, { error: 'send_failed' }],
    ]
    renderPage()
    await user.type(screen.getByLabelText('Email address of your Tenshu account'), 'a@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Please try again later.')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        "We couldn't send the email just now. Please try again in a few minutes.",
      ),
    )
    expect(screen.queryByLabelText('6-digit code')).not.toBeInTheDocument()
  })

  it('an unreachable service says so', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    renderPage()
    await user.type(screen.getByLabelText('Email address of your Tenshu account'), 'a@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "Couldn't reach the account service. Check your connection and try again.",
    )
  })

  it('"Send a new code" re-requests for the same address; "Use a different email" goes back', async () => {
    const user = userEvent.setup()
    responses = [
      [202, { status: 'code_sent' }],
      [202, { status: 'code_sent' }],
    ]
    renderPage()
    await requestCode(user)
    await user.click(ackBox())
    await user.click(screen.getByRole('button', { name: 'Send a new code' }))
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent(
        "We've sent a new code, if a Tenshu account uses this address.",
      ),
    )
    expect(sent.map((s) => s.body)).toEqual([{ email: 'a@example.com' }, { email: 'a@example.com' }])

    await user.click(screen.getByRole('button', { name: 'Use a different email' }))
    expect(screen.getByLabelText('Email address of your Tenshu account')).toHaveValue('a@example.com')
    expect(screen.queryByLabelText('6-digit code')).not.toBeInTheDocument()
  })

  it('renders in German and French', async () => {
    await i18n.changeLanguage('de')
    const { unmount } = renderPage()
    expect(screen.getByRole('heading', { level: 1, name: 'Tenshu-Konto löschen' })).toBeInTheDocument()
    unmount()
    await i18n.changeLanguage('fr')
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: 'Supprimer votre compte Tenshu' })).toBeInTheDocument()
  })
})
