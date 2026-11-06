// `RecoveryPage` is the Settings → Recovery surface. Tests cover the
// three status-panel variants (loading / verified / stale or never)
// + the static manifest list + the lost-every-device link. The
// `TestRecoveryDialog` and `RevealPhraseDialog` integrations are
// out of scope here — those dialogs each warrant their own slice.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { RecoveryStatus } from '../recoveryStatus'

const getRecoveryStatusMock = vi.fn<() => Promise<RecoveryStatus>>()

vi.mock('../recoveryStatus', () => ({
  getRecoveryStatus: () => getRecoveryStatusMock(),
}))

import { RecoveryPage } from './RecoveryPage'

function renderPage() {
  return render(
    <MemoryRouter>
      <RecoveryPage />
    </MemoryRouter>,
  )
}

describe('RecoveryPage', () => {
  it('shows the loading status panel until getRecoveryStatus resolves', () => {
    getRecoveryStatusMock.mockReturnValue(new Promise<RecoveryStatus>(() => {}))
    renderPage()
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('renders the verified panel with the prior verification date', async () => {
    const verifiedAtMs = Date.UTC(2026, 0, 15)
    getRecoveryStatusMock.mockResolvedValue({ kind: 'verified', verifiedAtMs })
    renderPage()
    expect(await screen.findByText('Recovery is verified')).toBeInTheDocument()
    const date = new Date(verifiedAtMs).toLocaleDateString()
    expect(screen.getByText(new RegExp(date))).toBeInTheDocument()
  })

  it('renders the never-verified amber panel + the static manifest', async () => {
    getRecoveryStatusMock.mockResolvedValue({ kind: 'never' })
    renderPage()
    expect(
      await screen.findByText('Verify your recovery phrase'),
    ).toBeInTheDocument()
    // Manifest entries from V1_MANIFEST.
    expect(screen.getByText('Master identity')).toBeInTheDocument()
    expect(screen.getByText('Drive files')).toBeInTheDocument()
    expect(screen.getByText('Always')).toBeInTheDocument()
    expect(screen.getByText('Default on')).toBeInTheDocument()
  })

  it('renders the two action cards and the lost-every-device link to /recovery', async () => {
    getRecoveryStatusMock.mockResolvedValue({ kind: 'never' })
    renderPage()
    await waitFor(() => expect(getRecoveryStatusMock).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: 'Start test' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reveal phrase' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open recovery flow/ })).toHaveAttribute(
      'href',
      '/recovery',
    )
  })

  it('links the §7.4 "Set up account recovery" entry to the capture route', async () => {
    getRecoveryStatusMock.mockResolvedValue({ kind: 'never' })
    renderPage()
    await waitFor(() => expect(getRecoveryStatusMock).toHaveBeenCalled())
    expect(screen.getByRole('link', { name: 'Set up recovery' })).toHaveAttribute(
      'href',
      '/control/settings/recovery/setup',
    )
  })
})
