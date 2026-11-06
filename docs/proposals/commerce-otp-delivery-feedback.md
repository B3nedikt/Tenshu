# OTP delivery feedback — telling the user when the code is not coming

**Status:** v0.1 (2026-08-27). **§2 SHIPPED** (the synchronous half, this PR); **§3 is design, no
code**. Companion docs:
[`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md) (the account lifecycle
these endpoints serve), [`commerce-end-to-end-dry-run.md`](commerce-end-to-end-dry-run.md) (CE-2 —
where `SmtpEmailSender` came from and what it deliberately does not do).

---

## 1. The incident this comes from

2026-08-27, on the maintainer's own account, signing in from Control-on-iOS:

| step | outcome |
|------|---------|
| `POST /commerce/account/register` | **202 Accepted**, 1650 ms |
| account row | created — `2026-08-27 11:56:29 UTC` |
| OTP | generated, hashed, stored |
| SMTP submission to Scaleway TEM | **accepted**, no exception |
| delivery | **failed — "Mailbox full"**, visible only in the Scaleway console |
| what the user saw | *"We emailed a 6-digit code to …"*, then nothing, forever |

The app's own diagnosis was unavailable to it. `SmtpEmailSender` logs and **rethrows**, and its
KDoc justifies that precisely: *"A send failure propagates … the alternative (swallow it and answer
202 otp_sent) would tell the user a code is coming when none is."* That reasoning is right and the
implementation honours it — but it only covers failures that happen **during** the submission. This
one happened minutes later, at the receiving mail server, long after the request was answered.

⚠ **The code is unrecoverable as a workaround.** OTPs are stored as `codeHash`
(`CommerceStore.putEmailVerification`) and `SmtpEmailSender` never logs the code by design, so
there is no operator path to read it out and hand it over. The user is simply stuck until they can
receive mail.

⭐ **Domain authentication is complete** — verified 2026-08-27: SPF
(`include:_spf.tem.scaleway.com`), the `blackhole.tem.scaleway.com` MX, DMARC (`p=none`), and a
2048-bit **DKIM** key all resolve. Nothing here is a mail-authentication problem.

> ⚠⚠ **Do not conclude "no DKIM" from a selector probe.** Scaleway TEM's selector is a **UUID**
> (`<uuid>._domainkey.tenshu.app`), so it is unguessable by construction: this doc's first draft
> swept 21 plausible selector names, found nothing, and asserted the record was missing. It was
> published and propagated the whole time. The two reads that actually answer the question are the
> selector shown in the TEM console, and the `s=` field of a `DKIM-Signature:` header on mail that
> arrived — never a guess list.

---

## 2. The synchronous half — shipped

Three failures the service already distinguished and the client threw away.

**Server.** `/register` no longer lets a send failure fall through to StatusPages' content-free
`500 internal_error`. It answers **`503 {"error":"send_failed"}`** — a transport failure the caller
can act on, told apart from a genuine bug.

⚠ **`/claim` was removed on 2026-10-13** (no client called it; `commerce-accounts-and-sessions.md`
§4.2). The paragraph below is kept because the rule outlives the route: any endpoint that sends only
for an address that exists must not let its answer vary with the send.

**⚠⚠ `/claim` does the opposite, and that asymmetry is load-bearing.** `/claim` attempts a send
only for an address that already has an account, so *any* answer that varies with the send's
outcome varies with **existence**. Before this change the unguarded throw reached StatusPages and
produced `500` for a real account and `202` for a fake one — the account-enumeration oracle the
endpoint's 202-regardless contract exists to prevent, handed over free by any transport outage.
`/claim` now swallows the failure, logs it, and answers 202 either way.
`OtpSendFailureTest.claim_answersIdenticallyForRealAndFakeAddresses_whenTheSendFails` pins it.

**Client.** `CommercePublisherClient.register` mapped every non-202 to *"Something went wrong.
Please try again."* It now maps by status:

| status | message |
|--------|---------|
| `429 rate_limited` | "Too many codes requested for this address. Wait an hour, then try again." |
| `503 send_failed` | "We couldn't send the email just now. Try again in a moment." |
| transport throw | (unchanged) "Couldn't reach the account service. Check your connection…" |
| anything else | (unchanged) "Something went wrong. Please try again." |

⚠ The 429 copy says **wait**, never *try again* — the budget is per hour (`20/ip/h`, `5/email/h`
send), so "try again" was the one instruction guaranteed not to work. It stays vague about the
number: the limits are a deployment knob and a quoted figure would go stale.

---

## 3. The asynchronous half — the bounce channel (design)

Everything in §2 is invisible to the incident in §1. A message the relay accepts and later fails to
deliver is past every point where our request handler still exists. To surface it at all, the
outcome has to come back to us from the provider and then be readable by the client.

### 3.1 Where the information is

Scaleway TEM records a per-message delivery status (the console showed "Mailbox full" for this
exact send) and can push delivery events. That is the only source: SMTP submission already
succeeded, so nothing local can derive it.

### 3.2 ⚠ The seam has to change, and its KDoc says why it was shut

`EmailSender.sendOtp` returns `Unit`, deliberately — *"which is why the interface returns Unit and
not a delivery receipt."* Correlating a later event with the attempt that caused it needs an
identifier: either the provider's message id captured at send time, or one we mint and attach as a
custom header for the provider to echo. Either way `sendOtp` grows a return value or an out-param,
and that is a real reversal of a documented decision, not an oversight to patch around. Take it
knowingly.

### 3.3 ⚠⚠ The hard part is not the webhook — it is who may ask

A delivery-status read keyed by **email address** is an enumeration oracle, the same class the
`/claim` 202 exists to avoid. The obvious shape — server returns an opaque, unguessable
`attemptId` in the 202 body; client polls `GET /commerce/account/registration/{attemptId}` — fixes
*that* leak: the id is unguessable and reveals only the outcome of an attempt the caller made.

But it introduces a second one that is easy to miss: **an attacker can register any address and
learn whether it bounced.** That turns the service into a free email-validation oracle — hard
bounce means "no such mailbox". They could learn the same by sending mail themselves, so this is
not catastrophic, but it is a capability we would be handing out at our own cost and under our own
sending reputation.

Mitigations, none free (see D2):

- **Collapse the taxonomy** the user sees to "we couldn't deliver to that address", never
  distinguishing hard bounce from full mailbox from blocked. Cheapest; costs the specific advice
  that would actually help ("your mailbox is full").
- **Report detail only for an address with an already-verified account**, generic otherwise. Keeps
  the useful message for the case in §1 (the maintainer's own account) and denies the oracle.
- **Spend send budget before answering**, as `/claim` already does for exactly this reason, so
  probing is rate-limited rather than free.

### 3.4 What the screen would say

The code step already has the affordances — *"Send a new code"* and *"Use a different email"* — so
this is copy plus a state, not new UI. On a delivered-then-bounced attempt it should stop implying
the code is in flight and point at the two moves that work. ⚠ It must not say "the address is
wrong" for a soft bounce: a full mailbox is a correct address the user can fix.

### 3.5 Operator visibility, which matters more than the user-facing half

A bounce whose reason is *authentication* (missing DKIM, DMARC alignment, blocklisting) is a
configuration failure, not a user failure, and telling the user to "try another address" would hide
exactly the signal the operator needs. Auth-class bounces should be visible to the maintainer —
admin surface or an alert — and must never be phrased to the user as their problem.

---

## 4. Decisions owed

- **D1 — correlation mechanism.** Provider message id captured from the transport, or our own id
  echoed via a custom header? The first is simpler; the second survives changing providers, which
  is the property CE-2 chose SMTP for in the first place.
- **D2 — how much of the bounce taxonomy reaches the user** (§3.3). This is the one that needs a
  call before any code; everything else follows from it.
- **D3 — poll or surface-on-next-action.** A poll needs a budget of its own; surfacing on the next
  "Send a new code" tap is free and slower. Bias toward the second unless the delay reads as broken.
- **D4 — retention.** An address plus a bounce reason is A28 personal data. Delivery events need a
  stated retention window, and it should be short — days, not the account's lifetime.

## 5. Phases

| phase | what | gated on |
|-------|------|----------|
| **P1** | Synchronous typed failures (§2) | — **shipped** |
| P2 | `EmailSender` returns a correlation handle; persist per-attempt delivery state | D1 |
| P3 | TEM webhook endpoint — signature-verified, idempotent | P2 |
| P4 | Client read path + code-screen copy | D2, D3 |
| P5 | Operator visibility for auth-class bounces (§3.5) | P3 |

## 6. What this is not

Not a retry or queue. `SmtpEmailSender` sends synchronously on the request thread and CE-2 accepted
that ("volumes are tiny"); nothing here changes it. A bounce is not a retryable condition anyway —
resending into a full mailbox fails identically, which is why the answer is to tell the user rather
than to try harder.
