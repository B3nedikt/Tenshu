// The stable message every SDK call rejects with in a tab that does not hold the runtime.
// ⚠ Kept here as well as in `@tenshu/sdk/core` so a public page can match on it without
// importing the Kotlin bundle; `RuntimeElsewhereException` (`RuntimeLease.kt`) is the source.
export const RUNTIME_ELSEWHERE = 'tenshu/runtime-elsewhere'
