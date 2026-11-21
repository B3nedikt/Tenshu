import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, streams, resetProxy, failure, defaultGrants, SOURCES } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { GrantsView } = await import('./GrantsView')

beforeEach(() => resetProxy())

function row(source: string): HTMLElement {
  return document.querySelector<HTMLElement>(`[data-source="${source}"]`)!
}

function readSwitch(name: string): HTMLInputElement {
  return screen.getByRole('switch', { name }) as HTMLInputElement
}

describe('GrantsView', () => {
  it('lists every source with its switch, per service, as native Settings does', async () => {
    renderWithProviders(<GrantsView />)
    expect(await screen.findByRole('switch', { name: 'Workouts' })).toBeChecked()
    // ⚠ One switch per source: the calendar's separate write switch is gone (2026-11-19).
    expect(screen.getAllByRole('switch')).toHaveLength(SOURCES.length)
    expect(screen.getByText(/one app at a time/)).toBeInTheDocument()
    expect(screen.getByText('These switches apply to Mia on all your devices.')).toBeInTheDocument()
  })

  it('turning a source off calls setGranted and draws the set the runtime read back', async () => {
    proxy.setGranted.mockResolvedValue({ ok: true, grants: defaultGrants({ FITNESS: { read: false } }) })
    renderWithProviders(<GrantsView />)
    await userEvent.click(await screen.findByRole('switch', { name: 'Workouts' }))
    expect(proxy.setGranted).toHaveBeenCalledWith('FITNESS', false)
    expect(await screen.findByRole('switch', { name: 'Workouts' })).not.toBeChecked()
  })

  // ⚠⚠ Never optimistic: until the result arrives the switch shows what the runtime last said, and
  // every switch is disabled so a second write cannot race the first.
  it('is not optimistic while the write is in flight', async () => {
    let settle!: (v: Awaited<ReturnType<typeof proxy.setGranted>>) => void
    proxy.setGranted.mockReturnValue(new Promise((r) => (settle = r)))
    renderWithProviders(<GrantsView />)
    await userEvent.click(await screen.findByRole('switch', { name: 'Places' }))
    expect(readSwitch('Places')).toBeChecked()
    expect(readSwitch('Files')).toBeDisabled()
    await act(async () => settle({ ok: true, grants: defaultGrants({ PLACES: { read: false } }) }))
    await waitFor(() => expect(readSwitch('Places')).not.toBeChecked())
    expect(readSwitch('Files')).toBeEnabled()
  })

  it.each([
    ['error_mia_unknown_source', 'Mia doesn’t know that source. Nothing was changed.'],
    ['error_internal', 'Something went wrong. Please try again.'],
  ])('a failed write (%s) keeps the old state and shows the error by kind under that row', async (kind, copy) => {
    proxy.setGranted.mockResolvedValue(failure(kind))
    renderWithProviders(<GrantsView />)
    await userEvent.click(await screen.findByRole('switch', { name: 'Timeline' }))
    const alert = await within(row('TIMELINE')).findByRole('alert')
    expect(alert).toHaveTextContent(copy)
    expect(alert).toHaveAttribute('data-kind', kind)
    expect(readSwitch('Timeline')).toBeChecked()
  })

  it('a write that landed but could not be read back re-reads the set, and is not shown as a failure', async () => {
    proxy.setGranted.mockResolvedValue({ ok: true, grants: null })
    renderWithProviders(<GrantsView />)
    await screen.findByRole('switch', { name: 'Workouts' })
    const readsBefore = proxy.grants.mock.calls.length
    proxy.grants.mockResolvedValue({ ok: true, grants: defaultGrants({ FITNESS: { read: false } }) })
    await userEvent.click(readSwitch('Workouts'))
    await waitFor(() => expect(proxy.grants.mock.calls.length).toBeGreaterThan(readsBefore))
    await waitFor(() => expect(readSwitch('Workouts')).not.toBeChecked())
    expect(within(row('FITNESS')).queryByRole('alert')).toBeNull()
  })

  it('a bridge rejection keeps the old state too', async () => {
    proxy.setGranted.mockRejectedValue(new Error('not permitted: mia.grants.setGranted'))
    renderWithProviders(<GrantsView />)
    await userEvent.click(await screen.findByRole('switch', { name: 'Timeline' }))
    expect(await within(row('TIMELINE')).findByRole('alert')).toHaveTextContent('Mia is not allowed to do that.')
    expect(readSwitch('Timeline')).toBeChecked()
  })

  it('follows a switch flipped on another device (the push)', async () => {
    renderWithProviders(<GrantsView />)
    expect(await screen.findByRole('switch', { name: 'Smart-home devices' })).toBeChecked()
    act(() => streams.grants.push({ ok: true, grants: defaultGrants({ DEVICES: { read: false } }) }))
    await waitFor(() => expect(readSwitch('Smart-home devices')).not.toBeChecked())
  })

  it('a failed read is a failure, never a default set of switches', async () => {
    proxy.grants.mockResolvedValue(failure('error_internal'))
    renderWithProviders(<GrantsView />)
    expect(await screen.findByText('Couldn’t load this')).toBeInTheDocument()
    expect(screen.queryByRole('switch')).toBeNull()
  })

  it('says so when the grants stream ends', async () => {
    renderWithProviders(<GrantsView />)
    await screen.findByRole('switch', { name: 'Files' })
    act(() => streams.grants.fail(new Error('mia stream ended')))
    expect(await screen.findByText(/Updates stopped/)).toBeInTheDocument()
  })
})
