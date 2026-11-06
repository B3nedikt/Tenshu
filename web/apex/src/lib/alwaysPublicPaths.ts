// The SPA routes the pre-launch curtain (components/ConstructionGate.tsx) never covers.
//
// ⚠⚠ Production builds DO set the curtain code (docs/how-to/move-apex-to-scaleway.md), so a route
// that must be reachable by a stranger — a store reviewer, a user who only has the Android app —
// has to be listed here, or it sits behind the curtain on tenshu.app while working fine in dev.
// Google Play requires the account-deletion page to be reachable without the app before Control
// can be reviewed. (The static support pages and the imprint are files, not SPA routes: Caddy
// serves them before the SPA ever loads, so they never meet the curtain at all.)
import { ACCOUNT_DELETION_HREF } from '../landing/marketingNav'

const ALWAYS_PUBLIC: readonly string[] = [ACCOUNT_DELETION_HREF]

/** Whether [pathname] is an SPA route the construction curtain must never hide. */
export function isAlwaysPublicPath(pathname: string): boolean {
  // Caddy serves `/account/delete/` as well (try_files falls through to the SPA), so the trailing
  // slash must not put the curtain back.
  // ⚠ Lower-cased: React Router matches paths case-insensitively, so `/Account/Delete` renders the
  // page — and must not render it behind the curtain.
  const lower = pathname.toLowerCase()
  const normalised = lower.length > 1 ? lower.replace(/\/+$/, '') : lower
  return ALWAYS_PUBLIC.includes(normalised)
}
