package app.tenshu.reachability

/**
 * [ControlReachability.realmVerdict]: whether Control's device belongs to a realm, as a **write
 * gate** for an app's unattended writers (`ADR 0025` §Decision 8.6).
 */
enum class RealmVerdict {
    /** Control has just become reachable (or never has) and the realm read has not answered yet. */
    PENDING,

    /** A realm exists (or the device is locked, which has one — §Decision 8.1). */
    PRESENT,

    /** Known: a successful read with no realm. The wall is up and unattended writes are held. */
    ABSENT,

    /** Cannot tell — no probe wired, the read failed, or Control is unreachable. Today's behaviour. */
    UNKNOWN,

    /**
     * A realm WAS present, and Control has been away since (Codex P1 on 90654ef7d). Unattended
     * writes are held until a realm read settles again — a Control reset or a device forgotten
     * during the outage has no realm — but the shell stays: a user who just had a library is not
     * thrown onto a checking screen for a reconnect. Left only by a read: PRESENT releases, ABSENT
     * walls. ⚠ The residual — a user act inside the ~1 s re-check — is accepted (§8.6).
     */
    RECHECKING,
    ;

    /** Whether unattended writes wait in this state. */
    val holdsWrites: Boolean get() = this == PENDING || this == ABSENT || this == RECHECKING

    /** Whether the realm read is retried on the short backoff in this state. */
    val awaitsAnAnswer: Boolean get() = this == PENDING || this == RECHECKING
}
