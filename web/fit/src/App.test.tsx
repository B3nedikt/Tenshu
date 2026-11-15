import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { proxy, resetProxy, workoutRow, detailOf } = await import('./test/proxyMock')
const { default: App } = await import('./App')

beforeEach(async () => {
  resetProxy()
  window.history.replaceState(null, '', '/')
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('opens on the dashboard, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    // No headline: the apex top bar already names the app (web-apps-layout.md §3.1).
    expect(screen.queryByRole('heading', { name: 'Fit' })).toBeNull()
    expect(await screen.findByRole('region', { name: 'Today' })).toBeInTheDocument()
    expect(screen.queryByText('Still connecting to Tenshu…')).toBeNull()
  })

  it('its sections are a navigation landmark, with Profile at the sidebar foot', async () => {
    renderWithQueryClient(<App />)
    const nav = screen.getByRole('navigation', { name: 'Fit sections' })
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual(['Dashboard', 'History', 'Profile'])
    expect(within(nav).getByRole('button', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('button', { name: 'Profile' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Today' })).toBeInTheDocument()
  })

  it('changing section closes the open workout', async () => {
    proxy.workoutsPage.mockResolvedValue({ ok: true, workouts: [workoutRow('w1', 0, { title: 'Long run' })], nextCursor: null })
    proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w1', 0, { title: 'Long run' })) })
    renderWithQueryClient(<App />)
    await userEvent.click(screen.getByRole('button', { name: 'History' }))
    await userEvent.click(await screen.findByRole('button', { name: /Long run/ }))
    expect(await screen.findByRole('heading', { name: 'Long run' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'History' }))
    expect(await screen.findByRole('list', { name: 'Workouts' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Long run' })).toBeNull()
  })

  it('History → a workout → Back', async () => {
    proxy.workoutsPage.mockResolvedValue({ ok: true, workouts: [workoutRow('w1', 0, { title: 'Long run' })], nextCursor: null })
    proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w1', 0, { title: 'Long run' })) })
    renderWithQueryClient(<App />)
    await userEvent.click(screen.getByRole('button', { name: 'History' }))
    await userEvent.click(await screen.findByRole('button', { name: /Long run/ }))
    expect(await screen.findByRole('heading', { name: 'Long run' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Back/ }))
    await waitFor(() => expect(screen.getByRole('list', { name: 'Workouts' })).toBeInTheDocument())
  })

  it('Profile holds goals, personal data and the read-only sources', async () => {
    renderWithQueryClient(<App />)
    await userEvent.click(screen.getByRole('button', { name: 'Profile' }))
    expect(await screen.findByText('Goals')).toBeInTheDocument()
    expect(screen.getByText('Personal data')).toBeInTheDocument()
    expect(screen.getByText('Data sources')).toBeInTheDocument()
  })

  describe('routes (web-apps-layout.md §3.4)', () => {
    const longRun = () => {
      proxy.workoutsPage.mockResolvedValue({ ok: true, workouts: [workoutRow('w-1', 0, { title: 'Long run' })], nextCursor: null })
      proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w-1', 0, { title: 'Long run' })) })
    }

    it('opens the section the start URL names', async () => {
      window.history.replaceState(null, '', '/profile')
      renderWithQueryClient(<App />)
      const nav = screen.getByRole('navigation', { name: 'Fit sections' })
      expect(within(nav).getByRole('button', { name: 'Profile' })).toHaveAttribute('aria-current', 'page')
      expect(await screen.findByText('Goals')).toBeInTheDocument()
    })

    it('puts the clicked section in the path, and the dashboard is the bare path', async () => {
      renderWithQueryClient(<App />)
      await userEvent.click(screen.getByRole('button', { name: 'History' }))
      expect(window.location.pathname).toBe('/history')
      await userEvent.click(screen.getByRole('button', { name: 'Profile' }))
      expect(window.location.pathname).toBe('/profile')
      await userEvent.click(screen.getByRole('button', { name: 'Dashboard' }))
      expect(window.location.pathname).toBe('/')
    })

    it('lands an unknown path on the dashboard', async () => {
      window.history.replaceState(null, '', '/nope')
      renderWithQueryClient(<App />)
      expect(await screen.findByRole('region', { name: 'Today' })).toBeInTheDocument()
    })

    it('keeps the open workout in the fragment, never the path, and Back clears it', async () => {
      longRun()
      renderWithQueryClient(<App />)
      await userEvent.click(screen.getByRole('button', { name: 'History' }))
      await userEvent.click(await screen.findByRole('button', { name: /Long run/ }))
      expect(await screen.findByRole('heading', { name: 'Long run' })).toBeInTheDocument()
      expect(window.location.pathname).toBe('/history')
      expect(window.location.hash).toBe('#w=w-1')
      expect(window.location.href).not.toContain('Long')
      await userEvent.click(screen.getByRole('button', { name: /Back/ }))
      expect(window.location.hash).toBe('')
      expect(window.location.pathname).toBe('/history')
    })

    it('opens the workout a fresh load names in its fragment', async () => {
      longRun()
      window.history.replaceState(null, '', '/history#w=w-1')
      renderWithQueryClient(<App />)
      expect(await screen.findByRole('heading', { name: 'Long run' })).toBeInTheDocument()
      expect(proxy.workout.mock.calls[0]?.[0]).toBe('w-1')
    })
  })
})
