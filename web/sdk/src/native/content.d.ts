// `ContentStream` — seekable decrypted reads for streaming preview.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// ContentStream: the D2b pull streaming ops (drive-streaming-preview.md §3.5, S6a).
// The Service Worker (S6b) drives these to answer HTTP Range requests for a
// <video src>; readRange decrypts only the covering chunks in the in-process runtime.
interface NativeContentStreamHandle {
  // Opaque proxy-side session handle for readRange / close.
  readonly handle: string
  // Total plaintext size of the file, in bytes.
  readonly totalSize: number
}

type NativeContentStreamOpen =
  | ({ readonly ok: true } & NativeContentStreamHandle)
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

interface ContentStreamInstance {
  // Open a seekable session for a personal file. ⚠ Result-shaped, not nullable: the old `T | null`
  // could not distinguish a refusal from "no stream", and callers assumed the latter.
  openStream(path: string): Promise<NativeContentStreamOpen>
  // Read up to `length` bytes at `offset`; empty Int8Array at EOF, null on error.
  // ⚠ `bytes` is EMPTY at/after EOF (a normal answer). `ok: false` is a failed range read —
  // which is what a truncated download looks like, and used to be an untraceable null.
  readRange(handle: string, offset: number, length: number): Promise<NativeFileBytes>
  // Release the session (idempotent).
  close(handle: string): Promise<void>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const ContentStream: { getInstance(): ContentStreamInstance }
}
