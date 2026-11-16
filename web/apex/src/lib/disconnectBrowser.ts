// "Disconnect this browser" — the one implementation behind both entry points (the lock screen's
// `LockedFlow` and the signed-in `DeviceMenu`).
//
// `Session.clear({ purgeRealm: true })` removes this browser from its realm (the shared
// `ForgetThisDevice` op: the device row's tombstone and the signed self-removal, waited on for up to
// 8 s), then wipes every SDK store. It then tells the passkey provider the passkey is dead.
//
// ⚠⚠ It does NOT reload. Callers show the outcome first when the removal is unconfirmed, then call
// `reloadAfterPurge` — the runtime still holds the identity the purge destroyed until the page loads
// again, so nothing but that reload may follow.
import type { SessionApi } from '@tenshu/sdk/onboarding'
import { signalPasskeyRemoved } from './webauthn'

export type DisconnectOutcome = 'confirmed' | 'unconfirmed'

export async function disconnectBrowser(
  session: Pick<SessionApi, 'clear'>,
): Promise<DisconnectOutcome> {
  const result = await session.clear({ purgeRealm: true })
  if (result.passkeyCredentialId !== null) await signalPasskeyRemoved(result.passkeyCredentialId)
  return result.removal === 'confirmed' ? 'confirmed' : 'unconfirmed'
}
