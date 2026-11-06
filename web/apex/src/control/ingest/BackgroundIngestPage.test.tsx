import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import type { IngestSourceItem } from '@tenshu/sdk/ingest'

const listMock = vi.fn<() => Promise<IngestSourceItem[]>>()
const setEnabledMock = vi.fn<(s: string, d: string, e: boolean) => Promise<boolean>>()

vi.mock('@tenshu/sdk/ingest', () => ({
  IngestSources: {
    list: () => listMock(),
    setEnabled: (sourceId: string, deviceId: string, enabled: boolean) =>
      setEnabledMock(sourceId, deviceId, enabled),
  },
}))

import { BackgroundIngestPage } from './BackgroundIngestPage'

function source(overrides: Partial<IngestSourceItem> = {}): IngestSourceItem {
  return {
    key: 'drive.gallery@dev1',
    sourceId: 'drive.gallery',
    appId: 'app.tenshu.drive',
    deviceId: 'dev1',
    displayName: 'Photos & Videos',
    sourceKind: 'GALLERY',
    enabled: true,
    status: 'UP_TO_DATE',
    pendingCount: 0,
    ...overrides,
  }
}

function renderPage() {
  return render(
    <MemoryRouter>
      <BackgroundIngestPage />
    </MemoryRouter>,
  )
}

describe('BackgroundIngestPage', () => {
  beforeEach(() => {
    listMock.mockReset()
    setEnabledMock.mockReset()
  })

  it('lists each source with its display name, status, and count', async () => {
    listMock.mockResolvedValue([
      source({ key: 'a', displayName: 'Photos & Videos', status: 'UP_TO_DATE' }),
      source({ key: 'b', displayName: 'Screenshots', status: 'RUNNING' }),
    ])
    renderPage()
    expect(await screen.findByText('Photos & Videos')).toBeInTheDocument()
    expect(screen.getByText('Screenshots')).toBeInTheDocument()
    expect(screen.getByText('Up to date')).toBeInTheDocument()
    expect(screen.getByText('Backing up…')).toBeInTheDocument()
    expect(screen.getByText('2 sources')).toBeInTheDocument()
  })

  it('shows the empty state when no source is registered', async () => {
    listMock.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText(/No background ingest yet/i)).toBeInTheDocument()
  })

  it('flips the kill switch and reloads', async () => {
    listMock.mockResolvedValue([source({ enabled: true })])
    setEnabledMock.mockResolvedValue(true)
    const user = (await import('@testing-library/user-event')).default.setup()

    renderPage()
    await screen.findByText('Photos & Videos')
    expect(listMock).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('switch'))

    await waitFor(() =>
      expect(setEnabledMock).toHaveBeenCalledWith('drive.gallery', 'dev1', false),
    )
    // Re-fetched after the toggle (initial + reload).
    await waitFor(() => expect(listMock).toHaveBeenCalledTimes(2))
  })
})
