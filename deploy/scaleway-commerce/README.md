# Commerce on Scaleway

**This is production commerce** (`commerce.service.tenshu.app`, since 2026-09-14).
[`deploy/digitalocean-commerce/`](../digitalocean-commerce/) is the retired box it replaced. See
[`../scaleway-relay/README.md`](../scaleway-relay/README.md) for the five DigitalOcean→Scaleway
differences.

## ⚠⚠ A mistaken apply here is a billing outage, not a test failure

Commerce has been in production since 2026-08-07, on this module since the cutover. It was built
beside the old box, not converted in place. A future move repeats that: stand up → verify → move the
data ([`move-commerce-to-scaleway.md`](../../docs/how-to/move-commerce-to-scaleway.md)) → cut DNS → retire.

## ⚠⚠ Browser access is off until `WEB_APP_ORIGIN` names the apex

Commerce installs CORS only for `WEB_APP_ORIGIN`, and only for that one origin (threat model T-CB-09).
Unset, every call from `tenshu.app` (web sign-in, checkout, plan sync) dies in the CORS preflight: the
`OPTIONS` gets a `405` (the routes are POST-only) and the browser never sends the `POST`. That `405` is
what shows in the access log. Until 2026-10-30 the compose file did not forward the key at all, so
setting it in `deploy.env` changed nothing. Commerce refuses to start on a wildcard or malformed value.

To turn it on for a running box (`/opt/tenshu/repo` cannot `git pull`, so the compose file goes by hand):

1. Copy master's `commerce-service/docker-compose.yml` to `/opt/tenshu/repo/commerce-service/` (back up the old one).
2. ⚠ Check which of the 19 newly forwarded keys `deploy.env` already sets. Each of those lines used to
   be ignored and now reaches the container:

   ```bash
   grep -E '^(ACCOUNT_CLOSURE_SWEEP_MINUTES|ADMIN_BIND_HOST|ADMIN_PORT|ADMIN_TOTP_SECRET|CHECKOUT_CANCEL_URL|CHECKOUT_SUCCESS_URL|ENTITLEMENT_TTL_HOURS|OTP_MAX_ATTEMPTS|OTP_TTL_MINUTES|PORTAL_RETURN_URL|PUBLIC_BASE_URL|SESSION_TTL_HOURS|STRIPE_API_BASE|STRIPE_MODE|SUBSCRIPTION_CANCEL_SWEEP_MINUTES|SUBSCRIPTION_CANCEL_WINDOW_MS|TOP_TIER_PRODUCT_ID|UNVERIFIED_ACCOUNT_EXPIRE_SWEEP_MINUTES|WEB_APP_ORIGIN)=' /opt/tenshu/deploy.env
   ```

   - An `ADMIN_TOTP_SECRET` that is not valid base32, or `STRIPE_MODE=fake`, stops commerce from
     starting. That is loud, so you will notice.
   - ⚠⚠ `STRIPE_API_BASE` is the silent one: a leftover stub URL would send live-key Stripe calls
     elsewhere and still start. Delete any line you cannot account for.
3. Add `WEB_APP_ORIGIN=https://tenshu.app` to `/opt/tenshu/deploy.env`, and mirror it as `web_app_origin`
   in the gitignored `terraform.tfvars`.
4. Recreate commerce from `/opt/tenshu/repo`, with the override file (it carries the `db` dependency)
   and **without** `-p` or `--project-directory`: the project name `commerce-service` comes from the first
   `-f` path, and a different one forks the stack. Run it once with `--dry-run` first:

   ```bash
   cd /opt/tenshu/repo && docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env up -d --force-recreate commerce
   ```
5. Verify from anywhere. The preflight must answer with `access-control-allow-origin: https://tenshu.app`:

   ```bash
   curl -s -o /dev/null -D - -X OPTIONS https://commerce.service.tenshu.app/commerce/account/register -H 'Origin: https://tenshu.app' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type'
   ```

## ⭐ SMTP needs nothing — but there is one way to break it

`enable_default_security` on a Scaleway security group blocks **25, 465 and 587** by default.
**2587 is not on that list**, so Transactional Email over 2587 works from a Scaleway Instance
exactly as it did from the DO droplet. No ticket, no flag, no change to this module.

⚠⚠ **The trap is "tidying" `smtp_port` to 587.** Scaleway's own TEM docs recommend 587 / 465 / 2465
— and those are either blocked by default here, or need implicit TLS that `SmtpEmailSender` does not
do (it sets `starttls.required`). So the port that looks most correct *by the vendor's own
documentation* is the one that silently fails on Scaleway while continuing to work on DigitalOcean.

⚠ 2587 was originally picked to dodge **DigitalOcean's** block of 25/465/587. That it also dodges
Scaleway's identical block is luck rather than design — but it is why this migration needs nothing.

Still worth one check on the box before debugging the app, because a blocked port surfaces as a
~15 s connect timeout that becomes a **500 on `/commerce/account/register`** — which reads as "OTP is
broken" rather than "the port is shut":

```bash
nc -vz smtp.tem.scaleway.com 2587
```

## ⚠ Swap, disk and monitoring

The swapfile is on the **root** disk and sized by `swapfile_size_gb` (1 GB with a prebuilt image, 4 GB
for an on-box build). A fixed 4 GB one filled commerce's 8.9 GB root disk in October 2026 and silently
stopped its backups for 23 days. Disk and backups report to a dead man's switch, installed by hand
after every (re)build: [`docs/how-to/box-health.md`](../../docs/how-to/box-health.md).

## ⚠ The database is unchanged, deliberately

`database_url` still defaults to the bundled Postgres container. Scaleway offers Managed Databases
and pointing this at one would be a genuine improvement over a container sharing the box — but that
is a *different* decision from changing provider, and doing both at once makes a failed deploy
ambiguous between them.

## ⚠ Copy the secrets, do not retype them

Commerce carries ~20 provider-independent settings (issuer keys, Stripe, admin, quotas). Copy them
from your existing `deploy/digitalocean-commerce/terraform.tfvars`. Retyping secrets is how one gets
mistyped.

## ⚠ `:22` is open to the whole internet, and there is no CA

`ssh_allowed_cidrs` defaults to `["0.0.0.0/0"]` in this module and in its two siblings, nothing here
touches `sshd_config`, and there is no SSH certificate authority anywhere in `deploy/`. Access is
long-lived Project keys reachable from any address.

This is **not** something to change casually from a runbook step — Scaleway injects Project SSH keys
at **first boot only**, so a mistake here is recovered by rebuild or console VNC, not by another
`apply`. It is written up, with the options and their lock-out risks, in
[`docs/tech-debt.md`](../../docs/tech-debt.md) → *"Every production box exposes `:22` to the whole
internet"*.

⭐ The pattern worth copying when this is addressed is the dev lab's (`deploy/scaleway-dev/`,
`docs/how-to/deploy-dev-lab-scaleway.md` §3b): an optional
`ssh_ca_public_key` that cloud-init installs as `TrustedUserCAKeys`, so short-lived certificates
replace long-lived keys without an overlay network or a third party.
