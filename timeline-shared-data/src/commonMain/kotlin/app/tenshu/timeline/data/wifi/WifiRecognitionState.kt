package app.tenshu.timeline.data.wifi

/**
 * **What Wi-Fi place recognition last did, or last could not do** (S7,
 * `docs/proposals/timeline-movement-capture.md` §18.5/§23).
 *
 * ### Why this type exists
 *
 * `WifiPlaceFingerprint.resolvePlaceId` had **two early returns that logged nothing**, and they were
 * exactly the two states most likely to be the true one: consent off (the default) and an unusable
 * BSSID. F2 added `wifi_recognised`/`wifi_adopted`/`wifi_learned` breadcrumbs so an on-device pass
 * could answer *"is this even working?"* — but only along the paths where it **does** work. The
 * silent half was the half that fires when something is wrong, which is why the feature could look
 * completely dead while behaving exactly as written.
 *
 * ### ⚠⚠ The distinction that makes this actionable
 *
 * `usable()` collapsed *no network*, *withheld identity* and *blank* into one `null`. They are not
 * the same thing to a user:
 *
 *  - [NoNetwork] — not on Wi-Fi. Nothing is wrong; nothing to do.
 *  - [IdentityWithheld] — on Wi-Fi, but Android returned the redacted `02:00:00:00:00:00`. That
 *    happens when **location services are off** or the permission is missing, and it is **the only
 *    state the user can act on**. Collapsing it into "no network" hid the one fixable case.
 *  - [ConsentOff] — the feature is off. Also actionable, and also previously invisible.
 *
 * Everything here is device-local: a state value in memory plus a `Diagnostics` line. Nothing is
 * stored, nothing is synced, and no new permission is involved (§5's posture is unchanged).
 */
sealed interface WifiRecognitionState {

    /** No reading yet this session — capture has not run and nothing has been probed. */
    data object Unknown : WifiRecognitionState

    /** The opt-in is off. ⚠ Reached **without touching the radio** — see `WifiPlaceFingerprint`. */
    data object ConsentOff : WifiRecognitionState

    /** Consent is on, but no Wi-Fi network is connected. Not a fault. */
    data object NoNetwork : WifiRecognitionState

    /**
     * Connected, but Android withheld the network's identity (`02:00:00:00:00:00`).
     *
     * ⚠ **The actionable one.** It means location services are off or the location permission is
     * missing — recognition cannot work until that changes, and no amount of visiting places will
     * teach it anything.
     */
    data object IdentityWithheld : WifiRecognitionState

    /** This network was already bound to a place. */
    data class Recognised(val placeId: String) : WifiRecognitionState

    /** A new radio at a venue we already knew — bound to that venue's place (§8.4 F2). */
    data class Adopted(val placeId: String) : WifiRecognitionState

    /** A network we had not seen; bound to the place the visit proposed. */
    data class Learned(val placeId: String) : WifiRecognitionState

    /**
     * A known network seen **far from where it was learned** — so it travels, and its binding says
     * nothing about where you are now.
     *
     * The archetype is your own phone hotspot: one BSSID, learned at a café, carried everywhere
     * afterwards. Without this the café would claim every dwell that hotspot is connected during.
     * [placeId] is the binding that was distrusted, kept for the diagnostics line — it is
     * deliberately *not* what recognition returns.
     */
    data class MovedNetwork(val placeId: String) : WifiRecognitionState

    /**
     * Is the feature able to do its job in this state?
     *
     * ⚠ [Unknown] is deliberately **not** a problem — "we have not looked yet" is not "it is broken",
     * and a Settings row that cried failure before capture had run would train the user to ignore it.
     */
    val isWorking: Boolean
        get() = this is Recognised || this is Adopted || this is Learned || this is Unknown

    /** Does this state name something the *user* can change? Drives whether Settings nudges. */
    val isActionable: Boolean
        get() = this is ConsentOff || this is IdentityWithheld
}
