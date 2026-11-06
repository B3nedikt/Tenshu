package app.tenshu.contacts.model

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * The contact's photo as `NSData`, for the SwiftUI shell's `UIImage(data:)`.
 *
 * ⚠ It exists because [Contact.avatar] is a `ByteArray`, which Kotlin/Native exports to Swift as
 * `KotlinByteArray` — a type whose *entire* Swift surface is `size` and `get(index:)`. There is no
 * pointer to hand to `Data`, so the only Swift-side conversion available is a bridged ObjC call per
 * byte, run inside a SwiftUI `body` that re-evaluates on every list pass. Converting here is one
 * bridged call and one memcpy instead.
 *
 * ⚠ Deliberately an extension in `iosMain` rather than a change to [Contact] itself: the model is
 * shared with the Compose shell, which decodes through its own expect/actual and must not grow a
 * Foundation type to satisfy iOS.
 */
@OptIn(ExperimentalForeignApi::class)
fun Contact.avatarData(): NSData? {
    val bytes = avatar ?: return null
    // An empty array has no address to take — `allocArrayOf` on it is undefined, not merely empty.
    // A zero-byte photo is also not a photo, so the caller wants the monogram either way.
    if (bytes.isEmpty()) return null
    return memScoped { NSData.create(bytes = allocArrayOf(bytes), length = bytes.size.toULong()) }
}
