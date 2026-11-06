// Identity entry point: device-realm types and operations.
// Backed by `:identity-service` on the Kotlin side.

import * as bundle from './_bundle'

export interface Device {
  readonly deviceId: string
  readonly name: string
  readonly publicKey: Int8Array
  readonly isCurrent: boolean
  readonly isAuthorized: boolean
  // The device's self-published hardware model (`DeviceHardware`), beside the
  // user-chosen `name`. Empty when the device has not published one yet —
  // render the name alone (`formatDeviceLabel`).
  readonly hardwareName?: string
}

// Phase B Devices contract (web-control-plane.md §4.3). The Kotlin
// `@JsExport object Devices` adds `observe` (callback + unsubscribe,
// matches Notifications.observe) and `revoke` (TResult<void>) on top of
// the existing demo. Apex consumers go through this `DevicesApi` so
// tests can substitute a mock at the application boundary.

export type DeviceRevokeError =
  | { readonly kind: 'network' }
  | { readonly kind: 'other'; readonly message: string }

export type TResult<T, E = string> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: E }

export interface DevicesApi {
  observe(callback: (devices: readonly Device[]) => void): () => void
  revoke(deviceId: string): Promise<TResult<void, DeviceRevokeError>>

  // Revoke THIS browser's own device from the realm (web self-revoke /
  // "sign out everywhere", roadmap Phase B). The SDK resolves the current
  // device id internally — the apex has no reliable handle on it
  // (`Device.isCurrent` is empty in practice and name-matching is
  // ambiguous when two devices share a name). The returned promise
  // resolves `ok` only once the `RevokeDevice` tombstone has been pushed
  // to the relay, so the caller may safely wipe local state afterwards
  // knowing the revocation will reach the realm's other devices.
  //
  // Ordering is load-bearing and lives on the caller: revoke first, wipe
  // second. The wipe (`Session.clear({ purgeRealm: true })`) destroys the
  // device signing key + relay JWT, so a wipe-first path could never
  // project the revocation — the browser would linger as a permanent
  // ghost device. Offline → `network` error: the tombstone could not be
  // pushed; the caller decides whether to wipe locally anyway (the web
  // "sign out everywhere" flow warns-and-proceeds, leaving the ghost to
  // be reaped by revoking from another device).
  revokeSelf(): Promise<TResult<void, DeviceRevokeError>>
}

const devicesKotlin = bundle.Devices.getInstance()

export const Devices: DevicesApi = {
  observe: (callback) => devicesKotlin.observe(devices => callback(devices)),
  revoke: (deviceId) =>
    devicesKotlin.revoke(deviceId) as ReturnType<DevicesApi['revoke']>,
  revokeSelf: () =>
    devicesKotlin.revokeSelf() as ReturnType<DevicesApi['revokeSelf']>,
}

// Legacy demo accessor kept for the per-service smoke tests + dev
// surface. All device-list rendering goes through `Devices.observe`.
export function demoDevice(): Device {
  return devicesKotlin.demo()
}
