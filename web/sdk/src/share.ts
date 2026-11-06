// Share entry point: the Mode-A recipient surface
// (`docs/proposals/drive-file-sharing.md` §3.4 / §6.5). Backed by
// `ShareViewer` on the Kotlin side (`:tenshu-sdk-js`).
//
// Anonymous by design — a recipient opens a link without any Tenshu
// session, so `ShareViewer.fetch` constructs its own HTTP client + crypto
// and hits the relay's anonymous `GET /share/...` endpoints. `K_share`
// comes from the URL fragment and is passed in; it never goes on the wire.

import * as bundle from './_bundle'

export type ShareFetchErrorKind = 'gone' | 'network' | 'decrypt' | 'meta'

export interface ShareFetchResult {
  // True iff the file was fetched + decrypted. On `false`, branch on
  // `errorKind` ('gone' = revoked/expired/absent, 'network' = transport,
  // 'decrypt' = AEAD/AAD failure, wrong key included, 'meta' = the descriptor
  // carries no readable sealed file details) — no exception is thrown for these.
  // On `true`, `fileName` / `mimeType` come from the sealed metadata (F3), so
  // they are set; the relay never had them in the clear.
  readonly ok: boolean
  readonly errorKind: ShareFetchErrorKind | null
  readonly bytes: Int8Array | null
  readonly fileName: string | null
  readonly mimeType: string | null
}

// Operation: Promise-returning. Suspend lives on the Kotlin side; JS sees a
// vanilla `Promise<ShareFetchResult>` safe to use as a TanStack Query `queryFn`.
export const ShareViewer = bundle.ShareViewer.getInstance()

// Authenticated management surface (control-ia.md §5.3) — the realm owner's
// view over their own outbound link shares. In-realm; requires a session.
// Distinct from the anonymous `ShareViewer` recipient surface above.
export interface ShareListItem {
  readonly shareId: string
  readonly fileName: string
  readonly filePath: string
  readonly createdAtMillis: number
  // null = no expiry / no cap.
  readonly expiresAtMillis: number | null
  readonly maxOpens: number | null
  readonly revoked: boolean
}

// `Shares.create(...)` result — the relay id + the per-share key fragment.
// Assemble the URL as `https://tenshu.app/s/{shareId}#k={urlFragment}`; the
// fragment is the read capability (A11) and never goes on the wire.
export interface ShareLink {
  readonly shareId: string
  readonly urlFragment: string
}

export const Shares = bundle.Shares.getInstance()
