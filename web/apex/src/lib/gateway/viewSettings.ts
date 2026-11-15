// `driveSettings.*` / `photoSettings.*`: the web apps' remembered view settings (web-apps-layout.md
// §3.6, L6). ⚠ Typed per app, not a generic `preference.*` method: an app's allowlist names exactly
// its own, so no framed app can read or write another's settings. DEVICE scope (this browser).
import { DriveSettings, PhotosSettings } from '@tenshu/sdk/viewSettings'
import type { GatewayModule } from './module'
import { asRecord } from './params'

const SORT_KEYS = ['name', 'modified', 'created', 'size'] as const
const LAYOUTS = ['list', 'grid'] as const
const DENSITIES = ['days', 'months', 'years'] as const

function oneOf<T extends string>(value: unknown, allowed: readonly T[], what: string): T {
  if (typeof value === 'string' && (allowed as readonly string[]).includes(value)) return value as T
  throw new Error(`${what} requires one of ${allowed.join(' | ')}`)
}

function bool(value: unknown, what: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`${what} requires a boolean`)
  return value
}

// Two modules, one namespace each (the gateway's rule): Drive's and Photos' are separate surfaces.
export const driveSettingsGateway: GatewayModule = {
  handlers: {
    'driveSettings.get': async () => DriveSettings.get(),
    'driveSettings.setListing': async (params) => {
      const r = asRecord(params)
      return DriveSettings.setListing(
        oneOf(r.key, SORT_KEYS, 'driveSettings.setListing { key }'),
        bool(r.descending, 'driveSettings.setListing { descending }'),
        oneOf(r.layout, LAYOUTS, 'driveSettings.setListing { layout }'),
      )
    },
    'driveSettings.setDetailsPane': async (params) =>
      DriveSettings.setDetailsPane(bool(asRecord(params).open, 'driveSettings.setDetailsPane { open }')),
  },
}

export const photoSettingsGateway: GatewayModule = {
  handlers: {
    'photoSettings.get': async () => PhotosSettings.get(),
    'photoSettings.setDensity': async (params) =>
      PhotosSettings.setDensity(oneOf(asRecord(params).density, DENSITIES, 'photoSettings.setDensity { density }')),
    'photoSettings.setColumns': async (params) => {
      const columns = asRecord(params).columns
      if (typeof columns !== 'number' || !Number.isInteger(columns) || columns < 3 || columns > 8)
        throw new Error('photoSettings.setColumns requires { columns } an integer 3…8')
      return PhotosSettings.setColumns(columns)
    },
    'photoSettings.setLayout': async (params) => {
      const r = asRecord(params)
      return PhotosSettings.setLayout(bool(r.mosaic, 'photoSettings.setLayout { mosaic }'), bool(r.fit, 'photoSettings.setLayout { fit }'))
    },
  },
}
