import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'
import { day, placeDetail, placeRow, proxy, resetProxy, visit } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { default: App } = await import('./App')

beforeEach(async () => {
  // The route reads the URL on mount (web-apps-layout.md §3.4).
  window.history.replaceState(null, '', '/')
  resetProxy()
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('renders its shell, with no lease banner while this tab holds the runtime', async () => {
    proxy.runtimeLease.mockResolvedValue('held')
    renderWithQueryClient(<App />)
    // No headline: the apex top bar already names the app (web-apps-layout.md §3.1).
    expect(screen.queryByRole('heading', { name: 'Timeline' })).toBeNull()
    const nav = screen.getByRole('navigation', { name: 'Timeline sections' })
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual(['Timeline', 'Places'])
    expect(within(nav).getByRole('button', { name: 'Timeline' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText('Nothing on your timeline yet')).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('navigates feed → moment → place → back, and to the places tab', async () => {
    proxy.runtimeLease.mockResolvedValue('held')
    const at = Date.UTC(2026, 5, 8, 9)
    proxy.feed.mockResolvedValue({ ok: true, days: [day('2026-06-08', visit('v1', at, { id: 'p1', name: 'Café' }))], trips: [], suggestion: null })
    proxy.momentDetail.mockResolvedValue({ ok: true, moment: visit('v1', at, { id: 'p1', name: 'Café' }) })
    proxy.place.mockResolvedValue({ ok: true, place: placeDetail(placeRow({ placeId: 'p1', name: 'Café' })) })
    proxy.places.mockResolvedValue({ ok: true, places: [placeRow({ placeId: 'p1', name: 'Café' })] })
    renderWithQueryClient(<App />)

    await userEvent.click(await screen.findByRole('button', { name: /Café/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Open this place' }))
    expect(await screen.findByRole('button', { name: 'Merge with another place' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Back/ }))
    expect(await screen.findByRole('button', { name: 'Open this place' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Places' }))
    expect(await screen.findByText('1 visit')).toBeInTheDocument()
  })

  describe('routes', () => {
    const nav = () => screen.getByRole('navigation', { name: 'Timeline sections' })

    beforeEach(() => {
      proxy.runtimeLease.mockResolvedValue('held')
      proxy.places.mockResolvedValue({ ok: true, places: [placeRow({ placeId: 'p1', name: 'Café' })] })
      proxy.place.mockResolvedValue({ ok: true, place: placeDetail(placeRow({ placeId: 'p1', name: 'Café' })) })
    })

    it('a start URL opens its section', async () => {
      window.history.replaceState(null, '', '/places')
      renderWithQueryClient(<App />)
      expect(within(nav()).getByRole('button', { name: 'Places' })).toHaveAttribute('aria-current', 'page')
      expect(await screen.findByText('1 visit')).toBeInTheDocument()
    })

    it('a start fragment opens its detail, and Back returns to the section', async () => {
      window.history.replaceState(null, '', '/places#place=p1')
      renderWithQueryClient(<App />)
      expect(await screen.findByRole('button', { name: 'Merge with another place' })).toBeInTheDocument()
      expect(proxy.place).toHaveBeenCalledWith('p1')

      await userEvent.click(screen.getByRole('button', { name: /Back/ }))
      expect(await screen.findByText('1 visit')).toBeInTheDocument()
      expect(window.location.hash).toBe('')
      expect(window.location.pathname).toBe('/places')
    })

    it('an unknown path lands on the timeline', async () => {
      window.history.replaceState(null, '', '/nope')
      renderWithQueryClient(<App />)
      expect(within(nav()).getByRole('button', { name: 'Timeline' })).toHaveAttribute('aria-current', 'page')
      expect(await screen.findByText('Nothing on your timeline yet')).toBeInTheDocument()
      expect(window.location.pathname).toBe('/nope') // not rewritten until the user moves
    })

    it('a section click moves the path; a place moves only the fragment, by id', async () => {
      renderWithQueryClient(<App />)
      await userEvent.click(within(nav()).getByRole('button', { name: 'Places' }))
      expect(window.location.pathname).toBe('/places')
      expect(window.location.hash).toBe('')

      await userEvent.click(await screen.findByRole('button', { name: /Café/ }))
      expect(await screen.findByRole('button', { name: 'Merge with another place' })).toBeInTheDocument()
      expect(window.location.pathname).toBe('/places')
      expect(window.location.hash).toBe('#place=p1')
      // ⚠⚠ Never the name, anywhere in the URL.
      expect(window.location.href).not.toMatch(/Caf/)

      await userEvent.click(within(nav()).getByRole('button', { name: 'Timeline' }))
      expect(window.location.pathname).toBe('/')
      expect(window.location.hash).toBe('') // a section change closes the detail
      expect(await screen.findByText('Nothing on your timeline yet')).toBeInTheDocument()
    })
  })
})
