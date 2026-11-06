// A realm purge is only half-done when this function returns. `IndexedDbSecureStorageDelegate.clearAll`
// states the other half on itself:
//
//   "Caller must reload the page after purge — `SyncManagerImpl` captures `currentDeviceId` at
//    construction, so the live instance still holds the old ID until next reload."
//
// ⚠⚠ Both purge call sites used `navigate('/', { replace: true })`, which is an SPA transition and
// NOT a load: the `JsRuntime` singleton survives it holding the old device id, owner id and signing
// key. Observed 2026-08-28 — a browser that had been "disconnected" kept minting a JWT for its
// removed device (`POST /sync/log [jwt] → 401 Invalid token`, where an identity-less device sends
// `[anon]`), so it could never re-pair: the relay refuses a removed identity on both routes.
//
// `location.assign` tears the runtime down, which is the documented precondition.
export function reloadAfterPurge(path: string): void {
  window.location.assign(path)
}
