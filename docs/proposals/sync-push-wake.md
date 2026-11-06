# Sync push wake — content-free APNs wakes from the relay

**Status:** v0.2 (2026-10-02). Design only, no code. ⭐ **The maintainer accepted every
recommendation in §12 the same day, and settled PW-D12 as **V0 now, V1 only if measured delivery is poor**.** This is phase **S4** of
[`ios-background-scheduling.md`](ios-background-scheduling.md), whose D4 (maintainer, 2026-10-02)
decided that push wakes are wanted, "coalesced and content-free". The threat-model amendment is drafted in Appendix A and lands with the code
(House Rule 2), not with this document.

**Relates to:** [`ios-background-scheduling.md`](ios-background-scheduling.md) §4.2, §5.4, §7 (D2,
D4, D5); [`ios-notification-elevation.md`](ios-notification-elevation.md) §5.3, §5.4, §12, §13;
[`sync-relay-api.md`](sync-relay-api.md) §2, §4.2, §9;
[`sync-relay-persistence.md`](sync-relay-persistence.md);
[`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §4.1 (device-key JWT);
[`docs/sync/threat-model.md`](../sync/threat-model.md) A6, A7, A32, A37, T-T-07, T-B-02, T-B-03,
T-GR-05, T-CI-06, T-MR-05; [`core-background-processing.md`](core-background-processing.md) §10.6
and `DrainSync`.

**How to read the claims.** *Read* means read in the code or the cited doc on 2026-10-02.
*Inferred* means concluded from what was read and not verified by running anything. *Apple* means a
platform rule taken from Apple's published APNs and UIKit documentation, to be re-checked against
the current documentation when the code is written.

---

## 1. What this is

A device that changes something tells the relay, as today. The relay then asks Apple to wake the
idle iPhones of the same realm. The woken app pulls through the ordinary sync routes. The push
says "pull" and nothing else.

| It is | It is not |
|---|---|
| a hint that shortens the wait for the next pull | a delivery guarantee (§8.5) |
| one fixed payload, identical for every user and every event | a carrier for block data, ciphertext, a realm id or a count |
| an adjunct beside the sync protocol ([`sync-relay-api.md`](sync-relay-api.md) §9) | a change to any existing route |
| iOS only in this document | FCM, web push, Live Activities, critical alerts (§11) |

## 2. What exists today (read)

| Fact | Where |
|---|---|
| The relay has no push token store, no APNs client and no outbound HTTP client except the S3 SDK | `sync-relay-server/` (routes under `routing/`, stores under `storage/`) |
| Device auth is a per-request EdDSA JWT, `iss = ownerId`, `sub = deviceId`, verified against the `device` table | `auth/JwtAuth.kt`, `auth/DeviceRegistry.kt` |
| The relay already stores each device's pull cursor | `device_cursor` (migration 17), written by `POST /sync/log` |
| A foreground client polls every 30 s | `SyncScheduler` (`pollingIntervalMs = 30000`) |
| A `Device` tombstone removes the `device` row. No other per-device row is deleted with it | `SyncRouting.kt` (`revokeIfNotStale`); no `DELETE FROM device_cursor` or `hlc_tip` exists |
| All first-party apps on one iPhone share **one** `deviceId` and one device key | [`ios-background-scheduling.md`](ios-background-scheduling.md) §2 |
| Control's wake posts a **local** notification for a pending request, with generic PII-free copy | `IosPendingRequestNotifier`, `ControlSurface.runPendingRequestRefresh` |
| Drive is the only first-party iOS app with a `UIApplicationDelegateAdaptor` | `drive-ios/Sources/DriveIosApp.swift` |
| No app declares `aps-environment` or the `remote-notification` background mode | every `*-ios/project.yml` and `*.entitlements` |
| The drain entry is `SyncScheduler.drainNow()`, single-flight, returns `Unit` | `sync-service-proxy` |
| Relay secrets are lines in `/opt/tenshu/deploy.env` on the box, several added by hand | `deploy/scaleway-relay/README.md` |
| The relay database is backed up nightly and kept 30 days | `deploy/scaleway-relay-backups/variables.tf` (`retention_days`) |

## 3. Threat model

### 3.1 What the relay newly holds

| Item | What it is | Why it matters |
|---|---|---|
| Push token | Apple's opaque address for one app install on one phone | Apple can resolve it to a device and an Apple ID. It is the first relay-held identifier that a third party can turn into a person. |
| Token to `(ownerId, deviceId)` link | the row that says which realm a token wakes | With Apple's help it de-anonymises a realm. `ownerId` is otherwise a hash of a public key. |
| Topic (bundle id) per token | which app holds the token | With one token per app this is an app inventory, the thing A37 and T-SE-01 keep off the relay (§7). |
| APNs provider key (`.p8`, ES256) | the credential that lets the relay send pushes to the team's apps | The first signing secret the relay holds. Whoever has it and a token can send any push, including a visible alert (§3.3). |
| `last_sent_at` per token | one timestamp, overwritten | Needed so the floor between wakes survives a restart. No send history is kept. |

The relay learns **no new timing**. It sends the push, and it already sees every push and pull
([`ios-notification-elevation.md`](ios-notification-elevation.md) §12, adopted). That section says
the observer that changes is Apple, which is right for timing. It does not cover the token link
above, which is new relay-held state. This document adds that.

### 3.2 What Apple newly learns

| Apple learns | Detail |
|---|---|
| that this device has something to fetch, and when | at the resolution of the floor between wakes (§4), not per block |
| which bundle id | the topic header on every send |
| that the token belongs to a Tenshu realm that is active while the phone is idle | Apple minted the token and holds its link to the device and Apple ID |
| the relay's address and send volume | one HTTP/2 connection from the relay host |

Apple does **not** learn the `ownerId`, the `deviceId`, which device made the change, what kind of
change it was, how many changes there were or how large. None of these is sent (§3.4).

### 3.3 What each adversary gains

| Adversary | Gains | Does not gain |
|---|---|---|
| **Compromised relay (AD5)** | The token set, so a list of realms that have an iPhone. The provider key, so it can send any push to a registered token: wake storms that burn battery and the app's background budget, and ⚠ **a visible alert with text it chooses, shown under the app's name** to a user who granted that app notification permission. It can also withhold wakes. | Any plaintext, any key, any ability to forge a block. Withholding wakes is weaker than withholding blocks, which it can already do (T-T-04). |
| **Apple under legal compulsion (AD4 through Apple)** | For a named Apple ID: that Tenshu Control is installed with push on, and a log of wake times. Apple has confirmed that governments request push records. | Which realm, which other devices, any content. |
| **Relay data plus Apple together** | A named person for an `ownerId`, then the realm's whole A6 metadata history under that name. This is the same class of join as A30 (account to `ownerId`) and it survives the strong-variant entitlement that removes A30. | Content. |
| **Network observer at the relay** | A sharper same-realm correlation: device X pushes, and seconds later device Y pulls. Today Y pulls on its own 30 s clock or not at all. | Anything inside TLS. Jitter and the debounce blur the correlation and do not remove it. |
| **Network observer at the phone** | Nothing new. Apple's push connection is shared by every app on the phone, and the pull that follows looks like any sync. | |
| **A stolen provider key without relay data** | Nothing usable. A push needs a token. | |

⚠ The visible-alert capability is the one a reader will miss. `apns-push-type` is chosen by the
sender, so a key provisioned for silent wakes can also send alerts, and iOS shows an alert without
running app code. The app cannot filter it. What bounds it: the alert carries text only, a tap
opens the app and nothing else, and the app must **never act on the payload of a remote
notification** (no deep link, no URL, no state change from `userInfo`).

### 3.4 What a push must never carry

House Rule 1 holds trivially: the relay reads nothing new and sends nothing it stores. The whole
request, for every user and every event:

```
POST /3/device/<token>
apns-topic:       app.tenshu.control.ios
apns-push-type:   background
apns-priority:    5
apns-expiration:  <now + 1 h>
apns-collapse-id: tenshu.wake
{"aps":{"content-available":1}}
```

- ⚠⚠ `apns-collapse-id` is a **constant**. Deriving it from an owner, a realm, a block or an event
  kind would hand Apple an identifier.
- ⚠ `apns-id` is left for APNs to assign. It is never derived from relay state.
- ⚠ No custom payload key. Not a cursor, not a count, not a "kind". A field added "so the client
  can skip a pull" is the first step away from content-free.
- ⚠ The body has a fixed length, so its size says nothing.

### 3.5 Which asset each item falls under

| Item | Existing asset? | Proposal |
|---|---|---|
| Wake timing visible to Apple | A6 (metadata), T-T-07, T-B-03 | An A6 amendment. Same kind of leak, new observer. |
| Token and its owner/device link | Not A7 (a session token authenticates to the relay and is not load-bearing). Not A6 (A6 lists what is seen in passing, and this is a durable identifier a third party resolves to a person). Closest precedent: A30. | **New asset.** |
| APNs provider key | None. A29 is the closest shape, but the relay holds only A29's public half. | **New asset.** |
| The `/sync/wake/token` routes and the relay-to-APNs egress | none | **New attack surface.** |

Asset numbers: A41 and A42 are free in the threat model today (A36 is claimed by
`drive-folder-link-sharing.md`, A22 and A23 are reserved). Take the next free numbers when the code
lands. Appendix A uses A41 and A42 as placeholders.

### 3.6 Two things the relay cannot wake for without breaking an existing defence

- ⚠⚠ **A shared-folder invitation.** The grant mailbox is addressed by an HKDF of the handshake
  secret and picked up with no JWT, so the relay does not know which owner a grant is for (read:
  `MembershipGrantRouting.kt`). That anonymity is the defence in T-GR-05. A relay that wakes the
  grantee must first be told which token belongs to which grant address, and that is the
  grantor-to-grantee edge T-GR-05 says the relay never sees. No encoding avoids it: the relay has
  to map the event to a token, and the token maps to an owner.
- ⚠⚠ **A change in a shared realm.** T-GR-05 says the relay sees a member only as a realm-local
  device id under a realm-local authority key, never the member's personal `ownerId` (read in the
  threat model; not checked against client code). Registering the phone's one APNs token under
  both the personal realm and a shared realm lets the relay join them on the token, which gives it
  the co-membership graph.

Both are decisions (§12, PW-D4 and PW-D5). The recommendation is to wake for neither. A wake for
any personal-realm event drains every realm and polls the grant mailbox, and the refresh task
remains the floor.

### 3.7 Consistency with earlier text

| Text | Standing |
|---|---|
| T-B-02 (withdrawn): "each app registers its own OS background trigger and wakes itself", no reverse trigger, IPC stays app to Control | Holds. A push token is an OS trigger the app registers for itself. No IPC direction is added and no app starts another. |
| I9, no headless escalation | Holds if the handler dispatches only `HeadlessSafe` work (`DrainSync` carries the trait). Accepting or approving a request is never run from a wake. |
| [`sync-relay-api.md`](sync-relay-api.md) §4.2 and §9: "avoids OS-native push for now", "deliberate non-goal" | **Superseded by D4.** Its framing survives: an adjunct that triggers the regular endpoints. Both sections owe a pointer here when the code lands (Appendix B). |
| [`ios-notification-elevation.md`](ios-notification-elevation.md) §5.4: "Already rejected one level down" | **Superseded by D4.** §12's two framings are adopted, with the addition in §3.1. Its D3 and its PII rule (§13.3) bind §9 of this document. |
| Threat model §11 item 5: cover traffic is a v1 non-goal | Holds. Jitter here is a blur, not cover traffic. |

## 4. Mitigations and their cost

| Mitigation | What it does | Cost | Recommendation |
|---|---|---|---|
| Collapse (`apns-collapse-id`) | APNs keeps one undelivered wake per device, not a queue | none | yes, constant id |
| Floor between wakes per token | Apple sees "activity in this window". Also keeps inside Apple's guidance of two or three background pushes an hour, past which iOS throttles the app (Apple). | a second change inside the window waits for the window or for the refresh task | **20 min** (PW-D3) |
| Debounce | a burst of blocks becomes one wake | adds the debounce to every wake | **10 s** |
| Jitter | blurs the push-to-wake correlation for a network observer and for Apple | adds latency, hides nothing about the fact of activity | **uniform 0 to 30 s** |
| Wake only a device that has not pulled since the event | a foreground phone is never pushed, so Apple's log has no entries while the user is using the app | the relay keeps a last-pull time per device in memory | yes (PW-D6) |
| Wake on every block | none. It is not camouflage, it hands Apple the whole activity timeline ([`ios-notification-elevation.md`](ios-notification-elevation.md) §12) | throttled by iOS within the hour | **no** |
| User switch | a user who does not accept the Apple timing leak turns it off | sync on that phone falls back to the refresh task | yes, default is PW-D2 |
| One token per phone, not per app | no app inventory at the relay, one Apple log entry per wake | only one app is woken (§7) | yes (PW-D1) |
| Token deletion | see §5.4 | a deleted token stays in nightly backups for up to 30 days | yes |
| No send log | the relay keeps one overwritten timestamp per token and logs sends as a count | less to debug with | yes |

### 4.1 The coalescing rule

Stated as behaviour. The names are placeholders.

1. **Events.** An accepted `POST /sync/push` or `POST /sync/push-tombstone` for owner *O* from
   device *A*, and (PW-D4) an accepted `POST /sync/invite/response` for an invite owned by *O*.
2. **Targets.** Every wake token of *O* whose `deviceId` is not *A*. ⚠ Because the apps on one
   iPhone share one `deviceId`, this also keeps a phone from waking itself.
3. **Live device.** If the target device made an authenticated pull (`/sync/log` or
   `/sync/changes`) in the last 75 s (two and a half foreground poll intervals), it is polling.
   Do not send. Re-check when the 75 s lapse: if it pulled after the event, drop the wake,
   otherwise continue. ⚠ This applies to block and tombstone events only. An invite response
   always continues, because a foreground Drive pulling under the shared device id does not post
   Control's alert.
4. **Schedule.** Send at `max(event + debounce, last_sent_at + floor) + jitter`. If a wake is
   already scheduled for the token, do nothing.
5. **Cancel.** A pull from the device after the event cancels the scheduled wake.
6. **State.** Scheduled wakes and last-pull times are in memory. A restart loses them, and the
   refresh task covers the gap. `last_sent_at` is in the table.

With these numbers the first change after a quiet period reaches an idle phone in 10 to 40 s plus
Apple's delivery time, and later changes arrive at most once per 20 min.

⚠ The relay uses the last pull **time**, not `device_cursor`. The stored cursor is the `since` of
the last request, so it always trails the newest page and would read every device as behind.

## 5. Wire

### 5.1 Routes

All three are new and additive. ⚠ The prefix is `/sync/wake`, not `/sync/push`: `/sync/push` is
the block-push namespace (`/sync/push`, `/sync/push/probe`).

**`PUT /sync/wake/token`** registers, rotates and refreshes.

| | |
|---|---|
| Auth | device-key JWT. The row is keyed by the JWT's `iss` and `sub`. Neither appears in the body. |
| Body (JSON) | `provider` (`"apns"`), `environment` (`"production"` or `"sandbox"`), `topic` (bundle id), `token` (hex) |
| `204` | stored. The same body again is a no-op that moves `refreshed_at`. A different token for the same `(owner, device, provider, topic)` replaces the old one. |
| `400` | malformed JSON, unknown provider, an environment or topic outside the relay's allowlist, a token that is not hex of a plausible length. Typed error body ([`sync-relay-api.md`](sync-relay-api.md) §5.2). |
| `401` | missing, expired or unknown-device JWT, exactly as every other route (`JwtAuthenticator`) |
| `404` | this relay has no push wake (older build, or no APNs configuration) |
| `429` | more than 10 registrations an hour from one device. A token changes rarely. |

- ⚠ The token is in the **body**, never the path. The relay logs one line per request with the
  path.
- A token is unique per `(provider, environment)`. Registering a token another row holds **moves**
  it: the other row is deleted. A phone reset and paired into a different realm must stop waking
  for the old one. ⚠ Cost: a realm device that learned another phone's token can steal its wakes
  or point its own realm's wakes at that phone. Tokens are known to the app, the relay and Apple
  only, so this is a nuisance channel and not a disclosure. Rejecting the conflict would strand a
  re-paired phone until the old row expired.
- Not entitlement-gated. Pulls are not gated ([`commerce-billing-plane.md`](commerce-billing-plane.md)
  §9 Q5), and a wake only causes a pull.

**`DELETE /sync/wake/token?provider=apns&topic=<bundle id>`** removes this device's row. Without
`topic` it removes every row of this device. `204` whether or not a row existed, so it is
idempotent and no existence oracle. `401` and `404` as above.

**`GET /sync/wake/token`** lists this device's registrations as `provider`, `environment`, `topic`,
`refreshedAt`. It never returns a token. The settings screen and the verify steps in §10 use it.

### 5.2 Unknown, removed and fenced devices

| Device state | Register | Send |
|---|---|---|
| Unknown or removed (no `device` row) | `401`, like any route | never. The sender re-checks `DeviceRegistry.getPublicKey` at send time, so a row that outlived its device is inert. |
| Removed while registered | the `Device` tombstone path deletes its rows (§5.4) | never |
| Fenced by the schema epoch | accepted | sent. ⚠ The fence is client-side (`SchemaPullFence`), so the relay cannot see it. A fenced phone is woken, refuses to pull, and must report "nothing run" and end. The wake is wasted and harmless. |
| Over the auth-failure budget | `401` with `Retry-After`, unchanged | unaffected |

⚠ In dev mode `requireOwnerId` accepts `X-Dev-Owner` and returns no device id. These routes need
the device, so they follow `authenticateAndRecordTip` and read `X-Dev-Device`.

⚠ [`sync-relay-api.md`](sync-relay-api.md) §2 says state-changing endpoints reject a re-used JWT
nonce. No nonce store was found in the relay code, so a captured JWT can be replayed for up to
five minutes (inferred from the absence). A replayed `PUT` is a no-op. A replayed `DELETE` removes
a registration the client restores at its next launch. This design does not depend on nonces.

### 5.3 No `RELAY_PROTOCOL_VERSION` bump

`RelayProtocol.kt` is normative: "Purely additive routes do NOT need a bump — a client that never
calls a new route cannot be broken by its existence, and an old relay's 404 is already handled as
'unsupported' at the call sites that probe". These are three new routes. No existing route changes
its meaning, body or status codes, and the envelope, auth and blob-reference format are untouched.
`/sync/push` gains a side effect its caller cannot observe. So the constant stays at 3 and House
Rule 9's last paragraph applies: no coordinated redeploy. The relay ships first, and clients follow
at their own pace.

- ⚠ [`ios-background-scheduling.md`](ios-background-scheduling.md) §5.4 said this was "a relay
  protocol change (`RELAY_PROTOCOL_VERSION`), so a coordinated deploy". That contradicted the rule
  above and is corrected in the PR that adds this document.
- ⚠ The client must read `404` as "unsupported, stop asking until next launch". It is not a
  failure, and it must not feed the `401` self-repair path.
- ⚠ If the registrar is exposed as a schema `Operation`, House Rule 9 and the op-permission entry
  apply to the owning service. Recommendation: keep it proxy-internal, called from platform code,
  with no client-facing operation (inferred to be possible; check when writing it).

### 5.4 Storage

One table in the relay database, added as the next migration (21 is the newest today), behind a
store interface with an in-memory twin like every other relay store.

```sql
CREATE TABLE wake_token (
    owner_id      TEXT    NOT NULL,
    device_id     TEXT    NOT NULL,
    provider      TEXT    NOT NULL,   -- 'apns'
    environment   TEXT    NOT NULL,   -- 'production' | 'sandbox'
    topic         TEXT    NOT NULL,   -- bundle id
    token         BLOB    NOT NULL,
    registered_at INTEGER NOT NULL,
    refreshed_at  INTEGER NOT NULL,
    last_sent_at  INTEGER,            -- one value, overwritten
    PRIMARY KEY (owner_id, device_id, provider, topic)
);
CREATE UNIQUE INDEX wake_token_by_token ON wake_token(provider, environment, token);
```

A row is deleted when:

| Trigger | How |
|---|---|
| the device turns push wake off, signs out or resets | `DELETE /sync/wake/token` |
| the device is removed from the realm | in `DeviceRegistry.revoke`, so both callers are covered: the `Device` tombstone (`revokeIfNotStale`) and shared-realm member removal |
| Apple says the token is dead | `410 Unregistered`, `400 BadDeviceToken`, `400 DeviceTokenNotForTopic` (§6.3) |
| the token moved to another device | the unique index (§5.1) |
| the row was not refreshed for 60 days | a sweep beside the relay's other GC jobs. A live client refreshes at least weekly (§8.2). |
| the realm is removed | ⚠ **No relay path that deletes a realm's `device` rows was found.** `AccountClosureReclaimer` reclaims content only. Until one exists, a closed account's rows end by the 60-day sweep. A future realm-removal path must include this table. |

⚠ The nightly database backup keeps 30 days, so a deleted token remains in backups for up to 30
days. The provider key is not in the database and is never backed up with it.

## 6. Sending

### 6.1 Shape

Two pieces, so a second provider is an added implementation and not a rewrite. Only APNs is
specified.

| Piece | Job |
|---|---|
| Coalescer | §4.1: decides whether and when a token is woken. Knows nothing about Apple. |
| Sender (one per provider) | takes `(environment, topic, token)`, returns one of: sent, token gone, slow down, provider rejects us, try later |

### 6.2 APNs request (Apple)

| | |
|---|---|
| Transport | HTTP/2, one long-lived connection per host. The JDK's `java.net.http.HttpClient` speaks HTTP/2 and the relay runs on Java 21, so no new dependency is needed (inferred; the relay has no such client today). |
| Hosts | `api.push.apple.com` for production tokens, `api.sandbox.push.apple.com` for sandbox tokens, chosen per row |
| Auth | provider token: a JWT signed ES256 with the `.p8` key, header `kid` = key id, claim `iss` = team id, claim `iat`. Minted by the relay and reused for about 50 min. Apple rejects a token older than 60 min and rejects re-minting more often than every 20 min. |
| Headers and body | exactly §3.4 |
| Priority | `5`. Apple requires it for a background push. `10` is an error. |
| Expiry | now + 1 h, so a phone that was offline gets one wake when it reconnects. `0` (deliver now or drop) is the alternative (PW-D10). |

### 6.3 What a response does

| APNs answer | Action |
|---|---|
| `200` | write `last_sent_at` |
| `410 Unregistered` | delete the row, unless `refreshed_at` is newer than the timestamp Apple returns (the client re-registered since) |
| `400 BadDeviceToken`, `400 DeviceTokenNotForTopic` | delete the row. ⚠ A sandbox token sent to the production host also answers `BadDeviceToken`, so a wrong `environment` looks like a dead token. Count these. |
| any other `400` (`BadCollapseId`, `BadTopic`, `PayloadEmpty`, ...) | a relay bug or configuration error. Keep the row, log the reason without the token. |
| `403 ExpiredProviderToken` | mint a new provider token, retry once |
| `403 InvalidProviderToken` | the key, key id or team id is wrong. Stop sending until restart and log an error. ⚠ Do not add this to `GET /health`: its body is fixed by design (T-T-09). |
| `429 TooManyRequests` | Apple is throttling this device token. Double this token's floor for its next sends. No retry. |
| `429 TooManyProviderTokenUpdates` | keep the cached provider token |
| `500`, `503`, connection reset, `GOAWAY` | reconnect and retry with backoff up to three times, then drop. The refresh task is the floor. |

⚠ Log lines and metrics carry the reason and a count. Never the token, the owner or the device.

### 6.4 Configuration and where the key lives

| Setting | Meaning |
|---|---|
| `APNS_KEY_FILE` | path to the `.p8` inside the container |
| `APNS_KEY_ID`, `APNS_TEAM_ID` | the key's id and the team id. Identifiers, not secrets. |
| `APNS_TOPICS` | allowlist of bundle ids the relay will register and send to |
| `APNS_ENVIRONMENTS` | `production`, `sandbox` or both |
| `WAKE_FLOOR_MS`, `WAKE_DEBOUNCE_MS`, `WAKE_JITTER_MS` | §4, with the defaults given there |

- All of key file, key id, team id and topics, or none. A partial set makes the relay refuse to
  start, as a partial `S3_*` set does. None means the `/sync/wake` routes do not exist and answer
  `404`, the precedent `RELAY_OPERATOR_TOKEN` set for the operator routes.
- **The key is a file on the relay box**, owner root, mode `0600`, bind-mounted read-only into the
  container. Not an environment variable: `deploy.env` is written `0644` by cloud-init, a
  container's environment is readable through `docker inspect`, and a multi-line PEM does not
  belong in an env file.
- **Who can read it:** root on the relay host (the maintainer), and the hosting provider with
  physical access. Keep it out of Terraform variables so it never enters Terraform state. Place it
  by hand, as the `S3_*` lines are.
- ⚠⚠ `docker compose --env-file` feeds substitution only. Each `APNS_*` and `WAKE_*` name must be
  declared in the `environment:` block of `sync-relay-server/docker-compose.yml`, and the key
  mount in `volumes:`, or the relay starts without push wake and nothing says so. This is the
  2026-09-13 `S3_*` failure again.
- ⚠ `deploy/scaleway-relay/README.md` ("Rebuilding this instance is an outage procedure") lists
  what cloud-init cannot restore. The key file and the `APNS_*` lines join that list.
- Apple lets a `.p8` be downloaded once. The original goes in the maintainer's password manager.
  Rotation: create a second key in the developer portal, switch file and key id, revoke the first.
- To check in the developer portal: whether the team can create a key limited to one topic and
  one environment. If it can, use a production-only key for Control's topic on the production
  relay. That narrows what a stolen key can address. Not verified here.

## 7. Which app holds the token

iOS wakes only the app whose token was pushed. With the per-item lease (D2) any woken app can run
the drain, so the question is which app the relay should be able to wake.

| | One token per installed app | One designated app (Control) | Most recently used app |
|---|---|---|---|
| Relay learns | which first-party apps are installed with push on: an **app inventory**, the thing A37 and T-SE-01 moved off the relay | that the device is an iPhone | which app the user used last, and a registration every time that changes: an app-usage trace |
| Apple learns per event | one log entry per app, or the relay has to pick one | one entry | one entry |
| Woken per event | up to ten processes racing for one lease, nine of them wasting their own background budget | one | one |
| Works if the user never opens the holder | yes, another app holds one too | depends on how iOS treats a rarely opened app (below) | yes by construction |
| The pending-request alert appears | only if Control is among the woken | yes | only when Control was the last app used |
| Token churn | low | lowest | a registration and a deletion per app switch |

**Recommendation: Control holds the one token.** Reasons, in order:

1. The alert for a pending request is a local notification, and a local notification belongs to
   the app that posts it. [`ios-notification-elevation.md`](ios-notification-elevation.md) §5.3
   rejected Drive posting Control's alerts. So the S4 verify step ("a pending request reaches a
   backgrounded Control") needs **Control** to be the woken app whichever option is chosen.
2. Control is installed on every iPhone that has a realm: the other apps adopt its identity.
3. It gives the relay the least: no inventory, no usage trace.
4. A woken Control is not an "owner" in D2's sense. It takes the sync-drain lease like any app and
   skips when another process holds it.

⚠ **This is in tension with the reason given for D2**, "iOS wakes the apps the user uses, and
nobody opens Control". That reason was about `BGAppRefreshTask`, where iOS budgets by how often
the app is opened. For a background push Apple documents throttling by volume and energy, not by
app usage. Whether a rarely opened Control is deprioritised anyway is **not known** and is
measured in P2 (§10): count sends at the relay against handler runs on the phone for a week. If
the delivered share is poor, the fallback is a second row for the most recently used app, accepted
with its metadata cost. The table in §5.4 already allows one row per topic.

A rarely opened app has one advantage: it is rarely in the app switcher, so it is rarely
force-quit, which is the state that blocks delivery outright (§8.5).

## 8. Client behaviour

### 8.1 Declarations

| What | Where | Note |
|---|---|---|
| `aps-environment` entitlement | `control-ios/Control.device.entitlements` and the simulator one | `development` for a build run from Xcode, `production` for TestFlight and the App Store. Needs the Push Notifications capability on the app id in the company team. |
| `remote-notification` in `UIBackgroundModes` | `control-ios/project.yml`, beside `fetch` | ⚠⚠ In `project.yml`. `Info.plist` is generated and gitignored. |
| A `UIApplicationDelegateAdaptor` | `control-ios` | ⚠ Control has none. The token callbacks and the remote-notification handler are app-delegate methods. |
| The environment the build was signed for | one build setting used both in the entitlement and in an `Info.plist` key the app reads | ⚠ Do not derive it from `#if DEBUG`. A Release build installed from Xcode with a development profile gets a **sandbox** token, and a wrong environment reads at the relay as a dead token (§6.3). |

### 8.2 Registration

- Ask iOS for a token at every launch once the realm is provisioned and the switch is on
  (`registerForRemoteNotifications`). A silent push needs **no** notification permission, so this
  shows no prompt (Apple).
- Send `PUT /sync/wake/token` when the token, environment or topic differs from the last one the
  relay acknowledged, or when that acknowledgement is older than 7 days. Keep the last
  acknowledged value in device-local storage. It is not synced.
- `404`: the relay has no push wake. Stop until next launch. `401`: the ordinary device-not-known
  path, nothing special. Network failure: retry with the next launch or wake.
- Turning the switch off: `DELETE`, then `unregisterForRemoteNotifications`. ⚠ Persist a
  "deletion owed" flag and retry until `204`. Off must mean the relay holds no token for this
  phone, and a setting that only stops listening would be a lie.
- Sign-out, realm reset, deprovision: `DELETE` first, while the device key still exists. The
  relay's revoke path deletes the row in any case.

### 8.3 The handler

`application(_:didReceiveRemoteNotification:fetchCompletionHandler:)` gives about 30 s (Apple).

1. Ignore the payload. Nothing in `userInfo` is read (§3.3).
2. Install the runtime headless (D5). If the realm is sealed, end with "failed" and run nothing.
   Two sealed cases exist and both are legitimate: the phone has not been unlocked since boot (the
   baseline Keychain class is `AfterFirstUnlockThisDeviceOnly`, read in A32 and
   `SecurityFrameworkKeychain.kt`), and the user chose the user-presence gate (A32), which cannot
   be satisfied without a person. ⚠ Do not try to unlock.
3. Take the sync-drain lease (S1). If another process holds it, end with "no data": that process
   is draining.
4. Drain through `DrainSync`. ⚠ Not `forceDrainOnce`: a wake is not consent to sync over
   cellular, and the Wi-Fi-only gate stays in force.
5. Poll the pending-request sources and let `IosPendingRequestNotifier` post what is new.
6. Refresh the registration if it is due (§8.2).
7. Report truthfully and stop by 25 s: "new data" if the drain applied something, "no data" if it
   ran and found nothing or was gated, "failed" if it was sealed, offline or out of time.
   iOS uses the answer and the time taken to budget future wakes (Apple).

⚠ Step 7 needs something that does not exist. `SyncScheduler.drainNow()` returns `Unit`, and the
two existing drivers wait a fixed window and report success regardless (`BackgroundSync.swift`,
`ControlBackgroundRefresh.swift`). The drain has to return whether it applied anything, whether
more remains and whether a gate stopped it. [`ios-background-scheduling.md`](ios-background-scheduling.md)
§5.1 already asks for this, so it is owed once and used by both. Whether one `drainNow()` exhausts
a multi-page backlog was not checked.

### 8.4 Order relative to S0 and S1

| S4 piece | Needs | What it delivers without it |
|---|---|---|
| Relay: store, routes, coalescer, sender (P1) | nothing | can be built and deployed in parallel with S0 and S1 |
| Control: token, handler in today's form (P2) | nothing. The handler is `runPendingRequestRefresh`, as the refresh task runs it today. | the pending-request alert arrives promptly. ⚠ Without S0 each app has its own store on a real iPhone, so a wake fills **Control's** store only. Drive still shows the change only after Drive itself syncs. |
| Handler runs the leased drain and reports truthfully (P3) | S0 (one store), S1 (the lease), the drain result of §8.3 | this is the phase where "a change made elsewhere is in Drive on an idle iPhone" becomes true |

Without S1, a wake that arrives while another Tenshu app is in the foreground runs a second drain
in a second process. That is today's "every process runs every job" state and no worse. The
live-device rule (§4.1) makes it rare.

### 8.5 iOS limits, stated plainly

- A background push is **throttled**. Apple's guidance is two or three an hour, and iOS may delay
  or drop more.
- It is **not delivered to an app the user force-quit**, until the user opens the app again or
  restarts the phone.
- It may be **dropped or deferred in Low Power Mode**, and it is not delivered while Background
  App Refresh is off for the app or for the phone.
- It is delivered **at most once**, and never when the token is stale.

So nothing may depend on a wake arriving. Every piece of state the wake updates is also updated by
the refresh task and by the next foreground launch. The relay keeps no "was woken" state that a
correctness decision reads. A user-facing status must not say "up to date" because a wake was
sent. Settings can read `UIApplication.backgroundRefreshStatus` and say when iOS is blocking
wakes.

## 9. Visible notifications (optional sub-phase, not decided)

A pending request deserves something the user sees. The relay cannot read content. Four options,
and the choice is the maintainer's (PW-D12, decided 2026-10-02: **V0**, see §12).

| | What it is | Reveals | Costs | Fails when |
|---|---|---|---|---|
| **V0. Local notification after the silent wake** | Nothing new. The wake runs Control, and Control posts the notification it already posts (`IosPendingRequestNotifier`), with the same generic copy. | nothing beyond §3 | nothing beyond P2 | the wake is not delivered (§8.5). The alert then appears at the next refresh task or launch. |
| **V1. Generic alert push from the relay** | The relay sends `apns-push-type: alert` with a fixed localised string ("Open Tenshu Control") for request-class events. iOS shows it without running the app, including for a force-quit app. | To Apple: the **class** of event. An alert means a request arrived, a silent wake means ordinary sync. | The relay must classify events, and the text is the relay's to send. Relay-sent alerts become normal, which turns the AD5 alert capability of §3.3 from an anomaly into something users expect. An alert cannot be withdrawn when the request is handled on another device, so it can be stale. Needs notification permission. | permission is denied |
| **V2. Encrypted payload, decrypted by a Notification Service Extension** | The sending client seals a short text. The relay forwards the opaque blob with `mutable-content` and a generic fallback line. An extension holding realm keys through the shared Keychain group rewrites the text on the phone. | To Apple: the class, plus a ciphertext it stores until delivery. This is the first realm ciphertext to leave the relay for a third party. | A second key-holding process on the phone (widens A32). An extension has a small memory limit and about 30 s, and whether the Kotlin runtime fits was not measured. A 4 KB payload limit. D4's "content-free" is relaxed for this class. | the extension cannot decrypt (sealed realm, phone not unlocked since boot). The fallback line shows. |
| **V3. Alert with no payload, the extension pulls** | As V1, but the extension pulls from the relay and writes the text. | as V1 | as V2 without ciphertext through Apple, plus a sync client inside the extension. An extension cannot suppress an alert without a special Apple entitlement, so a stale alert still shows. | as V2 |

Facts that bear on the choice:

- ⚠⚠ **The detail V2 and V3 would add is forbidden on a lock screen today.**
  [`ios-notification-elevation.md`](ios-notification-elevation.md) §13.3 pins by test that the
  alert never shows an invite's claimed name, fingerprint or SAS, nor a grant's grantor
  fingerprint, because a claimed name is quarantined until the SAS is confirmed. A pairing
  request's device name is self-asserted as well. For the request classes that exist, decrypted
  text may say nothing more than the generic line.
- ⚠ **Who can trigger an alert.** `POST /sync/pairing/request` is unauthenticated and addressed by
  `ownerId`. `POST /sync/invite/response` is anonymous and bounded to one response per invite. A
  relay that sends visible alerts on the first lets anyone who knows an `ownerId` put a
  notification on the user's lock screen.
- V0 costs nothing and is in place once P2 ships. V1 to V3 exist to cover the cases where a
  silent wake is not delivered.

## 10. Phases

Each verify step needs an iPhone, an Android phone in the same realm and the relay.

| | What | Needs | Verify |
|---|---|---|---|
| **P0** | The decisions in §12. The amendment text agreed (Appendix A). | this document | ✅ 2026-10-02: PW-D1 to PW-D11 and PW-D13 answered (§12). P1 needs D9, D10 and D11 as much as D1–D8 |
| **P1** | Relay: `wake_token` store, the three routes, the coalescer, the APNs sender, configuration, deletion on device removal. Threat-model amendment in the same PR. Key provisioned on the box. | P0, the Push Notifications capability and a key in the company team | Uses a throwaway diagnostic patch to Control that is not merged: the entitlement, the background mode, a line that prints the token and a line that prints on receipt. (a) Run that build from Xcode, read the token from the console and register it by hand with `PUT /sync/wake/token` (`sandbox`). Background Control, do not force-quit it. Rename a file on Android. The receipt line prints on the iPhone within a minute. (b) Rename three more files within a minute: no further wake for 20 min. (c) Keep Control in the foreground and rename a file: the relay's send counter does not move. (d) Remove the iPhone from the realm on Android: `GET /sync/wake/token` from the iPhone answers `401` and the row is gone from the relay database. |
| **P2** | Control: entitlement, background mode, app delegate, registrar, handler in today's form (§8.4), the settings switch | P1 | (a) Lock the iPhone with Control in the background. On Android, redeem a contact invite the iPhone's user issued. The "pending request" notification appears on the locked iPhone in under a minute. (b) Turn the switch off: `GET /sync/wake/token` lists nothing, and repeating (a) shows nothing until Control is opened. (c) Force-quit Control and repeat (a): nothing arrives, then open Control and the request is there. (d) Leave it a week and compare the relay's send count with the handler's run count: this is the measurement PW-D1 waits for. |
| **P3** | The handler takes the sync-drain lease, runs `DrainSync` and reports truthfully | S0, S1, the drain result (§8.3) | (a) Upload a photo on Android while the iPhone is locked and idle. Wait two minutes, put the iPhone in airplane mode, then open Drive: the photo's row is there. (b) With Drive in the foreground, trigger a wake by hand: the log shows one drain, and Control's handler ends "no data". |
| **P4** | Visible notifications | PW-D12. ⚠ For V1 to V3, a **threat-model amendment in the same PR**: they end Appendix A's constant, content-free payload (V1 shows Apple the event class; V2 and V3 add ciphertext or an extension that holds keys) | depends on the option chosen |

P1 changes the relay and the security contract. P2 and P3 change clients only.

## 11. Out of scope

| | Why |
|---|---|
| FCM and Android | Android wakes itself (WorkManager, the expedited drain). The sender split in §6.1 and the `provider` column leave room. Nothing else is specified. |
| Web push | A different trust boundary (browser vendors), and the web runtime has no background story yet. |
| Live Activities and widgets | Deferred in `home-and-mia-apps.md`. A Live Activity push is a content push. |
| Critical alerts | A separate Apple entitlement and a different justification. |
| Waking for shared-realm changes and grant drops | §3.6, unless PW-D4 or PW-D5 say otherwise. |
| Long-poll on `/sync/log` | [`sync-relay-api.md`](sync-relay-api.md) Phase 5, not started. It serves the foreground and does not replace a wake. |

## 12. Decisions (maintainer, 2026-10-02)

⭐ **Accepted as recommended: PW-D1 to PW-D11 and PW-D13.** The *Recommendation* column is the
decision. ⚠ PW-D1 was accepted knowingly against D2's argument that nobody opens Control: the token
is Control's alone until the P2 delivery measurement says a second row is needed. **PW-D12: V0 now** (below).

| # | Decision | Options | Recommendation |
|---|---|---|---|
| **PW-D1** | Which app holds the token | (a) Control only. (b) every installed app. (c) the most recently used app. | **(a)**, with the P2 measurement deciding whether (c) is added as a second row (§7). ⚠ In tension with D2's stated reason. |
| **PW-D2** | The user switch and its default | (a) on by default, with a switch and one sentence of disclosure in Control's settings. (b) off by default, an election like MapKit. | **(a)**, and the amendment must say it departs from the opt-in precedent of T-MR-05 and T-B-01. The case for (a): the payload is content-free, the resolution is 20 min, and D4's purpose is not met for a user who never finds the switch. The case for (b): Apple is an observer the project cannot audit. |
| **PW-D3** | Floor, debounce, jitter | floor 15, 20 or 60 min. Debounce 10 s. Jitter 0 to 30 s. | **20 min, 10 s, 0 to 30 s**, all relay configuration, so changing them is a redeploy and not a client release. |
| **PW-D4** | Which relay events wake | (a) another device's blocks and tombstones. (b) an invite response. (c) a pairing request. (d) a grant drop. | **(a) and (b) yes.** **(c) no**: the route is unauthenticated, anyone who knows an `ownerId` could spend the phone's wake budget, and pairing is a flow the user is present for. **(d) no**: it cannot be done without giving up T-GR-05 (§3.6). |
| **PW-D5** | Register the token under shared-realm identities | (a) no, personal realm only. (b) yes, and accept that the relay can join a person's realms. | **(a)**. |
| **PW-D6** | Skip a device that is pulling | (a) yes (§4.1 rule 3). (b) wake regardless. | **(a)**. |
| **PW-D7** | Assets | (a) two new assets (token set, provider key) and a new surface. (b) fold the tokens into A6 and the key into a note. | **(a)**. The token is an identity link of A30's class, not passing metadata. |
| **PW-D8** | Sandbox tokens on the production relay | (a) accept both environments. (b) production only. | **(a)** while builds run from Xcode talk to the production relay. Revisit at release. |
| **PW-D9** | Key custody | (a) a root-only file on the box, placed by hand. (b) an environment variable in `deploy.env`. | **(a)**. Also use a topic- and environment-limited key if the portal offers one. |
| **PW-D10** | `apns-expiration` | (a) one hour. (b) zero. | **(a)**. A late wake is still a correct hint. |
| **PW-D11** | Stale-row sweep age and registration refresh | 60 days and 7 days, or other values | as stated |
| **PW-D12** | Visible notifications | V0 only, V1, V2 or V3 (§9) | ⭐ **Decided 2026-10-02: V0 now.** The P2 delivery measurement also answers whether request alerts arrive late in practice. If they do, add **V1 for invite responses only**, with its threat-model amendment (§10 P4). V2 and V3 wait for content that is both allowed on a lock screen (§9) and worth showing: for today's request classes the decrypted text could say no more than the generic line. |
| **PW-D13** | When S4's client work runs relative to S0 and S1 | (a) P1 and P2 now, P3 after S1. (b) everything after S1. | **(a)**. P1 and P2 depend on neither and deliver the request alert. |

## 13. What was not found or not verified

- No relay path removes a realm's device rows (§5.4).
- No JWT nonce store in the relay (§5.2).
- Whether iOS deprioritises background pushes for a rarely opened app (§7). To be measured.
- Whether a background push arrives on the simulator. The simulator delivers remote pushes on
  Apple-silicon Macs, and `xcrun simctl push` can inject one, but neither was tried.
- Whether the company team's portal offers topic- or environment-limited keys (§6.4).
- Whether one `drainNow()` exhausts a multi-page backlog (§8.3).
- That shared-realm traffic uses realm-local device ids was read in the threat model only (§3.6).
- Nothing here was run. Every "Apple" fact is from documentation.

---

## Appendix A — Draft amendment for `docs/sync/threat-model.md`

Not applied by this document. It lands with P1, in the same PR as the relay code. Numbers are
placeholders: take the next free asset, surface and section numbers at that time.

**Header paragraph (after the last sub-amendment):**

> **Push-wake sub-amendment ([`sync-push-wake.md`](../proposals/sync-push-wake.md), S4 of
> `ios-background-scheduling.md`):** the relay can now ask Apple to wake a realm's idle iPhones.
> Adds assets **A41** (the push-wake token set) and **A42** (the APNs provider signing key), attack
> surface **#20** (the `/sync/wake/token` routes and the relay-to-APNs egress), an **A6**
> amendment (wake timing becomes visible to Apple) and threat group **§8.23 (T-PW-01..06)**.
> Landed with the P1 relay slice. House Rule #1 is untouched: the push is one constant payload
> with no block data, no ciphertext, no realm or device identifier and no count, and the relay
> decrypts nothing. No `RELAY_PROTOCOL_VERSION` change: the routes are additive.
> **Supersedes** the "out of scope" position on OS-native push in `sync-relay-api.md` §4.2 and §9.

**A6, appended:**

> *Amendment (push wake, [`sync-push-wake.md`](../proposals/sync-push-wake.md)):* for a device
> that registered a wake token, **Apple** now observes that the realm had activity while the
> device was idle, and when, at the resolution of the relay's floor between wakes (20 minutes by
> default) and never per block. Apple also observes the app's bundle id. This is the same kind of
> timing metadata the relay has always seen (T-T-07, T-B-03), disclosed to a second party the
> project cannot audit. It is reduced by coalescing, by a debounce and jitter, by never waking a
> device that is already pulling, and by a per-device switch. It is not removed. A device with the
> switch off has no token at the relay and discloses nothing to Apple by this path.

**New assets:**

> - **A41. Push-wake token set.** Per registered device: Apple's device token for one app install,
>   with its environment and bundle id, keyed by `(ownerId, deviceId)` in the relay's `wake_token`
>   table, plus one overwritten `last_sent_at`. Not end-to-end encrypted: the relay must present
>   the token to Apple. The token is the first relay-held identifier that a third party can
>   resolve to a person: Apple holds its link to a device and an Apple ID, so relay data joined
>   with Apple's de-anonymises an `ownerId`. Same class as A30, and unlike A30 it is not removed by
>   the strong-variant entitlement. Custody: relay database (and its backups, 30 days). Deleted on
>   the device's request, on device removal, on Apple's report that the token is dead, on a move
>   to another device, and after 60 days without a refresh. The token is registered under the
>   **personal realm only**: registering it under a shared-realm identity would let the relay join
>   a member's realms on the token (T-GR-05). One token per device, held by Control, so the table
>   is not an app inventory (A37).
> - **A42. APNs provider signing key.** An ES256 private key issued by Apple to the developer
>   team, held by the relay as a root-only file outside the database and outside its backups.
>   The first signing secret the relay holds. With a token from A41 it can send any push to that
>   app install, including a visible alert. Compromise is a **nuisance and phishing-text**
>   problem, not a confidentiality break: a push decrypts nothing, and the apps never act on a
>   remote notification's payload. Rotation: issue a second key, switch, revoke the first in
>   Apple's developer portal.

**Attack surface:**

> 20. **Push wake.** `PUT`/`DELETE`/`GET /sync/wake/token` (device-key JWT), the relay's
>     `wake_token` store, and the relay's outbound HTTP/2 connection to Apple's push service. The
>     only relay egress that reaches a third party on behalf of a specific user.

**Threat group §8.23, push wake:**

> **T-PW-01. Apple observes realm activity timing.**
> Actors: Apple as the push provider; AD4 through legal process served on Apple. Assets: A6.
> Each wake tells Apple that a token's realm had activity at about that time, and which app was
> woken. Defense: the payload is constant and carries no identifier; wakes are coalescing-bounded
> to one per floor interval, debounced, jittered and suppressed for a device that is pulling; the
> user can turn push wake off, which deletes the token. Residual: **Accepted, documented.** An
> idle device's realm-activity windows are observable by Apple, and we cannot verify Apple's
> handling of them.
>
> **T-PW-02. Relay data joined with Apple's de-anonymises a realm.**
> Actors: AD4 reaching both the relay and Apple; AD5 with Apple's cooperation. Assets: A41.
> A token from the relay, presented to Apple, yields a device and an Apple ID, and with them a
> name for an `ownerId` and its A6 history. Defense: partial. One token per device, short
> retention, deletion on removal, and the switch. Residual: **Medium for a targeted user**, the
> same class as T-CB-04, and not closed by the strong-variant entitlement.
>
> **T-PW-03. A compromised relay sends pushes of its choosing.**
> Actors: AD5. Assets: A42, A41.
> With the provider key and the token set a relay can send wake storms, which spend battery and
> the app's background budget so that genuine wakes are throttled, and can send a **visible alert
> with attacker-chosen text under the app's name** to a user who granted that app notification
> permission. iOS shows an alert without running app code, so the app cannot filter it. Defense:
> a wake runs only `HeadlessSafe` work (I9) and pulls through the authenticated routes, so a
> forged wake costs a pull and changes nothing; the apps ignore the payload of every remote
> notification (no deep link, no URL, no state change); the product never asks for a phrase, a
> code or an approval from a notification. Residual: **Low** for wake storms (availability),
> **Medium-Low** for alert text (social engineering, bounded to text and to opening the app).
>
> **T-PW-04. A relay withholds or delays wakes.**
> Actors: AD5. Invariants: I6. A wake is a hint; the refresh task and the next launch pull
> regardless, and withholding blocks is a power the relay already has (T-T-04). Residual: **Low.**
>
> **T-PW-05. Token redirection.**
> Actors: a realm device that learned another install's token (which requires A41 or the
> device). Registering a token moves it, so such a device can stop the victim's wakes or point
> its own realm's wakes at the victim's phone. No content moves and nothing is disclosed.
> Residual: **Low.**
>
> **T-PW-06. Wake as a cross-user or unauthenticated trigger.**
> Actors: AD2, AD3. A relay event that an outside party can cause becomes a way to wake a
> victim's phone. Defense: wakes are triggered by the owner's own authenticated devices and by a
> response to an invite the owner issued (one per invite); **not** by the unauthenticated pairing
> request and **not** by a grant drop, which the relay could only target by learning the
> grantor-to-grantee edge (T-GR-05). The floor bounds any trigger to one wake per interval.
> Residual: **Low.**

**§9, appended to the I1 bullet:**

> The push wake adds no content exposure: the relay sends Apple a constant, and I1 is unaffected.
> It adds an identity link (A41) and a timing disclosure to a third party (A6), both documented.

**§10, appended:**

> - Apple's handling of push tokens and push metadata. Outside the protocol; mitigated by sending
>   as little as possible and by the per-device switch.

## Appendix B — Documents owed a change when the code lands

| Document | Change |
|---|---|
| `docs/sync/threat-model.md` | Appendix A |
| [`sync-relay-api.md`](sync-relay-api.md) | the three routes as a new §3.x; §4.2 and §9 point here in place of "out of scope" |
| [`ios-notification-elevation.md`](ios-notification-elevation.md) | §5.4 and §12 point here; §7's "APNs lands for sync" trigger is marked fired |
| [`ios-background-scheduling.md`](ios-background-scheduling.md) | S4's row and §5.4 gain the as-built notes |
| `deploy/scaleway-relay/README.md` and `sync-relay-server/docker-compose.yml` | the key file, the `APNS_*` lines, the rebuild checklist |
| `docs/how-to/ios-signing.md` | the Push Notifications capability and the `aps-environment` values |
| `AGENTS.md` | a row once code exists: the track can then break something silently |
