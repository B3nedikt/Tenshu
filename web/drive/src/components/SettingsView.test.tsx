import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const storageStatus = vi.fn()
vi.mock('../lib/proxy', () => ({
  APEX: 'http://localhost:5173',
  storageStatus: () => storageStatus(),
}))

const { SettingsView } = await import('./SettingsView')
const { APEX } = await import('../lib/proxy')

function renderSettings() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <SettingsView />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  storageStatus.mockReset()
})

describe('SettingsView', () => {
  it('shows the about blurb and a Tenshu account hand-off link', () => {
    storageStatus.mockResolvedValue(null)
    renderSettings()

    expect(screen.getByText('About')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Manage account in Tenshu' })).toHaveAttribute(
      'href',
      APEX,
    )
  })

  it('links the privacy policy in the browser language', () => {
    storageStatus.mockResolvedValue(null)
    vi.spyOn(navigator, 'language', 'get').mockReturnValue('de-DE')
    renderSettings()

    const link = screen.getByRole('link', { name: 'Datenschutzerklärung' })
    expect(link).toHaveAttribute('href', 'https://tenshu.app/legal/privacy/de')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('labels the link in English for a reader the policy is not published for', () => {
    storageStatus.mockResolvedValue(null)
    vi.spyOn(navigator, 'language', 'get').mockReturnValue('es-ES')
    renderSettings()

    expect(screen.getByRole('link', { name: 'Privacy policy' })).toHaveAttribute(
      'href',
      'https://tenshu.app/legal/privacy/en',
    )
  })

  it('shows a no-plan hand-off when there is no storage', async () => {
    storageStatus.mockResolvedValue({ hasStorage: false, usedBytes: 0, limitBytes: 0 })
    renderSettings()

    expect(await screen.findByText(/No storage plan on this device/)).toBeInTheDocument()
  })

  it('shows used-of-limit when a storage plan is bound', async () => {
    storageStatus.mockResolvedValue({
      hasStorage: true,
      usedBytes: 512 * 1024 * 1024,
      limitBytes: 1024 * 1024 * 1024,
    })
    renderSettings()

    await waitFor(() => {
      expect(screen.getByText(/512 MB of 1 GB used/)).toBeInTheDocument()
    })
  })
})
