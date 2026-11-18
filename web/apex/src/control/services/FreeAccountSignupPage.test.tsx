// §5.2 email + OTP. One screen for sign-up and sign-in.
import { afterEach, describe, expect, it } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import i18n from '../../i18n'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { FreeAccountSignupPage } from './FreeAccountSignupPage'
import { PublisherClientProvider } from './PublisherClientProvider'
import { MockPublisherClient } from './publisherClient'
import type { RegisterResult } from './commerceApi'
import { installFakeTurnstile, uninstallFakeTurnstile } from '../../test/fakeTurnstile'

function renderPage(client = new MockPublisherClient()) {
  render(
    <PublisherClientProvider client={client}>
      <MemoryRouter>
        <FreeAccountSignupPage />
      </MemoryRouter>
    </PublisherClientProvider>,
  )
  return client
}

afterEach(async () => {
  uninstallFakeTurnstile()
  await i18n.changeLanguage('en')
})

describe('FreeAccountSignupPage', () => {
  it('starts on the email step', () => {
    renderPage()
    expect(screen.getByText(/Sign in or create/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send code' })).toBeInTheDocument()
  })

  // `register` sends TOS_VERSION and PRIVACY_VERSION, so the form links both pages. Continuing
  // accepts the terms and confirms the minimum age; ⚠ the privacy half only informs (GDPR: the
  // legal bases are Art. 6(1)(b)/(f), not consent).
  it('links the terms and the privacy policy, and asks agreement only to the terms', () => {
    renderPage()
    const notice = screen.getByText(/By continuing you accept our/)
    expect(notice).toHaveTextContent('confirm that you are 16 or older')
    expect(notice.textContent).toMatch(/How we handle your data is explained in our privacy policy\.$/)
    expect(notice.textContent).not.toMatch(/accept[^.]*privacy/i)
    expect(screen.getByRole('link', { name: 'terms of use' })).toHaveAttribute('href', 'https://tenshu.app/legal/terms/en')
    // Above the button: read before the tap that gives the acceptance.
    expect(notice.compareDocumentPosition(screen.getByRole('button', { name: 'Send code' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByRole('link', { name: 'privacy policy' })).toHaveAttribute(
      'href',
      'https://tenshu.app/legal/privacy/en',
    )
  })

  it('links the German pages, the words inside the links, in German', async () => {
    await act(() => i18n.changeLanguage('de'))
    renderPage()
    expect(screen.getByText(/Mit dem Fortfahren akzeptierst du unsere/)).toHaveTextContent('mindestens 16 Jahre alt')
    expect(screen.getByRole('link', { name: 'Nutzungsbedingungen' })).toHaveAttribute('href', 'https://tenshu.app/legal/terms/de')
    expect(screen.getByRole('link', { name: 'Datenschutzerklärung' })).toHaveAttribute(
      'href',
      'https://tenshu.app/legal/privacy/de',
    )
  })

  // ⚠ A 6-digit code typed here, not a link to tap. The commerce plane has never
  // sent a verification link, which is what the old copy promised.
  it('asks for a code, not a link', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText(/Email address/), 'a@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))

    expect(await screen.findByLabelText(/6-digit code/)).toBeInTheDocument()
    expect(screen.getByText(/We sent a 6-digit code to a@example.com/)).toBeInTheDocument()
    expect(screen.queryByText(/link/i)).not.toBeInTheDocument()
  })

  it('reports a rejected code without leaving the step', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText(/Email address/), 'a@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    await user.type(await screen.findByLabelText(/6-digit code/), '00000')
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByText(/didn't work/)).toBeInTheDocument()
    expect(screen.getByLabelText(/6-digit code/)).toBeInTheDocument()
  })

  it('offers a resend', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText(/Email address/), 'a@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    expect(await screen.findByRole('button', { name: 'Send a new code' })).toBeInTheDocument()
  })
})

/** Records the Turnstile token each `register` was given; answers [answer]. */
class TokenRecordingClient extends MockPublisherClient {
  readonly tokens: Array<string | null | undefined> = []
  answer: RegisterResult = { kind: 'otpSent' }
  override async register(email: string, displayName: string | null, turnstileToken?: string | null): Promise<RegisterResult> {
    this.tokens.push(turnstileToken)
    if (this.answer.kind !== 'otpSent') return this.answer
    return super.register(email, displayName)
  }
}

// Commerce D16: with a site key, each send carries a fresh Turnstile token.
describe('FreeAccountSignupPage with Turnstile', () => {
  it('waits for a token, sends it, and needs a fresh one to resend', async () => {
    const turnstile = installFakeTurnstile()
    const user = userEvent.setup()
    const client = renderPage(new TokenRecordingClient()) as TokenRecordingClient
    await user.type(screen.getByLabelText(/Email address/), 'a@example.com')
    expect(screen.getByRole('button', { name: 'Send code' })).toBeDisabled()
    expect(turnstile.options()).toMatchObject({ action: 'register' })

    turnstile.solve('tok-1')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    await screen.findByLabelText(/6-digit code/)
    expect(client.tokens).toEqual(['tok-1'])
    expect(turnstile.resets()).toBe(1)
    expect(screen.getByRole('button', { name: 'Send a new code' })).toBeDisabled()
  })

  it('says the check failed on a refused challenge', async () => {
    const turnstile = installFakeTurnstile()
    const user = userEvent.setup()
    const client = new TokenRecordingClient()
    client.answer = { kind: 'challengeFailed' }
    renderPage(client)
    await user.type(screen.getByLabelText(/Email address/), 'a@example.com')
    turnstile.solve('tok-1')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    expect(await screen.findByText(/couldn't confirm this request came from a person/)).toBeInTheDocument()
  })

  it('without a site key renders no widget and sends no token', async () => {
    const user = userEvent.setup()
    const client = renderPage(new TokenRecordingClient()) as TokenRecordingClient
    expect(screen.queryByTestId('turnstile')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText(/Email address/), 'a@example.com')
    await user.click(screen.getByRole('button', { name: 'Send code' }))
    await screen.findByLabelText(/6-digit code/)
    expect(client.tokens).toEqual([null])
  })
})
