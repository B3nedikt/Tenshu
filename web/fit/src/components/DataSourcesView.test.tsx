import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, source } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { DataSourcesView } = await import('./DataSourcesView')

beforeEach(() => {
  resetProxy()
})

describe('DataSourcesView', () => {
  it('lists the projected rows read-only: no switch, no "Import now", no button at all', async () => {
    proxy.dataSources.mockResolvedValue({
      ok: true,
      sources: [
        source(),
        source({ kind: 'TenshuHealth', deviceId: null, deviceLabel: null, isThisDevice: true, state: 'Recording', itemCount: null, atMs: null }),
        source({ deviceId: 'dev-2', deviceLabel: 'iPhone', kind: 'AppleHealth', state: 'PermissionNeeded', autoImport: false, needsAttention: true }),
      ],
      batchImport: { lastRunAtMs: Date.UTC(2026, 9, 1), lastStatus: 'Succeeded', runCount: 4 },
    })
    renderWithProviders(<DataSourcesView />)
    const list = await screen.findByRole('list', { name: 'Data sources' })
    const rows = within(list).getAllByTestId('source-row')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('Health Connect · Pixel 8')
    expect(rows[0]).toHaveTextContent('Imported')
    expect(rows[0]).toHaveTextContent('1,234 items')
    expect(rows[0]).toHaveTextContent('Imports automatically')
    expect(rows[1]).toHaveTextContent('Tenshu Health · This browser')
    expect(rows[2]).toHaveTextContent('Permission needed')
    expect(rows[2]).toHaveTextContent('Automatic import off')
    expect(rows[2]).toHaveTextContent('Needs attention on that device')
    expect(screen.getByTestId('batch-import')).toHaveTextContent('succeeded (4 runs)')

    expect(screen.queryAllByRole('button')).toEqual([])
    expect(screen.queryAllByRole('checkbox')).toEqual([])
    expect(screen.queryAllByRole('switch')).toEqual([])
    expect(screen.getByText('Sources are managed on your devices.')).toBeInTheDocument()
  })

  it('every state the projection can emit has copy', async () => {
    const states = ['NeverRun', 'Imported', 'NoData', 'Failed', 'Running', 'Recording', 'PermissionNeeded', 'Unavailable', 'Sample'] as const
    proxy.dataSources.mockResolvedValue({ ok: true, sources: states.map((state, i) => source({ deviceId: `d${i}`, state })), batchImport: null })
    renderWithProviders(<DataSourcesView />)
    await waitFor(() => expect(screen.getAllByTestId('source-row')).toHaveLength(states.length))
    for (const row of screen.getAllByTestId('source-row')) expect(row.textContent).not.toMatch(/sources\.state\./)
  })

  it('none yet says so, with the footnote', async () => {
    renderWithProviders(<DataSourcesView />)
    expect(await screen.findByText('No data sources yet.')).toBeInTheDocument()
    expect(screen.getByText('Sources are managed on your devices.')).toBeInTheDocument()
  })

  it('a failed read is a failure by kind, not "no sources"', async () => {
    proxy.dataSources.mockResolvedValue(failure('error_unannounced_session'))
    renderWithProviders(<DataSourcesView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_unannounced_session')
    expect(alert).toHaveTextContent('Tenshu needs to reconnect.')
    expect(screen.queryByText('No data sources yet.')).toBeNull()
  })
})
