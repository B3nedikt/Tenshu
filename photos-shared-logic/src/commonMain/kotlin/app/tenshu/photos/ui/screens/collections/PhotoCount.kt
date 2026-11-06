package app.tenshu.photos.ui.screens.collections

/**
 * "1 photo", "2 photos" — the one way a count of photographs reads, on every platform: the
 * Collections tiles and cards, album cards and headers (Compose and SwiftUI), the feed's bulk
 * report and the Trash copy. Swift calls it as `PhotoCountKt.photoCount(count:)`.
 *
 * ⚠ Every Collections subtitle once printed "${n} photos", so one favourite read "1 photos" on the
 * Favourites tile (seen in the iOS store captures, 2026-10-23), and a year or place of one photo the same.
 */
fun photoCount(count: Int): String = if (count == 1) "1 photo" else "$count photos"

/** "1 video", "2 videos" — the Videos tile's count, by the same rule as [photoCount]. */
fun videoCount(count: Int): String = if (count == 1) "1 video" else "$count videos"
