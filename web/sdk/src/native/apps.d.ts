// `Apps` — the realm's synced `AppRegistration` rows (docs/proposals/web-apex-home.md §3.5, H6).
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// One device an app has an Active registration on. `name` is `null` when the realm roster holds no
// authorised device with that id (revoked, or not synced yet) — never a guessed name.
interface NativeRegisteredDevice {
  readonly deviceId: string
  readonly name: string | null
}

// One app with at least one Active registration. `appId` is the NATIVE catalogue id
// (`app.tenshu.photos`), not the web registry's (`photos`).
interface NativeRegisteredApp {
  readonly appId: string
  readonly installedAtMs: number
  readonly verified: boolean
  readonly devices: readonly NativeRegisteredDevice[]
}

type NativeRegistrationsResult = { readonly ok: true; readonly apps: readonly NativeRegisteredApp[] } | NativeFailure

interface AppsInstance {
  // Seeds with one-shot reads, then re-emits on every change to the rows or the device roster.
  // `onError` fires once if the stream ends for any reason but the returned unsubscribe.
  observeRegistrations(callback: (r: NativeRegistrationsResult) => void, onError: (message: string) => void): () => void
  // Deregisters the app on EVERY device (tombstones + grant purge). There is no per-device form on
  // the web: the browser holds no registration of its own.
  removeApp(appId: string): Promise<{ readonly ok: true } | NativeFailure>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Apps: { getInstance(): AppsInstance }
}
