import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ProfileView } from './ProfileView'
import { proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

describe('ProfileView', () => {
  it('shows the pushed own name and saves a new one', async () => {
    renderWithProviders(<ProfileView />)
    expect(await screen.findByText('No name set')).toBeInTheDocument()
    await waitFor(() => expect(streams.profile.open()).toBe(1))
    act(() => streams.profile.push({ displayName: 'Ben', fingerprint: '' }))
    expect(await screen.findByText('Ben')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Edit name' }))
    const field = screen.getByLabelText('Your name')
    await userEvent.clear(field)
    await userEvent.type(field, 'Benedikt')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(proxy.setOwnDisplayName).toHaveBeenCalledWith('Benedikt'))
  })

  it('surfaces a failed save by its kind', async () => {
    proxy.setOwnDisplayName.mockResolvedValueOnce({ ok: false, error: { kind: 'error_generic' } } as never)
    renderWithProviders(<ProfileView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Edit name' }))
    await userEvent.type(screen.getByLabelText('Your name'), 'X')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument()
  })
})
