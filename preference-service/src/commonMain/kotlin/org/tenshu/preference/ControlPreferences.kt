package org.tenshu.preference

/**
 * Control's preference declarations. Control is the proxy / control plane and
 * is always installed, so its preferences are part of the platform vocabulary
 * and live here in `:preference-service` rather than a `:control-shared`
 * module — any consumer can read them without depending on Control's UI module
 * (`docs/proposals/core-preference-service.md` §3.8).
 *
 * Scope per setting:
 *  - background-sync settings are realm-wide (the user's intent applies to
 *    every paired device, so a single realm row drives them all);
 *  - the recovery-affordance dismiss timestamp is device-scoped (dismissing
 *    on phone shouldn't hide the banner on desktop — UX state lives per
 *    device, same pattern as `DrivePreferences.OfflineFiles`).
 */
object ControlPreferences {

    val WifiOnly = Preference(
        id = "control.bgsync.wifiOnly",
        scope = PreferenceScope.REALM,
        default = true,
        adapter = BooleanAdapter,
    )

    val MaxMonthlyDataMb = Preference(
        id = "control.bgsync.maxMonthlyDataMb",
        scope = PreferenceScope.REALM,
        default = 500L,
        adapter = LongAdapter,
    )

    /**
     * Epoch ms until which the home-screen recovery affordance is dismissed
     * on *this* device. Default `0L` = never dismissed (show the banner).
     * Set to `now + 72h` when the user dismisses; see
     * `docs/proposals/onboarding-low-friction.md` §7.2 Level 0.
     *
     * DEVICE-scoped so each device tracks its own dismiss state — phone and
     * desktop nag independently until the user acts.
     */
    val RecoveryAffordanceDismissedUntilMs = Preference(
        id = "control.onboarding.recoveryAffordance.dismissedUntilMs",
        scope = PreferenceScope.DEVICE,
        default = 0L,
        adapter = LongAdapter,
    )

    /**
     * Has the user confirmed they have saved their recovery phrase?
     *
     * ⚠⚠ This exists because the recovery banner used to key on **device count** — it stopped
     * warning once the roster reached two, believing a second device meant a second copy of the
     * master identity. It does not: a joiner never receives the phrase
     * (`PairingKeyBlob` carries no master key), so a two-device realm can hold exactly one copy.
     * The banner therefore switched off at the moment it wrongly concluded the user was safe, and
     * offered "Add another device" as a resolving action for a risk that action does not resolve
     * (`admission-revocation-names-the-capability.md` §5b.12.1 — this is how a realm on this
     * project lost its master identity).
     *
     * ⚠ DEVICE-scoped, matching [RecoveryAffordanceDismissedUntilMs]: only a device that *holds*
     * the phrase can nag about it, so a realm-wide flag would buy nothing and would let one device
     * silence a warning it cannot itself raise.
     */
    val RecoveryPhraseSaved = Preference(
        id = "control.onboarding.recoveryAffordance.phraseSaved",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )

    /**
     * The user dismissed Home's "download a region for offline maps" suggestion
     * (`maps-region-delivery.md` §7 phase 7).
     *
     * DEVICE-scoped, and dismissal is **permanent on that device** rather than timed: this is a
     * discovery hint, not a safety warning like [RecoveryAffordanceDismissedUntilMs], and the thing
     * it points at (Settings → Offline maps) stays reachable forever. Re-offering something the user
     * has already declined would be nagging about an optional convenience.
     */
    val OfflineMapsSuggestionDismissed = Preference(
        id = "control.maps.offlineSuggestion.dismissed",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )

    /**
     * Whether the free-tier quota soft-limit banner ("You're using 80% of your
     * free Tenshu storage") has been shown-and-dismissed on *this* device.
     * Default `false` = not yet dismissed. Set to `true` the moment the user
     * either dismisses it or taps *See plans* — the banner is **one-time and
     * permanent** ("the only marketing prompt in the app"; no re-prompts),
     * `docs/proposals/control-subscription-ux.md` §2.3 / `retention-quota-model.md`
     * P1b.
     *
     * DEVICE-scoped so dismissing on one device doesn't suppress the (still
     * useful) first sight of it on another — same pattern as
     * [RecoveryAffordanceDismissedUntilMs].
     */
    val QuotaSoftLimitBannerDismissed = Preference(
        id = "control.storage.quotaSoftLimit.dismissed",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )
}
