package app.tenshu.control.data

/**
 * Detects which of the three §2 landing states the current device is in,
 * synchronously on app start (no relay round-trip).
 *
 * The detection runs once per app launch and the result is consumed by
 * `App` to pick the initial nav destination.
 */
interface LandingStateRepository {

    suspend fun detect(): LandingState
}
