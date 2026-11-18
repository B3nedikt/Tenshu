// photos-app.md §42.7: named people, read-only. The list follows its push stream, says when the
// stream died, and offers no naming.
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { person, photo, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { PeopleView } = await import('./PeopleView')

beforeEach(() => {
  resetProxy()
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})

describe('People', () => {
  it('lists the named people and follows the push stream', async () => {
    proxy.listPeople.mockResolvedValue({ ok: true, people: [person({ name: 'Anna', photoCount: 2 })] })
    renderWithProviders(<PeopleView onPerson={() => {}} />)
    expect(await screen.findByText('Anna')).toBeInTheDocument()
    expect(screen.getByText('2 photos')).toBeInTheDocument()

    act(() => streams.people.push({ ok: true, people: [person({ name: 'Anna', photoCount: 2 }), person({ name: 'Bo' })] }))
    expect(await screen.findByText('Bo')).toBeInTheDocument()
  })

  it('says where names come from, and offers no naming', async () => {
    renderWithProviders(<PeopleView onPerson={() => {}} />)
    expect(await screen.findByText('No named people yet.')).toBeInTheDocument()
    expect(screen.getByText(/grouped and named in the Photos app/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /name|rename/i })).toBeNull()
  })

  it('a dead stream says so rather than freezing', async () => {
    renderWithProviders(<PeopleView onPerson={() => {}} />)
    await screen.findByText('No named people yet.')
    act(() => streams.people.fail(new Error('collector died')))
    expect(await screen.findByText(/stopped updating/)).toBeInTheDocument()
  })

  it('a failed list renders its kind, never "No named people yet"', async () => {
    proxy.listPeople.mockResolvedValue({ ok: false, error: { kind: 'error_internal', message: '' } })
    renderWithProviders(<PeopleView onPerson={() => {}} />)
    expect(await screen.findByRole('alert')).toHaveAttribute('data-kind', 'error_internal')
    expect(screen.queryByText('No named people yet.')).toBeNull()
  })

  it('a click opens the person; an open person shows their photographs', async () => {
    proxy.listPeople.mockResolvedValue({ ok: true, people: [person({ name: 'Anna' })] })
    const onPerson = vi.fn()
    const view = renderWithProviders(<PeopleView onPerson={onPerson} />)
    await userEvent.click(await screen.findByText('Anna'))
    expect(onPerson).toHaveBeenCalledWith('Anna')

    view.unmount()
    proxy.personPhotos.mockResolvedValue({ ok: true, photos: [photo({ name: 'anna-1.jpg' })] })
    renderWithProviders(<PeopleView name="Anna" onPerson={onPerson} />)
    expect(await screen.findByRole('button', { name: /anna-1\.jpg/ })).toBeInTheDocument()
    expect(proxy.personPhotos).toHaveBeenCalledWith('Anna')
  })

  // A pushed count change refetches in place: the grid (and an open viewer) never drops to a spinner.
  it('a count change on an open person refetches without unmounting the grid', async () => {
    proxy.listPeople.mockResolvedValue({ ok: true, people: [person({ name: 'Anna', photoCount: 2 })] })
    const a = photo({ name: 'anna-1.jpg' })
    const b = photo({ name: 'anna-2.jpg' })
    proxy.personPhotos.mockResolvedValue({ ok: true, photos: [a, b] })
    renderWithProviders(<PeopleView name="Anna" onPerson={() => {}} />)
    const tile = await screen.findByRole('button', { name: /anna-1\.jpg/ })
    let answer: (v: unknown) => void = () => undefined
    proxy.personPhotos.mockImplementation(() => new Promise((resolve) => (answer = resolve)) as never)
    act(() => streams.people.push({ ok: true, people: [person({ name: 'Anna', photoCount: 1 })] }))
    await waitFor(() => expect(proxy.personPhotos).toHaveBeenCalledTimes(2))
    expect(tile).toBeInTheDocument()
    expect(screen.queryByText('Loading…')).toBeNull()
    act(() => answer({ ok: true, photos: [b] }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /anna-1\.jpg/ })).toBeNull())
  })
})
