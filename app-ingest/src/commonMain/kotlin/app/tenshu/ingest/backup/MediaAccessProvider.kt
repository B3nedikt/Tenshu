package app.tenshu.ingest.backup

/**
 * How much of the device's media library this app can actually see
 * (`drive-synced-folders-new-folder-prompt.md` §5).
 *
 * Android 14 (API 34) split the old binary grant into three states by adding
 * `READ_MEDIA_VISUAL_USER_SELECTED` — "Select photos and videos". [PARTIAL] is the
 * state that breaks folder-shaped features: `MediaStore` still answers every query,
 * it just silently filters rows to the user's selection, so *nothing fails* — the
 * device simply appears to have fewer folders and fewer photos than it does.
 *
 * Platforms without the concept bind [FullMediaAccessProvider].
 */
enum class MediaAccess {
    /** Every bucket and item on the device is visible. The only state in which
     *  folder enumeration means what it says. */
    FULL,

    /**
     * Android 14+ "Selected photos". `MediaStore` returns **only the user's chosen
     * items**, so a bucket listing enumerates "folders containing something I
     * picked", not "folders on this device". Every folder-shaped feature has to
     * stand down here rather than act on a filtered view.
     */
    PARTIAL,

    /** No media grant. Enumeration returns nothing. */
    DENIED,
}

/**
 * Reads the current media grant. Deliberately a **live read, never cached**: the
 * user can change the grant from system settings while Drive is backgrounded, and
 * on Android a partial selection can expire on its own.
 */
fun interface MediaAccessProvider {
    fun current(): MediaAccess

    /**
     * Whether the app may read a photo's **location** (`timeline-photos.md` T-P4).
     *
     * ⚠⚠ Deliberately **not** folded into [MediaAccess]. It answers a different question — "may we
     * see where a photo was taken", not "how much of the library can we see" — and demoting a
     * fully-granted library to [MediaAccess.PARTIAL] over it would misreport what backup can do,
     * to a state that changes real behaviour (which buckets enumerate).
     *
     * ⚠ Its absence is invisible without asking: Android strips the GPS tags rather than failing,
     * so a photo backed up without this grant is indistinguishable from one that never carried a
     * coordinate. That is why it needs a signal of its own rather than riding the media state —
     * nothing else can notice it is missing.
     *
     * Defaults to `true` for platforms with no such concept (desktop, iOS) and for test doubles:
     * "no redaction to lift" and "grant held" are the same outcome for every caller.
     */
    fun photoLocationGranted(): Boolean = true
}

/** Platforms with no partial-access concept (desktop, iOS) and test defaults. */
object FullMediaAccessProvider : MediaAccessProvider {
    override fun current(): MediaAccess = MediaAccess.FULL
}
