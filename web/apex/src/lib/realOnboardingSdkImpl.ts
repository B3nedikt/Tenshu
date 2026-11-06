// Real onboarding SDK adapter — the Kotlin-backed half. ⚠ Reached ONLY through the dynamic import in
// `./realOnboardingSdk.ts` (web-app-suite.md F6): a static import of this file puts the whole Kotlin
// runtime back into the apex's first chunk, landing page included.
//
// Imports the bridged singletons from
// `@tenshu/sdk/onboarding` (which themselves delegate to the Kotlin
// `@JsExport` objects in `:tenshu-sdk-js`) and exposes them through the
// same `OnboardingSdk` shape the apex flows consume via
// `useOnboardingSdk()`.
//
// ⚠⚠ No side effect any more. Importing this module used to call
// `TenshuSdk.configure(...)`, which starts the runtime and so takes the
// one-per-origin `tenshu-runtime` lock — on EVERY apex page, landing
// included. The runtime now starts lazily (`./runtime.ts` `ensureRuntime`,
// called by `RequiresRuntime` on app routes); a public page only ever reads
// `Session.peek`, the runtime-free hint (`./sessionHint.ts`).
//
// Relay URL resolution:
//   1. `VITE_TENSHU_RELAY_URL` env var (build-time inject for prod
//      deployments; `vite dev` reads `.env*` files in `web/apex/`).
//   2. Falls back to `http://localhost:8080` in dev mode (matches
//      `:sync-relay-server`'s default `./gradlew :sync-relay-server:run`
//      bind).
//   3. Falls back to `https://relay.tenshu.app` in production builds.

import { Pairing, Realm, Recovery, Session } from '@tenshu/sdk/onboarding'
import type { OnboardingSdk } from './mockOnboarding'
import { RELAY_URL } from './relayUrl'
import { peekSession } from './sessionHint'

export const RealOnboardingSdkImpl: OnboardingSdk = {
  Session: { ...Session, peek: peekSession },
  Realm,
  Pairing,
  Recovery,
}

export { RELAY_URL }
