import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, streams, resetProxy, failure, answer, hit, turn, conversation, historyOf } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { HistoryView } = await import('./HistoryView')

beforeEach(() => resetProxy())

describe('HistoryView', () => {
  // ⚠⚠ MQ-D8: the history is memory-only and goes on sign-out — said plainly, in every state.
  it('says plainly that the history lives in memory and is cleared on sign-out', async () => {
    renderWithProviders(<HistoryView />)
    expect(await screen.findByText('No conversations yet.')).toBeInTheDocument()
    const note = screen.getByTestId('memory-note')
    expect(note).toHaveTextContent('only in this browser’s memory')
    expect(note).toHaveTextContent('never saved')
    expect(note).toHaveTextContent('cleared when you sign out')
  })

  it('lists the conversation with its title and question count, and opens its read-only thread', async () => {
    proxy.history.mockResolvedValue(historyOf([conversation(2, 'workouts yesterday')]))
    proxy.turnsPage.mockResolvedValue({
      ok: true,
      turns: [
        turn(2, 'where was that café', answer('answered', 'Searched your places.', [hit('tenshu/place/place', 'Blue Bottle', 'Berlin')])),
        turn(1, 'workouts yesterday', answer('noMatch', 'You’ve turned off workouts.')),
      ],
      nextCursor: null,
    })
    renderWithProviders(<HistoryView />)
    const row = await screen.findByRole('button', { name: /workouts yesterday/ })
    expect(row).toHaveTextContent('2 questions')
    await userEvent.click(row)

    expect(await screen.findByRole('heading', { name: 'workouts yesterday' })).toBeInTheDocument()
    expect(await screen.findByText('Blue Bottle')).toBeInTheDocument()
    expect(screen.getByText('You’ve turned off workouts.')).toHaveAttribute('data-verdict', 'noMatch')
    // Read-only: no input in a past thread.
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByTestId('memory-note')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Back/ }))
    expect(await screen.findByRole('button', { name: /workouts yesterday/ })).toBeInTheDocument()
  })

  it('a sign-out empties it: the pushed history drops the conversation, and the open thread with it', async () => {
    proxy.history.mockResolvedValue(historyOf([conversation(1)]))
    proxy.turnsPage.mockResolvedValue({ ok: true, turns: [turn(1, 'q', answer('noMatch', 'a'))], nextCursor: null })
    renderWithProviders(<HistoryView />)
    await userEvent.click(await screen.findByRole('button', { name: /what is on my calendar/ }))
    await screen.findByText('a')
    streams.history.push(historyOf([]))
    expect(await screen.findByText('No conversations yet.')).toBeInTheDocument()
  })

  it('a failed read is a failure, not "no conversations"', async () => {
    proxy.history.mockResolvedValue(failure('error_internal'))
    renderWithProviders(<HistoryView />)
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Couldn’t load this')).toBeInTheDocument()
    expect(screen.queryByText('No conversations yet.')).toBeNull()
  })

  it('a bridge rejection is its own sentence', async () => {
    proxy.history.mockRejectedValue(new Error('timed out'))
    renderWithProviders(<HistoryView />)
    expect(await screen.findByText('Tenshu took too long to answer. Try again.')).toBeInTheDocument()
  })
})
