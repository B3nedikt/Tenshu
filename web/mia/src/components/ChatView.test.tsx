import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, streams, resetProxy, failure, answer, hit, turn, conversation, historyOf } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { ChatView } = await import('./ChatView')

beforeEach(() => resetProxy())

// The session's conversation holding `turns` (newest first on the wire, as `turnsPage` answers).
function withTurns(...turns: ReturnType<typeof turn>[]) {
  proxy.history.mockResolvedValue(historyOf([conversation(turns.length)]))
  proxy.turnsPage.mockResolvedValue({ ok: true, turns: [...turns].reverse(), nextCursor: null })
}

describe('ChatView', () => {
  it('greets when there is no conversation yet, and says the browser only looks things up', async () => {
    renderWithProviders(<ChatView />)
    expect(await screen.findByRole('heading', { name: 'Hi, I’m Mia' })).toBeInTheDocument()
    expect(screen.getByText(/only looks things up/)).toBeInTheDocument()
    expect(proxy.turnsPage).not.toHaveBeenCalled()
  })

  it('renders an answer with native’s rich cells: calendar event, workout, place — each its own kind', async () => {
    withTurns(
      turn(
        1,
        'what is on my calendar tomorrow',
        answer('answered', 'Searched your calendar for tomorrow.', [
          hit('tenshu/calendar/eventOccurrence', 'Standup', '09:00 – 09:30', ['e1', '2026-10-29T09:00']),
          hit('tenshu/health/workout', 'Morning run', '5.2 km · 28 min'),
          hit('tenshu/place/place', 'Blue Bottle', 'Berlin'),
          hit('tenshu/something/new', 'Unknown row'),
        ]),
      ),
    )
    renderWithProviders(<ChatView />)
    const results = await screen.findByRole('list', { name: 'Results' })
    expect(screen.getByText('Searched your calendar for tomorrow.')).toBeInTheDocument()
    const rows = within(results).getAllByRole('listitem')
    expect(rows.map((r) => r.querySelector('svg')!.getAttribute('data-kind'))).toEqual(['event', 'workout', 'place', 'file'])
    expect(within(rows[0]!).getByText('Calendar event:')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('5.2 km · 28 min')).toBeInTheDocument()
    expect(within(rows[2]!).getByText('Berlin')).toBeInTheDocument()
    expect(proxy.turnsPage).toHaveBeenCalledWith('c1', null)
  })

  it('an answered with no hits is "nothing matched" — the only empty result there is', async () => {
    withTurns(turn(1, 'workouts yesterday', answer('answered', 'Searched your workouts for yesterday.')))
    renderWithProviders(<ChatView />)
    expect(await screen.findByText('Searched your workouts for yesterday. — nothing matched.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Results' })).toBeNull()
  })

  // ⚠⚠ mia-query-layer.md §14.1: a refusal (here a grant switched off) is `noMatch`, and renders as
  // the planner's sentence — never as a result list, and never as "nothing matched".
  it('a noMatch shows its explanation and NEVER an empty result list', async () => {
    const why = 'You’ve turned off Mia’s access to your workouts, so I can’t look that up.'
    withTurns(turn(1, 'workouts yesterday', answer('noMatch', why)))
    renderWithProviders(<ChatView />)
    const said = await screen.findByText(why)
    expect(said).toHaveAttribute('data-verdict', 'noMatch')
    expect(screen.queryByRole('list', { name: 'Results' })).toBeNull()
    expect(screen.queryByText(/nothing matched/)).toBeNull()
  })

  it('an unavailable says it could not look — no list, no "nothing matched"', async () => {
    const why = 'Something went wrong on this device, so I couldn’t look that up. Nothing was searched.'
    withTurns(turn(1, 'photos like a sunset', answer('unavailable', why)))
    renderWithProviders(<ChatView />)
    expect(await screen.findByText(why)).toHaveAttribute('data-verdict', 'unavailable')
    expect(screen.queryByRole('list', { name: 'Results' })).toBeNull()
    expect(screen.queryByText(/nothing matched/)).toBeNull()
  })

  it('shows turns oldest first, each question above its answer', async () => {
    withTurns(turn(1, 'first question', answer('noMatch', 'first answer')), turn(2, 'second question', answer('noMatch', 'second answer')))
    renderWithProviders(<ChatView />)
    const turns = await screen.findByRole('list', { name: 'Conversation' })
    expect(within(turns).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'first questionfirst answer',
      'second questionsecond answer',
    ])
  })

  it('pages earlier turns by the cursor it was given', async () => {
    proxy.history.mockResolvedValue(historyOf([conversation(3)]))
    proxy.turnsPage
      .mockResolvedValueOnce({ ok: true, turns: [turn(3, 'q3', answer('noMatch', 'a3'))], nextCursor: 'm1.3' })
      .mockResolvedValueOnce({ ok: true, turns: [turn(2, 'q2', answer('noMatch', 'a2'))], nextCursor: null })
    renderWithProviders(<ChatView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Show earlier' }))
    expect(await screen.findByText('q2')).toBeInTheDocument()
    expect(proxy.turnsPage).toHaveBeenLastCalledWith('c1', 'm1.3')
    expect(screen.queryByRole('button', { name: 'Show earlier' })).toBeNull()
  })

  it('sends the question, clears the input, and refetches when the history push moves', async () => {
    proxy.history.mockResolvedValue(historyOf([]))
    proxy.ask.mockResolvedValue({ ok: true, conversationId: 'c1', turn: turn(1, 'workouts yesterday', answer('noMatch', 'x')) })
    renderWithProviders(<ChatView />)
    const input = await screen.findByRole('textbox', { name: 'Your question' })
    await userEvent.type(input, 'workouts yesterday')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(proxy.ask).toHaveBeenCalledWith('workouts yesterday')
    await waitFor(() => expect(input).toHaveValue(''))

    proxy.turnsPage.mockResolvedValue({ ok: true, turns: [turn(1, 'workouts yesterday', answer('noMatch', 'Pushed answer'))], nextCursor: null })
    streams.history.push(historyOf([conversation(1)]))
    expect(await screen.findByText('Pushed answer')).toBeInTheDocument()
  })

  it('keeps a draft typed while the previous question was being answered', async () => {
    let answerNow!: (v: Awaited<ReturnType<typeof proxy.ask>>) => void
    proxy.ask.mockReturnValue(new Promise((r) => (answerNow = r)))
    renderWithProviders(<ChatView />)
    const input = await screen.findByRole('textbox', { name: 'Your question' })
    await userEvent.type(input, 'workouts yesterday')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    await userEvent.clear(input)
    await userEvent.type(input, 'places last week')
    await act(async () => answerNow({ ok: true, conversationId: 'c1', turn: turn(1, 'workouts yesterday', answer('noMatch', 'x')) }))
    expect(input).toHaveValue('places last week')
  })

  it('says Mia is thinking while the runtime composes an answer', async () => {
    proxy.history.mockResolvedValue(historyOf([], true))
    renderWithProviders(<ChatView />)
    expect(await screen.findByText('Mia is thinking…')).toHaveAttribute('role', 'status')
  })

  it('bounds the input to the gateway’s 500 characters and counts down near the end', async () => {
    renderWithProviders(<ChatView />)
    const input = await screen.findByRole('textbox', { name: 'Your question' })
    expect(input).toHaveAttribute('maxLength', '500')
    expect(screen.queryByTestId('remaining')).toBeNull()
    await userEvent.click(input)
    await userEvent.paste('a'.repeat(450))
    expect(screen.getByTestId('remaining')).toHaveTextContent('50 characters left')
  })

  it('will not send a blank question', async () => {
    renderWithProviders(<ChatView />)
    const send = await screen.findByRole('button', { name: 'Send' })
    expect(send).toBeDisabled()
    await userEvent.type(screen.getByRole('textbox', { name: 'Your question' }), '   ')
    expect(send).toBeDisabled()
    expect(proxy.ask).not.toHaveBeenCalled()
  })

  // Every refusal before asking renders by kind and keeps the text, so it can be sent again.
  it.each([
    ['error_mia_session_cleared', 'You were signed out or Tenshu locked while Mia was answering, so that question wasn’t kept. Ask again.'],
    ['error_mia_utterance_too_long', 'That question is too long. Keep it under 500 characters.'],
    ['error_mia_empty_utterance', 'Type a question first.'],
    ['error_mia_invalid_zone', /time zone/],
    ['error_mia_unexpected_action', /Nothing was changed/],
    ['error_something_new', 'Something went wrong. Please try again.'],
  ] as const)('a refused question (%s) renders by kind and keeps the text', async (kind, copy) => {
    proxy.ask.mockResolvedValue(failure(kind))
    renderWithProviders(<ChatView />)
    const input = await screen.findByRole('textbox', { name: 'Your question' })
    await userEvent.type(input, 'what is on my calendar tomorrow')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(copy)
    expect(alert).not.toHaveTextContent('diagnostic')
    expect(input).toHaveValue('what is on my calendar tomorrow')
  })

  it('a bridge rejection is its own sentence', async () => {
    proxy.ask.mockRejectedValue(new Error('not permitted: mia.ask'))
    renderWithProviders(<ChatView />)
    await userEvent.type(await screen.findByRole('textbox', { name: 'Your question' }), 'hi')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Mia is not allowed to do that.')
  })

  it('a failed history read is a failure with a retry, never the greeting', async () => {
    proxy.history.mockResolvedValue(failure('error_internal'))
    renderWithProviders(<ChatView />)
    expect(await screen.findByText('Couldn’t load this')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Hi, I’m Mia' })).toBeNull()
  })

  it('a failed turns page renders by kind (a cleared conversation)', async () => {
    proxy.history.mockResolvedValue(historyOf([conversation(2)]))
    proxy.turnsPage.mockResolvedValue(failure('error_mia_conversation_not_found'))
    renderWithProviders(<ChatView />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/This conversation is gone/)
  })

  it('says so when the history stream ends', async () => {
    renderWithProviders(<ChatView />)
    await screen.findByRole('heading', { name: 'Hi, I’m Mia' })
    streams.history.fail(new Error('mia stream ended'))
    expect(await screen.findByText(/Updates stopped/)).toBeInTheDocument()
  })
})
