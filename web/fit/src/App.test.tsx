import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { proxy, resetProxy, workoutRow, detailOf } = await import('./test/proxyMock')
const { default: App } = await import('./App')

beforeEach(async () => {
  resetProxy()
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('opens on the dashboard, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    expect(screen.getByRole('heading', { name: 'Fit' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Today' })).toBeInTheDocument()
    expect(screen.queryByText('Still connecting to Tenshu…')).toBeNull()
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
})
