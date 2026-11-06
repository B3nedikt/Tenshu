# Set up OTP email delivery

Wires a real transport behind `:commerce-service`'s `EmailSender` so the account OTP actually
reaches a user's inbox (`commerce-end-to-end-dry-run.md` CE-2). Until this is done the code is
printed to the container log and the flow cannot be demonstrated to anyone who is not reading it.

**§A picks a provider** (including the GDPR position, and why *not* to route by user location).
**§0–§10 are a worked setup**, written against Amazon SES because that is the case with the most
gotchas; §2–§4 (DKIM, SPF alignment, DMARC) are the same work at every provider, with different
hostnames.

> ⚖️ **Deliverability is the whole job.** The service side is done and configuration-only; what
> takes the time is DNS. An OTP that lands in spam is indistinguishable from "the OTP is broken"
> from a user's side, and unlike a broken feature nobody reports it — they just don't sign up.
> Budget for DNS propagation and do this before the demo, not on the day.

> **Nothing here locks us to one vendor.** `SmtpEmailSender` speaks plain SMTP precisely so the
> provider is a `deploy.env` change (CE-2 / D3) — four env vars and a DNS change, no code.

---

## A. Choosing a provider

### A.1 The candidates

Every option below speaks SMTP, so **the code does not care** — this is purely an operational and
jurisdictional choice. ⚠ Ownership changes hands often in this market; verify the parent company
before committing, because the parent is the whole point of the EU column.

| Provider | Home / parent | Why you'd pick it | Why you wouldn't |
|---|---|---|---|
| **Scaleway Transactional Email** | 🇫🇷 France (Iliad) | Strongest sovereignty story of the lot — French company, French infra, **no US parent**. Cheap, plain SMTP, no marketing-platform baggage. | Youngest and smallest of these; lower default quotas and a stricter anti-abuse gate to get through. |
| **Mailjet** | 🇫🇷 France, parent 🇸🇪 Sinch | Mature transactional sender with an explicit EU data-residency option; EU-parented, so the transfer question still doesn't arise. | Shares a parent with Mailgun; check which entity actually processes if residency matters. |
| **Brevo** (ex-Sendinblue) | 🇫🇷 France | Mature, well-documented, generous free tier, strong GDPR marketing posture. | It is a *marketing* platform that also does transactional — more product than we need. |
| **MailerSend** | 🇱🇹 Lithuania (MailerLite) | EU-parented, transactional-first, pleasant API. | Smaller ecosystem; fewer people have debugged its edge cases publicly. |
| **Amazon SES** | 🇺🇸 US parent, EU regions available | Cheapest at volume, best-understood, everyone has run it. | US-parented — the one open question in §9. |
| **Postmark** | 🇺🇸 US (ActiveCampaign) | Best-in-class deliverability reputation for transactional mail. | US-parented **and** no EU processing region — strictly worse than SES for us. |

**Recommendation, in order:**

1. **Scaleway TEM** if the privacy positioning is meant to be load-bearing in marketing. It is the
   only option where "your email address never leaves EU-owned infrastructure" is true without
   qualification, which is a sentence that matches the rest of the product.
2. **Mailjet** if you want that story with more maturity behind it and are content with
   EU-parented-but-larger.
3. **SES** if you want to move today and treat the sovereignty question as acceptable-with-SCCs
   (§9). It is a defensible choice; it is just not a *distinctive* one.

For **the dry run specifically**, any of them works, and Brevo/Mailjet free tiers are the fastest
route to a first real email because SES starts sandboxed (§5). Don't over-think the demo choice —
it is four env vars.

### A.2 "EU provider for EU users, SES for everyone else" — don't

It's a reasonable instinct, and the answer is a clear no. Three reasons, in increasing order of how
much they'd actually hurt:

1. **You'd be routing on a signal that doesn't answer the legal question.** At `/register` all you
   have is an email address and an IP. But GDPR does not apply based on where someone is standing
   — it applies to your establishment and to offering services to people in the EU. A German user
   on holiday in California is still a data subject; a US user on an EU VPN is not. IP geolocation
   would sort people into the wrong buckets *and* the sorting itself processes personal data.

2. **It doubles the part that is actually hard.** The service side is four env vars, but
   deliverability is DKIM keys, an SPF include, DMARC alignment, reputation warm-up, bounce
   handling and a suppression list — **per provider**. Two providers means two of each, two
   reputations that can independently rot, and two suppression lists that don't know about each
   other, so an address that hard-bounced on one still gets tried on the other. There is also a
   hard technical ceiling: SPF permits **10 DNS lookups**, and every `include:` spends some.

3. **It increases the compliance work rather than reducing it.** Both providers land in the Art. 30
   record and the privacy notice, and you additionally have to document and defend the routing
   rule. Worse, the awkward case doesn't go away: to claim "no EU resident's data reaches the US
   provider" you'd have to *prove* the geolocation never misfires, which you can't.

And the thing being protected is **one email address plus a six-digit code** — an address the user
also typed into a dozen newsletters. Splitting infrastructure to shrink a blast radius that small
is poor economics.

**If sovereignty matters, use the EU provider for everyone.** It's simpler, cheaper, and a strictly
better sentence: *"all account email is processed in the EU"* beats *"EU users get EU processing"*,
which invites the obvious follow-up.

### A.3 The two versions of this idea that *do* make sense

- **Failover, not jurisdiction.** A second provider as a fallback when the first is down is
  genuinely valuable for OTP mail, because an outage means *nobody can sign up*. That is a
  health-based switch, not a geography-based one, and it wants both providers in the **same**
  jurisdiction. The seam already supports it — a `FailoverEmailSender` wrapping two
  `SmtpEmailSender`s needs no interface change. Premature at current volume; worth knowing the
  shape.
- **Tenant-pinned data residency.** The real version of "EU users get EU treatment" is the user
  choosing a data region at signup, with the **account store** pinned to it — and email then simply
  follows the tenant's region. Note the direction: residency is a property of where the PII
  *lives*, not of how one message is sent. Our A28 store is a single Postgres today, so this would
  be a much larger design (region-pinned Postgres, region-pinned relay) and email would be a
  consequence of it, never the thing driving it.

⚠ Worth noticing: the account store — the Postgres that holds these addresses durably — is already
on an EU droplet. Sending to an EU region of any provider above keeps the whole path in the EU. The
only question §9 is really asking is whether a **US-parented** company sits behind that EU region.

### A.4 Scaleway TEM — the concrete settings

**This is the provider in use for `tenshu.app`.** §0–§10 below are worked against SES because it
has the most gotchas; everything structural there still applies (DKIM, SPF alignment, DMARC,
suppression, reputation). Only these values differ:

```dotenv
SMTP_HOST=smtp.tem.scaleway.com
SMTP_PORT=2587                     # ⚠⚠ NOT 587 on a DigitalOcean droplet — see below
SMTP_USERNAME=<the Project ID of the project the TEM domain lives in>
SMTP_PASSWORD=<the SECRET KEY of an API key in that project>
SMTP_FROM=no-reply@tenshu.app
SMTP_FROM_NAME=Tenshu
SMTP_STARTTLS=true
```

⚠⚠ **Use port 2587. DigitalOcean blocks outbound 25, 465 **and** 587 from the droplet**, so the
standard submission port fails with a *connect timeout* — `SocketTimeoutException` out of
`SMTPTransport.openServer`, surfacing as `500` on `/commerce/account/register` after ~15 s.
Scaleway offers **2587** (STARTTLS) and 2465 (implicit TLS) for exactly this; `SmtpEmailSender`
sets `starttls.required`, so **2587** is the one that matches — 2465 would need implicit TLS.

⚠ **This can bite you *after* it has already worked.** Observed 2026-08-07: the first OTP of the
day delivered fine on 587, and the port was blocked later the same session — DO applies the block
once it observes outbound mail, so a successful first send is not evidence the port is open.
Verify from the droplet rather than from a send:

```bash
for p in 587 2587 465 2465 25; do
  printf "port %-5s " $p
  timeout 6 bash -c "cat < /dev/null > /dev/tcp/smtp.tem.scaleway.com/$p" 2>/dev/null \
    && echo OPEN || echo "blocked/timeout"
done
```

⚠ The same block is why DO deploys cannot use most providers' default port either — it is a
property of the *host*, not of Scaleway. On a provider that does not block, 587 is fine.

⚠⚠ **The username is a Project ID, not an email or a login.** Both credentials come from the
Scaleway console (**Transactional Email → Domain overview** for the project id; **IAM → API keys**
for the secret). The API key's secret is shown **once** at creation, exactly like SES's.

What Scaleway does differently from §2–§4:

- **DKIM is a single TXT record**, not SES's three CNAMEs, and its name is shown in the console —
  don't guess the selector. Add it in the DO DNS panel like any other record.
- **SPF is one include on the apex** (`v=spf1 include:_spf.tem.scaleway.com -all`) — there is **no
  custom MAIL FROM subdomain to configure**, so §3's `mail.` MX/TXT pair has no Scaleway
  equivalent. Alignment comes for free.
- ⚠ Scaleway publishes an **MX of `blackhole.tem.scaleway.com`** for the sending domain. That is
  deliberate — it is a domain that sends but does not receive. If `tenshu.app` ever needs a real
  inbox, that MX is the record you must change, and doing so is a mail-routing decision, not a
  TEM one.
- **Verification is explicit**: after the records are in, hit *Verify* in the console (or the API).
  Nothing sends until the domain reads **Verified**, and propagation can take hours.
- **The sandbox equivalent** is a per-domain quota rather than a verified-recipients-only mode, so
  §5's "it works for me is a sandbox illusion" hazard does not arise in the same shape — but the
  quota does start low, and raising it goes through an anti-abuse review (§A.1).

The GDPR question in §9 largely dissolves here: Scaleway is French, Iliad-parented, with no US
parent — which is the whole reason §A.1 ranks it first. The "still owed" list at the end of §9
(Art. 30 record, privacy notice, retention, deletion) applies unchanged; only the
transfer-impact-assessment item drops away.

---

## 0. What you need

- An AWS account.
- Control of `tenshu.app` DNS. Today that is the DigitalOcean **Networking → Domains** panel (the
  same zone the relay/apex records live in — see `deploy-relay-digitalocean.md`).
- ~30 minutes, plus DNS propagation (minutes to a few hours), plus **up to 24 h** if you need to
  leave the SES sandbox (§5).

---

## 1. Pick a region

Use an **EU region** — `eu-central-1` (Frankfurt) or `eu-west-1` (Ireland). This decides where
SES processes the recipient address and message body, and it is the first thing §9 leans on.

Everything below is per-region: an identity verified in Frankfurt does not exist in Ireland, so
pick once and stay there. Note it down — the SMTP endpoint carries it.

---

## 2. Verify the domain, with Easy DKIM

SES console → **Configuration → Identities → Create identity → Domain** → `tenshu.app`. Leave
**Easy DKIM** on (RSA 2048).

SES gives you **three CNAME records**. Add all three in the DigitalOcean DNS panel:

| Type | Hostname | Value |
|---|---|---|
| CNAME | `<token1>._domainkey` | `<token1>.dkim.amazonses.com.` |
| CNAME | `<token2>._domainkey` | `<token2>.dkim.amazonses.com.` |
| CNAME | `<token3>._domainkey` | `<token3>.dkim.amazonses.com.` |

⚠ DigitalOcean appends the domain to hostnames — enter `<token>._domainkey`, **not**
`<token>._domainkey.tenshu.app`, or you will create `._domainkey.tenshu.app.tenshu.app`.

Wait for the identity to flip to **Verified**. Nothing else works until it does.

---

## 3. Set a custom MAIL FROM domain (this is the SPF half)

Still on the identity → **Custom MAIL FROM domain** → `mail.tenshu.app`.

DKIM alone is not enough for a strict DMARC policy: DMARC needs **alignment**, and by default SES
sends with its own `amazonses.com` envelope sender, which aligns with nothing of ours. A custom
MAIL FROM subdomain is what makes SPF align with `tenshu.app`.

| Type | Hostname | Value |
|---|---|---|
| MX | `mail` | `10 feedback-smtp.<region>.amazonses.com.` |
| TXT | `mail` | `"v=spf1 include:amazonses.com ~all"` |

⚠ The MX here is for SES's **bounce feedback**, not for receiving user mail. If `tenshu.app` ever
gains a real inbox, that is a different record on a different name — don't merge them.

---

## 4. Publish DMARC

| Type | Hostname | Value |
|---|---|---|
| TXT | `_dmarc` | `"v=DMARC1; p=none; rua=mailto:dmarc@tenshu.app"` |

Start at `p=none` — it asks receivers to *report* rather than reject, so a misconfiguration shows
up in the reports instead of silently destroying every OTP. Once the reports are clean for a week
or two, tighten to `p=quarantine` and then `p=reject`.

⚠ Only **one** DMARC record per domain. If one already exists, edit it rather than adding a second
— two records is treated as none.

---

## 5. Leave the sandbox (or don't, yet)

New SES accounts are sandboxed: **you can only send to addresses you have verified**, capped at
200 messages/day and 1/second.

- **For the dry run that is fine.** Verify your own address (Identities → Create identity → Email
  address, click the link) and the §5 demo works end to end. Do this first — it unblocks you today.
- **Before any real user signs up** you must request production access: SES console → **Account
  dashboard → Request production access**. Describe the use case honestly: *transactional
  one-time-passcode emails to users who entered their own address to create an account; no
  marketing; recipients are never uploaded in bulk*. Approval is usually well under 24 h.

⚠ While sandboxed, a send to an unverified address fails at the transport, so `/register` answers
`500` and the user sees a generic failure. That is the honest behaviour (CE-2 chose to propagate
rather than claim `202 otp_sent` with no mail coming), but it does mean *"it works for me"* is a
sandbox illusion until you have production access.

---

## 6. Create SMTP credentials and configure the service

SES console → **SMTP settings → Create SMTP credentials**. This creates an IAM user with
`ses:SendRawEmail` and shows a username + password **once**.

⚠⚠ SMTP credentials are **not** your AWS access keys. The SMTP password is derived from an IAM
secret by a documented algorithm; pasting a raw AWS secret key will fail to authenticate. Take the
values from this screen, and store them the way every other deployment secret is stored.

Then, in the commerce `deploy.env`:

```dotenv
SMTP_HOST=email-smtp.<region>.amazonaws.com
SMTP_PORT=587
SMTP_USERNAME=<from the SES screen>
SMTP_PASSWORD=<from the SES screen>
SMTP_FROM=no-reply@tenshu.app
SMTP_FROM_NAME=Tenshu
SMTP_STARTTLS=true
```

Port 587 with STARTTLS matches the service's defaults, and `SmtpEmailSender` sets
`starttls.required` so it will **refuse to fall back to plaintext** — the OTP is a live credential
and must not cross the network in clear.

⚠ `SMTP_FROM` must be on the verified domain. SES rejects a `From` it has not verified, and the
failure surfaces as a generic send error rather than anything that names the cause.

Redeploy: `./deploy/redeploy-commerce.sh` (or edit `/opt/tenshu/deploy.env` and
`docker compose … up -d --force-recreate commerce`).

Confirm on boot — the service prints which sender it selected:

```
Commerce email: SMTP email-smtp.eu-central-1.amazonaws.com:587 from no-reply@tenshu.app
```

If it instead prints `DEV LOG SENDER`, the SMTP vars did not reach the container. And note that
with `DEV_MODE=false` the service now **refuses to start** in that state rather than quietly
logging codes — so a silent misconfiguration is not a state you can end up in on production.

---

## 7. Try it locally first

The whole thing can be rehearsed with no deployment at all:

```bash
SMTP_HOST=email-smtp.eu-central-1.amazonaws.com \
SMTP_FROM=no-reply@tenshu.app \
SMTP_USERNAME=... SMTP_PASSWORD=... \
./scripts/local-commerce-stack.sh
```

The banner will say `Commerce email: SMTP … (real mail)`. Sign up from Control with a **verified**
address (§5) and the code arrives in your inbox instead of the log.

Check the message lands in the inbox and not in spam, and that the header shows `dkim=pass` and
`spf=pass` — in Gmail, *Show original*.

---

## 8. Rate limits are already on

`/register` is budgeted at 20/IP/hour and 5/email/hour, `/verify` at 20/email/hour
(CE-2). These exist because `/register` is unauthenticated **by design** — the verified email is
the sybil gate — so without them a real sender is an unmetered outbound-mail bill and a way to
mail-bomb a third party. Tune with `OTP_SEND_PER_IP_PER_HOUR`, `OTP_SEND_PER_EMAIL_PER_HOUR`,
`OTP_VERIFY_PER_EMAIL_PER_HOUR`; `<= 0` disables one.

They are also what protects the **SES reputation metrics** that decide whether we stay out of the
sandbox: a bounce rate over ~5% or complaints over ~0.1% puts sending under review.

---

## 9. Is this GDPR-compliant? — the honest answer

**Not legal advice**, and this belongs on the ⚖️ counsel list alongside the retention clause and
the acceptable-use policy. But the shape of the question is clear enough to write down.

**Short version: yes, workably. With any of §A's EU-parented providers the question barely arises;
with SES it does arise, is answerable, and is the one place in Tenshu where personal data reaches a
US-parented company — which deserves a deliberate decision rather than a default.**

### What is actually involved

Very little, which helps. The only personal data reaching SES is the **recipient's email address**
and the **message body** (a six-digit code). No name, no `ownerId`, no realm data — the sync plane
is ciphertext-only (House Rule #1) and nothing about it goes near email. The address is already
stored in our own Postgres as A28 PII, so SES adds **no new category of data**: what it adds is a
new **processor**, and with it a possible **international transfer**.

### The parts that are straightforwardly fine

- **Roles.** We are the controller; AWS is a processor. Standard.
- **Processor agreement (Art. 28).** AWS incorporates a GDPR Data Processing Addendum into its
  Service Terms by default — there is nothing separate to sign. Verify it still reads that way when
  you sign up rather than trusting this sentence.
- **Legal basis.** Art. 6(1)(b), performance of a contract — the user typed their address in order
  to create an account, and the OTP is how that account is created. Already what the code
  documents; no consent needed and none should be asked for, which is also why this must never
  become a channel for anything else.
- **Data minimisation.** One address, one code, no attachments, no tracking pixel, no open
  tracking. Keep it that way.
- **Region.** Choosing `eu-central-1` / `eu-west-1` (§1) keeps processing and storage in the EU.

### The part that needs a decision

AWS is US-parented, so even with an EU region there is a **potential for US access** (CLOUD Act,
remote support). The usual mitigations apply and are real: the Standard Contractual Clauses in
AWS's DPA, its supplementary-measures commitments, and its certification under the EU–US Data
Privacy Framework. Most EU companies rely on exactly this and it is defensible. Whether it is
*comfortable* is a different question — and for a product whose entire premise is that we cannot
read your data, routing account identifiers through a US hyperscaler is a slightly awkward
sentence to have to write in a privacy notice.

Two things make this cheaper to decide than it looks:

1. **The blast radius is one email address.** Not content, not metadata about usage — the same
   address the user could equally have typed into any newsletter.
2. **Switching is four env vars.** `SmtpEmailSender` speaks plain SMTP for exactly this reason
   (CE-2 / D3). The EU-parented options in **§A.1** remove the transfer question entirely and cost
   a `deploy.env` change plus the same DNS work in §2–§4. If the sovereignty story matters to the
   brand, that is the cheaper answer, and it is worth deciding **before** DNS records are published
   rather than after.

⚠ And note what is *not* a way out: routing EU users to an EU provider and everyone else to SES.
**§A.2** works through why that sorts people by the wrong signal, doubles the deliverability work,
and grows the compliance file instead of shrinking it.

**Recommendation:** if you want to move today, SES with an EU region is fine and I would not block
on it. If the privacy positioning is meant to be load-bearing in marketing, prefer an EU provider —
the work is identical and the sentence in the privacy notice is much easier to write.

### Either way, still owed

- **Art. 30 record of processing** naming the chosen provider as a processor for "account
  verification email".
- **Privacy notice** naming the recipient category. ⚠ Acceptance is recorded **once at
  registration** and there is **no re-acceptance flow**, so decide deliberately whether this
  warrants a `PRIVACY_VERSION` bump — the same open question already flagged for the 2026-08-04
  retention clause. Existing accounts hold older versions.
- **A transfer impact assessment** if you stay with a US-parented provider and want the file to be
  complete.
- **Retention at the provider.** SES does not persist message bodies, but does keep event and
  reputation data. Don't add logging that would make a code or an address durable at either end —
  `SmtpEmailSender` deliberately never logs the code, and `LoggingEmailSender` (which does) cannot
  start in production.
- **Deletion.** An address in the SES **suppression list** outlives an account closure. If we ever
  action an erasure request, the suppression entry is a place to check.

---

## 10. Once it works

- Set a **bounce/complaint destination**: SES → Configuration sets → an SNS topic, or at minimum
  check the Account dashboard's reputation metrics before launch. Sustained bounces get sending
  paused.
- Keep the SES **suppression list** in mind: an address that hard-bounced is dropped silently
  afterwards, which will look like "the OTP never arrives" for that one user.
- ⚖️ Work through §9's "still owed" list before real users sign up (Art. 30 record, privacy
  notice, `PRIVACY_VERSION` decision). CE-2's threat-model delta is an A28 processor note.
