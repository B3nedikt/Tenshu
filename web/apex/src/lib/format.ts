// File-scale byte sizes (the dev page's upload list). Tops out at MB on purpose:
// its inputs are individual files, and it carries KB/MB precision no storage
// gauge wants. `formatStorageBytes` below is the quota-scale one — see its note
// for why the two are not merged.
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(2)} MB`
}

const STORAGE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB'] as const

/**
 * Quota-scale byte sizes — the Services storage gauge and the tier names. A
 * twin of the native `formatStorageBytes` (`:storage-service-common`), which
 * Android's and iOS's Services screens and every app's storage banner share —
 * rule for rule, so no platform describes the same entitlement differently.
 * The pinned cases in `format.test.ts` are the same as its Kotlin test's.
 *
 * ⚠⚠ Binary steps, because the plans are binary: Free is 5 GiB. iOS rendered
 * it with decimal steps as "5.37 GB" (2026-10-14). And the TB step is
 * load-bearing: without it Premium's 2 TiB reads "2048 GB" (CE-6).
 *
 * One decimal, dropped when `.0` ("5 GB", not "5.0 GB"), rounded half-up — a
 * truncated usage figure understates by up to a whole unit. The unit steps up
 * once the value would *display* as 1024, so 1023.96 KB reads "1 MB".
 *
 * ⚠ Deliberately a second formatter rather than a rewrite of `formatBytes`,
 * which is the dev page's file-size formatter; restyling it would silently
 * change every listing that uses it.
 */
export function formatStorageBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 1024) return `${Math.max(0, Math.trunc(bytes) || 0)} B`
  let value = bytes
  let unit = 0
  while (unit < STORAGE_UNITS.length - 1 && Math.round(value * 10) >= 1024 * 10) {
    value /= 1024
    unit++
  }
  const tenths = Math.round(value * 10)
  const whole = Math.floor(tenths / 10)
  const rest = tenths % 10
  return `${rest === 0 ? whole : `${whole}.${rest}`} ${STORAGE_UNITS[unit]}`
}

export function bytesToHex(bytes: Int8Array): string {
  return Array.from(bytes, b => (b & 0xff).toString(16).padStart(2, '0')).join('')
}
