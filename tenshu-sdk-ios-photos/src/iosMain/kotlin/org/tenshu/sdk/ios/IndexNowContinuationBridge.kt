package org.tenshu.sdk.ios

/**
 * Swift's `BGTaskScheduler` driver for *Index now* continued (`photos-ios/Sources/IndexNowContinuation.swift`,
 * `ios-on-device-inference.md` PR-7, iOS 26+). Installed once at launch through
 * [PhotosLocalRuntime.installIndexNowContinuation], only on iOS 26+ and only behind the build flag.
 *
 * ⚠⚠ Swift owns no decision: Kotlin says when to submit and when the task is complete. Swift
 * answers through [PhotosLocalRuntime.onIndexNowTaskAnswered], [PhotosLocalRuntime.onIndexNowTaskLaunched]
 * and [PhotosLocalRuntime.onIndexNowTaskExpired].
 */
interface IndexNowContinuationBridge {
    /**
     * Registers a launch handler for [identifier] (the **fully composed** one: a suffix that is new
     * each time, so no identifier is ever registered twice), then submits a
     * `BGContinuedProcessingTaskRequest` for it with `strategy = .fail`. ⚠ Must not block, and
     * must answer: a refusal through [PhotosLocalRuntime.onIndexNowTaskAnswered].
     */
    fun submit(identifier: String, title: String, subtitle: String)
}

/** One launched `BGContinuedProcessingTask`, as Swift hands it over. */
interface IndexNowContinuedTask {
    /** `task.progress`: [done] of [total] photos; [total] `0` while the library is walked. */
    fun progress(done: Long, total: Long)

    /** `setTaskCompleted(success:)`. ⚠ Called exactly once by Kotlin. */
    fun complete(success: Boolean)
}
