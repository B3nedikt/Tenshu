// DataPage is a generic walker over `TenshuSdk.realmDataTypeIds()`. We mock that one SDK
// surface and assert (a) it renders a card per realm-data type, and (b) the genericity
// guarantee: a synthetic second type produces a second card with ZERO component changes.
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

const realmDataTypeIdsMock = vi.fn<() => Promise<string[]>>()

vi.mock('@tenshu/sdk/core', () => ({
  TenshuSdk: {
    realmDataTypeIds: () => realmDataTypeIdsMock(),
  },
}))

import { DataPage } from './DataPage'

function renderPage() {
  render(
    <MemoryRouter>
      <DataPage />
    </MemoryRouter>,
  )
}

describe('DataPage', () => {
  it('renders a card for the File realm-data type, linking to its inventory route', async () => {
    realmDataTypeIdsMock.mockResolvedValue(['tenshu/file'])
    renderPage()

    const link = await screen.findByRole('link', { name: /Files/i })
    expect(link).toHaveAttribute('href', '/control/data/files')
  })

  it('always offers the Devices and Shares subsections', async () => {
    realmDataTypeIdsMock.mockResolvedValue(['tenshu/file'])
    renderPage()

    expect(await screen.findByRole('link', { name: /Devices/i })).toHaveAttribute(
      'href',
      '/control/data/devices',
    )
    expect(screen.getByRole('link', { name: /Shares/i })).toHaveAttribute(
      'href',
      '/control/data/shares',
    )
  })

  it('renders a second card for a synthetic new type with no component change (generic walker)', async () => {
    realmDataTypeIdsMock.mockResolvedValue(['tenshu/file', 'tenshu/photo'])
    renderPage()

    expect(await screen.findByRole('link', { name: /Files/i })).toBeInTheDocument()
    // "tenshu/photo" → derived label "Photo", route segment "photo" — purely from the
    // presentation fallback, proving a new realm-data type surfaces with zero code change.
    const photo = screen.getByRole('link', { name: /Photo/i })
    expect(photo).toHaveAttribute('href', '/control/data/photo')
  })
})
