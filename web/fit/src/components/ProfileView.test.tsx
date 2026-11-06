import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, PROFILE } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { localToday, addDays } = await import('../lib/dates')
const { ProfileView } = await import('./ProfileView')

beforeEach(() => {
  resetProxy()
})

async function openEdit(field: string) {
  await userEvent.click(await screen.findByRole('button', { name: `Edit ${field}` }))
  return screen.findByRole('dialog')
}

async function fill(dialog: HTMLElement, label: string, value: string) {
  const input = within(dialog).getByLabelText(label)
  await userEvent.clear(input)
  if (value) await userEvent.type(input, value)
}

function save(dialog: HTMLElement) {
  return within(dialog).getByRole('button', { name: 'Save' })
}

describe('goals', () => {
  it('shows the goals and refuses a value outside the SDK’s bounds before sending', async () => {
    renderWithProviders(<ProfileView />)
    expect(await screen.findByText('30 min')).toBeInTheDocument()
    expect(screen.getByText('8,000')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Edit goals' }))
    const dialog = await screen.findByRole('dialog')

    await fill(dialog, 'Active minutes per day', '4')
    expect(within(dialog).getByTestId('bound')).toHaveTextContent('from 5 to 240')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Active minutes per day', '241')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Active minutes per day', '240')
    await fill(dialog, 'Steps per day', '30001')
    expect(within(dialog).getByTestId('bound')).toHaveTextContent('from 1,000 to 30,000')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Steps per day', '1000')
    expect(within(dialog).queryByTestId('bound')).toBeNull()

    await userEvent.click(save(dialog))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(proxy.setGoals).toHaveBeenCalledWith(240, 1000)
  })

  it('a refusal from the SDK keeps the dialog open and says why', async () => {
    proxy.setGoals.mockResolvedValue(failure('error_fit_invalid_goal'))
    renderWithProviders(<ProfileView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Edit goals' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(save(dialog))
    expect(await within(dialog).findByRole('alert')).toHaveAttribute('data-kind', 'error_fit_invalid_goal')
  })

  it('a failed goals read is a failure, not a default goal', async () => {
    proxy.goals.mockResolvedValue(failure('error_internal'))
    renderWithProviders(<ProfileView />)
    expect(await screen.findByRole('alert')).toHaveAttribute('data-kind', 'error_internal')
    expect(screen.queryByText('30 min')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Edit goals' })).toBeNull()
  })
})

describe('profile', () => {
  it('reads "Not set" for every unset field', async () => {
    renderWithProviders(<ProfileView />)
    await screen.findByText('Personal data')
    await waitFor(() => expect(screen.getAllByText('Not set')).toHaveLength(5))
  })

  it('formats height and weight in the profile’s units', async () => {
    proxy.profile.mockResolvedValue({ ok: true, profile: { ...PROFILE, heightCm: 180, weightKg: 72.5, units: 'imperial' } })
    renderWithProviders(<ProfileView />)
    expect(await screen.findByText('5′ 11″')).toBeInTheDocument()
    expect(screen.getByText('159.8 lb')).toBeInTheDocument()
  })

  it('metric: centimetres and kilograms', async () => {
    proxy.profile.mockResolvedValue({ ok: true, profile: { ...PROFILE, heightCm: 180, weightKg: 72.5 } })
    renderWithProviders(<ProfileView />)
    expect(await screen.findByText('180 cm')).toBeInTheDocument()
    expect(screen.getByText('72.5 kg')).toBeInTheDocument()
  })

  it('switches units with one write', async () => {
    renderWithProviders(<ProfileView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Imperial' }))
    expect(proxy.setUnits).toHaveBeenCalledWith('imperial')
  })

  it('height: whole centimetres in 1..300', async () => {
    renderWithProviders(<ProfileView />)
    const dialog = await openEdit('Height')
    await fill(dialog, 'Centimetres', '301')
    expect(within(dialog).getByTestId('bound')).toHaveTextContent('at most 300')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Centimetres', '0')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Centimetres', '178')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.recordHeight).toHaveBeenCalledWith(178))
  })

  it('height in imperial is entered in feet and inches and sent as centimetres', async () => {
    proxy.profile.mockResolvedValue({ ok: true, profile: { ...PROFILE, units: 'imperial' } })
    renderWithProviders(<ProfileView />)
    const dialog = await openEdit('Height')
    await fill(dialog, 'Feet', '5')
    await fill(dialog, 'Inches', '12')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Inches', '10')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.recordHeight).toHaveBeenCalledWith(178))
  })

  it('weight: above 0 and at most 1 000 kg; imperial is sent as kilograms', async () => {
    renderWithProviders(<ProfileView />)
    const dialog = await openEdit('Weight')
    await fill(dialog, 'Kilograms', '0')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Kilograms', '1000.1')
    expect(within(dialog).getByTestId('bound')).toHaveTextContent('at most 1000 kg')
    await fill(dialog, 'Kilograms', '72,5')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.recordWeight).toHaveBeenCalledWith(72.5))
  })

  it('weight in pounds', async () => {
    proxy.profile.mockResolvedValue({ ok: true, profile: { ...PROFILE, units: 'imperial' } })
    renderWithProviders(<ProfileView />)
    const dialog = await openEdit('Weight')
    await fill(dialog, 'Pounds', '160')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.recordWeight).toHaveBeenCalled())
    expect(proxy.recordWeight.mock.calls[0]![0]).toBeCloseTo(72.5748, 3)
  })

  // The unit round trips are lossy, so an untouched value is never re-sent.
  it('an untouched height or weight cannot be saved, so a lossy unit round trip never changes it', async () => {
    proxy.profile.mockResolvedValue({ ok: true, profile: { ...PROFILE, heightCm: 179, weightKg: 70, units: 'imperial' } })
    renderWithProviders(<ProfileView />)
    let dialog = await openEdit('Height')
    // 179 cm shows as 5'10", which would be sent back as 178.
    expect(within(dialog).getByLabelText('Feet')).toHaveValue(5)
    expect(within(dialog).getByLabelText('Inches')).toHaveValue(10)
    expect(save(dialog)).toBeDisabled()
    await userEvent.click(save(dialog))
    // Edited and edited back: still the value on screen, still nothing to save.
    await fill(dialog, 'Inches', '11')
    expect(save(dialog)).toBeEnabled()
    await fill(dialog, 'Inches', '10')
    expect(save(dialog)).toBeDisabled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    dialog = await openEdit('Weight')
    // 70 kg shows as 154.3 lb, which would be sent back as 69.99 kg.
    expect(within(dialog).getByLabelText('Pounds')).toHaveValue('154.3')
    expect(save(dialog)).toBeDisabled()
    await userEvent.click(save(dialog))
    await fill(dialog, 'Pounds', '155')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.recordWeight).toHaveBeenCalledTimes(1))
    expect(proxy.recordWeight.mock.calls[0]![0]).toBeCloseTo(70.307, 2)
    expect(proxy.recordHeight).not.toHaveBeenCalled()
  })

  it('an untouched metric height is not re-sent either', async () => {
    proxy.profile.mockResolvedValue({ ok: true, profile: { ...PROFILE, heightCm: 179 } })
    renderWithProviders(<ProfileView />)
    const dialog = await openEdit('Height')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Centimetres', '180')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.recordHeight).toHaveBeenCalledWith(180))
  })

  it('birth date: 1900-01-01 to today', async () => {
    renderWithProviders(<ProfileView />)
    const dialog = await openEdit('Birth date')
    await fill(dialog, 'Birth date', '1899-12-31')
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Birth date', addDays(localToday(), 1))
    expect(save(dialog)).toBeDisabled()
    await fill(dialog, 'Birth date', '1990-05-17')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.setBirthDate).toHaveBeenCalledWith('1990-05-17'))
  })

  it('name and sex', async () => {
    renderWithProviders(<ProfileView />)
    let dialog = await openEdit('Name')
    await fill(dialog, 'Name', '  Sam ')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.setName).toHaveBeenCalledWith('Sam'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    dialog = await openEdit('Sex')
    expect(save(dialog)).toBeDisabled()
    await userEvent.selectOptions(within(dialog).getByLabelText('Sex'), 'other')
    await userEvent.click(save(dialog))
    await waitFor(() => expect(proxy.setSex).toHaveBeenCalledWith('other'))
  })

  it('a refused profile value renders by kind', async () => {
    proxy.recordWeight.mockResolvedValue(failure('error_fit_invalid_profile_value'))
    renderWithProviders(<ProfileView />)
    const dialog = await openEdit('Weight')
    await fill(dialog, 'Kilograms', '70')
    await userEvent.click(save(dialog))
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_fit_invalid_profile_value')
    expect(alert).toHaveTextContent('outside the allowed range')
  })

  it('a failed profile read is a failure, not a blank profile', async () => {
    proxy.profile.mockResolvedValue(failure('error_internal'))
    renderWithProviders(<ProfileView />)
    await waitFor(() => expect(screen.getAllByRole('alert').some((a) => a.getAttribute('data-kind') === 'error_internal')).toBe(true))
    expect(screen.queryByText('Not set')).toBeNull()
  })
})
