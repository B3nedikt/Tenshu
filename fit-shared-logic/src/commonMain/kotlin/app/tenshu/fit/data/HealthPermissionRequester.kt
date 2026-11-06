package app.tenshu.fit.data

import app.tenshu.fit.model.ImportNotice

/**
 * Asks the platform for the health-read permissions the importer needs — the one step of the
 * import that only the app shell can perform (Android's Health Connect permission contract must be
 * launched from an `Activity`; iOS's HealthKit prompt likewise).
 *
 * Exists so the "Try again" on an `ImportStatus.PermissionsMissing` banner can actually *fix* the
 * problem. Without it the button could only re-run an import that was always going to fail for the
 * same reason, which is a button that lies: it looks like a remedy and does nothing.
 *
 * A plain DI-bound interface, not `expect`/`actual` — same convention as [HealthImporter] and
 * [RecordingEngineFactory]. Platforms that cannot request anything simply do not bind it, and the
 * view model falls back to a plain re-import.
 */
interface HealthPermissionRequester {

    /**
     * Launches the platform's permission prompt. Returns immediately — the grant lands
     * asynchronously, and the shell re-runs the import once the user has answered.
     */
    fun request()

    /**
     * Launches the prompt for the **deep-history** grant — the separate consent that lets an import
     * read past the platform's default window (`HealthConnectImporter.HISTORY_PERMISSION`).
     *
     * A distinct call rather than a parameter on [request] because the two are asked at different
     * moments for different reasons: [request] is the up-front gate without which nothing imports,
     * while this is an opt-in the user reaches for when they want their whole history. Defaults to
     * a no-op so a platform with no such notion needs no stub — the UI only offers it when the
     * importer reports `HistoryAccess.Grantable` anyway.
     */
    fun requestFullHistory() = Unit

    /**
     * Opens the platform's health-store settings, where the user manages what **other** apps may
     * write into it — Health Connect's "App permissions" screen.
     *
     * Not a permission *we* can request: the missing grant belongs to Google Fit, and no app may
     * prompt on another's behalf. Navigation is therefore the whole remedy, which is exactly why it
     * has to exist — an [ImportNotice.GoogleFitNotPermitted] that only *described* a switch buried
     * three levels into system settings would be a diagnosis with no cure attached.
     *
     * Defaults to a no-op; the UI offers it only where a shell binds a requester at all.
     */
    fun openHealthSettings() = Unit
}
