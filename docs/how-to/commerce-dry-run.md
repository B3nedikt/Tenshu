# The commerce dry run — walk the whole flow once, locally

One pass through **sign-up → entitlement → relay enforcement → purchase → tier change**, against a
real relay and a real commerce issuer running on your own machine, with only the payment processor
faked. This is §5 of [`commerce-end-to-end-dry-run.md`](../proposals/commerce-end-to-end-dry-run.md),
made runnable.

> This is the **local** runbook. [`reset-and-commerce-test.md`](reset-and-commerce-test.md) remains
> the one for a *deployed* instance — it wipes a droplet and flips enforcement on a live relay.
> Nothing here touches anything outward-facing.

**What a successful pass proves.** Three things had never been observed together before this
existed: that the relay accepts a push carrying a commerce-minted credential; that the usage figure
on the account card is the relay's real number; and that a tier change propagates from a payment
event all the way to the card. Everything in the chain is real except Stripe.

**What it does not prove.** Live Stripe field mappings (CE-3 fakes the processor), real email
deliverability unless you wire SMTP (§2), Android/iOS clients, or anything about production
infrastructure.

---

## 0. Prerequisites

- A JDK the repo's toolchains are happy with, and a desktop session (Control is a GUI app).
- ~10 minutes for the first run; the fat jars build once and are incremental afterwards.
- Optional: SMTP credentials to receive the OTP as a **real email** — see
  [`set-up-otp-email.md`](set-up-otp-email.md). Without them the code is printed to the commerce
  log, which is fine for a wiring check but is not the demo.

---

## 1. Bring the plane up

```bash
./scripts/local-commerce-stack.sh --reset
```

`--reset` wipes `.tenshu-local/` first, so you start from an empty relay and no accounts. Leave it
off to keep state between runs.

You should see:

```
==> Commerce store: IN-MEMORY (accounts are lost on restart)
==> Commerce email: dev log sender (export SMTP_HOST + SMTP_FROM for real mail)
==> Starting commerce issuer on 0.0.0.0:8081 (admin on 127.0.0.1:8082)
==> Waiting for the commerce issuer public key
    issuer pubkey: rcFAEfgtHFbZVqpPnXPYhYNhpgYEhSXg0Ixjjcdd2Mc=
==> Starting sync relay on 0.0.0.0:8080 (enforce=true)
────────────────────────────────────────────────────────────────────────────
  Tenshu local plane is up.
  …
```

Two lines are worth reading rather than skipping:

- **`issuer pubkey:`** — the script scraped it from commerce's boot log and handed it to the relay's
  `ENTITLEMENT_ISSUER_PUBKEY`. That is the same hand-off an operator does in production; here it is
  automatic because dev mode uses the pinned key.
- **`enforce=true`** — the relay will refuse unentitled writes. If you ever see `enforce=false`, the
  pass proves much less.

The script then tails both logs. Keep this terminal visible; steps 3 and 6 are read from it.

## 2. Optional — real email instead of the log

```bash
SMTP_HOST=… SMTP_FROM=no-reply@tenshu.app SMTP_USERNAME=… SMTP_PASSWORD=… \
  ./scripts/local-commerce-stack.sh --reset
```

The banner then says `Commerce email: SMTP … (real mail)` and the OTP arrives in your inbox. With
Amazon SES you must be out of the sandbox **or** have verified the recipient address first.

## 3. Run Control against it

In a second terminal:

```bash
./scripts/run-control-desktop-local-commerce.sh
```

⚠⚠ **Use this script, not `./gradlew :control-desktop:run`.** The plain run has
`TENSHU_BILLING_MOCK` defaulting to **true**, which selects `MockPublisherClient` — a fixture that
accepts any six digits and grants tiers locally without ever contacting the commerce service. The
whole dry run would appear to succeed while proving nothing. The script sets the mock off and points
all three commerce seams at `localhost:8081`.

Onboard as a new user. This bootstraps a genesis realm against your local relay.

### 3b. Or run the web surface against it

The apex has the same account + purchase flow (`docs/proposals/apex-currency-sweep.md` F2).

The stack script already admits the Vite dev server's origin (`WEB_APP_ORIGIN` defaults to
`http://localhost:5173`), so step 1 needs no change. Point the SPA at the local service and run it:

```bash
cd web && VITE_TENSHU_COMMERCE_URL=http://localhost:8081 pnpm -F apex dev
```

⚠⚠ **`WEB_APP_ORIGIN` is what makes this work at all, and it is not cosmetic.** The commerce service
ships with **no CORS** — every caller before the web surface was a native HTTP client, which the
same-origin policy does not apply to. If you serve the apex from any other origin (a preview build on
`:4173`, say), pass it through or every call dies in CORS preflight with nothing useful in the
browser console:

```bash
WEB_APP_ORIGIN=http://localhost:4173 ./scripts/local-commerce-stack.sh
```

⚠ It does double duty: it is also where Stripe sends the customer back to after paying
(`<origin>/control/services/checkout-result`). A `client=Web` checkout against a service with no web
origin configured is **refused before a Stripe session is created**, rather than taking the money and
then redirecting to a `tenshu://` link a browser cannot follow.

⚠ The web surface reaches Services at **/control/services**. Its account card reads the *synced*
entitlement, so — exactly as on native — the tier appears there when the webhook's grant syncs back,
not when the browser returns from Stripe.

## 4. Sign up

**Services → Sign up.** Enter an address and submit.

Read the code from the commerce log:

```bash
grep -i 'OTP for' .tenshu-local/commerce.log
```

Enter it. On success the service binds this device's `ownerId`, opens a session, and grants the
Free entitlement.

*If the code doesn't arrive:* **Send a new code** on the same screen re-sends without losing your
place. It draws on the per-address budget (5/hour) — see §9 if you hit `429`.

## 5. Watch the entitlement land

The Services card goes through two states, and both are correct:

1. **"Setting up your storage — this usually takes a moment."** The session exists; the signed
   entitlement has not synced back yet.
2. **"Plan: Free · 0 B of 5 GB used"** with a gauge, plus your email on the identity row.

The commerce log shows `POST /commerce/entitlement/mint` and then
`POST /commerce/entitlement/token`. The first is the synced record every device converges on; the
second is the bearer credential the relay checks.

## 6. Upload something — the commerce ⇒ relay hop

Start Drive (it talks to Control over IPC; Control must stay running):

```bash
./gradlew :drive-desktop:run
```

Upload a file. Then watch the relay log:

```bash
grep -E "POST - /sync/push" .tenshu-local/relay.log | tail
```

**`200` is the pass.** This is the step that had never been observed: a write accepted by a relay
with enforcement on, carrying a credential minted by the commerce service.

⚠ **Watch the right endpoint.** `POST /sync/changes` is the *ungated* poll and stays `200`
regardless of enforcement — it is not evidence of anything. `/sync/push` is the gated write.
A `402` there means no entitlement was presented; a `401` means the request never authenticated at
all (a different problem — device auth, not entitlement).

## 7. Real usage on the card

Return to Services. The gauge now reflects what the **relay** reports for your owner
(`GET /sync/usage`), not a local guess. Uploading more and revisiting moves it.

## 8. Buy Premium

**Get more storage → Premium.** Control opens a browser at the local confirm page — a stand-in for
Stripe Checkout, clearly badged *DEV PAYMENT RAIL — no money moves*.

Enter an email (use the same one you signed up with, so the purchase binds to your account) and
press **Pay**.

⚠ **Expect the browser to complain about `tenshu://checkout-result`.** The success redirect is a
deep-link Control does not register yet (the Layer-2 deep-link is still open in
`commerce-accounts-and-sessions.md`). That is cosmetic and does not affect the outcome: fulfilment
is confirmed by the synced entitlement, never by the browser return. Close the tab.

What happened behind that click, all of it real:

```bash
grep -E "POST - /commerce/webhook/stripe" .tenshu-local/commerce.log | tail -1
```

A `200` means the rail signed a Stripe-shaped event, posted it over HTTP to the **real** webhook
route, and that route verified the signature, found-or-created the account from the collected
email, bound the owner, extended the entitlement ledger and wrote the statutory invoice.

## 9. The tier change arrives

Back in Control: **Plan: Premium · … of 2 TB used**.

That is the third never-before-observed step. On the service side you can confirm both carriers
moved — the relay bearer token now asserts `tenshu.drive.premium` / 2 TiB, and the synced record
mints the same product.

## 10. A second device (optional but worth it)

Pair another device, or run a second Control profile. Without signing in there, the Services card
shows **the real plan and the real usage** plus a *Sign in to manage* button — no billing or
identity actions until a per-device email login.

This is the D11 boundary made visible: viewing rides the synced entitlement; acting needs a
session. ⚠ Before CE-4, signing in on that device would have *replaced* those correct figures with
hardcoded literals — worth checking that it no longer does.

## 11. The admin console

```bash
curl -H "Authorization: Bearer local-dev-admin" http://127.0.0.1:8082/admin/users
```

One row: the email, verified timestamp, status `Active`, rail `Stripe`, tier
`tenshu.drive.premium`, its validity window, source `Stripe`, and the bound `ownerId`.

The ledger and one account's history have their own views, and the filing export is two more:

```bash
BASE=http://127.0.0.1:8082
AUTH=(-H "Authorization: Bearer local-dev-admin")

curl "${AUTH[@]}" "$BASE/admin/purchases?from=2026-08-01&to=2026-08-31"
curl "${AUTH[@]}" "$BASE/admin/account?email=you@example.com"
curl "${AUTH[@]}" "$BASE/admin/export/ledger.csv?from=2026-08-01&to=2026-08-31"
curl "${AUTH[@]}" "$BASE/admin/export/vat-summary.csv?from=2026-08-01&to=2026-08-31"
```

⚠ The exports **require** an explicit `from`/`to` — a defaulted range is one missing parameter away
from "export everything". The views may default (current month to date); the exports may not.

⚠ Dates you type are **inclusive at both ends**: `to=2026-08-31` covers the whole of the 31st.

⚠ Amounts in the CSVs are **integer minor units** (`1190` = €11.90), deliberately — a German Excel
reads `"11.90"` as `1190`. The HTML views render decimals for reading; the files are for filing.

⚠ A `(unreconciled)` row in `vat-summary.csv` is not a country. It is the bucket for rows that could
not be attributed. **Resolve those before filing**: their gross is real money the country totals do
not include. ⚠ WC-6 closed the common case — a lost dispute now resolves its invoice through
the payment it names (`invoice.payment_ref`) — but the bucket is still reachable, and necessarily
so: Stripe's `payments` field is expandable, so a payload that omits it links to nothing and logs a
miss rather than guessing. (Pre-v6 invoices with no ref went with the 2026-10 reset.)

⚠⚠ This is the highest-risk surface in the system — the one place that shows A28 PII and the A30
`ownerId ↔ email` join in the clear, and since WC-4 it can export. It binds to loopback even locally,
on a port separate from the API, and is off unless a token is set. Keep that instinct.

⚠⚠ **Locally there is one factor; in production there are two.** With `DEV_MODE=false` the service
**refuses to start** unless `ADMIN_TOTP_SECRET` is set alongside `ADMIN_TOKEN`, and every request
then needs a six-digit code as well:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" -H "X-Admin-Totp: $(oathtool --totp -b "$ADMIN_TOTP_SECRET")" \
  "http://127.0.0.1:8082/admin/users"
```

⚠ The URL stays loopback in production too — `admin.tenshu.app` (§4.5 D8) is the *intended*
placement and is **not deployed**: the droplet serves one Caddy vhost, the issuer API, and the
console is published to no host port at all. On the deployed box that `127.0.0.1` is the
**container's** loopback, so you reach it by SSHing in and joining the container's network
namespace — not with an `ssh -L` tunnel. Recipe: `deploy-commerce-digitalocean.md`, "The admin
console needs two factors". Locally the stack is a bare `java -jar`, so the command above works
as written.

⚠ A code works **once**: accepted codes are burned, so a captured request cannot be replayed inside
the code's remaining validity. Re-run `oathtool` per request rather than reusing one.

---

## Troubleshooting — what each failure actually means

| Symptom | Cause |
|---|---|
| Services still says *No account yet* after signing up | You launched Control without the script, so `TENSHU_BILLING_MOCK` is on and you signed up against a fixture. |
| `grep OTP` finds nothing | SMTP is configured — check the boot line; the code went to the inbox, not the log. |
| `429` on sign-up | CE-2's budgets: 20/IP/hour and 5/address/hour. Wait, or restart the stack with `OTP_SEND_PER_EMAIL_PER_HOUR=…`. A refused request sends no mail and spends no budget. |
| `/sync/push` returns `402` | No entitlement presented. Usually the entitlement has not synced yet (§5) or the client is on the mock. |
| `/sync/push` returns `401` | Device authentication, not entitlement — the client never registered with the relay. Different problem; check onboarding. |
| Accounts vanished after restarting the stack | The commerce store is in-memory by default. Set `TENSHU_LOCAL_COMMERCE_DB` to a Postgres URL to keep them. |
| Ports already bound | A previous stack was SIGKILLed, so its trap never ran. Kill the leftover `java -jar …-all.jar` processes by pid. |
| Checkout page 404s on reload after paying | Correct: the session is consumed on confirm so a reload cannot mint a second event. |
| Closing an account did not cancel its subscription | The inline cancel is best-effort; an hourly sweep retries for 30 days. `grep -i 'subscription-cancel' .tenshu-local/commerce.log`. The close itself always stands — a Stripe outage cannot veto it. |
| Browser error on `tenshu://checkout-result` | Expected — see §8. |

---

## When you're done

`Ctrl-C` in the stack terminal stops both servers. `.tenshu-local/` holds the relay database, the
blobs and both logs; delete it or pass `--reset` next time.

## What to look at before you run it

Reference renders of the four Services-card states are produced by
`ServicesCardRenderTest` into `control-shared/build/reports/services-card/`:

```bash
./gradlew :control-shared:desktopTest --tests '*ServicesCardRenderTest*'
```

Useful for comparing what you see against what the code intends, without hunting through the app
for each state.
