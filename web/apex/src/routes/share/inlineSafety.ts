/**
 * What the Mode-A viewer may render, and what type its blob may carry.
 *
 * ⚠⚠ The MIME type is the SHARER's claim — sealed under `K_share` since F3, but still chosen by
 * whoever made the link, so for a recipient it is attacker-controlled. A `blob:` URL inherits the
 * apex's origin, so a blob typed `text/html` (or SVG, XHTML, XML, …) that is framed or navigated to
 * runs script AS the apex: its storage, its session, its parent window. Hence two rules:
 *
 * - **An allowlist, never a denylist.** Only the types below render inline; anything the list does
 *   not name is a download. A new dangerous type cannot slip through by being unlisted.
 * - **The blob's own type is defused too**, not just the preview: the download link can still be
 *   opened in a new tab, which navigates to the blob. A non-allowlisted file's blob is therefore
 *   `application/octet-stream`, which a browser downloads rather than renders.
 */

export type PreviewKind = 'image' | 'pdf' | 'text' | 'none'

export const OPAQUE_BLOB_TYPE = 'application/octet-stream'

// Raster formats only: an `<img>` cannot run SVG script, but the same blob opened in a tab can.
const RASTER_IMAGES: ReadonlySet<string> = new Set([
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'image/avif',
  'image/bmp',
])

// Types whose blob a browser renders without ever executing script, so the blob may keep them.
const BLOB_SAFE: ReadonlySet<string> = new Set([...RASTER_IMAGES, 'application/pdf', 'text/plain'])

/** The bare media type: lower-cased, parameters and whitespace dropped (`Text/HTML; x=y` → `text/html`). */
export function essence(mimeType: string): string {
  return (mimeType.split(';')[0] ?? '').trim().toLowerCase()
}

/** How the viewer may show a file of this claimed type. */
export function previewKind(mimeType: string): PreviewKind {
  const type = essence(mimeType)
  if (RASTER_IMAGES.has(type)) return 'image'
  if (type === 'application/pdf') return 'pdf'
  // Shown as TEXT (React-escaped in a <pre>), never parsed: `text/html` here is its source code.
  if (type.startsWith('text/') || type === 'application/json') return 'text'
  return 'none'
}

/** The type the decrypted bytes' blob is created with. */
export function blobType(mimeType: string): string {
  const type = essence(mimeType)
  return BLOB_SAFE.has(type) ? type : OPAQUE_BLOB_TYPE
}
