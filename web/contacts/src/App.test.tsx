import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import App from './App'
import i18n from './i18n'
import { contact, proxy, resetProxy, streams } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

beforeEach(() => {
  resetProxy()
  window.history.replaceState(null, '', '/')
})

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
}

describe('App', () => {
  it('subscribes once to each live stream, however many screens read them', async () => {
    void i18n.changeLanguage('en')
    proxy.listContacts.mockResolvedValue({ ok: true, contacts: [contact({ personId: 'ada', displayName: 'Ada' })] })
    renderApp()
    await userEvent.click(await screen.findByText('Ada'))
    expect(await screen.findByRole('heading', { name: 'Ada' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Duplicates' }))
    await userEvent.click(screen.getByRole('button', { name: 'You' }))
    expect(proxy.subscribeContacts).toHaveBeenCalledTimes(1)
    expect(proxy.subscribeOwnProfile).toHaveBeenCalledTimes(1)
    expect(streams.contacts.open()).toBe(1)
  })

  it('names no headline of its own, and keeps its sections in a navigation landmark', async () => {
    // web-apps-layout.md §5 L1: the apex top bar names the app.
    void i18n.changeLanguage('en')
    renderApp()
    expect(await screen.findByText('No contacts yet')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Contacts' })).toBeNull()
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull()
    const nav = screen.getByRole('navigation', { name: 'Contacts sections' })
    for (const name of ['Contacts', 'Invitations', 'Duplicates']) {
      expect(within(nav).getByRole('button', { name })).toBeInTheDocument()
    }
    // "You" sits at the sidebar's foot, still inside the one navigation landmark.
    expect(within(nav).getByRole('button', { name: 'You' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'You' })).toBeInTheDocument()
  })

  it('opens a person in the pane beside the list, and closes it again', async () => {
    void i18n.changeLanguage('en')
    proxy.listContacts.mockResolvedValue({ ok: true, contacts: [contact({ personId: 'ada', displayName: 'Ada' })] })
    renderApp()
    await userEvent.click(await screen.findByText('Ada'))
    const pane = await screen.findByRole('complementary', { name: 'Contact' })
    expect(within(pane).getByRole('heading', { name: 'Ada' })).toBeInTheDocument()
    // The list stays in view beside it.
    expect(screen.getByRole('main')).toHaveTextContent('Ada')
    await userEvent.click(within(pane).getByRole('button', { name: /All contacts/ }))
    expect(screen.queryByRole('complementary', { name: 'Contact' })).toBeNull()
  })

  it('renders in German', async () => {
    await i18n.changeLanguage('de')
    renderApp()
    expect(await screen.findByRole('navigation', { name: 'Kontakte-Bereiche' })).toBeInTheDocument()
    expect(await screen.findByText('Noch keine Kontakte')).toBeInTheDocument()
    await i18n.changeLanguage('en')
  })

  it('renders in French', async () => {
    await i18n.changeLanguage('fr')
    renderApp()
    expect(await screen.findByRole('button', { name: 'Doublons' })).toBeInTheDocument()
    await i18n.changeLanguage('en')
  })

  it('tells the user when Tenshu runs in another tab', async () => {
    void i18n.changeLanguage('en')
    proxy.runtimeLease.mockResolvedValue('elsewhere')
    renderApp()
    expect(await screen.findByText('Tenshu is open in another tab')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Use here' }))
    await waitFor(() => expect(proxy.runtimeUseHere).toHaveBeenCalled())
  })

  describe('routes (web-apps-layout.md §3.4)', () => {
    it('opens the section the start URL names', async () => {
      void i18n.changeLanguage('en')
      window.history.replaceState(null, '', '/profile')
      renderApp()
      const nav = await screen.findByRole('navigation', { name: 'Contacts sections' })
      expect(within(nav).getByRole('button', { name: 'You' })).toHaveAttribute('aria-current', 'page')
    })

    it('puts the clicked section in the path, and home is the bare path', async () => {
      void i18n.changeLanguage('en')
      renderApp()
      await userEvent.click(await screen.findByRole('button', { name: 'Duplicates' }))
      expect(window.location.pathname).toBe('/duplicates')
      await userEvent.click(screen.getByRole('button', { name: 'Invitations' }))
      expect(window.location.pathname).toBe('/invites')
      await userEvent.click(screen.getByRole('button', { name: 'Contacts' }))
      expect(window.location.pathname).toBe('/')
    })

    it('lands an unknown path on the contact list', async () => {
      void i18n.changeLanguage('en')
      window.history.replaceState(null, '', '/nope')
      renderApp()
      expect(await screen.findByText('No contacts yet')).toBeInTheDocument()
    })

    it('keeps the open person in the fragment, never the path, and clears it on close', async () => {
      void i18n.changeLanguage('en')
      proxy.listContacts.mockResolvedValue({ ok: true, contacts: [contact({ personId: 'ada-1', displayName: 'Ada' })] })
      renderApp()
      await userEvent.click(await screen.findByText('Ada'))
      const pane = await screen.findByRole('complementary', { name: 'Contact' })
      expect(window.location.hash).toBe('#p=ada-1')
      expect(window.location.pathname).toBe('/')
      expect(window.location.href).not.toContain('Ada')
      await userEvent.click(within(pane).getByRole('button', { name: /All contacts/ }))
      expect(window.location.hash).toBe('')
    })

    it('opens the person a fresh load names in its fragment', async () => {
      void i18n.changeLanguage('en')
      proxy.listContacts.mockResolvedValue({ ok: true, contacts: [contact({ personId: 'ada-1', displayName: 'Ada' })] })
      window.history.replaceState(null, '', '/#p=ada-1')
      renderApp()
      const pane = await screen.findByRole('complementary', { name: 'Contact' })
      expect(await within(pane).findByRole('heading', { name: 'Ada' })).toBeInTheDocument()
    })

    it('closes the open person on a section change', async () => {
      void i18n.changeLanguage('en')
      proxy.listContacts.mockResolvedValue({ ok: true, contacts: [contact({ personId: 'ada-1', displayName: 'Ada' })] })
      window.history.replaceState(null, '', '/#p=ada-1')
      renderApp()
      await screen.findByRole('complementary', { name: 'Contact' })
      await userEvent.click(screen.getByRole('button', { name: 'Duplicates' }))
      expect(window.location.hash).toBe('')
      await userEvent.click(screen.getByRole('button', { name: 'Contacts' }))
      expect(screen.queryByRole('complementary', { name: 'Contact' })).toBeNull()
    })
  })
})
