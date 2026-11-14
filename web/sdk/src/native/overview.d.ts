// `Overview` — the apex overview's synced settings (docs/proposals/web-apex-home.md §3.5).
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// The stored list of shown apps, in order. `[]` = never customised (the apex shows its default).
type NativeShownAppsResult = { readonly ok: true; readonly appIds: readonly string[] } | NativeFailure

interface OverviewInstance {
  shownApps(): Promise<NativeShownAppsResult>
  // Seeds with a one-shot read, then re-emits on every change. `onError` fires once if the stream
  // ends for any reason but the returned unsubscribe.
  observeShownApps(callback: (r: NativeShownAppsResult) => void, onError: (message: string) => void): () => void
  // Replaces the whole list (last write wins).
  setShownApps(appIds: readonly string[]): Promise<{ readonly ok: true } | NativeFailure>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Overview: { getInstance(): OverviewInstance }
}
