package app.tenshu.control.di

/**
 * Photos' continued *Index now* task on iOS 26+ (`ios-on-device-inference.md` PR-7): the
 * identifiers a `BGContinuedProcessingTaskRequest` is submitted under.
 *
 * ⚠⚠ The base must begin with Photos' **bundle id**, `app.tenshu.photos.ios`: Apple's header for
 * `BGContinuedProcessingTaskRequest` says the prefix "must at least contain the bundle ID of the
 * submitting application" and end in `.*`. PR-7's row wrote `app.tenshu.photos.indexnow.*`, which
 * does not; this is the deviation (§15 of the plan).
 *
 * ⚠ The wildcard is only the plist entry (`photos-ios/project.yml`, since `Info.plist` is
 * generated): each request registers and submits [PREFIX] + a fresh suffix.
 * `PhotosIndexNowTaskDeclaredTest` keeps the two in step.
 */
object PhotosIndexNowTask {
    /** What `BGTaskSchedulerPermittedIdentifiers` declares. */
    const val PERMITTED: String = "app.tenshu.photos.ios.indexnow.*"

    /** What every submitted identifier begins with: [PERMITTED] without its `*`. */
    const val PREFIX: String = "app.tenshu.photos.ios.indexnow."

    /** The system's live activity, before the task begins. ⚠ Copy placeholders (M11). */
    const val TITLE: String = "Indexing your photos"
    const val SUBTITLE: String = "So you can search them by what's in them"
}
