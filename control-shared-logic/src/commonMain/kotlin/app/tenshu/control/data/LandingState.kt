package app.tenshu.control.data

/**
 * Result of the §2 landing-state detection in `docs/proposals/control-onboarding-ux.md`.
 *
 * `Locked` is gated on the local-unlock primitive (Track C #11): a device
 * has a wrapper persisted via `SetUpLocalUnlock` but the master keys
 * haven't been re-loaded yet (process restart, explicit lock, etc.).
 * Pre-#11 installs that store the recovery phrase plaintext continue to
 * present as `Active` — `SyncManagerImpl.init` auto-loads from plaintext
 * when no wrapper is present, so detection sees the master keys live.
 */
sealed class LandingState {

    /**
     * The user has set up Tenshu on this device — a realm exists locally.
     * Route to `Destination.Home`.
     *
     * ⚠ "A realm exists", not "the keys are loaded": on a cold start the key-load
     * runs asynchronously behind Argon2id, and holding this state back until it
     * finishes is what used to send a provisioned device to [FirstTime]. Screens
     * that need live keys read `SyncStatus.isEnabled`.
     */
    data object Active : LandingState()

    /**
     * The unlock wrapper is persisted but master keys are not loaded.
     * Route to the §3.1 unlock screen.
     */
    data object Locked : LandingState()

    /**
     * No realm on this device yet — first run, or a re-onboarding after a
     * full local wipe. Route to the §4.0 chooser screen.
     */
    data object FirstTime : LandingState()
}
