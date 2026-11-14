// The web registry's app ids (`photos`) ↔ the native catalogue's (`app.tenshu.photos`, the
// `*_APP_ID` constants in `:control-shared-logic`'s `BundledAppManifestSource`). `AppRegistration`
// rows carry the native id; everything in the apex keys by the web one.
//
// ⚠ A derivation, not a table: every first-party app follows it. `appIds.test.ts` pins the nine, so a
// native id that stops following it fails there rather than reading as "used on no device".
const NATIVE_PREFIX = 'app.tenshu.'

export function nativeAppId(webAppId: string): string {
  return NATIVE_PREFIX + webAppId
}

/** The web id for a native id, or `null` when it is not a first-party id (a third-party app). */
export function webAppIdOf(nativeId: string): string | null {
  if (!nativeId.startsWith(NATIVE_PREFIX)) return null
  const id = nativeId.slice(NATIVE_PREFIX.length)
  return id.length > 0 ? id : null
}
