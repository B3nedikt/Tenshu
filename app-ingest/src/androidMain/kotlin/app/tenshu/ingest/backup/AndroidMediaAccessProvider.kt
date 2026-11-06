package app.tenshu.ingest.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Reads Drive's media grant across the three eras of Android's media permissions
 * (`drive-synced-folders-new-folder-prompt.md` §5):
 *
 * | API | Permission | States |
 * |---|---|---|
 * | ≤ 32 | `READ_EXTERNAL_STORAGE` | granted / not |
 * | 33 | `READ_MEDIA_IMAGES` (+`VIDEO`) | granted / not |
 * | 34+ | + `READ_MEDIA_VISUAL_USER_SELECTED` | full / **partial** / not |
 *
 * ⚠ **Check order is load-bearing on API 34+.** Under *full* access
 * `READ_MEDIA_VISUAL_USER_SELECTED` is granted **as well**, so testing it first
 * would report [MediaAccess.PARTIAL] for a fully-granted app. `READ_MEDIA_IMAGES`
 * must be the discriminator, exactly as in Google's reference snippet.
 *
 * ⚠ **The manifest must declare `READ_MEDIA_VISUAL_USER_SELECTED`** for this to be
 * meaningful. Without it, Android 14+ runs a compatibility path where choosing
 * "Select photos" grants `READ_MEDIA_IMAGES` **for the app session only** — it
 * reads as full access, then evaporates when the process is backgrounded or
 * killed, which is precisely when the headless backup worker runs.
 *
 * [MediaAccess.FULL] keys off images alone. Video rides the same request and, in
 * practice, the same user decision; a hypothetical images-yes/video-no grant would
 * silently hide video buckets, which is the pre-existing behaviour and not made
 * worse here.
 */
class AndroidMediaAccessProvider(
    private val context: Context,
) : MediaAccessProvider {

    override fun current(): MediaAccess = when {
        Build.VERSION.SDK_INT >= 34 -> when {
            granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.FULL
            granted(READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
            else -> MediaAccess.DENIED
        }

        Build.VERSION.SDK_INT >= 33 ->
            if (granted(Manifest.permission.READ_MEDIA_IMAGES)) MediaAccess.FULL
            else MediaAccess.DENIED

        else ->
            @Suppress("DEPRECATION")
            if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL
            else MediaAccess.DENIED
    }

    /**
     * `ACCESS_MEDIA_LOCATION`, which is what stops `MediaStore` stripping a photo's GPS tags.
     *
     * ⚠ Below API 29 there is no redaction to lift, so the answer is `true` — the caller's question
     * is "can we read a coordinate", and there it can.
     */
    override fun photoLocationGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** `Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED` exists only in the
         *  API-34 SDK constant table; spelled out so this compiles against any
         *  compileSdk and is safe to evaluate on older devices. */
        const val READ_MEDIA_VISUAL_USER_SELECTED =
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

        /**
         * The permissions Drive asks for to back up the gallery, correct for the
         * running OS. On 34+ the visual-user-selected permission is requested
         * **alongside** the read permissions in one call — requesting them
         * separately stacks two system dialogs.
         */
        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                READ_MEDIA_VISUAL_USER_SELECTED,
            ) + mediaLocation()

            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) + mediaLocation()

            else -> @Suppress("DEPRECATION") arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) +
                mediaLocation()
        }

        /**
         * `ACCESS_MEDIA_LOCATION`, asked for in the **same** dialog as the read permissions
         * (`timeline-photos.md` T-P4).
         *
         * ⚠⚠ Without it every image `MediaStore` hands out has its GPS tags stripped, so a backed-up
         * photo arrives in the realm with no record of where it was taken — and nothing downstream
         * can tell that apart from a photo that never had a coordinate. It is requested here rather
         * than at its own moment because it is meaningless alone: it only lifts a redaction on
         * images the read permissions already allow.
         *
         * ⚠ Denying it costs the coordinate and nothing else — the backup still runs, the photo
         * still uploads, and Timeline still places it by time. Which is why it is bundled rather
         * than gated: there is no state in which Drive should refuse to back a photo up because it
         * could not learn where the photo was taken.
         *
         * Empty below API 29, where there is no redaction to lift.
         */
        private fun mediaLocation(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else {
                emptyArray()
            }
    }
}
