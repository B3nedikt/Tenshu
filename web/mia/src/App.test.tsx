import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { resetProxy } = await import('./test/proxyMock')
const { default: App } = await import('./App')

beforeEach(async () => {
  resetProxy()
  window.history.replaceState(null, '', '/')
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('renders its shell on the chat, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    expect(screen.getByRole('button', { name: 'Chat' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByRole('textbox', { name: 'Your question' })).toBeInTheDocument()
    expect(screen.queryByText(/Tenshu is open in another tab/)).toBeNull()
  })

  // web-apps-layout.md §3.1/§5 L1: the apex top bar names the app, so no headline does; the
  // sections sit in the sidebar's navigation landmark, the grants (settings) at its foot.
  it('renders no headline naming the app, and its sections in a navigation landmark', async () => {
    renderWithQueryClient(<App />)
    await screen.findByRole('textbox', { name: 'Your question' })
    expect(screen.queryAllByRole('heading', { level: 1 })).toEqual([])
    expect(screen.queryByRole('heading', { name: 'Mia' })).toBeNull()
    const nav = screen.getByRole('navigation', { name: 'Mia sections' })
    // The grants (settings) sit at the sidebar's foot, inside the same landmark.
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual(['Chat', 'History', 'What Mia can read'])
    expect(screen.getByRole('button', { name: 'What Mia can read' })).not.toHaveAttribute('aria-current')
  })

  it('switches between Chat, History and What Mia can read', async () => {
    renderWithQueryClient(<App />)
    await userEvent.click(screen.getByRole('button', { name: 'History' }))
    expect(await screen.findByTestId('memory-note')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'What Mia can read' }))
    expect(await screen.findByRole('switch', { name: 'Files' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Chat' }))
    expect(await screen.findByRole('textbox', { name: 'Your question' })).toBeInTheDocument()
  })

  it('carries German and French copy', async () => {
    await i18n.changeLanguage('de')
    renderWithQueryClient(<App />)
    expect(screen.getByRole('button', { name: 'Verlauf' })).toBeInTheDocument()
    await i18n.changeLanguage('fr')
    expect(await screen.findByRole('button', { name: 'Historique' })).toBeInTheDocument()
  })

  describe('routes (web-apps-layout.md §3.4)', () => {
    it('opens the section the start URL names', async () => {
      window.history.replaceState(null, '', '/grants')
      renderWithQueryClient(<App />)
      expect(screen.getByRole('button', { name: 'What Mia can read' })).toHaveAttribute('aria-current', 'page')
      expect(screen.queryByRole('textbox', { name: 'Your question' })).toBeNull()
    })

    it('puts the clicked section in the path, and the chat is the bare path', async () => {
      renderWithQueryClient(<App />)
      await userEvent.click(screen.getByRole('button', { name: 'History' }))
      expect(window.location.pathname).toBe('/history')
      await userEvent.click(screen.getByRole('button', { name: 'What Mia can read' }))
      expect(window.location.pathname).toBe('/grants')
      await userEvent.click(screen.getByRole('button', { name: 'Chat' }))
      expect(window.location.pathname).toBe('/')
      expect(window.location.hash).toBe('')
    })

    it('lands an unknown path on the chat', async () => {
      window.history.replaceState(null, '', '/nope')
      renderWithQueryClient(<App />)
      expect(screen.getByRole('button', { name: 'Chat' })).toHaveAttribute('aria-current', 'page')
      expect(await screen.findByRole('textbox', { name: 'Your question' })).toBeInTheDocument()
    })
  })
})
