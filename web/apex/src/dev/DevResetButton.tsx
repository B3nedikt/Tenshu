// Dev-only "Reset local state" button — an on-device-testing aid for the
// web onboarding/pairing flows. Wipes the real SDK's IndexedDB stores plus
// session/local storage and reloads to `/`, so re-running first-time
// onboarding, signup, or pairing during local testing is one click instead
// of digging through DevTools → Application → Clear site data.
//
// Distinct from `MockDevtools` (which resets the *mock* SDK's state): this
// targets the **real** SDK's persistence, the thing that makes
// `Session.detect()` route a half-onboarded browser to `/lock` instead of
// the chooser. Gated on `import.meta.env.DEV`, so it's tree-shaken out of
// production bundles entirely.
import { useState } from 'react'

// Mirrors the IDB database names in
// `tenshu-sdk-js/.../internal/JsRuntime.kt` (FILE/SESSION/SECURE/MASTER_KEY).
// Used as a fallback when `indexedDB.databases()` isn't available
// (Firefox/Safari); on Chromium we enumerate so any future store is caught.
const KNOWN_DBS = [
  'tenshu-sdk-files',
  'tenshu-sdk-session',
  'tenshu-sdk-secure',
  'tenshu-sdk-master-keys',
]

async function listTenshuDatabases(): Promise<readonly string[]> {
  const idb = globalThis.indexedDB as IDBFactory & {
    databases?: () => Promise<{ readonly name?: string }[]>
  }
  if (typeof idb?.databases === 'function') {
    try {
      const dbs = await idb.databases()
      const names = dbs
        .map(d => d.name)
        .filter((n): n is string => typeof n === 'string' && n.startsWith('tenshu-'))
      if (names.length > 0) return names
    } catch {
      // Fall through to the known list (enumerate unsupported / blocked).
    }
  }
  return KNOWN_DBS
}

function deleteDatabase(name: string): Promise<void> {
  return new Promise(resolve => {
    const req = indexedDB.deleteDatabase(name)
    // Resolve on every terminal outcome — a still-open connection
    // (`onblocked`) is cleared by the reload that follows anyway.
    req.onsuccess = () => resolve()
    req.onerror = () => resolve()
    req.onblocked = () => resolve()
  })
}

export function DevResetButton() {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)

  if (!import.meta.env.DEV) return null

  async function wipe() {
    setBusy(true)
    try {
      window.sessionStorage.clear()
      // ⚠ Includes `tenshu.session.hint` (the landing's runtime-free session hint) — a wipe that
      // left it would greet a reset browser as signed in. Cleared wholesale on purpose.
      window.localStorage.clear()
    } catch {
      // Storage access can throw in sandboxed contexts — best effort.
    }
    const names = await listTenshuDatabases()
    await Promise.all(names.map(deleteDatabase))
    window.location.href = '/'
  }

  return (
    <div className="fixed bottom-3 left-3 z-50">
      {confirming ? (
        <div className="flex items-center gap-2 rounded-lg border border-danger/30 bg-surface/95 p-2 text-xs shadow-lg backdrop-blur">
          <span className="text-text-2">Wipe local realm + reload?</span>
          <button
            type="button"
            disabled={busy}
            onClick={() => void wipe()}
            className="rounded bg-danger px-2 py-1 font-medium text-white hover:bg-danger/90 disabled:opacity-60"
          >
            {busy ? 'Wiping…' : 'Wipe'}
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => setConfirming(false)}
            className="rounded px-2 py-1 text-text-2 hover:text-text"
          >
            Cancel
          </button>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          title="Dev only: wipe the SDK's IndexedDB + web storage and reload"
          className="rounded-lg border border-line bg-surface/90 px-2.5 py-1.5 text-xs font-medium text-text-2 shadow-lg backdrop-blur hover:text-text"
        >
          ⟲ Reset local state
        </button>
      )}
    </div>
  )
}
