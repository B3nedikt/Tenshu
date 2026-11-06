package app.tenshu.contacts.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode contact-photo bytes into a Compose `ImageBitmap`; `null` for anything that will not
 * decode, which the caller renders as the monogram.
 *
 * The UI-side decode, for the same reason Drive has one: `:contacts-shared-logic` is
 * Compose-free (Ph0) so the view models stay platform-neutral and iOS-capable, which means
 * they carry raw bytes and the screens decode. SwiftUI would use `UIImage(data:)`.
 *
 * ⚠ **This is the second copy of this seam in the repo** — `:drive-shared-ui` has one too.
 * Not shared, and the reason is that Drive's is genuinely a different function: it carries
 * the macOS ImageIO bridge for HEIC (`:macos-helpers`) and a two-pass downsample against
 * Android's ~100 MB canvas ceiling, both of which exist for full-resolution photo *viewing*.
 * A contact avatar is at most 256 KiB and is drawn at 40–96 dp. Graduating one of them into
 * `:ui-core` is a real follow-up, but it would drag `:macos-helpers` in with it, and per
 * §9.6 that is a `:ui-core` decision rather than something for Contacts to settle in passing.
 */
expect fun decodeAvatar(data: ByteArray): ImageBitmap?
