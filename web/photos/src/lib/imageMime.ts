// Copied from Drive (`web/drive/src/lib/imageMime.ts`): an app may not import another app.
// What kind of image a blob of bytes actually is (`drive-xxlarge-thumbnail-rung.md` §4.8).
//
// ⚠⚠ The type cannot be inferred from the file's NAME, because stored thumbnail classes are not
// the file. A `photo.png` has a JPEG `list` class; a browser-minted fallback is WebP. Labelling a
// Blob with the wrong type happens to work — browsers sniff — right up until something does not,
// and it makes every such URL unusable for a download.

const JPEG = [0xff, 0xd8, 0xff]
const PNG = [0x89, 0x50, 0x4e, 0x47]
const GIF = [0x47, 0x49, 0x46, 0x38]

function startsWith(bytes: Uint8Array, signature: number[]): boolean {
  if (bytes.length < signature.length) return false
  return signature.every((b, i) => bytes[i] === b)
}

/**
 * Sniffs the image type from its magic bytes, falling back to [fallback] when nothing matches.
 *
 * ⚠ Covers the four the ladder can produce: JPEG (every stored rung since 2026-08-31), PNG and
 * WebP (browser-minted fallbacks), and GIF. Anything else keeps the caller's guess rather than
 * asserting a wrong one.
 */
export function sniffImageMime(bytes: Uint8Array, fallback = 'application/octet-stream'): string {
  if (startsWith(bytes, JPEG)) return 'image/jpeg'
  if (startsWith(bytes, PNG)) return 'image/png'
  if (startsWith(bytes, GIF)) return 'image/gif'
  // RIFF....WEBP — the size field sits between the two markers, so check both ends.
  if (
    bytes.length >= 12 &&
    startsWith(bytes, [0x52, 0x49, 0x46, 0x46]) &&
    bytes[8] === 0x57 &&
    bytes[9] === 0x45 &&
    bytes[10] === 0x42 &&
    bytes[11] === 0x50
  ) {
    return 'image/webp'
  }
  return fallback
}
