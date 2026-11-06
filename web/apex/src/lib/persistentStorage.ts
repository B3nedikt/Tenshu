// Persistent-storage durability for this origin (`drive-web-parity.md` W10a).
//
// ⚠⚠ Why this matters more here than the API's name suggests. A web device's realm identity —
// its signing keypair and master-key material — lives in per-origin IndexedDB. Without a
// persistence grant that storage is **evictable**: the browser may delete it under pressure, with
// no prompt, taking the device's identity with it. Android and iOS keep the equivalent in
// KeyStore/Keychain, which the OS does not evict. There is no native twin to this failure.
//
// ⚠ Requested once per browser, not once per load. Chrome auto-grants on its own heuristics
// (installed, engagement) without prompting; Firefox PROMPTS. Re-asking on every load would turn
// a one-time decision into a recurring interruption, so a denial is remembered and not re-asked.
// The *state* is still read fresh every time — a user who later installs the app should see the
// truth, not our cached memory of a refusal.

const ASKED_KEY = 'tenshu-storage-persist-asked'

export type PersistenceState =
  // The browser has committed to keeping this origin's data.
  | 'persisted'
  // Storage works, but the browser may clear it — the identity is at risk.
  | 'evictable'
  // No StorageManager (or it threw). We do not know, so we say nothing.
  | 'unknown'

export async function readPersistence(): Promise<PersistenceState> {
  try {
    if (!navigator.storage?.persisted) return 'unknown'
    return (await navigator.storage.persisted()) ? 'persisted' : 'evictable'
  } catch {
    return 'unknown'
  }
}

/**
 * Ask for persistence if we have not already asked on this browser. Returns the state afterwards.
 * Safe to call on every signed-in mount — it short-circuits once granted or once refused.
 */
export async function ensurePersistence(): Promise<PersistenceState> {
  const current = await readPersistence()
  if (current !== 'evictable') return current

  try {
    if (window.localStorage.getItem(ASKED_KEY) === '1') return 'evictable'
  } catch {
    // Storage disabled entirely; asking again is the lesser harm.
  }

  try {
    if (!navigator.storage?.persist) return 'unknown'
    const granted = await navigator.storage.persist()
    try {
      window.localStorage.setItem(ASKED_KEY, '1')
    } catch {
      // Not being able to remember that we asked is not worth failing over.
    }
    return granted ? 'persisted' : 'evictable'
  } catch {
    return 'unknown'
  }
}
