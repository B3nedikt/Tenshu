package org.tenshu.sdk.ios

/**
 * Swift's `BGTaskScheduler` driver for the overnight index wake (`photos-ios/Sources/ProcessingWake.swift`,
 * `ios-on-device-inference.md` PR-6, §16). Installed once at launch through
 * [PhotosLocalRuntime.installProcessingWake], only behind the build flag and the static gates.
 *
 * ⚠⚠ Swift owns no decision: Kotlin says when to submit, when to withdraw, and when the task is
 * complete. Swift answers through [PhotosLocalRuntime.runProcessingWake] and
 * [PhotosLocalRuntime.onProcessingWakeExpired].
 */
interface ProcessingWakeBridge {
    /**
     * Submits (or replaces) a `BGProcessingTaskRequest` for [identifier] with exactly these
     * requirements. ⚠ Must not block; a refusal is logged and nothing else (the next leave or wake
     * submits again).
     */
    fun submit(identifier: String, requiresExternalPower: Boolean, requiresNetworkConnectivity: Boolean)

    /** `BGTaskScheduler.cancel(taskRequestWithIdentifier:)`. ⚠ Must not block. */
    fun withdraw(identifier: String)
}

/** One launched `BGProcessingTask`, as Swift hands it over. */
interface ProcessingWakeTask {
    /** `setTaskCompleted(success:)`. ⚠ Called exactly once by Kotlin. */
    fun complete(success: Boolean)
}
