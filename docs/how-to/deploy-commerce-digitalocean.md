# Deploying the commerce issuer + turning on relay enforcement

> ⚠⚠ **RETIRED box.** The issuer runs on Scaleway ([`deploy/scaleway-commerce/`](../../deploy/scaleway-commerce/),
> moved by [`move-commerce-to-scaleway.md`](move-commerce-to-scaleway.md)). The droplet steps below
> describe the box it replaced. The *relay enforcement* section still applies as written, and it
> targets the Scaleway relay.

The `:commerce-service` is the paid-storage **issuer** — it owns email-anchored accounts, sessions,
the entitlement ledger, and the Stripe money path, and it mints the signed token/entitlement the relay
checks on writes (`commerce-accounts-and-sessions.md` §4/§6, `commerce-billing-plane.md` §4/§8). This
guide stands it up on a DigitalOcean droplet and wires the relay to enforce against it.

It is a **separate trust boundary** from the relay (`commerce-billing-plane.md` D1): the issuer holds the
private signing seed (A29) **and** the email PII / account store (A28); the relay only ever holds the
matching **public** key and verifies ciphertext-only writes. Run them on **separate hosts** — the
plumbing here is standalone so you can.

> **What the issuer does now.** Devices create an **email-OTP account** (`/register` → `/verify`),
> which opens a **session**; `/mint` + `/token` are session-gated. Paid tiers ride **Stripe**
> (`/checkout` → hosted checkout → `/webhook/stripe` mints the entitlement). A `/redeem` code path
> grants a tier identity-light. The A28 store is **Postgres** (accounts, sessions, ledger, statutory
> invoices). The older "email whitelist → top tier" bootstrap is retired — its successor is
> `REDEMPTION_CODES` (grant yourself a tier without the Stripe rail while testing).

## What's in the repo

| File | Role |
|---|---|
| `commerce-service/Dockerfile` | Multi-stage build → `commerce-service-all.jar` (Ktor fat jar). Four stages: `build` + `base` + **`prebuilt`** (jar supplied by the caller) + **`full`** (jar built in-image, the default). |
| `commerce-service/docker-compose.yml` | `commerce` + `caddy` (automatic HTTPS). **Postgres is not bundled here** (deliberate — an explicit deploy choice). |
| `commerce-service/Caddyfile` | TLS termination for `$COMMERCE_DOMAIN` → `commerce:8081`. |
| `.github/workflows/commerce-image.yml` | Builds + pushes `ghcr.io/b3nedikt/tenshu-commerce` (the prebuilt-image path — amd64). |
| `deploy/digitalocean-commerce/` | **Terraform module** — droplet + volume + firewall + reserved IP (+ DNS) + a **bundled `postgres:16`** sidecar. |
| `deploy/build-commerce-image.sh` | **Build an amd64 image from a natively-built jar** — the only path that works on an arm Mac. `--ship <host>` loads it over ssh, no registry. |
| `deploy/redeploy-commerce.sh` | Pull a new issuer image onto the droplet and restart (fast path). |

There are **two ways to deploy**: the Terraform module (one command, recommended) or a manual
`docker compose` on any Docker host. Both need TLS (Caddy) — request bodies carry the user's email.

---

## Option A — Terraform (recommended)

`deploy/digitalocean-commerce/` mirrors the relay module (`deploy/digitalocean/`) but is its **own**
trust boundary, and it bundles a `postgres:16` sidecar (pgdata on a block volume) so the A28 store is
self-contained.

### 1. Generate the secrets

```bash
head -c 32 /dev/urandom | base64   # ISSUER_SEED — the key that mints "paid"; keep it off the relay + devices
openssl rand -hex 24               # database_password — for the bundled Postgres
```

### 2. Fill in terraform.tfvars

```bash
cd deploy/digitalocean-commerce
cp terraform.tfvars.example terraform.tfvars   # then edit
```

Minimum: `do_token`, `ssh_keys`, `domain` (e.g. `commerce.service.tenshu.app`, per
`web/architecture.md` §3.4 D9), `acme_email`, `issuer_seed`, `database_password`, **and the
`smtp_*` block (step 3b)**. Add the Stripe block (step 4) when you want the money path. Recommended:
set `commerce_image_tag = "latest"` (or a sha) so the droplet pulls a prebuilt image instead of
building on-box — then `droplet_size = "s-1vcpu-2gb"` is plenty (JVM issuer + Postgres).

> ⚠⚠ **Leaving `commerce_image_tag` unset is not neutral** — empty selects the RAM-hungry on-box
> build path. It was unset here until 2026-08-25.
>
> ⚠⚠ **On an arm Mac, `--target full` is not a slow path — it is no path.** It runs Gradle inside an
> amd64 image, i.e. under QEMU (~45 min), which is why the deployed image sat at a 2026-08-08 build
> while CI was down. Use [`deploy/build-commerce-image.sh`](../../deploy/build-commerce-image.sh):
> it builds the fat jar natively (seconds), then assembles an image whose only instructions are COPY
> and metadata — a jar is architecture-independent bytecode, so nothing about it needs amd64
> *execution*. `--ship <host>` sends it straight to the droplet with `docker save | ssh docker load`,
> so it does not depend on GHCR credentials either.

### 3b. Outbound OTP email — required, not optional

⚠⚠ **A `dev_mode = "false"` deploy with no SMTP config does not come up.**
`CommerceConfig.validate()` refuses to start when the only available sender is the one that prints
the verification code to the container log — the OTP is the account model's sole factor, so shipping
that is the same class of mistake as shipping the dev signing seed (CE-2 / D3). The module carries a
matching **plan-time precondition**, so the failure is a Terraform error naming the fix rather than a
droplet that boots into a crash loop.

```hcl
smtp_host     = "smtp.tem.scaleway.com"   # or email-smtp.<region>.amazonaws.com for SES
smtp_from     = "no-reply@tenshu.app"     # MUST be on a domain verified at the provider
smtp_username = "…"                        # Scaleway: the Project ID. SES: the SMTP user.
smtp_password = "…"                        # Scaleway: an API key's SECRET KEY.
```

The DNS half (SPF, DKIM, DMARC) is the part that actually takes time, and getting it wrong is
invisible — an OTP in spam is indistinguishable from a broken OTP, and nobody reports it. Do that
first: [`set-up-otp-email.md`](set-up-otp-email.md) (§A.4 for Scaleway, §2–§4 for SES).

### 3. Apply

```bash
terraform init
terraform apply
```

First boot installs Docker, mounts the volume, clones the repo (for the compose + Caddy files), stands
up Postgres + the issuer + Caddy, and provisions a TLS cert. Allow ~2–8 min. Liveness:

```bash
curl -fsS --max-time 10 https://commerce.service.tenshu.app/health   # → {"status":"ok"}
```

⚠ Use `/health`, not `/` — the latter is a static string that cannot fail while the process is up,
so it would report a green first boot even if Postgres never came up. See
[*Checking it is alive*](#checking-it-is-alive-health-not-) below.

⚠ **Every config change here replaces the droplet.** `deploy.env` is written by cloud-init, and
cloud-init is `user_data`, which DigitalOcean treats as immutable — so changing *any* `smtp_*`,
`stripe_*`, seed or tuning var is a destroy-and-recreate, not an in-place update. That is safer than
it sounds: the Postgres data volume and the reserved IP are **separate resources and survive**, so
the A28 store and the DNS record ride through untouched. But budget the boot time (long on the
on-box build path), and check `terraform plan` before applying — it says `must be replaced` plainly.

For a change that must land in seconds, edit `/opt/tenshu/deploy.env` on the droplet and
`docker compose … up -d --force-recreate commerce`. ⚠ Terraform state then disagrees with the
droplet and the next `apply` silently reverts you — mirror the change back into `terraform.tfvars`
the same day.

### Promoting a dev-mode issuer to production

A `dev_mode = "true"` droplet signs with the **committed** dev seed and logs OTPs. Promoting it is
three coupled changes that must land together:

1. `dev_mode = "false"` + a real `issuer_seed` (`head -c 32 /dev/urandom | base64`).
2. The `smtp_*` block (step 3b).
3. ⚠⚠ **The relay's `ENTITLEMENT_ISSUER_PUBKEY` must be rotated to match** (step 5). A new seed is
   a new public key, so every token and entitlement signed by the old one stops verifying. Devices
   re-mint on their next session, but any *already synced* dev-key entitlement record is dead —
   which is fine for a closed test and is exactly why a dev-mode issuer must never accrue real
   accounts.

Postgres survives the rebuild, so accounts and the ledger persist across the promotion.

---

## Option B — manual compose (any Docker host)

If you'd rather not use Terraform: on a Docker host, create `commerce-service/deploy.env` (gitignored)
with the vars from the module's `variables.tf` — at minimum `COMMERCE_DOMAIN`, `ACME_EMAIL`,
`ISSUER_SEED`, and a `DATABASE_URL` pointing at a Postgres **you** run (a managed DB, or your own
`postgres:16`). Then `docker compose --env-file deploy.env up -d` from `commerce-service/`. The Terraform
module's `cloud-init.yaml.tftpl` is the reference for the Postgres sidecar override if you want to
replicate it by hand.

### Checking it is alive: `/health`, not `/`

⚠⚠ **`GET /` is a static string** (`"Tenshu Commerce issuer is running!"`) and cannot fail while the
process is up. A 200 there says Ktor is listening and *nothing* about whether the service can serve
— on 2026-08-15 it answered 200 while the app held ten dead sockets and had zero live connections to
Postgres. Use it for process liveness only.

```bash
curl -s -o /dev/null -w "%{http_code}\n" --max-time 10 https://commerce.service.tenshu.app/health
# 200 {"status":"ok"}        — store round-tripped
# 503 {"status":"degraded"}  — store unreachable; the reason is in `docker compose logs commerce`
```

`/health` is unauthenticated (a monitor holds no session) and deliberately says nothing beyond
ok/degraded. ⚠ Give it an explicit timeout: a stalled pool makes it *slow* rather than failing, so
without `--max-time` a hang reads as "no answer yet" instead of "down".

⚠ After restarting Postgres, check the app actually reconnected — the pool's own stats will not tell
you (Hikari validates on borrow, so it reports healthy sockets that no longer exist):

```bash
ssh root@<ip> 'docker exec commerce-service-db-1 psql -U commerce -d commerce -t \
  -c "select client_addr, state, count(*) from pg_stat_activity where datname = '"'"'commerce'"'"' group by 1,2;"'
# no row from the container's IP => restart commerce to rebuild the pool
```

### ⚠⚠ The Postgres sidecar lives outside the repo, so repo-wide fixes miss it

The deployed stack is **two compose files merged**, not one:

```
/opt/tenshu/repo/commerce-service/docker-compose.yml   # in git
/opt/tenshu/commerce.override.yml                      # generated by cloud-init, ON-BOX ONLY
```

The `db` service is declared **only in the override** — deliberately, because the DB host is an
explicit choice on this trust boundary (see the compose header). Two consequences that have already
cost something:

- ⚠⚠ **A YAML anchor does not cross files.** `docker-compose.yml`'s `x-logging` anchor caps the
  `commerce` and `caddy` containers at 250 MB, but it **cannot** reach `db` — the override would need
  its own `logging:` block. As of 2026-08-15 `commerce-service-db-1` still logs unbounded (harmless
  today at 18 MB on a 77 G disk, but it is the same defect that filled the relay's root disk;
  `drive-upload-stop-and-reclamation.md` §4.6).
- ⚠ **The override is not in version control**, so `git pull` on the box never updates it and a diff
  against master will never show it. When reasoning about what is actually running, read the
  merged view rather than the repo file:

```bash
ssh root@<ip> 'docker inspect $(docker ps -q) \
  --format "{{.Name}} {{.HostConfig.LogConfig.Config}}"'
```

⚠ Recreating the stack after a repo change should name the services, so the money-path database is
not restarted for a change that does not concern it:

```bash
docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml \
  --env-file /opt/tenshu/deploy.env up -d --force-recreate commerce caddy
```

---

## 4. Stripe test-mode (for the live round-trip)

To validate the money path end-to-end, use Stripe **test mode** first (`sk_test_…`):

1. In the Stripe dashboard (test mode), create two **recurring Prices** — one for Basic, one for
   Premium — and note their `price_…` ids.
2. Add a **webhook endpoint** → `https://<your-domain>/commerce/webhook/stripe`, subscribed to at least
   `checkout.session.completed`, `invoice.paid`, and `customer.subscription.deleted`. Copy its signing
   secret (`whsec_…`).
3. Set these (tfvars `stripe_*`, or `deploy.env`):

   ```dotenv
   STRIPE_SECRET_KEY=sk_test_xxx
   STRIPE_WEBHOOK_SECRET=whsec_xxx
   STRIPE_PRICE_IDS=tenshu.drive.basic=price_xxx,tenshu.drive.premium=price_yyy
   ```

   The issuer refuses to start with the secret key but **not** the webhook secret (an unverified
   webhook is a mint-forgery vector, T-CB-07). With all three unset the `/checkout`, `/portal`,
   `/webhook` routes answer `503` and the free/redeem paths still work.

Then from a client (step 6) pick a paid tier → hosted Stripe checkout → pay with a test card
(`4242 4242 4242 4242`) → the webhook mints the entitlement → it syncs back and the device reflects the
tier. Watch it land: `ssh root@<domain> '<compose> logs -f commerce'`.

## 5. Read the issuer public key → wire the relay

The issuer logs its public key on boot; the module's `first_boot_hint` output prints the exact command.

```bash
ssh root@<domain> "docker compose \
  -f /opt/tenshu/repo/commerce-service/docker-compose.yml \
  -f /opt/tenshu/commerce.override.yml \
  --env-file /opt/tenshu/deploy.env logs commerce | grep -i 'issuer public key'"
# Commerce issuer public key (set relay ENTITLEMENT_ISSUER_PUBKEY to this): <BASE64>
```

On the **relay** — the Scaleway box, [`deploy/scaleway-relay/`](../../deploy/scaleway-relay/) — set these in
`/opt/tenshu/deploy.env` and recreate the relay container on its **pinned** tag
(`docker compose -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env up -d
--force-recreate --pull never relay`, from `/opt/tenshu/repo`). ⚠⚠ Not `deploy/digitalocean/`: that
is the retired relay, and a `terraform apply` there repoints production DNS at the dead droplet.
⚠ Mirror the same three into `deploy/scaleway-relay/terraform.tfvars` (`entitlement_issuer_pubkey`,
`entitlement_issuer_key_id`, `entitlement_enforce`). cloud-init rebuilds `deploy.env` from those
variables, whose defaults are empty and `false`, so a rebuilt relay would otherwise stop enforcing
without a word.

```dotenv
ENTITLEMENT_ISSUER_PUBKEY=<the base64 above>
# ENTITLEMENT_ISSUER_KEY_ID=2026-06   # only if you set signing_key_id on commerce
ENTITLEMENT_ENFORCE=true
```

> ⚠️ **The enforce flip is a hard cutover.** Once on, any client that does **not** present a valid
> `X-Entitlement` on a *write* gets `402` (reads stay open) — old builds, clients with no bound account,
> or any window where commerce is unreachable. Roll out configured clients (step 6) first; test with
> `ENTITLEMENT_ENFORCE=true` against a **staging** relay before production.

## 6. Point the clients at commerce

The desktop/android clients only talk to a real issuer when billing is un-mocked:

```dotenv
TENSHU_BILLING_MOCK=false
TENSHU_COMMERCE_URL=https://commerce.service.tenshu.app
```

(Defaults: `TENSHU_BILLING_MOCK=true`, `TENSHU_COMMERCE_URL=http://localhost:8081` /
`http://10.0.2.2:8081` on Android.) These are read from env / system properties by
`PlatformProxyModule`. Sign up a Free account (email-OTP) or buy a tier; the client caches the session +
token off the hot path and presents `X-Entitlement` on writes.

## The end-to-end loops

- **Free:** app `register(email)` → `verifyOtp(code)` opens a session → `/mint` writes the Free
  entitlement → it syncs; the relay accepts writes within the Free quota.
- **Paid (Stripe):** picker → `/checkout {ownerId, productId}` → hosted checkout → `webhook` mints the
  paid entitlement (highest-quota-among-valid wins) → syncs → the device shows the new tier.
- **Redeem:** `redeem(code)` (a `REDEMPTION_CODES` seed) → commerce-signed grant → syncs. Identity-light,
  no email/session.

## Quick closed test (no seed / no Stripe)

For a throwaway test set `dev_mode = "true"` (tfvars) / `COMMERCE_DEV_MODE=true` and omit `issuer_seed`:
commerce falls back to the **committed dev seed**, whose public half is
`EntitlementSignature.COMMERCE_PUBLIC_KEY`. Set the relay's `ENTITLEMENT_ISSUER_PUBKEY` to the base64 the
boot log prints (it's that pinned key). Insecure by design — the dev seed is public — so closed tests
only, never production. A `REDEMPTION_CODES` seed grants a tier without standing up Stripe at all.

## The admin console needs two factors

⚠⚠ **If you set `admin_token`, you must also set `admin_totp_secret`, or the service will not
start.** `validate()` refuses a single-factor console whenever `DEV_MODE=false`, the same way it
refuses the dev signing seed, the fake Stripe rail and the log email sender. This is the surface that
joins real PII with the `ownerId` indexing relay metadata, and it can export — one stolen bearer
token would otherwise de-anonymise every paying user at once.

⚠ **Upgrading an existing deployment:** set the seed *before* applying. Otherwise the container
crash-loops on boot with an `InvalidCommerceConfigException` naming `ADMIN_TOTP_SECRET`.

```bash
# Generate a seed, enrol it in an authenticator app, then put it in terraform.tfvars.
head -c 20 /dev/urandom | base32 | tr -d '='
```

Then every request carries both factors (the console is bearer-authenticated, so it is curl, not a
browser form).

⚠⚠ **An `ssh -L 8082:localhost:8082` tunnel does not reach it** — a natural assumption, and wrong in
two independent ways. The console binds `ADMIN_BIND_HOST`, which defaults to `127.0.0.1` *inside the
container*, and `docker-compose.yml` publishes no host port for the commerce service at all (only
Caddy publishes, 80/443). A tunnel forwards to the **droplet host's** loopback, where nothing is
listening. Reach it by joining the container's network namespace instead:

```bash
ssh root@<domain>
cd /opt/tenshu/repo
COMPOSE="docker compose -f commerce-service/docker-compose.yml \
  -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env"

docker run --rm --network "container:$($COMPOSE ps -q commerce)" curlimages/curl -sS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Admin-Totp: $(oathtool --totp -b "$ADMIN_TOTP_SECRET")" \
  http://127.0.0.1:8082/admin/users
```

`--network container:` shares the running container's network namespace, so its `127.0.0.1` is the
one the console is bound to. (`$COMPOSE exec commerce curl …` works only if the runtime image
happens to carry curl — `eclipse-temurin:21-jre` is not guaranteed to.) To pull a file down, run the
whole thing through ssh and redirect locally:

```bash
ssh root@<domain> "cd /opt/tenshu/repo && docker run --rm --network container:\$($COMPOSE ps -q commerce) \
  curlimages/curl -sS -H '…' 'http://127.0.0.1:8082/admin/export/ledger.csv?from=2026-07-01&to=2026-09-30'" \
  > ledger.csv
```

⚠ This is stricter than a tunnel, deliberately, and it is *not* what protects the console — root on
that box can read `ADMIN_TOKEN` and `ADMIN_TOTP_SECRET` straight out of `/opt/tenshu/deploy.env`
(0600), so both factors are already in the hands of anyone who can reach either path. The real
perimeter is `ssh_allowed_cidrs` plus your SSH key. What the container-loopback bind *does* buy is
that the port is unreachable from the sibling containers on `commercenet` — Caddy being the
internet-facing process most likely to be compromised — and that no `ports:` line exists to be
mis-edited later into an internet exposure (a published Docker port installs its own DNAT rules that
the DigitalOcean cloud firewall's inbound list never sees).

⚠ A code works **once** — accepted codes are burned so a captured request cannot be replayed while
it is still valid. Generate a fresh one per request.

⚠ Both factors failing look identical from outside (one opaque `401`); the server log says which. If
you are locked out, read the log rather than guessing.

## Still deferred

- **WORM / S3 statutory archive** for `invoice`/`acceptance` (survives GDPR erasure, §5.2) — rides the
  ops/infra track, not this module.
- **OSS VAT registration/filing** — a process, not code (§5).
- **Blinded tokens** (`commerce-billing-plane.md` Phase 6) — removes the `ownerId↔account` correlation
  residual (A30); the verifier is unchanged.
