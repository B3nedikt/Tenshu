# Take down a share link (operator runbook)

**What this is:** the "action" half of notice-and-action
([`acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md) §3; terms of use, "Reports").
Someone reports a share link; we decide it is clearly unlawful, or the law requires us to act; we take
that one link down by its `shareId`. Code: `POST /ops/share/takedown` (`OperatorRouting.kt`),
`ShareRegistry.takeDown` (`SqliteShareStore.kt`), CLI `deploy/share-takedown.sh`.

**What a takedown does**, in this order:

1. **Revokes the link and appends one audit row, as one transaction.** From that commit on, the
   descriptor `GET /share/<id>` and every chunk `GET` answer **410**. That is the same answer a revoke by
   the owner or an expiry gives, so the apex viewer shows its neutral "this link is no longer available"
   page and a recipient learns nothing about why.
2. **Deletes the link's chunk ciphertext at once**, instead of 30 days after the revoke as for an
   ordinary revoke (`share/<shareId>/<n>` in the blob store).
3. Leaves the revoked descriptor row in place until the share GC drops it one retention window (30 days)
   later. While it is there the owner's own "revoke" still succeeds (204) instead of failing and being
   retried, and no chunk can be uploaded under the id (410; a chunk already in flight is deleted again
   as it lands). **The id is retired for good:** the relay refuses to create a share under any id with a
   takedown row, before and after the GC, so the reported URL can never be re-published.

**What it does not touch:** the owner's own files. A share link holds its *own* encrypted copy; the
owner's durable content in the content plane is a different tier
([`retention-quota-model.md`](../proposals/retention-quota-model.md) §2) and stays. Other links of the
same owner stay up. We decrypt nothing and cannot: the key is the `#k=` fragment of the URL, which we
never receive.

⚠ **The weekly backup copy keeps the deleted chunks** for 35 to 42 days once pruning is on, longer until
it is ([`back-up-the-relay-database.md`](back-up-the-relay-database.md) "Pruning"; policy §4.4). They are
as encrypted there as anywhere. Do not try to edit the backup for a takedown. If an authority asks us to
preserve evidence, that copy is what exists; say so, and stop its pruning for the affected window.

## 1. From a report to a `shareId`

A report names a link of the form `https://tenshu.app/s/<shareId>#k=<key>`.

- The **`shareId`** is the path segment after `/s/`, up to `#` or `?`: 22 characters of `A–Z a–z 0–9 - _`.
- ⚠ **The part from `#` on is the decryption key.** The terms ask reporters to leave it out. If a report
  contains it anyway: do not open the link, do not paste the full URL into a ticket, chat or tool, and
  delete the key from the report you keep. You do not need to see the content to act, and the decision
  must not depend on our having seen it (policy §6).
- `deploy/share-takedown.sh` accepts the URL as well as the bare id, and strips the fragment itself
  before anything leaves your machine. Prefer pasting only the id anyway.

## 2. Decide, and pick a reason code

The terms promise to block or delete a link **if it is clearly unlawful or the law requires us to**.
The reporter has to explain why. Record the decision in the support ticket first, then pick one code:

| `reason` | Use for |
|---|---|
| `illegal_content` | Content clearly unlawful under the law that applies, not covered below. |
| `csam` | Child sexual abuse material. ⚠ Also see *Escalation* below before you do anything else. |
| `ip_infringement` | Copyright or trademark notice. |
| `malware_or_phishing` | Malware, credential phishing. |
| `terms_violation` | A breach of the terms that is not itself unlawful. |
| `authority_order` | An order from a court or authority. |
| `other` | None of the above. Say why in the ticket. |

The **note** is one line, at most 500 characters, and should be the **ticket reference** (for example
`support#1234`). ⚠⚠ Never a description of the content and never the reporter's name or address: the
audit row is append-only and is kept when the owner's account is closed. The relay cannot check this;
you are the guard.

## 3. Enable the operator route (once)

The route exists only while `RELAY_OPERATOR_TOKEN` is set in the relay's environment, and it answers
only a caller inside the relay container's network namespace. Caddy also refuses `/ops/*` at the edge.

```sh
ssh root@<relay-host>
grep -c '^RELAY_OPERATOR_TOKEN=' /opt/tenshu/deploy.env   # 1 = enabled, 0 = not
# If 0: add a random token and recreate the relay (a few seconds of downtime for syncing clients).
echo "RELAY_OPERATOR_TOKEN=$(openssl rand -hex 32)" >> /opt/tenshu/deploy.env
cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml \
  --env-file /opt/tenshu/deploy.env up -d relay
```

**Decided 2026-10-29: the token stays set permanently** (set at the production reset,
[`reset-and-commerce-test.md`](reset-and-commerce-test.md) §1). The same token also enables the
content-reclaim routes, which [`relay-content-reclaim.md`](relay-content-reclaim.md) used to switch off
after each run; it now leaves the token in place. A takedown has to be possible
*expeditiously* (DSA Art. 6), and a relay restart per notice is a needless delay and a needless outage.
The reclaim routes stay behind the same three brakes either way (loopback socket peer, constant-time
token, Caddy's edge 404), and the token never leaves the box. If the check above prints 0 (a box set up
before the reset), enable it as shown, once.

## 4. Run the takedown

From a machine that has the root ssh key for the relay box. ⚠ The script assumes the production relay
layout (`/opt/tenshu/deploy.env`, container `sync-relay-server-relay-1`); on the dev lab, call the route
by hand from the relay container's namespace.

```sh
deploy/share-takedown.sh <relay-host> '<shareId or reported URL>' <reason> '<ticket ref>'
# e.g.
deploy/share-takedown.sh relay.tenshu.app 'Q1JFQVRFRF9GT1JfVEVTVA' ip_infringement 'support#1234'
```

It prints the relay's answer:

```json
{"auditId":3,"shareId":"Q1JFQVRFRF9GT1JfVEVTVA","outcome":"taken_down","ownerId":"<64 hex>",
 "takenDownAtMs":1793267585174,"reason":"ip_infringement","bytesDeleted":6,"bytesDeleteCompleted":true}
```

| `outcome` | Meaning | Next |
|---|---|---|
| `taken_down` | The link existed and is down now. | Verify, record, notify (§5–6). |
| `already_taken_down` | A takedown of this id is already on record. Nothing new is revoked; the byte delete is retried. | Record it against the same ticket. |
| `not_found` | No link under this id: mistyped, never existed, or revoked or expired long enough ago that the GC already collected it. A row is still written, so the notice is on record as handled. | Check the id against the report. If it is right, the link is already dead: answer the reporter. |

- `bytesDeleteCompleted: false` means the link is dead but deleting its chunks failed (for example the
  bucket was unreachable). **Run the same command again**; a repeat retries the delete. If it keeps
  failing, the share GC deletes them one retention window after the revoke in any case.
- An **empty 404** is a refusal: the token is not set (the script says so), or you are not calling from
  the relay's namespace. Every refusal looks the same on purpose.
- A **400** names what was wrong with the input; nothing was written.

Local dry run against a dev relay: `RELAY_OPERATOR_TOKEN=<token> deploy/share-takedown.sh --local <id> other`
(the relay started with the same `RELAY_OPERATOR_TOKEN`).

## 5. Verify

```sh
curl -s -o /dev/null -w '%{http_code}\n' https://relay.tenshu.app/share/<shareId>          # 410
curl -s -o /dev/null -w '%{http_code}\n' https://relay.tenshu.app/share/<shareId>/chunk/0  # 410
```

⚠ A descriptor `GET` that succeeds counts as an "open" against a capped link. On a link that is down it
answers 410 and counts nothing, so this check is harmless afterwards. Do not run it *before* the
takedown.

The audit trail, on the relay box (the live database, by absolute path; read
[`relay-content-reclaim.md`](relay-content-reclaim.md) on proving it is the live file, and note that a
copy without its `-wal` misses recent rows):

```sh
DB=/var/lib/docker/volumes/sync-relay-server_relay_data/_data/relay.db
sqlite3 "$DB" "SELECT id, share_id, owner_id, datetime(taken_down_at/1000,'unixepoch'), reason_code,
  operator_note, outcome FROM share_takedown WHERE share_id = '<shareId>' ORDER BY id"
```

## 6. Record and notify

1. **The ticket:** `auditId`, `outcome`, the UTC time, the reason code. The audit row is the relay's
   record; the ticket is ours and holds the reasoning.
2. **The reporter:** confirm the decision (the terms promise both a receipt and the decision).
3. **The owner (statement of reasons, DSA Art. 17).** The terms promise it "as far as we are allowed
   to". Find the account from the `ownerId` in the answer through the commerce admin console's search,
   which matches owner ids as well as addresses (`GET /admin/users?q=<ownerId>`; how to reach the
   console: [`deploy-commerce-digitalocean.md`](deploy-commerce-digitalocean.md) "The admin console
   needs two factors"; that box is retired, but the steps are the same on the Scaleway commerce box,
   whose `deploy.env` carries `ADMIN_TOKEN` and `ADMIN_TOTP_SECRET`). A realm signed in with two addresses lists both. Write to the address with:
   which link (the `shareId` and the file's creation date if they ask; we do not know its name), that it
   was disabled and its stored copy deleted, the ground (the law, or the clause of the terms), the facts
   relied on (that a report was received and what it alleged, without the reporter's identity unless
   they agreed), that no automated means were used, and the remedies (reply to us; the courts). ⚠ Skip
   or delay this step when the law forbids informing the owner (for example an authority order, or a
   CSAM report while an investigation could be compromised).
4. **Escalation.** For `csam`, and wherever a statutory duty applies to us, report to the competent
   authority (policy §3 "Escalation"; counsel confirms the exact duties). Keep what we hold: the
   `shareId`, the `ownerId`, the audit row, the report. We do not hold the content in readable form.

## 7. Repeat infringers, and suspension

Takedowns by owner:

```sh
sqlite3 "$DB" "SELECT owner_id, COUNT(*) FROM share_takedown WHERE outcome = 'taken_down'
  GROUP BY owner_id HAVING COUNT(*) > 1"
```

The terms allow us, for serious or repeated breaches, to block sharing for an account and to suspend or
terminate the account. ⚠ **Neither "block sharing" nor "suspend" exists as a control yet.** What exists:

- **Closing the account** in the commerce admin console (`POST /admin/accounts/close`). This is
  **termination, not suspension**: with closure reclamation on (production since 2026-10-24) it deletes
  all of the owner's content once the 30-day grace has passed, unless the account is reopened
  (`/admin/accounts/reopen`) before then. Use it only for a termination you have decided on, and follow
  "Termination by us" in the terms (notice where reasonable).
- **Taking down each further link** as it is reported.

The proposed suspension design is in
[`acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md) §3 "Not built yet".

## What the owner's app shows

Today: **nothing changes in the owner's app.** Its *My shares* list comes from the owner's own synced
`Share` rows, and nothing tells the app that the relay took a link down, so the link stays listed as if
it were live. If the owner revokes it, the app's call succeeds (204) and the row disappears as for any
revoke: no error, no retry loop. The email in §6 is how the owner learns of a takedown. Showing "removed
following a report" in the app needs a relay read the owner's device can poll and a field on `Share`;
see the policy doc's "Not built yet".

## What the relay keeps

`share_takedown(id, share_id, owner_id, taken_down_at, reason_code, operator_note, outcome)`, relay
migration 4. Opaque ids, a fixed code, a time, and the operator's own one-line note: no user plaintext
(House Rule 1). It is still **new retained metadata**: the share's own row, which links the id to the
owner, goes with the GC or with account closure, and this row does not. Account closure keeps it
(`AccountErasure.KEPT_TABLES`), because it is our record of having acted on a notice. **Kept for 3 years
from the takedown** (maintainer decision 2026-10-29, roughly the ordinary limitation period for a
claim about the decision), then deleted. ⚠ The deletion is not built yet: the append-only triggers
refuse `DELETE`, so the first rows fall due in 2029 and need a purge step before then (`tech-debt.md`
*"Takedown audit rows have a 3-year retention and no purge"*). ⚠ The purge erases the owner link and the
note but keeps the bare `shareId` retired, or the reported URL could be re-published.

The append-only triggers hold against the application, not against a wipe: a full relay reset (`deploy/reset-test-data.sh`) deletes the database
and these rows with it. Before any reset, export the table if a notice may still need its evidence:
`sqlite3 "$DB" ".mode csv" "SELECT * FROM share_takedown" > takedowns-$(date +%F).csv`.
