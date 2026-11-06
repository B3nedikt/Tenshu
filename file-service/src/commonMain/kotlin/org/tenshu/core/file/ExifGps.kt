package org.tenshu.core.file

/**
 * Where a photograph was taken, as EXIF records it (`timeline-photos.md` T-P4).
 *
 * E7 degrees — the same encoding `Place` and every coordinate in this codebase use, so a photo's
 * position needs no conversion to sit beside a visit's.
 */
data class ExifLocation(val latE7: Long, val lngE7: Long)

/**
 * Read the GPS coordinate out of an image's metadata, or `null` when there is none.
 *
 * **Hand-rolled, and deliberately so.** The alternative is a platform API (Android's
 * `ExifInterface`) or a JVM library, and neither can run where this needs to: the parse happens in
 * `commonMain` so the same answer comes back on every platform, from the same bytes, with no
 * dependency added to a service module.
 *
 * ⚠⚠ **On Android 10+ these bytes are usually redacted before you get here.** `MediaStore` strips
 * the GPS tags from every image it hands out unless the *opening process* holds
 * `ACCESS_MEDIA_LOCATION` **and** asks for the original via `MediaStore.setRequireOriginal(uri)`.
 * A parser wired up without that finds nothing, on every photo, silently — which is why the
 * coordinate is read client-side in Drive (where the permission and the picker live) and passed to
 * the upload op, exactly as `sourceModifiedAt` already is and for the same reason.
 *
 * ⚠ No longer JPEG-only. The narrow reading this used to do — and the KDoc's claim that HEIC cost
 * nothing because "iOS has no gallery backup" — went stale the day `IosMediaSourceProvider` shipped
 * (`ios-drive-parity.md` P6b), and every iPhone photo in the realm was landing with no coordinate.
 * [readMediaMetadata] now covers HEIC/HEIF as well; this function is the one-field view of it that
 * the upload op and Takeout import already call.
 *
 * ⚠ Never throws. A truncated, malformed or simply GPS-less file is `null`: this runs on whatever
 * the user uploaded, and a parse failure must not fail an upload.
 */
fun readExifLocation(bytes: ByteArray): ExifLocation? = readMediaMetadata(bytes).location
