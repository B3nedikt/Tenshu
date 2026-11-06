# Deploy the dev lab on Scaleway

An **isolated copy** of the Tenshu server plane — relay + commerce + apex — on one Scaleway
instance, so you can break things without touching production. This is DL-1/DL-2 of
[`../proposals/ops-dev-lab.md`](../proposals/ops-dev-lab.md); DL-3 (Android devices in redroid
containers on the same box) comes later and needs nothing from you here.

Everything Terraform can do, Terraform does — including, unlike production, the DNS records. This
page is the other half: the seven things only you can do, in order, with the exact screen for each.
Sections marked **YOU** are manual.

⚠⚠ **Two of these are order-dependent and one is unrecoverable in place.** The SSH key must be on
the Project *before* the instance first boots (§3), and the API secret key is shown *once* (§4).

---

## 0. What you will have at the end

| URL | What |
|---|---|
| `https://relay.tenshu.xyz` | the dev sync relay (ciphertext only — House Rule #1 holds here too) |
| `https://commerce.tenshu.xyz` | the dev commerce service, on a **fake Stripe rail** |
| `https://tenshu.xyz` | the apex SPA, built against the dev relay |
| `ssh -L 8025:…` | mailpit — every dev OTP mail, delivered to nobody (optionally also `https://mail.tenshu.xyz`, read-only behind a token — §10) |

One instance (DEV1-M, 3 vCPU / 4 GB), one 40 GB block volume, one flexible IP, three A records.

---

## 1. **YOU** — a Scaleway account, and a look at your credits

👉 **<https://console.scaleway.com>**

You already have an account (production is migrating onto it). Two things worth checking before
you spend anything:

- **Billing → Vouchers/Credits.** Scaleway marks vouchers `Incl. X` / `Excl. X`, and the exclusion
  applies silently — a credit that excludes a product simply does not offset it. Ordinary compute
  and block storage are the common case, so a general credit should cover this box.
- **The console's own estimate is the number to trust.** Instance families differ in whether the
  root volume is included: the DEV1 line bundles local storage, while the newer lines (PLAY2,
  PRO2, COP-ARM) price compute alone and bill block storage separately at roughly €0.087/GB/month
  for the 5k-IOPS tier. Read the estimate on the create screen before applying; this page
  deliberately does not quote a monthly figure it cannot verify.

---

## 2. **YOU** — a separate Project

👉 Console → the **Project selector** (top left) → **Create project** → name it `tenshu-dev`.

⚠⚠ **Do not use the default Project.** Projects are free, and this is the cheapest isolation in the
whole design: a `terraform destroy` aimed at the dev lab then cannot reach the production relay
even if it is pointed at the wrong state file, and the dev lab's resources and billing are visible
apart from the real ones.

Copy the Project ID (Project → Settings) — it is `project_id` in the tfvars.

---

## 3. **YOU** — register your SSH key on that Project, *first*

👉 Console → Project `tenshu-dev` → **SSH keys** → **Add SSH key** (paste `~/.ssh/id_ed25519.pub`).

⚠⚠ **This must happen before `terraform apply`, and there is no way to fix it afterwards from
Terraform.** `scaleway_instance_server` has no SSH-key argument at all: Scaleway injects every key
registered on the *Project* through the metadata service, **at first boot only**. Add the key later
and you get `Permission denied (publickey)` on a box that is otherwise perfectly healthy. The
recoveries are a rebuild, or the console's **VNC** — which needs no SSH and is also how you read
`/var/log/cloud-init-output.log` when SSH is the broken thing.

⚠ `deploy/scaleway-relay` once carried an `ssh_key_ids` variable that nothing referenced. It looked
like the knob and was not; it was removed rather than copied into this module.

---

## 3b. **YOU** — an SSH certificate authority, so agents never hold a long-lived key

⚠⚠ **Do not put the key you use for production on this box's Project, if its private half will
travel.** The direction of risk is the one that is easy to get backwards: registering a *public* key
here exposes nothing — the box only ever holds public halves. The hazard is a key whose **private**
half is copied into a cloud session and which is *also* registered on the production Project. Keep
your personal key for yourself; give agents certificates.

**Once**, on your machine:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/tenshu-dev-ca -C "tenshu dev lab CA"
```

Put `~/.ssh/tenshu-dev-ca.pub` (the **public** half) into `terraform.tfvars` as `ssh_ca_public_key`.
Cloud-init writes it to `/etc/ssh/tenshu_ca.pub` and adds `TrustedUserCAKeys` to `sshd_config`.

⚠⚠ The CA's **private** half never goes on the box, into a cloud session, or into this repo. Anyone
holding it can mint access to every box that trusts the CA, for as long as they like.

**Per agent session**, hand over a throwaway keypair and a certificate that expires on its own:

```bash
ssh-keygen -t ed25519 -f ./agent-key -N "" -C "codex session"
ssh-keygen -s ~/.ssh/tenshu-dev-ca -I "codex-$(date +%F)" -n root -V +8h ./agent-key.pub
#   -I  an identity string; it lands in the box's auth log, so make it say who
#   -n  the principal — the USER it may log in as. Never omit it: a certificate with no
#       principals is valid for ANY user.
#   -V  the validity window. This is the whole point; nothing has to be revoked.
```

Give the session `agent-key` **and** `agent-key-cert.pub` (same directory, matching names — `ssh`
picks the certificate up automatically):

```bash
ssh -i agent-key root@<ip>
```

⭐ **Why certificates rather than a VPN, for now.** They are pure OpenSSH: no daemon, no overlay
network, no third party, and every existing script keeps working unchanged. ⚠ Narrowing
`ssh_allowed_cidrs` is *not* an alternative — a cloud session's egress address is neither stable nor
knowable in advance. A mesh (Headscale or Nebula, self-hosted — not a service with a proprietary
coordination server) earns its place at **DL-3**, when `adb` and a watchable UI have to come off the
public internet; there is no second node to justify one before that.

⚠ Verify it took, once the box is up:

```bash
ssh root@<ip> 'grep TrustedUserCAKeys /etc/ssh/sshd_config && ssh-keygen -l -f /etc/ssh/tenshu_ca.pub'
```

The bootstrap log says so too — `grep "ssh CA" /var/log/tenshu-bootstrap.log`. ⚠⚠ If it printed
`WARNING: sshd -t FAILED`, the CA is configured but sshd was never reloaded and certificate logins
will not work until it is.

---

## 4. **YOU** — an API key

👉 Console → **Organization** → **API keys** → **Generate API key**. Point its preferred Project at
`tenshu-dev`.

⚠ The **secret key is shown once** and is never retrievable. Save it before closing the dialog.

⚠ The *preferred Project* trap documented in `deploy/models/` and `deploy/scaleway-tiles/` is
**S3-only** — Object Storage carries no Project parameter, so a key's choice decides which buckets
it can see. This module creates no bucket and passes `project_id` explicitly, so the setting cannot
bite here. Set it to `tenshu-dev` anyway for tidiness.

---

## 5. **YOU** — put `tenshu.xyz` on Cloudflare

⭐ **Same DNS host as production.** `tenshu.app` is on Cloudflare; so is this. Free, and one
dashboard for both zones (2026-09-24 decision).

👉 **<https://dash.cloudflare.com>** → **Add a domain** → `tenshu.xyz` → Free plan.

Cloudflare gives you two nameservers. `tenshu.xyz` is registered at **Porkbun**, so set them there
(Porkbun → the domain → **Authoritative Nameservers** → replace the four `*.ns.porkbun.com` entries
with Cloudflare's two), then wait:

```bash
dig +short NS tenshu.xyz        # until it shows Cloudflare's two nameservers
```

⚠⚠ **Do NOT create the A records yet** — you do not know the IP until step 7 has applied. Cloudflare
answers queries for a zone before the nameservers are switched, so you can add the domain now and
delegate whenever; the records come after the apply.

⚠⚠ **`manage_dns` stays `false`.** It creates records in *Scaleway* DNS, and with the zone served by
Cloudflare that is a silent failure: a clean apply, records in a zone nobody queries, and the only
symptom is that nothing resolves. The default is false for exactly this reason.

⚠⚠ **Every record here is DNS-only — leave the orange cloud OFF.** Proxying would put Cloudflare in
the TLS path that Caddy terminates, push its ACME validation through the proxy, and cap request
bodies at 100 MB — the relay's content-plane chunks are multi-MB ciphertext and its Caddyfile allows
256 MB. `deploy/cloudflare-dns`'s header records the same rule for production, and it is the one
Cloudflare setting that will silently break this plane.

⚠ This does **not** use production's DNS machinery, and that is deliberate. `tenshu.app`'s records
live in a provider-neutral inventory ([`deploy/dns/records.json`](../../deploy/dns/records.json))
behind an adapter ([`deploy/cloudflare-dns`](../../deploy/cloudflare-dns/)) so that no service module
owns a record — a split that earns its keep for a zone serving mail with a rollback story. Three
static A records have neither problem. ⭐ And they really are static: `scaleway_instance_ip.dev` is a
standalone resource the server *references*, so rebuilding the instance keeps the address. You write
these records once.

⚠ Reusing `deploy/cloudflare-dns` for this zone was considered and rejected: its inventory checks
hard-require MX + SPF + DKIM + DMARC + `commerce.service`, and it carries `prevent_destroy` on the
zone. Pointing it here would mean loosening production's guards for a dev convenience. If the dev
lab ever grows records worth keeping, parameterise that module properly rather than weakening it.

---

## 6. **YOU** — a GitHub token and two secrets

**GitHub PAT** 👉 **<https://github.com/settings/personal-access-tokens/new>**
- *Repository access* → **Only select repositories** → `B3nedikt/Drive`
- *Permissions* → *Repository permissions* → **Contents: Read-only**

Required: the box clones the repo even when it pulls prebuilt images, because the compose file and
Caddyfile live in it. Without a token the clone fails as `could not read Username for
'https://github.com'`, which reads like a prompt problem rather than a missing credential.

**Two secrets** — generate them here; §7 stores them in the Keychain, not the tfvars:

```bash
head -c 32 /dev/urandom | base64     # → issuer_seed
head -c 24 /dev/urandom | base64     # → postgres_password
```

⚠⚠ **Generate a new `issuer_seed`. Never copy production's.** The entire isolation argument is that
a dev-signed entitlement is worthless against the production relay and vice versa. Copying the seed
across quietly deletes that property.

---

## 7. Plan, then apply

```bash
cd deploy/scaleway-dev
cp terraform.tfvars.example terraform.tfvars   # the NON-secret values from §§2–5
terraform init
./tf.sh plan                                   # ⚠ always; see below
./tf.sh apply
terraform output verify_hint
```

⭐ **`./tf.sh` is `terraform` with the five secrets read from the macOS Keychain** for that one
command — the Scaleway key pair (§4), the git token and the two generated secrets (§6). Store each
once; `-w` with no value prompts, so nothing reaches shell history:

```bash
security add-generic-password -U -a tenshu -s scw-dev-access-key -w
security add-generic-password -U -a tenshu -s scw-dev-secret-key -w
security add-generic-password -U -a tenshu -s dev-git-token -w
security add-generic-password -U -a tenshu -s dev-issuer-seed -w
security add-generic-password -U -a tenshu -s dev-postgres-password -w
```

⚠ Hygiene, not a boundary: `terraform.tfstate` still holds every one of them in plaintext
(`ops-secret-custody.md` D3). ⚠ The dev `issuer_seed` may live in the Keychain although that doc
keeps trust anchors out of automation — it is a throwaway that production rejects by construction,
which is the property §9's isolation proof checks.

**Why `plan` first.** The module carries **preconditions** that REFUSE, at plan time, every way the
first Scaleway deploy of the relay actually failed — plus one of its own. ⚠⚠ They were `check` blocks
until 2026-09-23, and a `check` block only warns: the plan succeeded with exit 0 and every one of
these could be waved straight through (`ops-dev-lab.md` §10.7). A precondition is an Error — the plan
aborts, nothing is created, exit 1:

- `dev_domain` under `tenshu.app` → **refused**. A `dev-` prefix on the production zone is one typo
  away from a client that silently reaches the real relay.
- `acme_email` at `example.com` → refused. Let's Encrypt rejects it outright (`invalidContact …
  forbidden domain`), and the symptom is a TLS handshake failure with no certificate ever issued —
  never an error at apply.
- empty `git_token`, `issuer_seed` or `postgres_password` → refused, each with the failure it would
  otherwise have produced twenty minutes later in a container restart loop.
- a `postgres_password` containing `$`, `#`, a quote, a backslash or whitespace → refused: the
  compose env file would hand Postgres a different string. (base64's `+ / =` are fine — the JDBC
  URL's copy is percent-encoded for you.)

### ⚠⚠ Verify in this order — a green health check is not enough

```bash
ssh root@<ip> 'cloud-init status --long'   # 1. bootstrap finished
ssh root@<ip> 'findmnt /mnt/tenshu'        # 2. the DURABLE volume is mounted
curl -fsS https://relay.tenshu.xyz/health  # 3. the relay answers
```

Step 2 is the one that matters. The cloud-init **warns and carries on** if it cannot find the data
volume, putting dev state on the root disk where a rebuild destroys it — and the box looks perfectly
healthy either way. The live relay answered `{"status":"ok","durable":true}` for five days while
holding zero rows: `durable:true` means *a database is configured and its volume is writable*, not
that your data is there.

---

## 8. Ship the three images

Nothing is built on the box. Two ways in:

```bash
./deploy/ship-dev-stack.sh root@$(cd deploy/scaleway-dev && terraform output -raw dev_ip)
```

This builds all three, `docker save | ssh docker load`s them, flips the tags in
`/opt/tenshu/dev.env` and restarts the stack. No registry, no CI, no GHCR credentials.

**Or** set `ghcr_user`/`ghcr_token` in the tfvars and the box pulls its own images — the
workflows publish to GHCR. `--ship` stays the faster loop for iterating, and it
leaves the previous image on the box, so a rollback is a tag flip plus a restart.

⚠⚠ Either way the apex is built against `https://relay.tenshu.xyz` — `ship-dev-stack.sh` reads the
domain off the box to get this right. **Vite inlines the relay URL at build time**, so an apex image
built any other way will serve from your dev box and talk to *production*.

---

## 9. **YOU** — turn on entitlement enforcement

Deliberately not automatic: the relay needs the *public* half of the issuer key, and commerce only
prints it once it has booted with the seed. Enabling it earlier locks every client out of your own
dev relay.

```bash
ssh root@<ip> '/opt/tenshu/compose logs commerce | grep -i "public"'
```

⭐ `/opt/tenshu/compose` is a wrapper cloud-init writes, and the only place the invocation is
spelled. ⚠⚠ Do not reach for a bare `docker compose -f …` here: this plane is the **two production
composes plus an overlay**, and any single one of those files on its own is a different, wrong
deployment (`deploy/scaleway-dev/stack/dev-overlay.yml` header).

Paste that value into `/opt/tenshu/dev.env` as `ENTITLEMENT_ISSUER_PUBKEY`, set
`ENTITLEMENT_ENFORCE=true`, then `bash /opt/tenshu/up.sh`.

⚠⚠ **Then put the same value in `terraform.tfvars` as `entitlement_issuer_pubkey`.** `dev.env` is
on the root disk; a rebuild re-renders it, and without the variable the box comes back with
enforcement off and nothing to show for it. With it, this section is a one-time step: the key is a
pure function of `issuer_seed`, so it never changes underneath you.

⚠ **Then ship the apex once more** (`deploy/ship-dev-stack.sh root@<ip> --only apex`). §8 built the
web bundle while the key was still blank, so the first bundle does not carry this plane's issuer and
reports every entitlement issued here as unverified. The script reads the key off the box, so the
second ship picks it up.

⭐ Then prove the isolation actually holds: a token minted by *production* commerce must be rejected
by this relay, and one minted here rejected by production's. If either is accepted, the seeds are
shared and §6's warning was not heeded.

---

## 10. Point clients at the dev plane

⚠⚠ **A client needs THREE things, not one — and all three are compiled in.** This plane's commerce
signs with its own seed under its own issuer id (`tenshu.commerce.dev`), and a client only trusts
an issuer it was built to trust. Point a build at the relay alone and it pairs, then never opens
its sync gate: the entitlement is unverifiable, `hasStorage` fails closed.

```properties
# local.properties (gitignored) — or the same names as -P flags / TENSHU_* environment variables
tenshu.relayUrl=https://relay.tenshu.xyz
tenshu.commerceUrl=https://commerce.tenshu.xyz
tenshu.billingMock=false
tenshu.extraIssuer=tenshu.commerce.dev:<ENTITLEMENT_ISSUER_PUBKEY from §9>
```

⭐ Check it took: a debug build prints `Entitlement trust: publishers=[tenshu.dev, tenshu.commerce,
tenshu.commerce.dev]` at start-up. Two ids means the build was never told, however it was launched.

| Client | How |
|---|---|
| **apex / web** | already, once the apex was shipped *after* §9 — `ship-dev-stack.sh` reads the issuer off the box and builds the bundle with it. ⚠ On the web that only keeps a dev-plane record from raising an "unverified entitlement" error: the web reader checks no signature, so a bundle without the issuer syncs all the same |
| **Control Android** | the four `local.properties` lines above, then rebuild **every** app you install — Android has no runtime knob |
| **Control desktop** | the same four as `TENSHU_*` variables on `./gradlew :control-desktop:run`. ⚠⚠ **Not beside a real Control on the same Mac:** the Keychain item wrapping its secrets is named after the package, so a dev instance shares it — and a reset inside the dev instance deletes it for both |
| **iOS** | not covered here — nothing in this plan runs iOS (`ops-dev-lab.md` §5) |

⚠ A device already paired against production does not migrate. Use a fresh emulator/profile, or
reset local state with `scripts/reset-local-device-state.sh`.
⚠ A **release** build ignores `tenshu.extraIssuer` by design, and says so in the same start-up line.
⚠ So does a **web bundle** when the value came from `local.properties` or a `gradle.properties`: the
web trusts an issuer only when its own build was handed it (`TENSHU_EXTRA_ISSUER` / `-P`), so a line
left behind in a file cannot reach a production image. For a local `vite` run against the dev plane,
export the variable.

### The dev-lab emulator

A client that stays onboarded against this plane needs a device the smoke gate never reinstalls.
That is the AVD **`tenshu_dev_lab`**, always on port 5580 (so its serial is `emulator-5580`), which
`scripts/android-smoke.sh` refuses to pick. Screen PIN `1111` — Control's app lock has nothing to
unlock against on an emulator with no credential.

```bash
~/Library/Android/sdk/emulator/emulator -avd tenshu_dev_lab -port 5580 &      # add -no-window for headless

# Build and install with the settings in the ENVIRONMENT, so local.properties stays production's:
ANDROID_SERIAL=emulator-5580 \
TENSHU_RELAY_URL=https://relay.tenshu.xyz TENSHU_COMMERCE_URL=https://commerce.tenshu.xyz \
TENSHU_BILLING_MOCK=false TENSHU_EXTRA_ISSUER=tenshu.commerce.dev:<ENTITLEMENT_ISSUER_PUBKEY> \
  ./gradlew :control:installDebug :drive-android:installDebug
```

⚠⚠ `ANDROID_SERIAL` is what keeps this build off the smoke emulator and off a plugged-in phone.
Sign-up codes land in the box's mailpit: `ssh -L 8025:127.0.0.1:8025 root@<ip>`, then
`http://localhost:8025`.

### Sign-up codes for a session with no SSH

A cloud session cannot open port 22: its sandbox lets out HTTPS only, and only to hosts on the
environment's allowlist (measured 2026-10-12 — `CONNECT tunnel failed, response 403` until the
hosts were allowed, and a timeout on 22 either way). The certificates of §3b are therefore no use
to it, and without the inbox it cannot create an account.

`mailpit_token` turns on **`https://mail.<dev_domain>`**: three read-only routes of mailpit's API
behind a bearer token. Everything else on that hostname is a 404 — mailpit's API can also delete
mail, send it and fetch the links inside it from the box, and its UI asks for no credential.
(Reading a message marks it read in mailpit; nothing else changes.)

```bash
# once: generated straight into the Keychain, never shown
security add-generic-password -U -a tenshu -s dev-mailpit-token -w "$(openssl rand -hex 24)"
# plus a DNS-only A record  mail.<dev_domain> -> the box, beside the other three (§5)

# a session that holds the token:
curl -s -H "Authorization: Bearer $TENSHU_DEV_MAILPIT_TOKEN" \
  "https://mail.<dev_domain>/api/v1/messages?limit=1"        # also /api/v1/search, /api/v1/message/<id|latest>
```

⚠ Give the token to a cloud session as an **environment variable of its environment**, not in its
brief: a brief is stored with the session, a variable is not printed anywhere.
⚠ `./tf.sh` picks the token up for the next rebuild. On a box that is already running, add
`MAIL_SITE=mail.<dev_domain>` and `MAILPIT_TOKEN=…` to `/opt/tenshu/dev.env`, `git pull` in
`/opt/tenshu/repo`, and `bash /opt/tenshu/up.sh` — only Caddy is recreated.
⚠⚠ In that order, and run `up.sh` straight after the pull: the new Caddyfile names `MAIL_SITE`,
and a Caddy restarted between the pull and `up.sh` still has the old environment, cannot parse an
empty site address, and takes every hostname down with it.
⚠⚠ **Rotating or revoking the token is a step on the box, not a Terraform apply.** `./tf.sh apply`
only rewrites the instance's stored `user_data`, which cloud-init never re-reads (§11): the
running Caddy keeps the old token and keeps serving, while the `mail_endpoint` output already
says otherwise. To rotate, store the new value in the Keychain, put it in `MAILPIT_TOKEN` in
`/opt/tenshu/dev.env`, and `bash /opt/tenshu/up.sh`. To switch the endpoint off, set
`MAIL_SITE=http://mail.disabled.invalid` and an empty `MAILPIT_TOKEN` there, then `up.sh`. Either
way, confirm it: the old token must now get a 404.
⚠ Whoever holds the token reads every code this plane sends, so can sign in as any dev account.
That is a test plane's risk, and it is the reason the token is long and the endpoint off by default.

---

## 11. Living with it

**Mail.** `ssh -L 8025:127.0.0.1:8025 root@<ip>` then <http://localhost:8025>. Bound to loopback on
purpose — it is an unauthenticated inbox.

**Logs.** `ssh root@<ip> '/opt/tenshu/compose logs -f'` — likewise `… ps`, `… restart relay`, `… down`.

**Reset.** The dev plane is meant to be wiped: stop relay, commerce, apex and db, `rm -rf
/mnt/tenshu/relay/* /mnt/tenshu/postgres/*`, then let `ship-dev-stack.sh` be the first start. ⚠ Never
`up` the old images on the emptied volumes: they write their own `schema_version` rows and a newer
image then skips its migrations. Exact commands and post-checks:
[`reset-and-commerce-test.md`](reset-and-commerce-test.md) §3a. (`deploy/reset-test-data.sh` is
production-shaped and is not wired to this box.)

**Stripe test mode instead of the fake rail.** Wanted when the point is Stripe's own Checkout page
and webhook payloads. In the Stripe **sandbox**: two recurring Prices, and a webhook endpoint at
`https://commerce.<dev_domain>/commerce/webhook/stripe` subscribed to `checkout.session.completed`,
`invoice.paid`, `customer.subscription.deleted`, `charge.refunded` and `charge.dispute.closed`. Then
store `dev-stripe-secret-key` and `dev-stripe-webhook-secret` in the Keychain (`tf.sh` has the
commands) and set `stripe_price_ids` in the tfvars. ⚠⚠ Test keys only — the plan refuses a live one,
because commerce runs `DEV_MODE=true` here and would not.

⚠ That makes a **rebuild** come back on the real rail; a running box does not notice (next
paragraph). To switch one in place, set the same four lines in `/opt/tenshu/dev.env` —
`STRIPE_MODE=real`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_IDS` — and
`/opt/tenshu/compose up -d --force-recreate commerce`. `restart` is not enough: it reuses the old
environment.

**⚠⚠ Editing cloud-init does NOT rebuild the box.** A changed `user_data` — a template edit, a
rotated git token, a new image tag — plans as an in-place update, and cloud-init only ever runs
once per instance, so a plain apply changes nothing on the box. Rebuild on purpose:

```bash
# from the repo root
deploy/scaleway-dev/tf.sh apply -replace=scaleway_instance_server.dev   # instance only; IP + data volume survive
ssh-keygen -R <ip>                                                      # the host key is new
deploy/ship-dev-stack.sh root@<ip>                                      # ⚠ shipped images lived on the root disk
```

Enforcement comes back on by itself **if** `entitlement_issuer_pubkey` is in the tfvars (§9);
check the relay's log for `Entitlement ENFORCED` either way. The accounts, the relay's data and
Caddy's certificates are on the volume and come back untouched.

**Tear down.** `terraform destroy` releases the instance, the volume, the IP and the records.

---

## 12. Troubleshooting

| Symptom | Cause |
|---|---|
| `Permission denied (publickey)` on a healthy box | ⚠⚠ The SSH key was not on the Project before first boot (§3). Keys are injected once, at boot. Rebuild, or use the console's VNC. |
| Box boots clean, nothing installed, no error | `user_data` reached Scaleway as a string rather than a map. The module handles this; suspect it only if you edited `main.tf`. |
| Every port open | `inbound_default_policy` lost its `"drop"`. Scaleway security groups default to ACCEPT. |
| Volume fails at create with an IOPS error | `data_volume_iops` is tier-dependent and validated at create, not plan. 5000 is the standard tier. |
| `no space left on device` on an image pull | The **root** volume, not the data volume. 30 GB here for six images; raise `root_volume_size_gb`. |
| Caddy loops on ACME errors | The zone is not live at Scaleway yet (§5), or the records have not propagated. `dig +short NS tenshu.xyz`. Uncomment the staging CA in `stack/Caddyfile` while iterating — Let's Encrypt rate-limits *failed* authorisations. |
| The apex does not resolve but `relay.` does | The zone-root record. `""` is the root; a name equal to the zone creates `tenshu.xyz.tenshu.xyz`. This module spells it correctly — the bug was in the first zone-level DNS module, recorded here because it is invisible. |
| relay/commerce/apex not running | Their images are not on the box. §8. Expected on a first boot without GHCR credentials. |
| commerce exits at startup | `ISSUER_SEED` is not valid base64, or not 32 bytes. ⚠⚠ An **empty** seed no longer refuses: this plane runs `COMMERCE_DEV_MODE=true` (see §6), so an empty one silently falls back to the committed public dev seed — the plan-time assert in `main.tf` is what catches it, and only before the box exists. |
| commerce exits with *"STRIPE_MODE=fake is a dev-only payment rail"* | `COMMERCE_DEV_MODE` is not `true` in `/opt/tenshu/dev.env`. `CommerceConfig.validate()` refuses the pair, and the dev plane needs both. |
| `sqlite3: command not found` while verifying | Installed by this cloud-init (the relay box had to learn this the hard way). If it is missing, the bootstrap did not finish — check `cloud-init status --long`. |
