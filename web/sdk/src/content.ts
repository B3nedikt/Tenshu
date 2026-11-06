// ContentStream entry point: the D2b pull streaming ops
// (`docs/proposals/drive-streaming-preview.md` §3.5, S6a). Backed by
// `ContentStream` on the Kotlin side (`:tenshu-sdk-js`) — the same
// `OnDemandResolution` streaming ops the native apps run, dispatched through
// the apex in-process runtime. The Service Worker (S6b) drives these to answer
// HTTP `Range` requests for a `<video src>`, so a large video previews without
// ever loading the whole file into memory (avoids the blob-URL OOM path).

import * as bundle from './_bundle'

// A handle to an open streaming session (`openStream(...)`). `totalSize` is the
// file's total plaintext length — the SW needs it for `Content-Length` /
// `Content-Range`.
export interface ContentStreamHandle {
  readonly handle: string
  readonly totalSize: number
}

// What `openStream` answers.
//
// ⚠⚠ A discriminated union, not `ContentStreamHandle | null`. The nullable form could not say
// WHY, so a refusal and "this file has no stream" arrived identically and every caller assumed
// the latter — which is how W7's streaming download fell back to a whole-file read on every
// file, silently, with green tests. See `tech-debt.md` (2026-09-01).
export type ContentStreamOpen =
  | ({ readonly ok: true } & ContentStreamHandle)
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

// Operation surface: Promise-returning, safe as an op call. `readRange` decrypts
// only the covering chunk(s) in the in-process runtime (no whole-file blob); it
// resolves an empty `Int8Array` at EOF and `null` on error.
export const ContentStream = bundle.ContentStream.getInstance()
