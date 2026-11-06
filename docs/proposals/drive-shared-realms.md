# Drive — Shared Realms (Named-Recipient Sharing)

**Status:** Sketch (2026-05-28). Forward-looking concept doc, intentionally
lighter than an implementation-ready proposal. Companion to
[`docs/file-sharing.md`](../file-sharing.md) + [ADR 0018](../adr/0018-file-sharing-mode-a.md)
(link sharing, Mode A — already shipped; does *not* depend on this).

**Purpose.** Establish the conceptual frame and major dependencies for sharing
a file with a *named* Tenshu identity — i.e. "share with Alice" rather than
"copy this link." The link-sharing proposal needs to know what to defer, the
UI needs to know what shape to gesture at, and the milestones doc already lists
the relay-level prerequisite ("Cross-realm content distribution") but doesn't
flesh out how that lands inside Drive specifically. This doc fills that gap at
sketch-fidelity. A full implementation-ready proposal will replace it once the
cross-realm-distribution milestone is moving.

**What this is not.** Not a wire-protocol spec, not a schema sketch with field
names, not a phased implementation plan with file paths. Those will come in a
follow-up doc when the cross-realm-distribution milestone is being sized.

---

## 1. Why a separate proposal

Link sharing (Mode A) lets the relay stay a per-realm mailbox. Named-recipient
sharing (Mode B) breaks that assumption: the *creator's* relay storage of a
shared file needs to be visible to the *recipient's* realm. This is the same
break the [`milestones.md`](../milestones.md) "Cross-realm content
distribution" entry calls out for App Console / Creator / Creations. In other
words, *sharing a file with another Tenshu user* is the simplest concrete
instance of cross-realm content distribution; ironing out its shape will
inform the design that App Console & Creations eventually consume.

Three dependencies make this larger than a single proposal can absorb:

1. **A Tenshu-identity primitive.** Today `IdentityService` knows only `Device`
   (per-realm). There is no notion of "another user's identity" addressable
   from within Drive. The stale `User` shape in `identity-service/identity-agent.md`
   is Supabase-era documentation; it does not reflect what's in the codebase.
2. **Cross-realm relay routing.** Today `SyncMailbox` is strictly keyed by
   `ownerId`; a block from realm-X cannot be delivered to realm-Y. The
   "Cross-realm content distribution" milestone is the umbrella for this.
3. **Recipient-side virtual surface.** Bob's Drive needs somewhere to *show*
   "Alice shared report.pdf with you" — a virtual folder or inbox that
   participates in normal Drive UI without polluting Bob's own file tree.

This doc sketches each.

---

## 2. Tenshu identity — the addressable user

To share *with* someone, we need a way to name them that survives device
changes, doesn't reveal more than the user wants, and gives the relay
something to route on without learning who-shares-with-whom in plaintext.

The natural anchor is the **public half of the master identity key (A1)** —
already a stable per-user value, already cryptographic, already known to the
relay (per the existing master-public-key store from
[`sync/design.md`](../sync/design.md) §2.2 / ADR 0007). User-facing surface
is then a *Tenshu handle* derived from it, with two surface possibilities:

- **Fingerprint-only** (privacy-maximal). Users exchange a 6-word fingerprint
  out of band (same format as the SAS). No directory; no discovery. Highest
  privacy, worst UX.
- **Username-mapped via the relay** (UX-friendly). Users pick a memorable
  handle (`alice.tenshu`); the relay holds a `handle → master-pubkey` map and
  serves a discovery endpoint. Adds a metadata surface (relay sees which
  handles look up which others, can correlate sender + recipient). Better UX
  by a wide margin.

Neither is freely better; the choice has threat-model weight (a discoverable
directory is itself a target). **Resolved (2026-06-11): no directory** — identity
references are acquired exclusively via a mutual invitation handshake (link/QR),
and contacts accrete as a side effect of sharing. See
[`contact-service-identity-model.md`](contact-service-identity-model.md) §2 for the
decision rationale and the data model.

A "social proof" overlay — Bob verifies Alice's handle out of band the first
time, the same SAS-style gesture as pairing — is the v1 mitigation regardless
of which directory shape lands. Subsequent shares against the same
master-pubkey skip the gesture.

---

## 3. Cross-realm relay routing

The relay protocol gains a way for blocks signed by realm-X to be delivered
to realm-Y, with an entitlement proof so revocation propagates. The
milestones entry lists three sub-dependencies that this doc inherits:

- Wire-level distinction between intra-realm (today's `SyncMailbox`) and
  cross-realm flow.
- Storage shape for entitlement records (revocable, signed).
- Threat-model amendment: relay now sees `(creator-realm, consumer-realm)`
  pairs even though payloads stay ciphertext — a new metadata leak in the
  A6 class. (Mode A's T-S-02 anonymous fetch-trace is the simpler precursor;
  Mode B adds *attributable* fetch traces.)

How it cuts:

- **Encryption.** The shared file is wrapped with a *recipient-specific* key,
  ideally derived from the recipient's master pubkey via an ECDH-flavoured
  envelope (same primitive family as pairing's SAS-derived shared secret).
  Each recipient gets their own wrapped-key envelope; the underlying
  ciphertext is shared across recipients (in contrast to Mode A's
  per-share re-encrypt). The relay never sees plaintext or unwrapped keys.
- **Revocation.** Per-recipient: drop that recipient's envelope. Realm-X
  signs and publishes a `RevokeShareTo(recipient)` block; the relay refuses
  to serve the envelope; the recipient's existing local copy is unrecoverable
  in absence of the envelope (assuming the recipient never extracted plaintext
  — capability-style limitation, same as Mode A's T-S-01).
- **Permission level.** Read-only for v1 of Mode B as well. Comment is
  feasible (append-only comment threads as a separate composite). Edit is
  the CRDT track — out of scope for the foreseeable future.

---

## 4. Recipient surface — the virtual shared folder

Bob's Drive shows shared files in a *Shared with me* destination, which is
the second tab of the SharedScreen split called out in
[`docs/file-sharing.md`](../file-sharing.md) §8 (UX surface). Each row: file name,
who shared it, when, current permission level. Tapping opens the standard
file-preview screen.

Mechanically, *Shared with me* is a **virtual folder**: it presents as a
`Folder` to Drive's UI but is not a real entity in Bob's realm's
file-service. Its contents are derived from a new `IncomingShare` projection
(name TBD) over the cross-realm flow from §3. Two shape questions for the
implementation-ready doc:

- **Virtual-folder pattern.** Does the virtual folder live as a synthetic
  `Folder` injected by a `FilesRepository` extension, or as a real `Folder`
  entity Bob's realm holds with a flag suppressing it from his own sync?
  Synthetic is simpler; real makes "drag a shared file into a real folder" a
  natural rename op. Decide when sizing.
- **Lifetime.** When Alice revokes a share, the row disappears from Bob's
  *Shared with me*. Does Bob keep a local-only copy of the bytes if he had
  loaded them? Probably yes — same capability semantics as Mode A's
  "revocation doesn't un-give." Confirm.

A **named shared folder** — Alice creates "Project X folder" and adds Bob +
Carol as recipients, with files added later auto-propagating — is the
natural Mode B extension and a real product feature. It's deferred to a
later phase of Mode B, gated on the virtual-folder shape above.

---

## 5. UI surface implication

Once Mode B lands, the half-painted Drive surface from
[`docs/file-sharing.md`](../file-sharing.md) §8 fills in:

- **SharedScreen / Shared with me**: real data from the cross-realm inbox.
- **`FileActionsBottomSheet.Share`**: opens a recipient picker (Tenshu handle
  lookup, social-proof gesture on first use), confirms, runs the cross-realm
  share op.
- **`FileActionsBottomSheet.Manage access`**: per-file recipient list with
  per-row permission + revoke.
- **`MultiSelectBottomSheet.Share`**: same recipient picker, multiple files
  in one share action.
- **"Shared by Alice" badges** on file rows in *Shared with me*.
- **Per-recipient audit trail** in the FileDetailsScreen activity list (now
  the long-deferred `userId` from
  [`file-service-history.md`](file-service-history.md) §3.1 has somewhere
  to point).

---

## 6. Dependencies summary

For the future implementation-ready spec:

| Dependency | Owner | Status |
|---|---|---|
| Tenshu-identity primitive (handle + discovery) | `:identity-service` + relay; new design doc | Not started |
| Cross-realm relay routing | [`milestones.md`](../milestones.md) "Cross-realm content distribution" | Not started |
| Recipient-side virtual folder shape | `:file-service` + `:drive-shared` | Not started |
| Per-recipient envelope encryption | `:sync-service` (new key-wrap primitive) | Not started |
| Threat-model amendments | `docs/sync/threat-model.md` — new T-S-1x for cross-realm flow, A12 for envelopes, new T-C-10 revisit (was deferred for shared realms) | Not started |
| `file-service-history.md` §4.5 `userId` resolution | `:file-service` | Deferred to here |

The shape of (a) is the riskiest decision (privacy ↔ UX trade-off), the
shape of (b) is the most cross-cutting, and (c)/(d) compose cleanly once
(a)/(b) are settled.

---

## 7. Why this stays a sketch for now

The cost gap between sketch and implementation-ready spec is large because
each dependency above has its own design weight. Promoting this doc means:

- Picking the identity-directory shape (§2) — that decision alone is a
  multi-week threat-model conversation.
- Sizing cross-realm routing (§3) — that's the milestone-level work.
- A full threat-model rewrite of T-C-10 and additions for cross-realm
  metadata leaks.

Until the platform is ready to absorb that, this doc serves three concrete
purposes:

1. Tells the Mode A proposal what to defer (`Share`/`Manage access` rows in
   FileActions stay TODO; the *Shared with me* half of SharedScreen stays
   empty-stated; the multi-select *Share* card stays disabled).
2. Tells the FileService history work why `userId` is still deferred.
3. Gives a single doc to point at when "what about sharing with another
   Tenshu user?" comes up in design conversation.

Promote to a real proposal when (a) and (b) are being sized — at the latest,
when the App Console & Tenshu Store / Creator / Creations cluster starts
moving, because they need the same cross-realm primitive.
