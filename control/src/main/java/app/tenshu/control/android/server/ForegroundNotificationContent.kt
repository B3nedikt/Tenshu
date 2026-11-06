package app.tenshu.control.android.server

/**
 * What the foreground-service notification should say (`drive-upload-progress.md` P5).
 *
 * Android-free, and deliberately so — same reason [ForegroundPromotion] and `ForegroundTaskLiveness`
 * are: the interesting part is the *choice* between the two messages, and a `Service` is not
 * unit-testable on the JVM. `ProxyService` only renders what this decides.
 */
internal sealed interface ForegroundNotificationContent {

    /**
     * A file-upload pass is running: *"Uploading 3 of 12"*. Outranks [Tasks] because it is the one
     * the user can connect to something they did — "2 background tasks" is true but tells them
     * nothing they can act on or verify.
     */
    data class Uploading(val current: Int, val total: Int) : ForegroundNotificationContent

    /**
     * A media index pass is running: *"Indexing photos, 300 of 9 000"* (`inference-service.md` §34).
     *
     * ⚠ Ranked BELOW [Uploading] and above [Tasks]. An upload is something the user just did and is
     * waiting on; indexing is long, background, and started once — so it explains a warm phone
     * without displacing the message about the thing in flight.
     */
    data class Indexing(val done: Int, val total: Int) : ForegroundNotificationContent

    /** Nothing is uploading; fall back to the liveness count that kept this service promoted. */
    data class Tasks(val count: Int) : ForegroundNotificationContent
}

/**
 * Pick the notification's message.
 *
 * [passTotal] `== 0` means no upload pass is in flight (the figures are cleared at every tick
 * boundary), so the notification falls back to the task count it showed before P5.
 *
 * ⚠ [current] is 1-based and capped, matching the banner: "Uploading 1 of 3" while the first file
 * is in flight reads correctly where "0 of 3" reads as though nothing has started, and the final
 * completion callback must not render "4 of 3".
 *
 * ⚠⚠ [ingestRunning] stands the upload message down entirely (`drive-upload-progress.md` §14.1).
 * While an app is driving a bulk ingest — Drive's Synced-Folders drain — it is a *producer* on the
 * queue these figures measure, so [passTotal] is a snapshot that grows between passes: the user
 * watched "20 of 400" become "20 of 500". That app posts its own notification from the drain's own
 * total ("Backing up 21 of 400"), so two notifications were narrating one operation with different
 * numbers and only one of them could be right. Same reconciliation P6 made between the banner and
 * Drive's folder-upload bar — the surface that knows more owns the story — applied across
 * processes; standing down costs nothing here because the app's notification is already up.
 */
internal fun foregroundNotificationContent(
    taskCount: Int,
    passTotal: Int,
    passCompleted: Int,
    ingestRunning: Boolean = false,
    /**
     * The running index pass's position, or `null` when none is running (§34).
     *
     * ⚠⚠ Null and `0 of 0` are different: a pass over a library with no images really does report a
     * total of zero, and rendering "Indexing 0 of 0" over it would be a notification about nothing.
     */
    indexing: Pair<Int, Int>? = null,
): ForegroundNotificationContent =
    when {
        passTotal > 0 && !ingestRunning -> ForegroundNotificationContent.Uploading(
            current = (passCompleted + 1).coerceAtMost(passTotal),
            total = passTotal,
        )
        indexing != null && indexing.second > 0 -> ForegroundNotificationContent.Indexing(
            done = indexing.first.coerceAtMost(indexing.second),
            total = indexing.second,
        )
        else -> ForegroundNotificationContent.Tasks(taskCount)
    }
