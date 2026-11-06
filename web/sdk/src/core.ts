// Core entry point: SDK identity + cryptographic helpers that don't
// belong to a feature service. Pure value-typed wrappers.

import * as bundle from './_bundle'

export const TenshuSdk = bundle.TenshuSdk.getInstance()
export const Bip39 = bundle.Bip39.getInstance()

// One runtime per origin (docs/web/architecture.md "Cross-tab"). The error message every SDK call
// rejects with in a tab that does not hold the runtime — match on it, it is a stable id.
export const RUNTIME_ELSEWHERE = 'tenshu/runtime-elsewhere'

export type RuntimeLeaseState = Awaited<ReturnType<typeof TenshuSdk.awaitRuntimeLease>>

// A tab that does not run the runtime and should offer "Use here".
export function isRuntimeElsewhere(state: RuntimeLeaseState | undefined | null): boolean {
  return state === 'elsewhere' || state === 'displaced'
}
