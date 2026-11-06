// `RecoveryBanner` reads the IDB-backed verified-at timestamp through
// `getRecoveryStatus`. We mock that import to drive each branch of the
// banner without touching IndexedDB — `recoveryStatus.test.ts` already
// covers the IDB read itself.
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { RecoveryStatus } from './recoveryStatus'

const getRecoveryStatusMock = vi.fn<() => Promise<RecoveryStatus>>()

vi.mock('./recoveryStatus', () => ({
  getRecoveryStatus: () => getRecoveryStatusMock(),
}))

import { RecoveryBanner } from './RecoveryBanner'

function renderBanner() {
  return render(
    <MemoryRouter>
      <RecoveryBanner />
    </MemoryRouter>,
  )
}

describe('RecoveryBanner', () => {
  it("renders nothing while the IDB probe is in flight", () => {
    getRecoveryStatusMock.mockReturnValue(new Promise<RecoveryStatus>(() => {}))
    const { container } = renderBanner()
    expect(container).toBeEmptyDOMElement()
  })

  it("renders nothing once the phrase is reported verified", async () => {
    getRecoveryStatusMock.mockResolvedValue({ kind: 'verified', verifiedAtMs: 1 })
    const { container } = renderBanner()
    // The probe is async — assert the empty body holds after a tick to
    // catch a regression where a `verified` status accidentally rendered.
    await waitFor(() => expect(getRecoveryStatusMock).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it("renders the never-verified copy and links to /control/settings/recovery", async () => {
    getRecoveryStatusMock.mockResolvedValue({ kind: 'never' })
    renderBanner()
    expect(
      await screen.findByText('Verify your recovery phrase'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Verify' })).toHaveAttribute(
      'href',
      '/control/settings/recovery',
    )
  })

  it("renders the stale copy with the prior verification date", async () => {
    const verifiedAtMs = Date.UTC(2025, 5, 10) // 10 Jun 2025
    getRecoveryStatusMock.mockResolvedValue({ kind: 'stale', verifiedAtMs })
    renderBanner()
    expect(
      await screen.findByText('Re-verify your recovery phrase'),
    ).toBeInTheDocument()
    // toLocaleDateString is locale-dependent; assert just that the
    // prose mentions a date string derived from `verifiedAtMs`.
    const expectedDate = new Date(verifiedAtMs).toLocaleDateString()
    expect(screen.getByText(new RegExp(expectedDate))).toBeInTheDocument()
  })
})
