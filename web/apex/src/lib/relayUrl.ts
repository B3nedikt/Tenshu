// Relay origin resolution, side-effect-free. Mirrors the logic in
// `realOnboardingSdk.ts` but WITHOUT calling `TenshuSdk.configure(...)` — the
// Mode-A share viewer (`routes/share/[shareId].tsx`) is anonymous and must not
// drag in the onboarding/runtime chain just to learn the relay URL.
//
//   1. `VITE_TENSHU_RELAY_URL` env var (build-time inject for prod).
//   2. `http://localhost:8080` in dev (`:sync-relay-server`'s default bind).
//   3. `https://relay.tenshu.app` in production builds.
export const RELAY_URL: string =
  (import.meta.env.VITE_TENSHU_RELAY_URL as string | undefined) ??
  (import.meta.env.DEV ? 'http://localhost:8080' : 'https://relay.tenshu.app')
