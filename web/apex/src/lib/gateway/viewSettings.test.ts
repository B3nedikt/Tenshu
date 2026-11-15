// The view-settings gateway (web-apps-layout.md L6): forwards typed values to the SDK, and refuses a
// value outside the vocabulary without calling it — nothing malformed is ever stored.
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { DriveSettings, PhotosSettings } = vi.hoisted(() => ({
  DriveSettings: {
    get: vi.fn(async () => ({ ok: true, key: 'name', descending: false, layout: 'list', detailsPane: false })),
    setListing: vi.fn(async (_k: string, _d: boolean, _l: string) => ({ ok: true })),
    setDetailsPane: vi.fn(async (_o: boolean) => ({ ok: true })),
  },
  PhotosSettings: {
    get: vi.fn(async () => ({ ok: true, density: 'months' })),
    setDensity: vi.fn(async (_d: string) => ({ ok: true })),
    setLayout: vi.fn(async (_m: boolean, _f: boolean) => ({ ok: true })),
    setColumns: vi.fn(async (_c: number) => ({ ok: true })),
  },
}))
vi.mock('@tenshu/sdk/viewSettings', () => ({ DriveSettings, PhotosSettings }))

const { driveSettingsGateway, photoSettingsGateway } = await import('./viewSettings')
const handler = (method: string) => (driveSettingsGateway.handlers[method] ?? photoSettingsGateway.handlers[method])!

beforeEach(() => vi.clearAllMocks())

describe('view settings gateway', () => {
  it('forwards well-formed calls', async () => {
    await handler('driveSettings.get')(undefined)
    await handler('driveSettings.setListing')({ key: 'size', descending: true, layout: 'grid' })
    await handler('driveSettings.setDetailsPane')({ open: true })
    await handler('photoSettings.setDensity')({ density: 'years' })
    expect(DriveSettings.get).toHaveBeenCalled()
    expect(DriveSettings.setListing).toHaveBeenCalledWith('size', true, 'grid')
    expect(DriveSettings.setDetailsPane).toHaveBeenCalledWith(true)
    expect(PhotosSettings.setDensity).toHaveBeenCalledWith('years')
    await handler('photoSettings.setLayout')({ mosaic: true, fit: false })
    expect(PhotosSettings.setLayout).toHaveBeenCalledWith(true, false)
    await handler('photoSettings.setColumns')({ columns: 4 })
    expect(PhotosSettings.setColumns).toHaveBeenCalledWith(4)
  })

  it.each<[string, unknown]>([
    ['driveSettings.setListing', { key: 'colour', descending: true, layout: 'grid' }],
    ['driveSettings.setListing', { key: 'name', descending: 'yes', layout: 'grid' }],
    ['driveSettings.setListing', { key: 'name', descending: false, layout: 'tiles' }],
    ['driveSettings.setListing', {}],
    ['driveSettings.setDetailsPane', { open: 1 }],
    ['photoSettings.setDensity', { density: 'decades' }],
    ['photoSettings.setDensity', undefined],
    ['photoSettings.setLayout', { mosaic: 'yes', fit: false }],
    ['photoSettings.setLayout', { mosaic: true }],
    ['photoSettings.setColumns', { columns: 2 }],
    ['photoSettings.setColumns', { columns: 9 }],
    ['photoSettings.setColumns', { columns: 4.5 }],
    ['photoSettings.setColumns', { columns: '4' }],
  ])('%s refuses %j without calling the SDK', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires/)
    expect(DriveSettings.setListing).not.toHaveBeenCalled()
    expect(DriveSettings.setDetailsPane).not.toHaveBeenCalled()
    expect(PhotosSettings.setDensity).not.toHaveBeenCalled()
    expect(PhotosSettings.setLayout).not.toHaveBeenCalled()
    expect(PhotosSettings.setColumns).not.toHaveBeenCalled()
  })
})
