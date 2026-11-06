// Local helpers for the MB-1 contact handshake UI (drive-web-mode-b-
// sharing.md). The handshake ops live on the apex (lib/proxy); these are the
// browser-local bits the React layer owns per House Rule #5.

import type { CompleteResult } from '@tenshu/sdk/contact'

// Display form of a full-hex identity fingerprint: the leading 15 hex chars as
// `XXXXX-YYYYY-ZZZZZ` (uppercased). Mirrors the native `displayFingerprint`
// (drive-shared-logic) verbatim — the convention pinned in
// drive-manage-access-ux.md §2.3, so a web↔native pair reads the same string.
export function displayFingerprint(fingerprintHex: string): string {
  const head = fingerprintHex.slice(0, 15).toUpperCase()
  const groups: string[] = []
  for (let i = 0; i < head.length; i += 5) groups.push(head.slice(i, i + 5))
  return groups.join('-')
}

// The §5.3 SAS as two groups of three for easier comparison (mirrors the native
// `sas.chunked(3).joinToString(" ")`). A non-6-digit value passes through.
export function formatSas(sas: string): string {
  if (sas.length !== 6) return sas
  return `${sas.slice(0, 3)} ${sas.slice(3)}`
}

// The display name the user sends inside handshake payloads. Drive on the web
// has no profile surface (and the apex preferenceService isn't wired into the
// JS runtime), so — like the native repo's `ownDisplayName` preference — we
// keep it browser-local. The People view prompts for it on first use.
const OWN_NAME_KEY = 'tenshu.drive.contactDisplayName'

export function readOwnDisplayName(): string {
  try {
    return localStorage.getItem(OWN_NAME_KEY) ?? ''
  } catch {
    return ''
  }
}

export function writeOwnDisplayName(name: string): void {
  try {
    localStorage.setItem(OWN_NAME_KEY, name.trim())
  } catch {
    // Storage blocked (private mode / disabled) — the name simply won't persist
    // across reloads; the in-session value still flows through the handshake.
  }
}

// What the completion prompt says when `completeHandshake` did not complete — by typed outcome,
// never by the proxy's English (`core-error-copy-catalogue.md` §5.0). The two refusal sentences
// match the Compose and SwiftUI catalogues (`error_invite_response_changed`,
// `error_invite_code_not_shown`); for 'responseChanged' the remedy IS the message — a retry
// refuses the same way, so "try again" would be wrong.
export function completionFailureCopy(
  result: CompleteResult,
  action: 'accept' | 'decline',
): string {
  switch (result.outcome) {
    case 'responseChanged':
      return 'The reply to this invite changed after it was shown, so nothing was saved. Decline this invite and send a new one.'
    case 'codeNotShown':
      return 'There is no code to confirm for this invite yet. Try again in a moment.'
    default:
      return action === 'accept'
        ? "Couldn't complete the connection. Try again."
        : "Couldn't decline. Try again."
  }
}
