import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EventEditor } from './EventEditor'
import { HOME, WORK, occurrence, proxy, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const calendars = [WORK, HOME]

function open(target: Parameters<typeof EventEditor>[0]['target']) {
  const onClose = vi.fn()
  renderWithProviders(<EventEditor target={target} calendars={calendars} defaultCalendarId={HOME.calendarId} onClose={onClose} />)
  return onClose
}

const weekly = () =>
  occurrence({
    title: 'Standup',
    date: '2026-11-03',
    eventId: 'evt-standup',
    // The third Tuesday of the series; it started on 2026-10-20.
    recurrenceId: '2026-11-03T09:00',
    recurrenceRule: 'FREQ=WEEKLY;BYDAY=TU',
    isRecurring: true,
    zoneId: 'Europe/Berlin',
  })

describe('EventEditor — create', () => {
  it('saves a floating event in the default calendar, then its default reminder by the returned id', async () => {
    const onClose = open({ mode: 'create', date: '2026-10-26', minute: 14 * 60 })
    expect(screen.getByLabelText('Calendar')).toHaveValue(HOME.calendarId)
    expect(screen.getByLabelText('Start time')).toHaveValue('14:00')
    expect(screen.getByLabelText('End time')).toHaveValue('15:00')
    expect(await screen.findByLabelText('10 minutes before')).toBeChecked()
    expect(screen.getByText('Reminders ring on your phone and desktop.')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Title'), 'Review')
    await userEvent.selectOptions(screen.getByLabelText('Repeat'), 'weekly')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(proxy.saveEvent).toHaveBeenCalledWith({
      eventId: '',
      calendarId: HOME.calendarId,
      title: 'Review',
      startLocal: '2026-10-26T14:00',
      endLocal: '2026-10-26T15:00',
      timeZoneId: '',
      allDay: false,
      location: '',
      notes: '',
      recurrenceRule: 'FREQ=WEEKLY;BYDAY=MO',
    })
    expect(proxy.setReminders).toHaveBeenCalledWith('evt-new', [10])
  })

  it('an all-day range is stored with an exclusive end', async () => {
    open({ mode: 'create', date: '2026-10-26' })
    await userEvent.type(screen.getByLabelText('Title'), 'Trip')
    await userEvent.click(screen.getByLabelText('All day'))
    expect(await screen.findByLabelText('On the day at 09:00')).toBeChecked()
    // A date input takes a whole value at once (jsdom has no picker to type into).
    fireEvent.change(screen.getByLabelText('Ends'), { target: { value: '2026-10-28' } })
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(proxy.saveEvent).toHaveBeenCalledWith(
        expect.objectContaining({ allDay: true, startLocal: '2026-10-26T00:00', endLocal: '2026-10-29T00:00' }),
      ),
    )
    expect(proxy.setReminders).toHaveBeenCalledWith('evt-new', [-540])
  })

  it('a default reminder that arrives after the user ticked one does not replace it', async () => {
    let resolve: (v: { ok: true; minutes: number | null }) => void = () => {}
    proxy.defaultReminderMinutes.mockReturnValueOnce(new Promise((r) => (resolve = r)) as never)
    open({ mode: 'create', date: '2026-10-26' })
    await userEvent.click(screen.getByLabelText('5 minutes before'))
    await act(async () => resolve({ ok: true, minutes: 10 }))
    expect(screen.getByLabelText('5 minutes before')).toBeChecked()
    expect(screen.getByLabelText('10 minutes before')).not.toBeChecked()
  })

  it('will not save an event that ends before it starts', async () => {
    open({ mode: 'create', date: '2026-10-26' })
    await userEvent.type(screen.getByLabelText('Title'), 'Oops')
    await userEvent.clear(screen.getByLabelText('End time'))
    await userEvent.type(screen.getByLabelText('End time'), '08:00')
    expect(screen.getByText('The event ends before it starts.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })
})

describe('EventEditor — recurrence: this occurrence vs the series', () => {
  it('asks, and "only this event" writes an override keyed by the ORIGINAL start, no reminders', async () => {
    const onClose = open({ mode: 'edit', occurrence: weekly() })
    expect(screen.getByLabelText('Repeat')).toHaveValue('weekly')
    expect(screen.getByText('Times are in Europe/Berlin, the event’s own time zone.')).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('Start time'))
    await userEvent.type(screen.getByLabelText('Start time'), '09:30')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await userEvent.click(await screen.findByRole('button', { name: 'Only this event' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(proxy.saveOccurrence).toHaveBeenCalledWith(
      expect.objectContaining({ eventId: 'evt-standup', startLocal: '2026-11-03T09:30', recurrenceRule: 'FREQ=WEEKLY;BYDAY=TU', timeZoneId: 'Europe/Berlin' }),
      '2026-11-03T09:00',
    )
    expect(proxy.saveEvent).not.toHaveBeenCalled()
    expect(proxy.setReminders).not.toHaveBeenCalled()
  })

  it('"all events" saves the series, keeps its zone, and rewrites its reminders', async () => {
    const onClose = open({ mode: 'edit', occurrence: weekly() })
    await screen.findByLabelText('10 minutes before')
    await userEvent.clear(screen.getByLabelText('Title'))
    await userEvent.type(screen.getByLabelText('Title'), 'Daily sync')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(/The series will start on Nov 3, 2026/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'All events in the series' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(proxy.saveEvent).toHaveBeenCalledWith(
      expect.objectContaining({ eventId: 'evt-standup', title: 'Daily sync', timeZoneId: 'Europe/Berlin', recurrenceRule: 'FREQ=WEEKLY;BYDAY=TU' }),
    )
    expect(proxy.setReminders).toHaveBeenCalledWith('evt-standup', [10])
    expect(proxy.saveOccurrence).not.toHaveBeenCalled()
  })

  it('a changed rule cannot be applied to one occurrence', async () => {
    open({ mode: 'edit', occurrence: weekly() })
    await userEvent.selectOptions(screen.getByLabelText('Repeat'), 'daily')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('button', { name: 'Only this event' })).toBeDisabled()
  })

  // An override carries only times, title, location and notes: anything else would vanish silently.
  it.each([
    ['its calendar', async () => userEvent.selectOptions(screen.getByLabelText('Calendar'), HOME.calendarId)],
    ['all day', async () => userEvent.click(screen.getByLabelText('All day'))],
  ])('changing %s cannot be applied to one occurrence', async (_what, change) => {
    open({ mode: 'edit', occurrence: weekly() })
    await change()
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('button', { name: 'Only this event' })).toBeDisabled()
    expect(screen.getByText(/only the whole series can take/)).toBeInTheDocument()
  })

  it('toggling all day on an existing event keeps the reminders the user edited', async () => {
    const one = occurrence({ title: 'Lunch', date: '2026-10-26', eventId: 'evt-lunch' })
    const onClose = open({ mode: 'edit', occurrence: one })
    await userEvent.click(await screen.findByLabelText('10 minutes before'))
    await userEvent.click(screen.getByLabelText('All day'))
    await userEvent.click(screen.getByLabelText('All day'))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(proxy.remindersFor).toHaveBeenCalledTimes(1)
    expect(proxy.setReminders).toHaveBeenCalledWith('evt-lunch', [])
  })

  it('a custom rule is shown read-only and saved back untouched', async () => {
    const custom = { ...weekly(), recurrenceRule: 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH' }
    open({ mode: 'edit', occurrence: custom })
    expect(screen.queryByLabelText('Repeat')).not.toBeInTheDocument()
    expect(screen.getByText('Custom')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await userEvent.click(await screen.findByRole('button', { name: 'All events in the series' }))
    await waitFor(() =>
      expect(proxy.saveEvent).toHaveBeenCalledWith(expect.objectContaining({ recurrenceRule: 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH' })),
    )
  })

  it('deleting asks the same question: one occurrence or the series', async () => {
    const onClose = open({ mode: 'edit', occurrence: weekly() })
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Delete only this event' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(proxy.deleteOccurrence).toHaveBeenCalledWith('evt-standup', '2026-11-03T09:00')
    expect(proxy.deleteEvent).not.toHaveBeenCalled()
  })

  it('a one-off is saved and deleted without the question', async () => {
    const one = occurrence({ title: 'Lunch', date: '2026-10-26', eventId: 'evt-lunch' })
    const onClose = open({ mode: 'edit', occurrence: one })
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(screen.queryByRole('button', { name: 'Delete only this event' })).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(proxy.deleteEvent).toHaveBeenCalledWith('evt-lunch')
  })
})

describe('EventEditor — refusals and failures', () => {
  // ⚠⚠ The unknown-zone refusal: nothing to edit, a clear reason, and no save path at all.
  it('refuses to edit an event whose zone is unresolved, and says why', async () => {
    const far = occurrence({ title: 'Far away', date: '2026-10-26', zoneId: 'America/Nowhere', zoneUnresolved: true, eventId: 'evt-far' })
    const onClose = open({ mode: 'edit', occurrence: far })
    expect(screen.getByRole('alert')).toHaveTextContent('This event can’t be edited here')
    expect(screen.getByRole('alert')).toHaveTextContent('America/Nowhere')
    expect(screen.getByRole('alert')).toHaveTextContent('Edit it on your phone or desktop')
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(proxy.remindersFor).not.toHaveBeenCalled()

    // Deleting writes no time, so it stays possible.
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(proxy.deleteEvent).toHaveBeenCalledWith('evt-far')
    expect(proxy.saveEvent).not.toHaveBeenCalled()
  })

  it('a failed reminders read is said, and saving leaves the stored reminders alone', async () => {
    proxy.remindersFor.mockResolvedValueOnce({ ok: false, error: { kind: 'error_generic' } } as never)
    const one = occurrence({ title: 'Lunch', date: '2026-10-26', eventId: 'evt-lunch' })
    const onClose = open({ mode: 'edit', occurrence: one })
    expect(await screen.findByText(/Couldn’t load this event’s reminders/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(proxy.saveEvent).toHaveBeenCalled()
    expect(proxy.setReminders).not.toHaveBeenCalled()
  })

  it('a refused save renders by kind and keeps the form', async () => {
    proxy.saveEvent.mockResolvedValueOnce({ ok: false, error: { kind: 'error_calendar_invalid_rule' } } as never)
    const onClose = open({ mode: 'create', date: '2026-10-26' })
    await userEvent.type(screen.getByLabelText('Title'), 'X')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_calendar_invalid_rule')
    expect(alert).toHaveTextContent('This repeat rule isn’t valid')
    expect(screen.getByLabelText('Title')).toHaveValue('X')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('a bridge rejection on save is its own sentence', async () => {
    proxy.saveOccurrence.mockRejectedValueOnce(new Error('request timed out'))
    open({ mode: 'edit', occurrence: weekly() })
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Only this event' }))
    expect(await screen.findByText('Tenshu took too long to answer. Try again.')).toBeInTheDocument()
  })
})
