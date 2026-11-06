import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { resetProxy } = await import('./test/proxyMock')
const { default: App } = await import('./App')

beforeEach(async () => {
  resetProxy()
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('renders its shell on the chat, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    expect(screen.getByRole('heading', { name: 'Mia' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Chat' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('textbox', { name: 'Your question' })).toBeInTheDocument()
    expect(screen.queryByText(/Tenshu is open in another tab/)).toBeNull()
  })

  it('switches between Chat, History and What Mia can read', async () => {
    renderWithQueryClient(<App />)
    await userEvent.click(screen.getByRole('tab', { name: 'History' }))
    expect(await screen.findByTestId('memory-note')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'What Mia can read' }))
    expect(await screen.findByRole('switch', { name: 'Files' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Chat' }))
    expect(await screen.findByRole('textbox', { name: 'Your question' })).toBeInTheDocument()
  })

  it('carries German and French copy', async () => {
    await i18n.changeLanguage('de')
    renderWithQueryClient(<App />)
    expect(screen.getByRole('tab', { name: 'Verlauf' })).toBeInTheDocument()
    await i18n.changeLanguage('fr')
    expect(await screen.findByRole('tab', { name: 'Historique' })).toBeInTheDocument()
  })
})
