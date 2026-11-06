# Acceptable use & service limits — single source of truth

**Status:** Draft v0.10 (2026-10-29) — product-side source text, **not yet counsel-reviewed, not legal
advice**. This file exists so the rules live in ONE place and every surface renders from it instead of
inventing its own wording. Established alongside the folder-link-sharing design
([`drive-folder-link-sharing.md`](../proposals/drive-folder-link-sharing.md) Q2); the storage/consent
substrate is the commerce plane's
([`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md) §5.1 — versioned
ToS acceptance, GDPR bases, subprocessor duties).

**Where this is displayed** (each surface links or excerpts; none re-authors):

1. The **terms of use**, published 2026-10-28 at `/legal/terms/<lang>`
   (`web/apex/support-pages/terms.ts`, German binding, English and French translations) and accepted
   at sign-up: the sign-up screens (Control's `FreeAccountSignupScreen`, iOS Control's `SignInView`,
   web `FreeAccountSignupPage`) say "By continuing you accept the terms of use and confirm that you
   are 16 or older" and link the terms and the privacy policy. `/register` records
   `TOS_VERSION = TERMS_DATE` (`2026-10-28`) in the versioned `acceptance` record (commerce §5.1);
   `commerceApi.test.ts` and `TermsVersionParityTest` pin both clients to the page's date. The
   terms render §§1–7 below in user-facing words; what the page decided beyond them, and what a
   lawyer should check, is in "Terms of use" at the end of this file.
2. The **share-creation UI** — one line of copy + a link ("shares count against your storage; limits
   apply").
3. The **anonymous share viewer** (`/s/{shareId}`) — footer: "Report this share" + a limits link.
4. The **abuse report form** (§3).
5. The **account-closure confirmation** (§4.3 + §5.2 — the grace period, the irreversibility, and
   the forfeited remainder of a paid period must be shown *before* the action, not only in the
   terms). Built 2026-10-23 as the **account-deletion** surface: Control's `AccountDeletionScreen`
   and the web page `/account/delete` both show all three before the destructive button
   (`commerce-accounts-and-sessions.md` §9.7). ⚖️ Their copy is the product's reading of §4.3/§4.5
   and wants the same counsel pass.
6. The **checkout acknowledgement step** — the cancellation/refund summary (§5.2) plus the
   withdrawal-right acknowledgement the customer must tick before a purchase can start (§5.4).
   ✅ Built: Control's `CheckoutScreen`, enforced by `POST /commerce/checkout`.
7. The **privacy notice** (`/legal/privacy`, `web/apex/support-pages/privacy.ts`, de/en/fr), which
   states §7's **minimum age (16)** beside the terms and the sign-up line (§7, "Where it is stated").

---

## 1. Storage accounting (maintainer decision, 2026-07-14)

- Shared content is an independently encrypted copy; **it deducts from the owner's storage quota** like
  any other data. Sharing a 10 GB folder consumes ~10 GB of quota for as long as the share exists.
- There is no separate "share quota". What the user's tier allows, sharing allows. Heavy legitimate use
  (a photographer publishing large sets) is served by buying a larger tier — the same 2 TiB Premium that
  serves heavy storage.
- Revoking a share frees its storage after the GC retention window.
- Rationale: quota is the natural, already-metered brake. Casual users never notice the duplication;
  someone publishing a terabyte notices exactly as much as they should.

## 2. Transfer limits (defaults — knobs, confirm at P1)

Two layers, both enforced relay-side, both content-blind:

**Per-owner daily egress pool** across all of an owner's shares (Dropbox-parity shape):

| Tier | Link egress / day |
|---|---|
| Free | 20 GB |
| Basic (50 GiB) | 200 GB |
| Premium (2 TiB) | 1 TB |

Exceeded ⇒ `429` + `Retry-After`; the viewer renders "this link is busy — try again later" (NOT the
410 revoked/expired state — the distinction matters to recipients).

**Per-IP limits on the anonymous endpoints** (infra-level): ~60 descriptor `GET`s/min/IP; ~4 concurrent
chunk streams/IP. Ordinary viewing is unaffected; scraping and hotlink farms are not.

*Comparables (verify current values before counsel review):* Dropbox publishes 20 GB/day (free) →
~200 GB (Plus) → ~1 TB/day (Professional/Business); Google keeps its limits opaque (a hot file trips a
~24 h "download quota exceeded" cooldown); Mega — the closest E2E cousin — meters free transfer per IP.
Publishing our numbers (Dropbox-style, not Google-style) is deliberate: it is the honest posture and it
is what lets the limit be enforced without dispute.

*Cost bound (operator note, not user-facing):* at ~$10/TB egress overage, a maxed free account costs
~$6/mo and a maxed Premium ~$300/mo — bounded by construction. Structural relief if needed: version-keyed
encrypted chunks are immutable ⇒ CDN/R2-cacheable (zero-egress), traded against revocation latency.

## 3. Notice-and-action (takedown)

Tenshu's relay stores **only ciphertext** and holds **no decryption keys** — it structurally cannot
inspect shared content (whitepaper Zero-Trust principle; threat model House Rule #1). Under the EU DSA
this is a coherent hosting posture: **no general monitoring obligation** (Art. 8), but a working
**notice-and-action mechanism** (Art. 16) and expeditious action on knowledge (Art. 6 liability shield).
So:

- **Report intake:** a form/address accepting a share link + reason. ⚠ **The form strips the URL
  fragment client-side** — the `#k=` capability key never reaches us, even in a report. We receive only
  the `shareId`. We act on notice **without decrypting** (we cannot), like Mega has for a decade; the
  reporter, holding the full URL, is the party who could see the content.
- **Action:** disable/delete the share record by `shareId` — the link dies globally, ciphertext is
  GC'd. The relay knows `shareId → ownerId` (share creation is authenticated), so repeat-infringer
  policy can suspend an owner's sharing capability, then the account. The owner is told, with reasons
  (DSA Art. 17; the terms' "Decision" item).
- **How it is operated (built 2026-10-29):** runbook
  [`take-down-a-share-link.md`](../how-to/take-down-a-share-link.md). The operator runs
  `deploy/share-takedown.sh <relay-host> <shareId or URL> <reason> <ticket ref>`, which calls the relay's
  `POST /ops/share/takedown` from inside the relay container (loopback socket peer + `RELAY_OPERATOR_TOKEN`;
  Caddy refuses `/ops/*` at the edge). In one transaction the relay revokes the share, so recipients get
  the same neutral 410 as a revoke or an expiry, and appends a row to the append-only `share_takedown`
  audit table (`shareId`, opaque `ownerId`, time, a fixed reason code, the operator's ticket reference;
  no content and no reporter data). It then deletes the link's chunk ciphertext **at once**, not 30 days
  later as after an ordinary revoke. The owner's own files are not touched. The audit row survives
  account closure and is kept for **3 years from the takedown**, then deleted (maintainer decision
  2026-10-29; the purge is not built yet, first rows fall due 2029). The taken-down `shareId` itself stays
  retired after the purge. The owner is notified by email, the address found through the commerce
  admin console's owner-id search.
- **Not built yet** (proposed, not decided):
  - *"Removed following a report" in the owner's app.* The owner's *My shares* list is the owner's own
    synced `Share` rows and nothing tells it about a takedown, so a taken-down link stays listed until
    the owner revokes it (which then succeeds quietly). Showing it needs an owner-authenticated relay
    read (for example `GET /share/{id}/status` → `active | revoked | expired | removed`), a polled or
    on-open refresh, and a `removedAt` on `Share`: a schema reshape (House Rule 9) and UI on Android,
    desktop, iOS and web. Until then the email is how the owner learns of it.
  - *Blocking sharing for an account, and suspending an account.* The terms allow both; neither exists
    as a control. The only account lever today is commerce's admin close, which is termination: with
    closure reclamation on it deletes the owner's content after the 30-day grace. Proposed: (1) a relay
    `share_suspension(owner_id, since, reason_code)` checked by `POST /share`, set and lifted through
    the same operator route family and audited like a takedown, so `POST /share` answers 403
    `sharing_suspended` and the share UI says so; (2) a commerce account state `suspended` that stops
    minting entitlement tokens, so with `ENTITLEMENT_ENFORCE` on the relay refuses new writes while
    keeping content and reads, lifted by an admin `reopen`. (1) is small; (2) touches the entitlement
    plane and wants its own design.
- **Escalation:** unlawful-content reports that trigger statutory duties (e.g. CSAM reporting where an
  obligation applies to us) are forwarded to the competent authority. Counsel to confirm the exact
  duties for our operating jurisdictions before launch.
- **Grounding:** the ToS acceptable-use section is what makes takedown + suspension contractually clean
  rather than ad-hoc.

## 4. Data retention & account closure (2026-08-04)

This is the section the ToS retention clause is written from, and it is the **prerequisite for
switching reclamation on at all** ([`retention-quota-model.md`](../proposals/retention-quota-model.md)
Face A / D4). Two things make it read differently from an ordinary provider's retention clause:
we cannot read what we store, and only your own devices can tell us which part of it a deletion
releases.

### 4.1 What we keep, and for how long

| What | Where | How long |
|---|---|---|
| Your file content (encrypted; we hold no keys) | Relay content plane, keyed by an opaque account id | **Until you delete the file (§4.4) or close the account (§4.3).** Never expired for age or inactivity. ⚠ A weekly backup copy is kept separately and is not pruned automatically (§4.4). |
| Encrypted change records used to sync your devices | Relay sync log | **Kept while the account exists.** Only the encrypted contents of records about an individually deleted file are dropped, once all your devices have the deletion and 30 days have passed (§4.4). |
| Email address, account record, session | Commerce operational store | Until the account is deleted; erasable on request. |
| Invoices, terms-acceptance records | Commerce statutory archive | **~10 years**, retained under a legal obligation — these **survive** account deletion. |

**We do not delete your content because you stopped using it.** There is no inactivity expiry, no
"unopened for a year" sweep. A backup you do not touch for three years is still there. (This is not
a courtesy — it is the reason a time-based sweep was removed from the service: for durable content
it is a data-loss bug, not a retention policy.)

### 4.2 What never causes deletion

Stated positively, because the industry norm is the opposite:

- **A failed payment, an expired subscription, or a downgrade never deletes your content.** A lapse
  suspends *new uploads*, not what you have already stored.
- **Being over quota never deletes your content.** Uploads are refused until you are back under the
  limit; nothing existing is removed to make room (§1, and see D7 in the design doc).
- **Inactivity never deletes your content** (§4.1).

The **only** things that lead to deletion of your stored file content are **you deleting it** (a file,
a folder, or a file's old content when you overwrite it, §4.4), **you closing the account** (§4.3), the copy
held by a share link once it is revoked or expires, including a takedown under §3 (§1, §4.4), and an
operator clean-up of upload leftovers that no file refers to (§4.4).

### 4.3 Closing your account

1. You close the account.
2. A **grace period of 30 days** runs from the closure date. Reopening the account during that
   window cancels the deletion; nothing has been removed yet.
3. After the grace period, **all encrypted content stored for that account is permanently deleted.**

⚠️ **This is irreversible, and we cannot undo it for you even in principle.** We hold no keys and
keep no plaintext copy; once the encrypted blocks are gone, no action available to us recovers them.
**Export or download anything you want to keep before closing** — only your own devices can decrypt
it.

Two operational commitments that follow from how this is built, and must not drift:

- **The grace period is measured from the closure date we recorded**, never from when our deletion
  job happens to run. A delayed or retried deletion cannot quietly shorten *or* extend the window
  relative to what is published here.
- **The configured operational grace window must never be shorter than the period published above.**
  The window is a deployment setting (deliberately, so it can match whatever the terms commit to);
  this document is the floor, not a description of the current value.

> ⚠ **Status (2026-10-24) — this subsection is a running process.** The closure reclamation was
> switched ON on both sides on 2026-10-24 (`ACCOUNT_CLOSURE_RELAY_URL` on commerce,
> `ACCOUNT_CLOSURE_RECLAIM=true` on the relay; grace = the 30-day default), by the maintainer's
> decision ahead of counsel's review of this text. Before that date it was built but off
> ([`retention-quota-model.md`](../proposals/retention-quota-model.md) §3.3 / P3). Since 2026-10-23
> users **delete their own account** (in Control and at `tenshu.app/account/delete`,
> [`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md) §9.7), which
> closes it and erases the email at once; its stored content is then deleted 30 days later, and
> per-file deletion (§4.4) runs as before. "30 days later" means at least 30 days plus up to one
> sweep interval (hourly by default), and after the grace period a closed realm is re-posted every
> hour, so anything a still-paired device uploads afterwards is deleted within the hour. ⚠ A self-deleted account cannot be
> *reopened* (its email is gone); the equivalent is **signing in again from one of its devices
> within the grace period**, which keeps the files — the deletion only reclaims a realm no open
> account still holds. ⚠ And the weekly
> backup copy described in §4.4 is not reached by closure reclamation either.

### 4.4 Deleting a file, and what we cannot do

- **Deleting a file normally removes its encrypted content from our servers about 15–20 minutes
  after the deletion reaches us.** When you delete a file or a folder, or overwrite a file with new
  content, the device you do it on tells our servers, as part of syncing the change, that the file
  no longer refers to its encrypted content. Once nothing left in your storage refers to that
  content, an automatic clean-up deletes its encrypted blocks. With the standard configuration that
  clean-up waits a 15-minute grace period and runs every 5 minutes, and in the normal case the
  space stops counting against your storage as soon as the deletion reaches us.
  - **"Reaches us"** means the device you deleted on has synced the deletion. A deletion made
    offline counts from when that device next syncs. Your other devices are not consulted: a phone
    that is switched off, or an app you have not opened, does not hold it back.
  - **Content more than one file refers to** is removed only once the last file referring to it
    is deleted.
  - **Overwrites.** The previous content of an overwritten file is removed the same way. We do not
    keep earlier versions of a file on our servers.
- **What deleting a file does not remove, or may delay — today:**
  - ⚠ **Our weekly backup copy.** We keep a second, backup copy of the stored encrypted blocks,
    updated weekly, so that content can be recovered if our own clean-up deletes something by
    mistake. It is **not pruned today**, so blocks you delete can remain in it after they are gone
    from the live store. Once the operator enables pruning, blocks that have left the live store are
    deleted from the backup copy too: **normally 35 to 42 days after they left it, while the weekly
    backup job runs**, never sooner than 30 days, and **later if a run fails, is late, or is held for
    review**.
    They are as encrypted there as anywhere else, and we hold no key to them.
    <!-- Source: docs/how-to/back-up-the-relay-database.md "Pruning"; 30 = MIRROR_RETENTION_DAYS. -->
  - **Content whose registration did not complete.** When a file is uploaded, the device also
    tells our servers which stored blocks make up its content. That step is best-effort; if it did
    not complete, deleting the file neither removes those blocks nor stops counting them against
    your storage.
  - **Internal pauses.** The clean-up can pause for the whole account while some content in it is
    in that incomplete state, and resumes later. During a pause, deleted content is not removed and
    there is no time limit we can promise.
  - **Share links.** A share link holds its own encrypted copy of what it shares. Deleting the file
    does not revoke the link: the copy stays stored, and stays viewable through the link, until you
    revoke it or it expires, and is deleted about 30 days after that. A link with no expiry keeps
    it until it is revoked. A link we take down after a report (§3) stops working and its copy is
    deleted at once.
  - **Files in a folder you share with other people.** Shared content is a separate encrypted copy
    (§1). Deleting a file in a shared folder does not yet remove that copy from our servers.
  - **Encrypted change records** (the sync log: a file's encrypted name, attributes and folder
    listings, and the encrypted "file deleted" notification your devices show). These are **not**
    removed when you delete a file. For a file deleted on its own, the encrypted contents of its
    records are dropped once every one of your devices has received the deletion **and** at least
    30 days have passed. Records of files deleted as part of a folder, folder listings and deletion
    notifications are not dropped by that process and can be kept for as long as the account
    exists. A record that a deletion happened (an
    opaque identifier and a timestamp) is also kept. Database backups holding these records, but no
    file content, are kept for 30 days.
  - **Leftovers of an interrupted or repeated upload** that never became part of a file. No file
    refers to them, so a file deletion has nothing to release; only an operator clean-up removes
    them.
- **We cannot delete an individual file from our servers on request.** We cannot tell which stored
  blocks belong to which of your files: our servers see only opaque identifiers, never a file's
  name, path or content. The file deletion your own device syncs is the only way to release one
  file's blocks. **To delete a file from our servers, delete it in the app** (and revoke any share
  link to it).
- **We cannot delete anything from your own devices.** Content you have downloaded or kept offline
  lives on your hardware; removing the apps removes it. ⚠ Today, a copy that one of your *other*
  devices had already downloaded can stay in the app's storage on that device after the file has
  disappeared from its list.
- **We cannot decrypt anything to verify a request**, including a deletion request. Every action
  here is taken on account identity, never on content.

### 4.5 Erasure requests (GDPR Art. 17)

**Individual files** are erased by deleting them in the app, which deletes their stored content as
§4.4 describes, with the same exceptions. We cannot act on a request that names a file (§4.4).

**The whole account:** an erasure request is handled as **close the account** (which reclaims stored
content after the grace period, §4.3 — switched on in production since 2026-10-24, see the status note there) **plus**
deletion of the account record and email address from the operational store. **Invoices and
terms-acceptance records are retained** for their statutory period under Art. 6(1)(c) — the standard
legal-obligation carve-out, described in the privacy notice
([`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md) §5.1/§5.2).

> ⚖️ **Counsel gates specific to this section** (beyond the whole-document review): the published
> grace period and its interaction with statutory erasure deadlines; whether "we cannot restore it"
> is adequately disclosed *before* the closure action, not only after; whether user-initiated
> per-file deletion plus per-account closure satisfies erasure obligations, given that we cannot act
> on a request that names a file; whether the backup copy (§4.4) — never pruned today; normally
> 35–42 days, with no hard upper bound, once pruning is switched on — is compatible with those
> obligations; and the notice obligations for
> changing this section for **existing** accounts — see the version note below.

## 5. Payments, cancellation & refunds (2026-08-06)

Written to close the ⚖️ item the cancel-on-close work surfaced
([`commerce-end-to-end-dry-run.md`](../proposals/commerce-end-to-end-dry-run.md) CE-5 follow-up):
closing an account now cancels its subscription, and what that means for money owed had never been
decided.

### 5.1 How subscriptions run

Paid plans are **monthly subscriptions** that renew until you cancel. You can cancel at any time,
from the billing portal, without contacting us and without giving a reason.

### 5.2 Cancelling — the standard model

**Cancelling stops future charges. It does not refund the period you have already paid for.** That
is the ordinary arrangement for online subscriptions and it is what we do.

Concretely:

- **Cancel a subscription while keeping your account** → no further charges; your plan runs to the
  end of the period you have paid for, then drops to Free. Nothing is deleted (§4.2).
- **Close your account** → the subscription is cancelled **immediately**, and the remainder of the
  current period is **forfeited, not refunded**. Closing is a wind-down: the paid tier stops
  working the moment the account closes, so leaving the subscription running would only mean
  charging you for something you can no longer use.

⚠️ **If you want the full period you have paid for, cancel the subscription and close the account
later.** Closing straight away is the shorter path and costs you the remainder — say so plainly at
the point of closing (§0, surface 5), not only here.

### 5.3 What we do not do

- **No pro-rata refunds** for unused time on cancellation, closure, or a downgrade.
- **No refunds for periods you did not use** the service. A subscription is availability, not
  metered consumption.
- **No cancellation fees**, and no penalty for cancelling early.

### 5.4 Your statutory rights are unaffected

Nothing above removes rights you have by law. Two that matter to us specifically, as the seller of
record in the EU:

- **The right of withdrawal.** EU consumers buying a digital service online have **14 days** to
  withdraw, and a storage plan keeps that right until the service is fully performed (§ 356 Abs. 4
  BGB). **At checkout we collect the customer's express request to start before the period
  ends**, and their acknowledgement that a withdrawal then costs the share of the price for the
  time up to it (§ 357a Abs. 2 BGB); both checkout screens link the withdrawal notice and model
  form (`/legal/terms/<lang>#withdrawal`), without which the period never starts (§ 356 Abs. 3).
  We record which version of the terms was acknowledged, against the account. Without the tick
  the purchase does not proceed at all. So a withdrawal inside 14 days refunds the price minus the
  days used; a renewal opens no new period, and §5.2 governs everything after the 14 days.
- **Faults.** If the service is defective or unavailable in a way we are responsible for, the
  remedies the law provides apply regardless of §5.3.

> **Decided for the terms page (2026-10-28, maintainer acting as counsel):** the published terms
> follow the **16(a)** reading below. Cloud storage is a *digital service* in German law
> (§ 327 Abs. 2 S. 2 BGB), the right of a service contract expires only once it is fully performed
> (§ 356 Abs. 4 BGB; § 356 Abs. 5 covers digital *content* only), and a consumer who asked for an
> early start and then withdraws owes value for the time supplied (§ 357a Abs. 2 BGB). So the terms
> say: the checkout tick is the **request to start at once**, a withdrawal inside 14 days refunds the
> price minus the days used, and a renewal opens no new period; they carry the statutory model
> withdrawal notice and form. Reasons: it is the reading the EU Commission's 2021 CRD guidance gives
> for cloud storage, being more generous than the law is never unlawful while the opposite is, and
> the exposure is the pro-rata slice of month one this section already accepts.
> ✅ **The checkout copy follows (2026-10-28):** Control's `checkout_ack_checkbox` and the web's
> `services.checkout.ackCheckbox` now read *"Ich verlange ausdrücklich, dass Tenshu vor Ablauf der
> Widerrufsfrist mit der Leistung beginnt. Mir ist bekannt, dass ich bei einem Widerruf den Anteil
> des Preises für die Zeit bis zum Widerruf zahle."* and link the notice; `CheckoutWithdrawalCopyTest`
> and `CheckoutPage.test.tsx` pin it. The privacy notice's purchase sentence says the same. The
> mechanism (`acknowledgement_required`, the `PurchaseWithdrawalWaiver` row) is unchanged: it is
> exactly the record § 357a Abs. 2 needs, whatever its name says.

⚖️ **Open for counsel, and the reason this section is not final:**

1. **Which article the acknowledgement is operating under — and only then, its wording.** The
   mechanism is built and enforced server-side — `/commerce/checkout` refuses a request without an
   acknowledgement, and a completed purchase leaves a durable acceptance record naming the
   acknowledged terms version. Two questions sit on top of it, and the second is the one usually
   asked first:

   - ⚠️ **Is this Art. 16(m) at all?** §5.4 is drafted against **Art. 16(m)** of the Consumer
     Rights Directive — *digital content* not supplied on a tangible medium, where express consent
     plus acknowledgement extinguishes the right outright. A cloud-storage subscription is at least
     arguably a **continuously supplied digital service**, which falls under **Art. 16(a)** —
     and that branch extinguishes the right only where the service is **fully performed**. A
     monthly subscription is not fully performed on day 3. If 16(a) is the right reading, then
     **Art. 14(3)** governs instead: a customer who withdraws inside the window owes a
     *proportionate* amount for what was actually supplied, so a partial refund is owed rather
     than nothing.
   - **Then: does the sentence a customer ticks satisfy it**, as transposed in each member state?
     A lawyer's question, not an engineer's.

   **The exposure is bounded, and much smaller than it first reads.** The withdrawal right attaches
   at *conclusion of the contract*, so **renewals carry none** — a customer cancelling midway
   through month 7 has no withdrawal claim at all, only the ordinary cancellation of §5.2 (this is
   the standard online-subscription model, and it is unaffected by any of the above). The worst case
   under a 16(a) reading is therefore a pro-rata slice of **month one**, for customers who withdraw
   within 14 days of first signing up. The product already encodes the underlying principle
   correctly: an acknowledgement is recorded at purchase and a renewal must not manufacture one.

   Purchases made *before* `2026-08-06` carry no acknowledgement and remain refundable inside 14
   days regardless of which article applies.
2. **There is no refund mechanism in the product.** Refunds, where owed, are issued by hand in the
   Stripe dashboard. That is fine at this volume and should be stated as such rather than implied
   to be automatic. ⚠️ Note that refunds occur for reasons the acknowledgement does not touch and
   cannot waive — **chargebacks** (which Stripe executes unilaterally), card fraud, billing errors,
   and the non-conformity remedies §5.4 already carves out. "We do not offer refunds" is therefore
   never the whole picture, and a **money-out event is certain to happen eventually**. ⚠️⚠️ Today
   the commerce ledger records only money *in*: the webhook handles
   `checkout.session.completed`/`invoice.paid` and nothing else, so a hand-issued refund leaves the
   `invoice` table unaware of itself. That is a reporting defect rather than a policy one — it is
   owned by [`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md)
   §4.7 (WC-1), because VAT already remitted on a refunded sale must be **corrected** in a later
   OSS return.
3. **Failed payments are covered by §4.2, not here** — a lapse suspends new uploads and deletes
   nothing.

## 6. What we never do

- We never decrypt user content — including during abuse handling. We cannot: the keys are not ours.
- We never proactively scan shared content. We cannot, and (DSA Art. 8) are not obliged to.
- Enforcement levers are exclusively **content-blind**: quota (§1), transfer limits (§2),
  takedown-by-`shareId` + account suspension (§3), and deletion driven by the user's own file deletes
  or by account closure (§4) — never a deletion we decide by looking at content.

## 7. Who can use Tenshu — minimum age (maintainer decision, 2026-10-24)

- **Tenshu is for people aged 16 and over.** The apps are general-purpose tools and are not designed
  for or directed at children. This is the age the published privacy notice already states
  (`web/apex/support-pages/privacy.ts`, section "children", de/en/fr), and it matches Google Play's
  target audience 16–17 and 18+ (`docs/how-to/play-console-answers.md` §0.9).
- **Why 16, not 13** (13 was considered): a few features rest on *consent* (Art. 6(1)(a)) rather
  than on the account contract — Timeline's opt-in place-name lookup and the marketing-consent flag.
  For those, GDPR Art. 8 lets each EU country set the age of digital consent between 13 and 16
  (Germany 16, France 15); 16 is the highest, so it is valid everywhere without a parental-consent
  check we could not perform. It also keeps the apps outside Google Play's Families policy (any
  audience band under 13), exactly as 13 would.
- ⚖️ **Under 18, for counsel.** The account and payments rest on *contract* (Art. 6(1)(b),
  `commerce-accounts-and-sessions.md` §5.1), so the open question is contractual capacity, not the
  consent age: in Germany a 16- or 17-year-old has limited capacity (§§ 106 ff. BGB) — a contract
  needs a parent's consent unless it brings only a legal advantage (§ 107) or is paid from the
  minor's own means (§ 110) — and if such a contract is provisionally invalid, the 6(1)(b) basis is
  too. Counsel to settle the user-facing wording per market.
- **Purchases:** in-app purchases go through Google Play or Apple's App Store, whose family and
  parental controls apply. ⚠ **Stripe web checkout has no such controls**, and it is exactly where the
  minors-contract question bites.
- ⚠ **Revisit before anything aimed at children.** A family plan where adults share one plan's
  storage does not change this; child accounts or features designed for children would put the apps
  under the Families policy (and COPPA in the US) and need this section rewritten first.
- **Where it is stated:** the privacy notice (16), the terms of use ("Mindestalter": 16, and a
  parent's consent for a paid plan under 18), and the sign-up line on every sign-up screen ("confirm
  that you are 16 or older", 2026-10-28). Changing the age is a `TOS_VERSION` **and** a
  `PRIVACY_VERSION` change (`commerceApi.ts`, `CommercePublisherClient.kt`).

## Terms of use — what the page decided, and the pre-paid-launch review

The page is `web/apex/support-pages/terms.ts` (`TERMS_DATE = 2026-10-28`). It renders §§1–7; where it
had to go beyond them, it chose (maintainer acting as counsel, with the 2026-10-28 review folded in):

- **Contract formation** (§ 312i BGB, Art. 246c EGBGB): free plan on confirming the e-mailed code;
  paid plan on Stripe's payment button, or the store's purchase button. Contract languages de/en/fr,
  the German binding and saying so itself. Every version is stored and the accepted one recorded;
  earlier versions on request. Platforms listed, and that content opens only with the user's keys.
- **Rails.** Web, desktop and APKs outside Play: we sell, through Stripe — upgrades apply at once,
  pro rata; downgrades from the next billing month. Play (and App Store, "as soon as possible"):
  Google or Apple sells, and their rules govern ([`commerce-store-link-out.md`](../proposals/commerce-store-link-out.md)
  D3–D5). Account deletion ends a Stripe subscription at once (the rest forfeited) and stops Play
  renewals; it leaves an Apple subscription untouched.
- **Cancellation:** at any time to the end of the paid month — in Control ("Services → Manage
  subscription"), on the web ("Manage billing"), **or by e-mail to support@** (text form, so a
  missing button never blocks a cancellation). No pro-rata refund of the current month (§5.2/§5.3).
- **Withdrawal:** the 16(a) / § 357a Abs. 2 BGB reading (§5.4), with the statutory model notice and
  form in the model's own "Sie" form, linked from both checkouts as `#withdrawal`.
- **Account deletion:** in Control (Android and desktop — iOS Control has no entry) or at
  `/account/delete`. Content leaves **live** storage 30 days later; signing in again before then on
  a device that still has the data keeps it; **an encrypted backup copy can outlive that** (the
  privacy policy says how long). The page never promises permanent, unrecoverable deletion while the
  mirror is unpruned — a test pins it.
- **Termination by us:** for good cause (abuse) at any time, with a warning where reasonable; and —
  **decision, maintainer-as-counsel default** — an ordinary termination right for **free** accounts
  with **two months'** notice by e-mail. Either way the content goes as on deletion, and prepaid time
  is refunded pro rata, except for abuse.
- **DSA:** support@ is the Art. 11/12 single point of contact (German or English); a notice should
  carry the link without its `#` key, the reason, name and e-mail and a good-faith statement (Art. 16);
  we confirm receipt and tell the reporter the decision; the owner gets a statement of reasons and
  the redress routes, courts included (Art. 17). A link is "blocked or deleted" — never "at once":
  no admin tool revokes by `shareId` yet (tracked separately).
- **Under 18:** a guardian's consent; for a paid plan in every case (§7). The privacy policy's
  children paragraph now says the same.
- **Liability:** unlimited for intent, gross negligence, injury to life, body or health, the
  Product Liability Act and guarantees; for slight negligence only for essential obligations
  (*Kardinalpflichten*) and capped at typical, foreseeable damage; statutory warranty unaffected.
- **Changes to the service** (§ 327r BGB): for a valid reason at no extra cost, told clearly (e.g. in
  the release notes); a change that impairs a paid plan more than insignificantly is announced by
  e-mail and opens a free 30-day termination. A shutdown is announced **at least three months**
  ahead, with a pro-rata refund.
- **Changes to the terms:** announced in advance; the date shows since when a version applies; a
  change to the user's disadvantage applies only once agreed (e.g. at the next sign-in), else the
  old terms continue (BGH XI ZR 26/20 rules out consent by silence).
- **Law and disputes:** German law, with the consumer's home-country mandatory protections kept
  (Art. 6(2) Rome I). § 36 VSBG: **not willing and not obliged** — the imprint's statement. No ODR
  link (the platform closed in July 2025).
- **Not stated, on purpose:** the §2 transfer-limit figures (defaults, not enforced — the page only
  reserves a daily limit) and the backup-copy details (the privacy policy owns them).
- **Privacy policy — changed in the same PR, date unchanged.** Its purchase sentence said the tick
  makes the customer *lose* the withdrawal right, contradicting the terms; it now says what the
  checkout asks (an express early start, pro-rata payment on withdrawal), and the children
  paragraph took the under-18 sentence. Both edits land in the still-unpublished `2026-10-28`
  version: on 2026-10-28 `tenshu.app/legal/privacy/de` still served `2026-10-23` (read-only `curl`),
  so `PRIVACY_POLICY_DATE` / `PRIVACY_VERSION` stay `2026-10-28` and no change notice is owed for
  them. Checklist (`privacy-review-checklist.md`): notice matches the code (the declaration stored is
  the same `PurchaseWithdrawalWaiver` row); German first, EN/FR in step; no new stored item, basis or
  retention; erasure reach unchanged; nothing called anonymous.

⚖️ **The review list, in the order it is owed** (not legal advice; the maintainer acts as counsel
until a lawyer is affordable):

**Before the Play beta** (free accounts and public share links are already live):

1. ✅ Closure text vs the backup mirror (no promise of permanent deletion) — fixed 2026-10-28.
2. ✅ The privacy notice's withdrawal sentence vs the terms — fixed 2026-10-28.
3. **DSA** — contact point, notice contents and statements of reason are in the page; still owed: a
   decision whether public share links make us an online platform (Art. 3(i)), and the admin tool
   that actually blocks a link by `shareId`.
4. ✅ Plan switches per rail, what account deletion cancels per rail, the deletion entry being
   Android/desktop only, and "only encrypted on our servers" — fixed in the page 2026-10-28.
5. **Minors** — the under-18 sentence per market (§§ 106 ff. BGB and equivalents).
6. **Termination by us** — confirm the two-month ordinary notice for free accounts and the
   "except for abuse" carve-out from the pro-rata refund.

**Before the first real Stripe sale:**

7. **§ 312f Abs. 2 BGB** — a contract-confirmation e-mail carrying the terms and the withdrawal
   notice on a durable medium. It is also what gives the model notice its protection (text form).
8. **§ 312k BGB** — a "Verträge hier kündigen" page reachable **without signing in**, leading to a
   confirmation page.
9. **§ 312j Abs. 3 BGB** — whether Stripe Checkout's "Abonnieren"/"Subscribe" label satisfies
   "zahlungspflichtig bestellen".
10. **Art. 246c / 246a EGBGB** — check the pre-contract information is complete where it is shown
    (checkout, not only this page).
11. **Minors on Stripe** — no parental controls on that rail.
12. **WC-1** — the ledger records only money in; a withdrawal refund must leave a trace and correct
    VAT ([`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md) §4.7).
13. **Withdrawal on portal upgrades** — whether an upgrade in Stripe's portal is a new contract with
    its own withdrawal period, and if so how the express request is collected there.

**Needs a lawyer** (the one-off pre-paid-launch review):

14. The withdrawal reading itself (16(a) / § 356 Abs. 4 / § 357a Abs. 2) and the checkout wording.
15. Liability for the free plan (§§ 521, 599 BGB analogous?) and how far the slight-negligence cap
    reaches when the main risk is losing stored data.
16. The re-acceptance flow the change clause promises (none exists yet), and the § 327r notice
    wording.
17. The translations (en/fr unreviewed; no language has had a native-speaker review).
18. Apple's standard EULA beside these terms (no custom EULA is filed).

## Version

**v0.11 · 2026-10-29** · §3 sets the takedown audit row's retention: 3 years from the takedown, then
deleted. No change to the terms' text.

**v0.10 · 2026-10-29** · §3 says how a takedown is operated (the operator route, the CLI, the
append-only audit table, the at-once deletion of the link's copy, the owner's email) and lists what
is proposed but not built: the in-app "removed" flag, blocking sharing, suspending an account. §4.4's
share-link bullet adds the takedown case. No change to the terms' text.

**v0.9 · 2026-10-28** · the terms of use are published (`/legal/terms/<lang>`, `TERMS_DATE`
`2026-10-28`) and accepted at sign-up on Control (Android/desktop), iOS Control and the web, with the
16+ confirmation shown above the button (surface 1, §7). §5.4 now takes the pro-rata reading (16(a) /
§ 357a Abs. 2 BGB) and both checkouts ask for it and link the withdrawal notice. §4.5 no longer
calls closure reclamation "not yet switched on". New section "Terms of use": the decisions (incl.
the two-month ordinary termination of free accounts) and the ⚖️ list in three groups. The privacy
notice's purchase and children sentences were corrected in its still-unpublished `2026-10-28`
version. **`TOS_VERSION` → `2026-10-28`** in both clients; `PRIVACY_VERSION` stays `2026-10-28`.


**v0.8 · 2026-10-24** · new §7: minimum age **16** — the age the published privacy notice already
states — matching the Play target audience (16–17, 18+). Records why not 13 (the consent-based
features and GDPR Art. 8's per-country ages), the under-18 contract-capacity question for counsel,
that Stripe checkout has no parental controls, and the families note.

**v0.7 · 2026-10-24** · §4.3's status note now describes a running process: both account-closure
flags (`ACCOUNT_CLOSURE_RELAY_URL`, `ACCOUNT_CLOSURE_RECLAIM`) were opened in production by the
maintainer's decision, ahead of ⚖️ counsel's review of §4 (still owed). Production then held one
account, the maintainer's own, and none closed — which also answers v0.2's notice-to-existing-accounts
question (moot). §4.3 also states that "30 days later" is a floor plus up to one hourly sweep.
Facts: [`retention-quota-model.md`](../proposals/retention-quota-model.md) §3.3.

**v0.6 · 2026-10-23** · ⚠ §4.4 said *"Server-side deletion is per account, not per file"*. That had
stopped being true of the code: since CR-2 B5/B6 (`content-plane-cr2-b5-spec.md`,
`content-plane-cr2-b6-spec.md`, 2026-10-04) the relay can free a deleted file's encrypted blocks itself,
15–20 minutes after the delete reaches it, when its sweep runs `armed` (`CONTENT_SWEEP_MODE`; `armed` is
the compose file's **default**, not a verified production fact — confirm with
`GET /ops/content-sweep/status` or the relay's boot line). §4.4 now leads with what normally happens and
lists what a file deletion does not remove or may delay: the backup copy (never pruned today), content
whose describe never landed, the drain's account-wide pause, share-link copies, shared-folder copies, the
sync log's change records and deletion notifications, and upload leftovers. §4.1, §4.2, §4.5 and §6
follow. §4.3 gained a status note: account-closure reclamation was then still switched off (both
flags), so that section was a commitment rather than a running process (superseded by v0.7). Facts and citations:
[`retention-quota-model.md`](../proposals/retention-quota-model.md) §3.4; the defects behind the caveats
are in `docs/tech-debt.md`. ⚖️ This changes what the terms say about deleting user data. Whether it owes
a `TOS_VERSION` / `PRIVACY_VERSION` bump and notice to existing accounts is a counsel and maintainer
call, to be taken together with the privacy-notice rewrite. Not bumped here.

**v0.5 · 2026-08-09** · sharpened §5.4's first ⚖️ item. It asked whether the acknowledgement's
*wording* satisfies Art. 16(m); the prior question is **whether 16(m) is the right article at all**,
since a continuously supplied storage subscription arguably sits under **16(a)** (extinguished only
when the service is *fully performed*) — in which case **Art. 14(3)** applies and a withdrawal
inside the window owes a *proportionate* refund rather than nothing. Also recorded why the exposure
is bounded: the withdrawal right attaches at contract conclusion, so **renewals carry none** and the
worst case is a pro-rata slice of month one — ordinary mid-subscription cancellation (§5.2) is
untouched by this and remains the standard model. §5.4 item 2 gained the note that refunds also
arise from chargebacks/fraud/billing errors/non-conformity, which no acknowledgement can waive, and
that the ledger currently records only money *in* — a reporting defect owned by
[`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md) §4.7 (WC-1).
No rule changed, so **ToS + privacy versions unchanged** (`2026-08-06` / `2026-08-04`).

**v0.4 · 2026-08-06** · *(log entry reconstructed 2026-08-09 — the version was bumped in the header
and §5.4 rewritten, but no entry was written, leaving this log asserting the opposite of the
section.)* The EU 14-day withdrawal right **is now disapplied** for acknowledged purchases, closing
v0.3's ⚠️ gap: checkout gained an acknowledgement step that collects express consent to immediate
performance plus acknowledgement that this forfeits the right, **enforced server-side** at
`POST /commerce/checkout` (`400 acknowledgement_required` — the rule lives in the service, not the
client, because the route is one anyone can POST and a UI-only tick would be decoration). A
completed purchase writes a durable `Acceptance` of kind `PurchaseWithdrawalWaiver` naming the
acknowledged terms version; a **renewal carries none and must not manufacture one**. Purchases made
before this date carry no acknowledgement and stay refundable inside 14 days. §5.4 rewritten
accordingly; what remains for counsel is the *wording* per member state, not whether the mechanism
exists. **ToS version `2026-08-06`**; privacy version unchanged.

**v0.3 · 2026-08-06** · added §5 (payments, cancellation & refunds): subscriptions renew until
cancelled; **cancelling stops future charges and does not refund the current period** — the ordinary
online-subscription model; closing an account cancels immediately and **forfeits** the remainder,
which the closure surface must say before the action. Statutory rights carved out explicitly.
⚠️ **Not final**: the EU 14-day withdrawal right is *not* disapplied today, because checkout does not
collect the express-consent acknowledgement that would disapply it — so a withdrawal inside 14 days
of a new purchase is refundable and handled by hand until that is decided. Written to close the ⚖️
item the cancel-on-close work surfaced. **ToS version bumped to `2026-08-06`**; privacy version
unchanged at `2026-08-04` (nothing about data handling moved).

**v0.2 · 2026-08-04** · added §4 (data retention & account closure): no inactivity or lapse-based
deletion, a published 30-day closure grace measured from the recorded closure date, irreversibility
stated plainly, and the two structural limits (no per-file server-side deletion, no reach into your
devices). Written to unblock the ⚖️ prerequisite on `retention-quota-model.md` Face A — the code
path exists but stays switched off until this clears counsel (⚠ superseded: opened 2026-10-24 ahead of
counsel, v0.7). **ToS + privacy versions bumped to
`2026-08-04`** (`CommercePublisherClient.TOS_VERSION` / `PRIVACY_VERSION`), so acceptance is
recorded against the terms that describe the deletion.

⚠️ **Known gap for counsel + product:** acceptance is recorded **once, at registration**, and there
is **no re-acceptance or change-notice flow**. Accounts registered before this bump hold
`2026-06-01` in their acceptance record. Since reclamation shipped switched off, no account was
affected — and before either closure flag opened, the question was whether the change needed notice
to existing accounts. ✅ **Answered 2026-10-24: moot.** When both flags opened, production held a
single account — the maintainer's own — so there were no existing accounts to notify. (The missing
re-acceptance flow still matters for the *next* change to these terms.)

v0.1 · 2026-07-14 · created from the folder-link-sharing Q2 resolution (maintainer): quota = the user's
storage quota; published Dropbox-shape transfer limits; DSA notice-and-action with a fragment-stripping
report form; counsel review required before any of this becomes user-facing legal text.
