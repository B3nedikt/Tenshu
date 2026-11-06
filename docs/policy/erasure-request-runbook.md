# Erasure and closure requests: the operator runbook

**Status:** Written 2026-10-28 against master `d58a79a47`. It follows the privacy notice from that
date: `web/apex/support-pages/privacy.ts` "Konto schließen" / "Closing your account", in the wording
that said what closure and the backup copy actually do, which this runbook was written with. Reviewed under
[`privacy-review-checklist.md`](privacy-review-checklist.md). Not legal advice.

This covers what to do when someone asks, by email, for their account or data to be deleted
(Art. 17 GDPR), or for their account to be closed. Most users never reach this page: they delete
their account themselves, in Control (Services → Delete account, on Android and desktop) or at
`tenshu.app/account/delete`. Both paths end in the same `eraseAccount` call
([`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md) §9.7).

## What the system does, so you know what you are confirming

| When | What happens | Where |
|---|---|---|
| The user enters the code | The account is **closed and erased at once**. The email becomes `erased:<id>`; `email_verified_at` and `display_name` are cleared; sessions, both OTP rows, the failed-code row and marketing consent are deleted. Stripe and Play subscriptions are cancelled. **It cannot be undone**: `reopenAccount` is a no-op on an erased row. | commerce `AccountDeletionRouting.kt`, `PostgresCommerceStore.eraseAccount` |
| Every hour after that | Commerce posts a signed closure record for every closed account whose data store no **open** account still holds. The relay answers `202` while the 30-day grace runs. | `AccountClosureDelivery` (runs at boot, then every `ACCOUNT_CLOSURE_SWEEP_MINUTES`, 60 by default), `/sync/account/closure` |
| ≥ 30 days after closure (and up to one sweep later) | The relay erases every owner-scoped table for that `ownerId` in one transaction, then its content bytes and the `<ownerId>/` prefix in the content bucket. It writes `erased_realm(owner_id, erased_at)` and refuses to re-create the realm (`410`). Re-posts after that are no-ops. | `AccountErasure`, `SqliteRealmStore.eraseOwner`, [`retention-quota-model.md`](../proposals/retention-quota-model.md) §3.3a |
| The user signs in again within the 30 days, on a device holding the data store | A **new** account binds to the same `ownerId`, and the sweep skips that owner (`closedAccountsWithOwners`). Nothing on the relay is deleted. | §9.7 |

⚠ Content deletion happens only while **both** flags are set: `ACCOUNT_CLOSURE_RELAY_URL` on commerce
and `ACCOUNT_CLOSURE_RECLAIM=true` on the relay. Both have been on in production since 2026-10-24. If
either is ever turned off, the notice is wrong. Fix the flag first; do not change the notice to match.

## Handling a request

### 1. Log it (the request record)

Before anything else, add an entry to the request log. A minimal record is enough to show we
complied (Art. 5(2)) without keeping more than needed:

```
received: 2026-10-28   channel: privacy@   kind: erasure | closure | other
account id: <commerce account.id>        owner id: <ownerId, if bound>
identity check: pointed to /account/delete <date> / erased_at <date>
answered: <date>   relay erased: <date, from erased_realm.erased_at>   notes: <deadline extension, refusal reason>
```

- **Where it lives:** ⚠ undecided; the maintainer must choose. It has to be somewhere that survives a
  restore of either database: **not** the commerce or relay database, and not the support inbox alone.
  The restore procedures read this log ([`back-up-the-relay-database.md`](../how-to/back-up-the-relay-database.md)
  *Re-applying erasures after a restore*).
- Record the **account id and owner id, not the address**. Once erased, the address is no longer in
  our systems except in the request email itself, and the log should not reintroduce it.
- Keep the entry for 3 years after the request is closed. That matches the general limitation period
  (§ 195 BGB) for a claim about how the request was handled. ⚖️ This is the maintainer's working
  value, not counsel's.

### 2. Verify the requester

The notice asks people to write **from the account's address**. That is necessary, not sufficient:
mail headers can be forged. The proof we rely on is the same as the self-service path, **a code
mailed to the account's address**. So the reply (template below) sends them to
`tenshu.app/account/delete`: they enter the address there, receive the code, and confirm. Only the
holder of the inbox can complete the deletion.

⚠ **Do not trigger the code for them** (`POST /commerce/account/delete/request`). The page always
starts at the address step and requests a **new** code, which replaces the stored hash
(`putEmailVerification`). A code we sent beforehand is therefore dead by the time they can type it.

To find the account and its data store first (read-only, on the commerce box):

```bash
ssh root@<commerce-box> "docker exec commerce-service-db-1 psql -U commerce -d commerce -c \"
  SELECT a.id, a.status, a.closed_at, a.erased_at, o.owner_id
  FROM account a LEFT JOIN account_owner o ON o.account_id = a.id AND o.superseded_at IS NULL
  WHERE a.email_normalised = lower('<address>')\""
```

⚠ The admin console's `/admin/accounts/close` **only closes**. It keeps the email and can be
reopened, so it is not an erasure. It is also probably unreachable in production today: compose does
not forward `ADMIN_TOTP_SECRET` ([`tech-debt.md`](../tech-debt.md) *Commerce's compose drops ~18 env
vars*). **No operator tool erases an account without the user's code.** If a case needs one (a child's
account, a person who cannot receive mail at the address but proves identity otherwise), it is
manual SQL today. See [`tech-debt.md`](../tech-debt.md) *No operator path erases a commerce account*.
Do not improvise that SQL: the statements `eraseAccount` runs are in `PostgresCommerceStore.kt`, and
they must run in one transaction.

**Unverified accounts** (a code was requested but never entered) go through the same flow: the
deletion code reaches whoever owns the inbox. Left alone, they are deleted automatically 30 days after
the last code request, with their request records (`UnverifiedAccountExpirer`), so a request about
one older than that usually finds nothing left to erase.

### 3. The deadline

- **Answer within one month** of receipt (Art. 12(3)). Count from the day the mail arrived, not from
  the day the identity check finished. Do not count on the clock stopping while you wait for the
  code: the ICO allows that, but EU practice is not settled (⚖️ for the launch review).
- You may extend by **two further months** if the case is complex. You must tell the person
  **within the first month**, with the reason.
- If we refuse (for example, the identity cannot be confirmed), say so within the month. Give the
  reason and their right to complain to a supervisory authority (Art. 12(4)). Ours is the BayLDA.
- "Answered" means the account is erased, or we have told them what we did and what remains. Relay
  content goes ≥ 30 days later by design (the published grace). The reply says so; it does not wait
  for it.

### 4. What is NOT erased: tell them

The reply must be honest about this. Each item is in the notice.

- **Invoices, credit notes and acceptance records** (versions, time, IP): ~10 years, statutory
  (§ 147 AO, § 257 HGB; acceptance under Art. 6(1)(c)/(f)). They key on the account id, not the
  email.
- **The pseudonymous commerce account row**: id, timestamps, rail, `stripe_customer_id` /
  `iap_original_txn_id`, the owner binding and `entitlement_history`. It is needed for the closure
  sweep, subscription-cancellation retries and late chargebacks. ⚠ No code purges it. "As long as the
  invoices" is an operator duty with no tool yet.
- **At Stripe or Google**, the customer object under that reference. ⚖️ Whether to delete Stripe's
  customer object is open (§9.7).
- **`erased_realm`**: one row per erased data store (sha256 of the master public key, plus the
  erasure time), kept while the service runs, so the data store cannot be re-created under the same
  key.
- **The weekly blob mirror** (`tenshu-relay-blobs-mirror`): ⚠ **not pruned** while `MIRROR_PRUNE` is
  off, which is the case in production. Everything mirrored stays indefinitely, encrypted.
- **Database snapshots**: relay and commerce dumps keep erased rows until their 30-day bucket
  lifecycle removes them.
- **Second-region copies (nl-ams)**, once the backup hardening is applied
  ([`back-up-the-relay-database.md`](../how-to/back-up-the-relay-database.md) *Applying the backup
  hardening*): the relay and commerce snapshots there keep erased rows ~31 days and are Object-Locked
  (no box key can remove them early), and the nl-ams blob mirror is ⚠ **never pruned from the box**
  (D3), so it keeps erased owners' bytes until an admin prunes it.
- **Shared data stores (shared folders) the person created**: never erased by their closure
  (§3.3a open item). Members may still rely on them.
- **Crash orphans**: bytes of an upload that died before its `content_blob` row was written.
  `eraseOwnerBytes` sweeps the `<ownerId>/` prefix only while that owner's free queue is non-empty, so
  an owner with no indexed blobs left keeps them (§3.3a *pre-existing orphans*). The bucket check in
  step 5 shows them. Delete leftovers under the prefix by hand, and note it in the request record.
- **Data on their own devices**, and on devices of anyone they shared with.
- **Our mail**: the request itself and our reply, kept as the request record says.

### 5. Verify the erasure

**Commerce, straight after confirmation:**

```bash
ssh root@<commerce-box> "docker exec commerce-service-db-1 psql -U commerce -d commerce -c \"
  SELECT id, status, closed_at, erased_at, email_normalised FROM account WHERE id = '<account id>';
  SELECT count(*) AS sessions FROM session WHERE account_id = '<account id>';\""
# expect: status Closed, erased_at set, email_normalised = 'erased:<id>', sessions 0
```

**Relay, ≥ 30 days after `closed_at`.** Check the commerce log first for
`Account-closure sweep: … ×200 reclaimed`. Then, on the relay box:

```bash
DB=/mnt/relay_data/relay.db; O='<owner id>'
sqlite3 -readonly "$DB" "SELECT owner_id, datetime(erased_at/1000,'unixepoch') FROM erased_realm WHERE owner_id='$O'"
# every table with an owner/realm column must have 0 rows for this owner (erased_realm: 1)
sqlite3 -readonly "$DB" "SELECT m.name || '|' || p.name FROM sqlite_master m JOIN pragma_table_info(m.name) p
  WHERE m.type='table' AND p.name IN ('owner_id','realm_id')" |
while IFS='|' read -r t c; do
  printf '%-28s %s\n' "$t" "$(sqlite3 -readonly "$DB" "SELECT COUNT(*) FROM \"$t\" WHERE \"$c\"='$O'")"
done
```

`content_free_queue` may show rows for an hour or so while bytes are still being deleted. The next
sweep finishes them. Then check that the content bucket holds nothing under `<owner id>/`. Use a
list call with the content-plane credentials, or the AWS CLI against the Scaleway endpoint with
`--prefix '<owner id>/'`, and expect 0 keys.

Note the `erased_at` date in the request log.

### 6. Reply templates

**Deutsch**

> Hallo,
>
> wir haben deine Anfrage vom {Datum} erhalten. Damit sicher ist, dass das Konto dir gehört, schließt du
> die Löschung selbst ab: Gib auf https://tenshu.app/account/delete die E-Mail-Adresse deines Kontos
> ein. Wir schicken dir dorthin einen Code, mit dem du die Löschung bestätigst.
>
> Mit der Bestätigung löschen wir dein Konto und deine E-Mail-Adresse sofort und kündigen ein Abo über
> Stripe oder Google Play. 30 Tage später löscht unser Server alle Daten deines Datenbestands, außer du
> meldest dich vorher wieder an. Was wir aufbewahren müssen oder (noch) nicht löschen können –
> Rechnungen und Nachweise bis zu zehn Jahre, ein Kontodatensatz ohne E-Mail-Adresse, ein Vermerk über
> den gelöschten Datenbestand und die verschlüsselte Sicherungskopie der Inhalte, aus der derzeit
> nichts gelöscht wird –, steht in unserer Datenschutzerklärung unter „Speicherdauer“:
> https://tenshu.app/legal/privacy/de
>
> Viele Grüße
> Tenshu

**English**

> Hello,
>
> we received your request of {date}. To make sure the account is yours, you complete the deletion
> yourself: enter your account's email address at https://tenshu.app/account/delete. We will send a
> code to that address, which you use to confirm the deletion.
>
> When you confirm, we delete your account and email address at once and cancel any subscription
> through Stripe or Google Play. 30 days later our server deletes all data of your data store, unless
> you sign in again before then. What we must keep or cannot (yet) delete — invoices and records for up
> to ten years, an account record without your email address, a record of the erased data store, and
> the encrypted backup copy of content, from which nothing is currently deleted — is listed in our
> privacy policy under "How long we keep data": https://tenshu.app/legal/privacy/en
>
> Kind regards,
> Tenshu

## When something goes wrong

- **A database restore** (relay or commerce) can bring erased data back. Follow *Re-applying
  erasures after a restore* in [`back-up-the-relay-database.md`](../how-to/back-up-the-relay-database.md)
  and the matching note in [`back-up-the-commerce-database.md`](../how-to/back-up-the-commerce-database.md)
  **before** the restored service takes traffic.
- **Restoring blobs from the mirror** (fr-par or nl-ams) can bring an erased owner's bytes back.
  Exclude every `<owner id>/` prefix whose owner is in `erased_realm` or the request log.
- **The sweep logs `401`s**: closure records are being refused (key, signature or clock), and
  nothing is being erased. See `retention-quota-model.md` §3.2.
