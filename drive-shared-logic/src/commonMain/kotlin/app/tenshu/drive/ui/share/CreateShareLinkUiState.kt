package app.tenshu.drive.ui.share

/**
 * State for [CreateShareLinkSheet] (`docs/file-sharing.md`).
 *
 * **P1a — interface only.** Field shape is fixed; the ViewModel transitions
 * between states in P1b.
 */
data class CreateShareLinkUiState(
    /** Path of the file being shared. */
    val filePath: String,
    /** Display name (last path segment) for the sheet header. */
    val fileName: String,
    /** Optional expiry (epoch millis); `null` = no expiry. */
    val expiresAtMillis: Long? = null,
    /** Optional one-shot/N-shot cap; `null` = no cap. */
    val maxOpens: Int? = null,
    /** True while the ShareLink op is in flight. */
    val isCreating: Boolean = false,
    /** Set once the op succeeds. */
    val link: ShareLinkUrl? = null,
    /** Inline error string for the sheet. Populated when the op fails. */
    val errorMessage: String? = null,
) {
    val advancedExpanded: Boolean
        get() = expiresAtMillis != null || maxOpens != null
}

/**
 * The shape the sheet renders + the *Copy link* button writes to the
 * clipboard. Both parts are kept here so the ViewModel exposes one cohesive
 * value rather than two unrelated strings.
 *
 * `urlFragment` (the per-share key) is the secret half; ViewModels MUST drop
 * this on configuration change rather than retain it (A11).
 */
data class ShareLinkUrl(
    val shareId: String,
    val urlFragment: String,
) {
    /** The full canonical URL the user copies. */
    fun toCopyableString(host: String = DEFAULT_HOST): String =
        "https://$host/s/$shareId#k=$urlFragment"

    companion object {
        /** Built-in fallback host — the apex, where the viewer route is deployed today. */
        private const val FALLBACK_HOST: String = "tenshu.app"

        /**
         * The **fragment-less** form of a share URL — `https://host/s/{shareId}`.
         *
         * This is what *My shares* "Copy ID" hands out, and it is deliberately not a
         * working capability URL: the `#k=` fragment is the per-share secret and is
         * never persisted (A11), so a listed share can only ever be identified, not
         * re-shared. Only the mint sheet ([toCopyableString], which still holds the
         * fragment in memory) yields the openable link.
         *
         * Single-sourced here because *dropping* the fragment is the load-bearing
         * privacy behaviour: an inline per-platform string would put that rule in
         * two files that no test ties together.
         */
        fun pathOnly(shareId: String): String = "https://$DEFAULT_HOST/s/$shareId"

        /**
         * Host that serves the recipient viewer route (`/s/{shareId}`).
         *
         * The viewer lives on the **apex** today — `web/apex` registers
         * `<Route path="s/:shareId">` (App.tsx) and is the only surface deployed at
         * `tenshu.app`. The per-app subdomain `drive.tenshu.app` is the web roadmap's
         * Phase 8 and is **not deployed yet**.
         *
         * Configurable for deployment via [platformShareHostOverride] (the JVM actual
         * reads `TENSHU_SHARE_HOST` / `-Dtenshu.shareHost`), so a build pointed at a
         * different web origin needs no code change; absent an override it falls back to
         * the apex. Callers needing a one-off host can still pass it to [toCopyableString].
         */
        val DEFAULT_HOST: String
            get() = platformShareHostOverride()?.takeIf { it.isNotBlank() } ?: FALLBACK_HOST
    }
}
