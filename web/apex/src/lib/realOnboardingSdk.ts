// The onboarding SDK the apex root provides — WITHOUT the Kotlin runtime in the first chunk.
//
// ⚠⚠ `OnboardingSdkProvider` sits above every route, the landing page included, so whatever it
// imports statically ships to every visitor. The Kotlin-backed adapter (`./realOnboardingSdkImpl.ts`)
// is 3.9 MB of runtime; until 2026-10-23 it was imported here and the public landing page
// downloaded all of it (web-app-suite.md F6). So every method below loads its implementation on
// first call, and the one thing a public page asks, `Session.peek`, never loads anything: it
// reads the runtime's localStorage hint (`./sessionHint.ts`).
//
// Every onboarding method already returns a Promise, so deferring the load changes no caller.
import type {
  PairingApi,
  RealmApi,
  RecoveryApi,
  SessionApi,
  SessionState,
} from '@tenshu/sdk/onboarding'
import type { OnboardingSdk } from './mockOnboarding'
import { RELAY_URL } from './relayUrl'
import { peekSession } from './sessionHint'

type Loader = () => Promise<OnboardingSdk>

/**
 * An `OnboardingSdk` whose methods call into `load()`'s result. `peek` is set only when the
 * underlying SDK has a runtime-free one: the real SDK does, the mock does not (callers then fall
 * back to `detect`).
 */
export function lazyOnboardingSdk(
  load: Loader,
  peek?: () => Promise<SessionState>,
): OnboardingSdk {
  let pending: Promise<OnboardingSdk> | null = null
  const sdk = () => (pending ??= load())

  const Session: SessionApi & { peek?: () => Promise<SessionState> } = {
    detect: () => sdk().then(s => s.Session.detect()),
    persist: token => sdk().then(s => s.Session.persist(token)),
    clear: options => sdk().then(s => s.Session.clear(options)),
    registerPasskey: registration => sdk().then(s => s.Session.registerPasskey(registration)),
    unlock: assertion => sdk().then(s => s.Session.unlock(assertion)),
    unlockWithPhrase: mnemonic => sdk().then(s => s.Session.unlockWithPhrase(mnemonic)),
    ...(peek ? { peek } : {}),
  }
  const Realm: RealmApi = {
    create: args => sdk().then(s => s.Realm.create(args)),
    ownerId: () => sdk().then(s => s.Realm.ownerId()),
  }
  const Pairing: PairingApi = {
    submit: args => sdk().then(s => s.Pairing.submit(args)),
    show: () => sdk().then(s => s.Pairing.show()),
    offer: deviceName => sdk().then(s => s.Pairing.offer(deviceName)),
  }
  const Recovery: RecoveryApi = {
    claim: args => sdk().then(s => s.Recovery.claim(args)),
    revealPhrase: () => sdk().then(s => s.Recovery.revealPhrase()),
  }
  return { Session, Realm, Pairing, Recovery }
}

export const RealOnboardingSdk: OnboardingSdk = lazyOnboardingSdk(
  () => import('./realOnboardingSdkImpl').then(m => m.RealOnboardingSdkImpl),
  peekSession,
)

export { RELAY_URL }
