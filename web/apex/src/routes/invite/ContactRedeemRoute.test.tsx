// The contact-invite redeem-on-open page (`drive-web-mode-b-sharing.md` §3). The invite rides the
// URL fragment, so the page must still see it after React throws a render away and retries: it
// used to scrub the fragment DURING render, and every real link then read as "incomplete" because
// React restarted that render (`RequiresRuntime` updated another component mid-render; see
// `runtimeRequested.test.tsx`). The suspending-sibling case pins the route's half with another way
// React discards a render; the rest pin the session-gated branches a recipient lands on.
import { StrictMode, Suspense, type ReactNode } from 'react'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import type { SessionState } from '@tenshu/sdk/onboarding'

const detectMock = vi.fn<() => Promise<SessionState>>()
const redeemMock = vi.fn()
const ackMock = vi.fn()

vi.mock('@tenshu/sdk/onboarding', () => ({
  Session: { detect: () => detectMock() },
}))
vi.mock('@tenshu/sdk/contact', () => ({
  Contacts: {
    redeemInvite: (link: string, name: string) => redeemMock(link, name),
    awaitAck: (id: string, sas: boolean) => ackMock(id, sas),
  },
}))

import ContactRedeemRoute from './redeem'

const LINK = 'http://localhost:3000/i#v1.AAAAinvitepayload'

// Suspends the first time it renders, so React discards the whole attempt, ContactRedeemRoute
// included, and retries — the same shape as the production restart, made deterministic.
let suspended = false
function SuspendOnce(): ReactNode {
  if (!suspended) {
    suspended = true
    throw new Promise<void>((resolve) => setTimeout(resolve, 0))
  }
  return null
}

function open(url: string, wrap: (page: ReactNode) => ReactNode = (page) => page) {
  window.history.replaceState(null, '', url)
  return render(<MemoryRouter initialEntries={['/i']}>{wrap(<ContactRedeemRoute />)}</MemoryRouter>)
}

describe('ContactRedeemRoute', () => {
  beforeEach(() => {
    detectMock.mockReset()
    redeemMock.mockReset()
    ackMock.mockReset()
    suspended = false
  })
  afterEach(() => {
    window.history.replaceState(null, '', '/')
  })

  it('keeps the invite through a render React discards (a suspending sibling)', async () => {
    detectMock.mockResolvedValue({ kind: 'firstTime' })
    open('/i#v1.AAAAinvitepayload', (page) => (
      <Suspense fallback={<p>loading</p>}>
        {page}
        <SuspendOnce />
      </Suspense>
    ))

    expect(await screen.findByRole('link', { name: 'Create an account' })).toBeInTheDocument()
    expect(screen.queryByText(/incomplete/)).not.toBeInTheDocument()
  })

  it('redeems the full link a signed-in recipient opened (StrictMode)', async () => {
    detectMock.mockResolvedValue({ kind: 'active', deviceName: 'Laptop' })
    redeemMock.mockResolvedValue({ outcome: 'redeemed', inviteId: 'inv-1', message: null })
    ackMock.mockResolvedValue({ status: 'ACCEPTED', peerName: 'Ada' })
    open('/i#v1.AAAAinvitepayload', (page) => <StrictMode>{page}</StrictMode>)

    await userEvent.type(await screen.findByRole('textbox', { name: 'Your name' }), 'Grace')
    await userEvent.click(screen.getByRole('button', { name: 'Connect' }))

    expect(redeemMock).toHaveBeenCalledWith(LINK, 'Grace')
    expect(await screen.findByText(/connected/)).toBeInTheDocument()
  })

  it('scrubs the secret from the address bar once the page is up', async () => {
    detectMock.mockResolvedValue({ kind: 'locked', deviceName: 'Laptop' })
    open('/i#v1.AAAAinvitepayload')

    expect(await screen.findByRole('link', { name: 'Unlock' })).toBeInTheDocument()
    expect(window.location.hash).toBe('')
    expect(window.location.pathname).toBe('/i')
  })

  it('still calls a link without its fragment incomplete', () => {
    open('/i')
    expect(screen.getByText(/invite link is incomplete/)).toBeInTheDocument()
    expect(detectMock).not.toHaveBeenCalled()
  })
})
