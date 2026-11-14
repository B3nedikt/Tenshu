// Apps entry point: the realm's synced app registrations — which app has run on which device
// (docs/proposals/control-app-registration.md §4, web-apex-home.md §3.5 / H6). Backed by
// `AppService.appRegistrations` on the Kotlin side (`tenshu-sdk-js/.../Apps.kt`).
//
// ⚠⚠ The browser writes no registration of its own (the apex has no native IPC handshake), so every
// row is about a native device. ⚠ `appId` is the native catalogue id (`app.tenshu.photos`).

import * as bundle from './_bundle'

export interface RegisteredDevice {
  readonly deviceId: string
  /** `null` when the roster holds no authorised device with this id — never guess one. */
  readonly name: string | null
}

export interface RegisteredApp {
  readonly appId: string
  /** Earliest `createdAt` across the app's devices — native's install date. */
  readonly installedAtMs: number
  readonly verified: boolean
  readonly devices: readonly RegisteredDevice[]
}

export type RegistrationsResult =
  | { readonly ok: true; readonly apps: readonly RegisteredApp[] }
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

export const Apps = bundle.Apps.getInstance()
