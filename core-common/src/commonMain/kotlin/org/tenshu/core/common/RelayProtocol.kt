package org.tenshu.core.common

/**
 * Monotonic version of the **sync relay's own wire contract** — the thing the relay
 * genuinely has a stake in, as opposed to the shape of the data it stores.
 *
 * Lives beside [TENSHU_STORAGE_FORMAT_VERSION] deliberately: the two numbers are routinely
 * confused, and the contrast between them is the whole point. Keeping them in the same module
 * (`:core-common`, the lowest module both `:sync-relay-server` and every client platform
 * already depend on) means a reader who finds one finds the other. Separate *files* because
 * they version two independent contracts that must be free to move independently
 * (`docs/proposals/core-schema-evolution.md` §7.5, §7.8).
 *
 * ## What this version covers
 *
 * - the HTTP routes (`/sync/realm`, `/sync/changes`, `/sync/blocks`, the content-plane and
 *   mailbox routes, …) and their **semantics**;
 * - the JSON request/response bodies of those routes;
 * - the `SyncBlock` **envelope** the relay parses and indexes — `blockId`, `ownerId`,
 *   `hlc`, `encryptedPayload`, `targetEntityIdHash`;
 * - the auth contract (device-key JWT shape, entitlement headers);
 * - the content-plane **blob-reference format** the relay validates (`REF_PATTERN`).
 *
 * ## What requires a bump (normative)
 *
 * Bump [RELAY_PROTOCOL_VERSION] when a client built against version *N* and a relay
 * built against version *N+1* (in either direction) would misunderstand each other:
 *
 * - the `SyncBlock` envelope gains, loses or reshapes a field;
 * - a route changes meaning, changes its status codes, or drops a field a peer requires;
 * - the auth or entitlement contract changes shape;
 * - the blob-reference format changes. **The archetype**: a manifest ref of the form
 *   `manifest-<contentId>` is one the relay's `REF_PATTERN` (`^blob-[0-9a-f]{64}$`)
 *   400-rejects on PUT *and* GET, so cross-device bytes break. That is a relay-contract
 *   change, and it is precisely the class this gate exists for.
 *
 * **Purely additive routes do NOT need a bump** — a client that never calls a new route
 * cannot be broken by its existence, and an old relay's 404 is already handled as
 * "unsupported" at the call sites that probe (the `RelayUnsupported` pattern).
 *
 * ## What explicitly does NOT require a bump (normative)
 *
 * **Any payload or schema change.** Under House Rule #1 the relay sees ciphertext: a Fit
 * composite gaining a field, a new `Syncable` type, a reshaped `Moment`, a new payload
 * variant inside the sealed envelope — none of it is observable to the relay and none of
 * it can affect the relay's behaviour. Those changes bump
 * [TENSHU_STORAGE_FORMAT_VERSION] (House Rule #9) and owe the relay **nothing**: no
 * redeploy, no coordination (§7.5): a schema change never forces a coordinated relay
 * redeploy for reasons the relay could not even observe.
 *
 * Dropping payload gating is safe rather than a lost backstop: an old client *pushing*
 * old-format blocks was always harmless (the relay stores opaque bytes; new peers upcast
 * on read, §8.2). The danger is exclusively on the *reading* side, and that is fenced
 * client-side by the realm epoch vector (§7.3), which does the job strictly better —
 * per-service instead of global, self-scoping, and E2E instead of leaking app usage.
 *
 * ## Why a range, not an exact match
 *
 * The relay accepts [RELAY_PROTOCOL_SUPPORTED_RANGE] =
 * `[RELAY_PROTOCOL_MIN_SUPPORTED_VERSION, RELAY_PROTOCOL_VERSION]`, not one value.
 * Exact match forces a **flag day**: every client on earth has to move the moment the
 * relay does. A range lets the operator-controlled component move *first and compatibly*
 * — deploy a relay that speaks N and N+1, then let clients roll through app stores at
 * their own pace. That is the entire reason for having an operator-controlled component.
 *
 * ## ⚠ The permanent floor rule (normative — lowering this is a data-corruption bug)
 *
 * **[RELAY_PROTOCOL_MIN_SUPPORTED_VERSION] must NEVER drop below
 * [RELAY_PROTOCOL_FENCING_FLOOR_VERSION] = 1.**
 *
 * Protocol **1** is the release baseline (`core-schema-evolution.md` Appendix A.2), and
 * client-side epoch fencing (§7.3/§7.7) is part of it — so it is the first version at which
 * the fence exists in the field for any release build. A client older than that does not
 * know epochs exist: it will happily pull blocks written
 * by newer peers and garble them mid-parse, because positional composites misalign rather
 * than error. For such a client the relay gate is *the only fence there is*. Those
 * clients must therefore stay rejected **forever** — this is not a compatibility
 * preference or a config knob to relax when someone complains that an old build cannot
 * connect. Lowering the floor silently corrupts data on the oldest devices in the fleet.
 *
 * [RELAY_PROTOCOL_SUPPORTED_RANGE] enforces the rule at class-init rather than trusting
 * this KDoc to be read.
 *
 * ### Versions
 *  - **1** — **the release baseline** (`core-schema-evolution.md` Appendix A.2;
 *    `how-to/reset-and-commerce-test.md`): `EdgedPush` / `CascadedTombstonePush` / `reap`, keyed
 *    entity ids (EI-1), domain-separated membership signatures (F4), and a per-request 426 gate on
 *    the bodies that carry `relayProtocolVersion`. It is served from a relay on a fresh volume
 *    (the production reset, Appendix A.2).
 *
 *    ⭐ **Envelope authentication is part of 1** (`sync-envelope-authentication.md`). Every
 *    `DeleteBlock` carries a 32-byte `authTag` (the relay 400s a tombstone push without one), and
 *    a `SyncBlock`'s payload is sealed with its envelope bound as associated data. ⚠ A build that
 *    announces 1 without envelope authentication makes every peer drop its tombstones and blocks
 *    — never run one against a baseline relay, the same rule as the pre-baseline series below.
 *
 *    ⭐ **Hashed pairing rendezvous and sealed device names are part of 1 too** (F7,
 *    `relay-metadata-minimisation.md`; threat model T-P-16): the join flow sends
 *    `pairingSecretHash` and `sealedDeviceName` instead of the secret and the clear name, the
 *    joiner polls `POST /sync/pairing/response/peek` (there is no `GET …?pairingSecret=` route),
 *    and the hub offer seals its name. ⚠ A build without them cannot pair with a relay or a host
 *    that has them, in either direction — the relay 400s its request and its host cannot open a
 *    sealed name. Pairing only; sync between already-paired devices is untouched.
 *
 *    ⭐ **So is hub-offer fetch by body.** The phone fetches a hub offer with
 *    `POST /sync/pairing/hub-offer/fetch` carrying the rendezvous key in the body (there is no
 *    `GET /sync/pairing/hub-offer?hash=` route; T-P-17), and both admission flows register the new
 *    device when it *fetches* its response (`/response/peek`, `GET /offer-response`), not when the
 *    host posts it. ⚠ A phone that only knows the `GET` route reads the relay's bare 404 as "relay
 *    does not support hub pairing"; the registration timing changes no wire shape. Relay, clients
 *    and hub deploy together.
 *
 *    ⭐ **The ownerId-blind recovery routes are part of 1 too** (F8, `relay-metadata-minimisation.md`;
 *    threat model T-RC-08): `GET /sync/recovery-challenge` names no realm,
 *    `POST /sync/recovery-claim` carries `masterPublicKey` instead of `ownerId`, `GET /sync/realm-epoch`
 *    needs the device JWT, and there are no A9 `/sync/recovery-blob` routes.
 *    ⚠ A build that sends `ownerId` cannot recover a realm against a relay that expects the key, or
 *    the reverse (the claim 400s); a pairing still completes but seeds its realm epoch from the local
 *    view (`PairingKeyInstaller` soft-fails). Sync between already-admitted devices is untouched.
 *
 *    ⚠⚠ The pre-baseline series (`core-schema-evolution.md` Appendix A.2) is closed. A
 *    *pre-baseline* build announcing protocol 1 predates F4 and EI-1 and would pass this gate, then
 *    diverge silently. Never install one against a baseline relay.
 *
 * ### Next bump
 *
 * Add the entry here. If old clients can no longer be served, or would *diverge silently* (a value
 * change such as an entity-naming scheme, not just a route), raise
 * [RELAY_PROTOCOL_MIN_SUPPORTED_VERSION] with it. A version range exists precisely to turn silent
 * divergence into a refusal.
 */
const val RELAY_PROTOCOL_VERSION: Int = 1

/**
 * The oldest relay-protocol version this build accepts. Together with
 * [RELAY_PROTOCOL_VERSION] it forms the inclusive [RELAY_PROTOCOL_SUPPORTED_RANGE] the
 * 426 gate enforces.
 *
 * Raise this only to cut off clients whose *relay contract* the relay can no longer
 * serve, and never below [RELAY_PROTOCOL_FENCING_FLOOR_VERSION] — see the permanent
 * floor rule on [RELAY_PROTOCOL_VERSION].
 *
 * There are two kinds of raise. The common one cuts off a client whose route or body is gone. The
 * rarer one cuts off a client that computes a *value* differently (EI-1's entity naming: both
 * sides sync happily and diverge). Both kinds are recorded in Appendix A.2.
 *
 * **1 at the release baseline**: every release client speaks protocol 1.
 */
const val RELAY_PROTOCOL_MIN_SUPPORTED_VERSION: Int = 1

/**
 * The first protocol version with **client-side epoch fencing** (§7.3/§7.7) —
 * the hard floor [RELAY_PROTOCOL_MIN_SUPPORTED_VERSION] may never go below.
 *
 * Kept as its own named constant rather than a bare `1` so the invariant is greppable and
 * assertable: a change that raises the other two constants must leave this one
 * alone, and the [RELAY_PROTOCOL_SUPPORTED_RANGE] check then still means what it says.
 */
const val RELAY_PROTOCOL_FENCING_FLOOR_VERSION: Int = 1

/**
 * The inclusive range of relay-protocol versions this build speaks — what the relay
 * advertises and what its 426 gate accepts.
 *
 * Derived, so the relay and any future advertising surface cannot drift from the two
 * constants. The `require`s make the permanent floor rule (see [RELAY_PROTOCOL_VERSION])
 * a **load failure** rather than a doc comment: a change that lowers
 * [RELAY_PROTOCOL_MIN_SUPPORTED_VERSION] past
 * [RELAY_PROTOCOL_FENCING_FLOOR_VERSION] fails loudly at first touch instead of silently
 * re-admitting clients that have no fence.
 */
val RELAY_PROTOCOL_SUPPORTED_RANGE: IntRange = run {
    require(RELAY_PROTOCOL_MIN_SUPPORTED_VERSION >= RELAY_PROTOCOL_FENCING_FLOOR_VERSION) {
        "RELAY_PROTOCOL_MIN_SUPPORTED_VERSION ($RELAY_PROTOCOL_MIN_SUPPORTED_VERSION) is below " +
            "RELAY_PROTOCOL_FENCING_FLOOR_VERSION ($RELAY_PROTOCOL_FENCING_FLOOR_VERSION): clients " +
            "older than that have no client-side epoch fence, so the relay gate is their only " +
            "fence — admitting them corrupts data (core-schema-evolution.md §7.5)"
    }
    require(RELAY_PROTOCOL_MIN_SUPPORTED_VERSION <= RELAY_PROTOCOL_VERSION) {
        "RELAY_PROTOCOL_MIN_SUPPORTED_VERSION ($RELAY_PROTOCOL_MIN_SUPPORTED_VERSION) exceeds " +
            "RELAY_PROTOCOL_VERSION ($RELAY_PROTOCOL_VERSION): the supported range would be empty"
    }
    RELAY_PROTOCOL_MIN_SUPPORTED_VERSION..RELAY_PROTOCOL_VERSION
}

/**
 * The maximum number of rows one `/sync/changes` (or `/sync/tombstones`) response may carry.
 *
 * ### Why this exists
 * Without it a lane answers "everything with `seq > since`" in one response, materialising
 * every matching row before serialising it. A device whose cursor is at 0 — a fresh install,
 * or any store wipe — asks for the entire owner history in a single call, and the relay must
 * hold all of it at once. A ~49 000-block / 244 MB history against a ~242 MB relay heap fails
 * every pull with `OutOfMemoryError`; the client scores that as "relay unreachable" and leaves
 * its cursor at 0, so the next tick asks the identical impossible question. The device reads
 * **permanently offline** while its pushes keep succeeding, and the retry loop pins the
 * relay's single core for every other device.
 *
 * A page limit makes the response size a function of this constant instead of history
 * length, so the memory ceiling does not move as the log grows.
 *
 * ### Why it is a shared constant rather than a request parameter
 * The client must know the limit to detect a *truncated* page (`rows.size >= this`), and that
 * signal decides how far its cursor may advance — see `SyncScheduler`'s cursor arithmetic. A
 * request parameter would have to ride the `GetChanges` operation's params, which are
 * client-serialised positional composites: adding one reshapes the wire and owes a
 * `TENSHU_STORAGE_FORMAT_VERSION` bump plus a lockstep fleet move (House Rule #9). Agreeing on
 * a compile-time value costs nothing on the wire and keeps the limit deployable to the relay
 * alone.
 *
 * ⚠ Both lanes must use the *same* limit, because the relay assigns blocks and tombstones
 * seqs from one shared `relay_seq` counter and the two cursor spaces are therefore directly
 * comparable.
 *
 * ⚠ **No cross-lane bound.** Nothing bounds one lane's advance by the other's truncation
 * point (a `min`-of-ceilings rule would let the dense tombstone lane throttle the sparse block lane
 * to +500 seq/tick); the client reads one merged lane (`sync-lane-cursor-split.md`). The
 * shared limit still matters — the merged lane's page cap is one budget spanning both kinds.
 *
 * ⚠ The merged lane does not infer truncation from this constant at all: `/sync/log` states
 * `hasMore` outright, which is exact where the row-count inference misreads an exactly-full
 * *final* page as truncated.
 *
 * 500 blocks at this history's ~5 KB mean envelope is ≈2.5 MB per response — comfortably
 * inside the smallest heap we deploy on, while still draining a 49 000-block backlog in ~100
 * ticks rather than one impossible one.
 */
const val SYNC_CHANGES_PAGE_LIMIT: Int = 500
