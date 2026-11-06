package org.tenshu.preference

/**
 * Typed declaration of a single preference — the pure storage/identity contract.
 * Lives in code (not the realm) so adding a new preference does NOT require a
 * new composite — the storage layer is one of [RealmSetting] / [DeviceSetting],
 * discriminated by [scope].
 *
 * `T` is the consumer-facing value type (`Boolean`, `Long`, `String`, ...);
 * [adapter] handles the `T <-> Data` round-trip into the matching Tenshu
 * primitive (`TBoolean`, `TInteger`, `TString`, ...).
 *
 * Declarations group under an `object <App>Preferences` per app:
 * `ControlPreferences.WifiOnly`, `DrivePreferences.OfflineFiles`, etc.
 * Control's preferences live in `:preference-service` (always installed);
 * app-specific preferences live in `:<app>-shared`. See
 * `docs/proposals/core-preference-service.md` §3.8.
 *
 * **Presentation (title/description) deliberately lives elsewhere.** This type
 * is consumed by the UI-free service layer and must stay localisation-free; a
 * settings screen resolves its own labels via Compose string resources keyed
 * off [id]. If a *generic* preference-rendering screen ever needs presentation
 * to travel with the declaration, add a separate `PreferencePresentation`
 * (string-resource refs, in `:ui-core` / app-shared) keyed by [id] — see
 * `core-preference-service.md` §5.4. Do NOT reintroduce display strings here.
 */
data class Preference<T>(
    val id: String,
    val scope: PreferenceScope,
    val default: T,
    val adapter: PreferenceAdapter<T>,
)

enum class PreferenceScope {
    /** One row for the whole realm; every device sees the same value. */
    REALM,

    /** One row per device; a user replacing a device retains the others'
     *  rows until the old device is explicitly removed from the realm
     *  ([core-preference-service.md §3.9]). */
    DEVICE,

    /** Realm row is the default; a per-device row, when present, overrides
     *  it for that device. Resolution lives on the read path
     *  ([core-preference-service.md §6.5]); v1 stores the rows but the
     *  override resolution is deferred until the first concrete consumer
     *  asks for it. */
    OVERRIDABLE,
}
