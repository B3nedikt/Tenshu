package app.tenshu.control.di

/**
 * Photos' overnight index wake on iOS (`ios-on-device-inference.md` PR-6, §16): the
 * `BGProcessingTaskRequest` it submits.
 *
 * ⚠ A processing task's identifier is fixed (unlike PR-7's per-press suffix) and must be listed in
 * `BGTaskSchedulerPermittedIdentifiers` (`photos-ios/project.yml`, since `Info.plist` is
 * generated), with `processing` in `UIBackgroundModes`. `PhotosProcessingWakeTaskDeclaredTest` keeps
 * the three in step.
 */
object PhotosProcessingWakeTask {
    /** What is registered at launch, submitted and permitted. */
    const val IDENTIFIER: String = "app.tenshu.photos.processing"

    /** ⚠⚠ D5: a scheduled pass never runs on battery. */
    const val REQUIRES_EXTERNAL_POWER: Boolean = true

    /** The pass reads the library already on the phone: no network is asked for. */
    const val REQUIRES_NETWORK_CONNECTIVITY: Boolean = false
}
