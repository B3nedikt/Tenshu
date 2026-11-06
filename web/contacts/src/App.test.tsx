import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import App from './App'
import i18n from './i18n'
import { contact, proxy, resetProxy, streams } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

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

  it('renders in German', async () => {
    await i18n.changeLanguage('de')
    renderApp()
    expect(await screen.findByRole('heading', { name: 'Kontakte' })).toBeInTheDocument()
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
})
