# Identity Service

The Identity Service owns the **realm's identity records** — who the devices are, who the members
of a shared realm are, and what the owner is entitled to.

> ⚠⚠ **It is not an authentication service.** There is no `User`, no `Session`, no password, no
> OAuth and no identity provider anywhere in the platform, and there is no user directory to look
> anybody up in. An identity here is a keypair a device holds. A device joins the user's own realm
> by **pairing** (QR + short-authentication-string, `docs/sync/pairing-flow.md`); a *person*
> becomes reachable only through the mutual **invitation handshake**
> ([`contact-invitation-handshake.md`](../docs/proposals/contact-invitation-handshake.md)).
> This file previously described a `Login` / `SignUp` / `LoginWithProvider` surface over an
> `AuthProviderDelegate`. None of it exists, or ever shipped.

## 1. Core Models

All of these live in `identity-service/src/commonMain/.../identity/`. Each carries a KDoc naming
the design doc that owns its rationale — read that before changing a field.

**The user's own realm**

* **`Device`** — one of the user's realm devices: a device holding the master identity and volume
  keys, explicitly added by pairing.
* **`DeviceName.kt`** — not a composite: a `deviceName(deviceId)`-style *resolver* that reads the
  human-friendly name ("Pixel 8") out of the synced device registry, returning `null` when the
  registry is unreachable or the row has not synced. Callers fall back to the raw `deviceId`.
* **`DeviceLastSeen`** — per-device "last heard from" observations. ⚠ Locally derived from ingest
  and **never synced**; that is the point, not a limitation (see its KDoc).
* **`AdmissionKeyAnnouncement`** / **`AdmissionCertGrant`** — a device's announced admission
  (A33) public key, and the A1-holder's depth-1 capability-cert answer to it
  ([`delegated-device-admission-authority.md`](../docs/proposals/delegated-device-admission-authority.md)).

**Shared realms (cross-user)**

* **`SharedRealmMembership`** — *this* member's record of one shared realm it belongs to:
  `(sharedRealmId, wrappedMasterKey, role)`, the realm master key wrapped under the member's own
  realm-identity X25519 sub-key (A13).
* **`Member`** / **`MemberAuthorityKey`** — the in-realm member list of a shared realm. ⚠ Note the
  asymmetry with `SharedRealmMembership`: a `Member` row lives *in the shared realm* and answers
  "who is in this realm?", while a `SharedRealmMembership` lives in *each member's own personal
  realm* and answers "which shared realms am I in?".
* **`PendingGrant`** / **`GrantedMembership`** — a grant the inbound poll has discovered but the
  user has not yet accepted. The grant mailbox is single-pickup (destructive), which is why the
  discovered record is quarantined durably rather than acted on inline.
* **`OwnedRealmMaster`** — the owner's durable custody of a shared realm's master keypair (A1).
* **`RetainedRealmKey`** — a *retired* shared-realm content key kept so a member can still read
  content written before a rotation. Removing a member rotates the content key; the retained
  keyring is what stops that rotation from orphaning history.

**Entitlement**

* **`Entitlement`**, **`EntitlementRecordCredential`**, **`EntitlementSignature`**,
  **`EntitlementUnverifiedError`** — a Publisher-signed grant of a paid tier to an `ownerId`.
  Synced and owner-scoped, so a second device learns of a subscription bought on the first.

## 2. Relations and operations

The service exposes **six** `serviceDataRelation`s and only **three** operations. That ratio is
the convention, not an omission — see §3.

| Relation                    | Chain-readable | Notes                                              |
|-----------------------------|----------------|----------------------------------------------------|
| `DEVICES`                   | yes            | `IdentityService.devices`; prefer over `getDevices` for anything that should *track* the roster rather than sample it. |
| `DEVICE_LAST_SEEN`          | yes            | Read alongside `DEVICES` and joined by `deviceId`. |
| `SHARED_REALM_MEMBERSHIPS`  | no             | Dedicated-op read surface.                          |
| `MEMBERS`                   | no             | Dedicated-op read surface.                          |
| `PENDING_GRANTS`            | no             | Dedicated-op read surface.                          |
| `RETAINED_REALM_KEYS`       | no             | Dedicated-op read surface.                          |

Operations: **`GetDevices`**, **`GetRealmMembers`** (filters by `realmId`), and
**`GetSharedRealmMemberships`**. That is the whole operation surface.

⚠⚠ **The relation id must not be the element type's id.** Relation ids and composite type ids
share one namespace in the registry, so a relation named exactly after its element type silently
*replaces* that type's schema — and every `saveData` of the composite then dies with
"No DataIdProvider found … and it is not a simple value". This is why `DEVICE_LAST_SEEN` is
`"$ID/lastSeenObservations"` and not `"$ID/deviceLastSeen"`; `DEVICES`/`Device` escape it only by
the accident of English pluralisation.

## 3. Implementation Patterns

* **⚠⚠ The writes live in `:sync-service-proxy`, not here.** This service is read-mostly: it
  defines the records and exposes them, but pairing, revocation and grant acceptance are
  sync-service operations (`ApprovePairingRequest`, `RevokeDevice`, `CreateRealm`,
  `GrantSharedRealmMembership`, `AcceptSharedRealmGrant`, …) that write `Device` / `Member` rows
  and let the generic relation maintainer rebuild the arrays. If you are looking for "where does a
  device get added", it is `ApprovePairingRequestImplementation`, not anything in this module.
* **Generic relation maintenance**: the arrays behind every relation above are derived on each
  (local or sync-arriving) save by the generic maintainer — no bespoke write path per relation.
* **DTOs & ROs**: standard `-common` / `-client` / `-proxy` separation. The proxy module holds only
  the three `Get*Implementation` executors plus the `identityProxyImplementationFactory(id)` and
  `ExecutionContext.initIdentityService()` functions (⚠ lowercase functions; the file names are
  `IdentityProxyImplementationFactory.kt` / `IdentityProxyInitialization.kt`).
* **Schema version**: `service(id, version = 2)`. v2 added `Device.admissionPublicKey` — optional
  and appended **last**, so v1 rows parse unchanged and the registered migration keeps a v1 store
  inside its derived readable floor instead of wiping it (House Rule 9).
* **Stringless**: like every service module, this one holds no user-facing strings — it produces
  typed values that the rendering module localises ([ADR 0016](../docs/adr/0016-localisation.md)).
* **Testing**: `commonTest` covers the relation maintainers and the chain query
  (`DeviceRelationMaintainerTest`, `SharedRealmMembershipRelationMaintainerTest`,
  `GetDevicesRelationQueryTest`), with fixtures in `IdentityTestUtils.kt` (`org.tenshu.identity.proxy`). KMP module — the test task
  is `jvmTest` / `desktopTest`, never `test`.

## 4. Security

Every record here is an asset in [the threat model](../docs/sync/threat-model.md) — A1 (realm
master), A12/A13 (shared-realm keys), A24/A25 (the invitation handshake), A26/A27 (membership
grants). Changing what is stored, synced or put on the wire in this module requires a threat-model
amendment **in the same PR** (House Rule 2).
