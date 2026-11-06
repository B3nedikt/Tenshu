package org.tenshu.sync.relay

/**
 * CR-2 B5's one switch (`content-plane-cr2-b5-spec.md` D2), the posture of `retention-quota-model.md`
 * P3: one flag per plane, so deletion is a deploy-time decision rather than a code review.
 *
 * ⚠ The clock (`content_unreferenced`) is kept in **every** mode, so arming later starts from an
 * accurate clock rather than only from the arming moment (§1.1).
 */
enum class ContentSweepMode(val wire: String) {
    /** The default. The clock is kept; nothing is swept, and held bytes count towards usage and the quota. */
    OFF("off"),

    /** The sweep runs and logs what it **would** free, per owner. Frees nothing; the quota is unchanged. */
    DRY_RUN("dry_run"),

    /**
     * ⚠⚠ The sweep frees content unreferenced for T and drains its bytes; held bytes leave
     * `/sync/usage` and the quota check (D5). (D3's 410 on the client FREE routes went with those
     * routes in CR-2 B6: no client frees.)
     */
    ARMED("armed"),
    ;

    companion object {
        /** `off` / `dry_run` / `armed`, case-insensitive; `null` for anything else. */
        fun parse(raw: String): ContentSweepMode? = entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) }
    }
}
