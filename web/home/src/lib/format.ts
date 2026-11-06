// Display formatting, mirroring native Home's formatters (`home-shared-logic` `HomeModel.kt`) so a
// figure reads the same on every platform: decimal byte units, "21.5°", and the storage tile's
// numeric "as of" stamp.
import type { TFunction } from 'i18next'
import type { HomeActivity, HomeDevice } from '@tenshu/sdk/home'

// `formatByteSize`: powers of 1000, one decimal below 10 ("1.5 GB", "12 GB"), truncated like native.
export function byteSizeText(bytes: number): string {
  if (bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let value = bytes
  let unit = 0
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000
    unit++
  }
  const whole = Math.trunc(value)
  const tenth = Math.trunc((value - whole) * 10)
  return unit === 0 || tenth === 0 || whole >= 10 ? `${whole} ${units[unit]}` : `${whole}.${tenth} ${units[unit]}`
}

// `formatDeciC`: tenths of a °C → "21.5°" / "21°". ⚠ Native's integer division drops the
// sign of a value in (-1°, 0°); kept identical so the platforms agree.
export function deciCText(deciC: number): string {
  const whole = Math.trunc(deciC / 10)
  const tenth = Math.abs(deciC) % 10
  return tenth === 0 ? `${whole}°` : `${whole}.${tenth}°`
}

const pad = (n: number) => String(n).padStart(2, '0')

// `formatAsOfTime`: "14:05" on the same local day as `nowMs`, else "2026-07-23 14:05". Numeric on
// purpose (no month names), like native.
export function asOfText(epochMs: number, nowMs: number): string {
  const at = new Date(epochMs)
  const now = new Date(nowMs)
  const time = `${pad(at.getHours())}:${pad(at.getMinutes())}`
  const sameDay =
    at.getFullYear() === now.getFullYear() && at.getMonth() === now.getMonth() && at.getDate() === now.getDate()
  return sameDay ? time : `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${time}`
}

// A row's time of day in the page's locale.
export function clockText(locale: string, epochMs: number): string {
  return new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit' }).format(epochMs)
}

// The device row's state line, per kind as native renders it (`SyncedDeviceAwareHomeDevicesRepository`
// → `DevicesView`): lights on/off with their level, a thermostat's temperature, connectivity for
// speakers, cameras and radio bridges, and a reading line for everything else (plugs, sensors, a kind a
// newer hub knows). ⚠ STATE AS STORED: a device whose state row has not synced says so, rather than
// showing native's defaults ("Off", 0°); a dimension the device does not report is left out, never 0.
export interface StateLine {
  readonly text: string
  // Connectivity rows that are offline render in the danger colour (native's red subtitle).
  readonly alert: boolean
}

export function stateLine(t: TFunction, device: HomeDevice): StateLine {
  const s = device.state
  if (!s) return { text: t('devices.state.noReading'), alert: false }
  switch (device.kind) {
    case 'light':
      if (s.power === 'on') {
        return { text: s.levelPercent !== null ? t('devices.state.onLevel', { level: s.levelPercent }) : t('devices.state.on'), alert: false }
      }
      if (s.power === 'off') return { text: t('devices.state.off'), alert: false }
      return { text: reading(s), alert: false }
    case 'thermostat':
      return { text: s.temperatureDeciC !== null ? deciCText(s.temperatureDeciC) : t('devices.state.noReading'), alert: false }
    case 'speaker':
    case 'camera':
    case 'bridge':
      return { text: s.online ? t('devices.state.connected') : t('devices.state.disconnected'), alert: !s.online }
    default:
      return { text: reading(s), alert: false }
  }
}

// Native's `sensorSummary`: "21.5° · 45%", empty when the device reports neither.
function reading(s: NonNullable<HomeDevice['state']>): string {
  return [s.temperatureDeciC !== null ? deciCText(s.temperatureDeciC) : null, s.humidityPercent !== null ? `${s.humidityPercent}%` : null]
    .filter((v): v is string => v !== null)
    .join(' · ')
}

// The activity row's second line: native's outcome words (`SyncedHomeActivityRepository`).
export function outcomeText(t: TFunction, event: HomeActivity): string {
  return t(`activity.outcome.${event.state}`)
}
