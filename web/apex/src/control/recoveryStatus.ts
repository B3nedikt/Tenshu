// Apex-owned IDB store for the "phrase verified at" timestamp written by
// the Settings → Recovery → Test recovery flow (per
// docs/proposals/control-ia.md §7.2 + docs/proposals/web-control-plane.md
// §4.6). Phase A only reads this — the write path lands with Phase B's
// Recovery section. The store is intentionally separate from the SDK's
// `tenshu-sdk-master-keys` IDB so the apex never co-mingles UI state
// with Kotlin/JS-owned state.
//
// Banner staleness is 6 months per `control-ia.md` §3 / §7.2. Missing
// mark and stale mark both fall under the same `unverified` status —
// the user-facing copy distinguishes the two via the `kind` discriminant.

const DB_NAME = 'tenshu-apex-control'
const DB_VERSION = 1
const STORE = 'recovery-status'
const KEY = 'verifiedAtMs'
const STALE_AFTER_MS = 6 * 30 * 24 * 60 * 60 * 1000

export type RecoveryStatus =
  | { readonly kind: 'verified'; readonly verifiedAtMs: number }
  | { readonly kind: 'stale'; readonly verifiedAtMs: number }
  | { readonly kind: 'never' }

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE)
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

export async function getRecoveryStatus(now: number = Date.now()): Promise<RecoveryStatus> {
  if (typeof indexedDB === 'undefined') return { kind: 'never' }
  let db: IDBDatabase
  try {
    db = await openDb()
  } catch {
    return { kind: 'never' }
  }
  return new Promise((resolve) => {
    const tx = db.transaction(STORE, 'readonly')
    const req = tx.objectStore(STORE).get(KEY)
    req.onsuccess = () => {
      const value = req.result
      db.close()
      if (typeof value !== 'number') {
        resolve({ kind: 'never' })
        return
      }
      const age = now - value
      if (age > STALE_AFTER_MS) {
        resolve({ kind: 'stale', verifiedAtMs: value })
      } else {
        resolve({ kind: 'verified', verifiedAtMs: value })
      }
    }
    req.onerror = () => {
      db.close()
      resolve({ kind: 'never' })
    }
  })
}

// Exposed for tests + the eventual Phase B Test-recovery flow that owns
// the write path. Apex's Phase A code paths only call the read.
export async function setRecoveryVerified(verifiedAtMs: number): Promise<void> {
  const db = await openDb()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite')
    tx.objectStore(STORE).put(verifiedAtMs, KEY)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
  db.close()
}
