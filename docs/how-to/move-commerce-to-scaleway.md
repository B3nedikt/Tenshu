# Moving commerce to Scaleway

Commerce is the hardest of the three remaining moves, and not because of its data — that is 8 MB.
It is hard because it holds **secrets other services verify against**, and because it is the only
service whose failure is invisible until a user tries to pay or a device tries to write.

Survey taken 2026-09-14 against production (`commerce.service.tenshu.app`, DigitalOcean).
⭐ The move ran the same day — production has been on Scaleway (`51.15.229.94`) since 15:38Z. The
steps below are as run, including three the survey did not predict (image, certificate, old timer).

---

## What is actually there

| | |
|---|---|
| image | `ghcr.io/b3nedikt/tenshu-commerce:2757b533` |
| store | **PostgreSQL 16.14** (`postgres:16`), db `commerce`, user `commerce` |
| size | **8 071 kB** — 12 tables |
| rows | acceptance 6 · schema_version 6 · account 2 · redemption_code 2 · account_owner 1 · email_verification 1 · session 1 · entitlement_history 1 · invoice 0 · stripe_event 0 · credit_note 0 · marketing_consent 0 |
| volume | `/mnt/commerce_data/pgdata`, 9.8 G, 1 % used |
| backups | ⚠⚠ **none** — no cron, no systemd timer, no dumps on disk |

⚠⚠ **Take a dump before touching anything.** Not as a migration step — as the first backup this
service has ever had. Postgres is 16.14, so the target must be **≥ 16**; a 15 will refuse the dump.

```bash
ssh root@<commerce> 'docker exec commerce-service-db-1 pg_dump -U commerce -Fc commerce' > commerce-$(date -u +%Y%m%dT%H%M%SZ).dump
```

## ⚠⚠ The constraint that breaks everything else

The relay verifies entitlements against a public key **derived from commerce's `ISSUER_SEED`**.
Verified on 2026-09-14:

```
commerce logs on boot:  "Commerce issuer public key (set relay ENTITLEMENT_ISSUER_PUBKEY to this): …"
sha256 of that value             = 19f87eb3e757cd4e
sha256 of the relay's configured ENTITLEMENT_ISSUER_PUBKEY = 19f87eb3e757cd4e   ✅ identical
```

⇒ **`ISSUER_SEED` must move byte-identically.** Regenerate it — or let the module default it — and
the derived public key changes, the relay rejects every entitlement it is shown, and **every write in
the realm starts failing** with `402 entitlement_required`. That failure does not look like a
commerce problem from any client; it looks like the relay refusing writes.

⭐ `ISSUER_ID` (`tenshu.commerce`) and `SIGNING_KEY_ID` must also match. Both `SIGNING_KEY_ID` and the
relay's `ENTITLEMENT_ISSUER_KEY_ID` are currently **empty**, which is consistent — no `kid` rotation is
in use. Keep them empty or change both together.

## The grace window is 72 hours, not minutes

`TOKEN_TTL_HOURS=72`. A device holding a live entitlement token keeps writing for up to three days
without commerce being reachable at all. So commerce downtime is **not** a realm outage, and this
cutover needs no rushed window — unlike the relay, where the database was the live sync log.

What does break while commerce is down: new sign-ups, sign-ins, checkout, and any device whose token
has expired.

## Secrets that must carry over

`/opt/tenshu/deploy.env` on the box; `deploy/scaleway-commerce` expects each as a tfvar (41 variables,
most of them these):

| must be identical | why |
|---|---|
| `ISSUER_SEED` | ⚠⚠ above — the relay's trust anchor |
| `ISSUER_ID`, `SIGNING_KEY_ID` | must agree with the relay's issuer/kid |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_IDS` | live billing; a webhook secret mismatch silently drops events |
| `POSTGRES_PASSWORD` / `DATABASE_URL` | must match the restored database's role |
| `REDEMPTION_CODES` | outstanding codes stop working if regenerated |
| `ADMIN_TOKEN`, `ADMIN_TOTP_SECRET` | admin access |
| `SMTP_*` | verification and receipt mail |

## Stripe

The webhook endpoint is **domain-based** (`commerce.service.tenshu.app`), so it follows the DNS flip
with no change at Stripe. ⭐ `stripe_event` is currently **0 rows**, so there is no delivery history
to preserve — but confirm that is still true on the day, and re-check Stripe's dashboard for failed
deliveries afterwards rather than assuming the cutover was transparent.

## ⚠⚠ The one correct `docker compose` invocation

Every stop/start below uses this, on both boxes, and **nothing shorter**:

```bash
DC='cd /opt/tenshu/repo && docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env'
```

It is what production runs (the container's `com.docker.compose.project.config_files` label names the
compose file *and* the override) and what `deploy/scaleway-commerce`'s cloud-init runs (its `COMPOSE=`
line). Measured on production 2026-09-14 with the read-only `docker compose … config`:

| invocation | renders | `ISSUER_SEED` | `db` service |
|---|---|---|---|
| `cd /opt/tenshu`, compose file only (this runbook's earlier text) | ❌ fails | — | — |
| path corrected, **no** override, **no** `--env-file` | ✅ | **empty** | **absent** |
| `$DC` | ✅ | set | present |

⚠⚠ **The middle row is the trap: it looks like the obvious fix and renders cleanly.** Without
`--env-file` the seed, `DATABASE_URL` and `SMTP_PASSWORD` resolve to nothing; without the override the
database service disappears. `up -d commerce` then sees a changed config and **recreates commerce with
no seed** — which, with `DEV_MODE=false`, fails validation and crashloops. A runbook command that takes
billing down; an earlier version of this file shipped one.

⭐ Before any `up`, check the rendered model rather than trusting the line — **both** halves:

```bash
ssh root@<box> "$DC config" | grep -qE '^\s+ISSUER_SEED: .{20,}' &&
  ssh root@<box> "$DC config --services" | grep -qx db && echo OK || echo 'STOP: wrong invocation'
```

⚠ The seed alone is not enough. Measured against production: an invocation with `--env-file` but
**no override** renders the seed and passes a seed-only check — with the `db` service gone. Four
wrong variants fail this pair (the two table rows above, env-file-without-override and
override-without-env-file); only `$DC` passes.

---

## The move

1. **Rehearsal dump** (above), and verify it restores into a scratch Postgres 16 *before* relying on
   the procedure. This one is practice — it will be stale by the time you cut over.
2. **Put every secret into `terraform.tfvars` first**, `ISSUER_SEED` verbatim.
   ⚠⚠ **Set `commerce_image_tag` to production's tag** (`2757b533` on 2026-09-14). Its default is
   empty, which means *build master on the box* — so leaving it blank silently turns the move into an
   upgrade to whatever master is that day.
   ⚠ `git_token` is required: the repository refuses anonymous clones. A **fine-grained** token on
   this repository with *Contents: Read-only* is enough.
   ⚠⚠ **The pinned image is not in GHCR.** `ghcr.io/b3nedikt/tenshu-commerce:2757b533` exists only on
   the old box — it was loaded there by `docker save | ssh docker load`, a path that never pushed.
   So cloud-init's `compose pull commerce` fails with `not found` and the bootstrap stops after the
   clone, with nothing running, **whatever `ghcr_token` holds**. Step 3a loads the image instead. If
   you did set `ghcr_token`, run `ssh root@<new> 'docker logout ghcr.io'` afterwards so no registry
   credential is left stored on the box — a classic token is often scoped far wider than
   `read:packages`.
   ⚠⚠ The module renders these into `/opt/tenshu/deploy.env` through **cloud-init, during
   `terraform apply`** — there is no later step that injects them. And with `dev_mode = false` and a
   blank `issuer_seed`, commerce fails validation on first boot, so applying "to see if it comes up"
   and filling secrets afterwards does not work.
3. `terraform apply` in `deploy/scaleway-commerce` — it provisions the box and a block volume for
   `pgdata`. ⚠ `manage_dns` stays `false`: Scaleway does not serve this zone.
   Then check what cloud-init actually did — its failure is silent from the outside:
   ```bash
   ssh root@<new> 'cloud-init status --long; findmnt /mnt/commerce_data'
   # ⚠⚠ an empty findmnt = Postgres would write to the root disk, which a rebuild destroys
   ssh root@<new> 'tail -n 30 /var/log/tenshu-bootstrap.log'   # expect the pull to fail — see step 2
   ```
3a. **Load the image from the old box** — the only place it exists:
   ```bash
   ssh root@<old> 'docker save ghcr.io/b3nedikt/tenshu-commerce:2757b533 | gzip -1' \
     | ssh root@<new> 'gunzip | docker load'
   for h in <old> <new>; do
     ssh root@$h "docker image inspect ghcr.io/b3nedikt/tenshu-commerce:2757b533 --format '{{.Id}}'"
   done   # the two IDs must be identical
   ```
3b. ⚠⚠ **Seed the TLS certificate before Caddy first starts.** While DNS still points at the old box,
   every HTTP-01 attempt from the new box fails, each failure counts toward Let's Encrypt's
   failed-validation limit, and certmagic backs off between retries — so a Caddy that has been trying
   is not ready when DNS moves, and the first minutes after the flip are a hard TLS failure on an
   HSTS-preloaded `.app` name. Copy the production certificate box to box instead (the private key
   never touches a workstation), into the volume, before anything starts:
   ```bash
   DC='cd /opt/tenshu/repo && docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env'
   C=caddy/certificates/acme-v02.api.letsencrypt.org-directory/commerce.service.tenshu.app
   ssh root@<new> "$DC create caddy"   # creates the container AND commerce-service_caddy_data; starts nothing
   ssh root@<old> "docker exec commerce-service-caddy-1 tar -C /data -cf - $C" \
     | ssh root@<new> 'docker run --rm -i -v commerce-service_caddy_data:/data caddy:2 tar -C /data -xf -'
   # identical on both ends — hashes only
   ssh root@<old> "docker exec commerce-service-caddy-1 sh -c 'sha256sum /data/$C/*.crt'"
   ssh root@<new> "docker run --rm -v commerce-service_caddy_data:/data caddy:2 sh -c 'sha256sum /data/$C/*.crt'"
   ```
   ⚠ If Caddy is already running on the new box (a pull that *did* succeed brings the whole stack up),
   `docker stop commerce-service-caddy-1` first, seed, then `docker start` it.
   Run the `$DC config` gate from *The one correct invocation* above, then `ssh root@<new> "$DC up -d"`,
   and prove the seed was picked up — without touching DNS:
   ```bash
   curl -sS -o /dev/null -w 'http=%{http_code} verify=%{ssl_verify_result}\n' \
     --resolve commerce.service.tenshu.app:443:<new-ip> https://commerce.service.tenshu.app/health   # 200, verify=0
   for h in <old-ip> <new-ip>; do
     echo | openssl s_client -connect $h:443 -servername commerce.service.tenshu.app 2>/dev/null | openssl x509 -noout -serial
   done   # serials identical
   ssh root@<new> "docker logs commerce-service-caddy-1 2>&1 | grep -cE '\"(obtaining|renewing) certificate\"'"   # 0
   ```
4. ⚠⚠ **Quiesce the source, then take the dump you will actually restore.** Everything between the
   dump and DNS convergence — a registration, a redemption, a session, a Stripe webhook — exists
   only on the old database, and **nothing merges it back**. Keeping the old box afterwards does not
   help: it holds writes the new one has never seen.
   ```bash
   # ⚠ DC again, on purpose: this runs after `terraform apply`, usually in a new shell. An unset DC
   #   sends a bare ` stop commerce` (exit 127), and a dump taken after it is not quiesced.
   DC='cd /opt/tenshu/repo && docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env'
   # stop commerce but leave postgres up. The dump runs ONLY if the stop worked AND the container is down.
   # The dump goes box to box — account emails and sessions never land on a workstation.
   # ⚠ pipefail: without it a failed pg_dump piped into a successful `cat` reads as success.
   set -o pipefail
   TS=$(date -u +%Y%m%dT%H%M%SZ)
   ssh root@<old> "$DC stop commerce" &&
     ssh root@<old> '[ -z "$(docker ps -q --filter name=^commerce-service-commerce-1$ --filter status=running)" ]' &&
     ssh root@<old> 'docker exec commerce-service-db-1 pg_dump -U commerce -Fc commerce' \
       | ssh root@<new> "umask 077; cat > /root/final-$TS.dump" &&
     ssh root@<new> "test -s /root/final-$TS.dump" ||
     echo 'STOP: commerce not confirmed stopped, or the dump failed or is empty — do not restore' >&2
   # and leave commerce stopped until DNS has moved
   ```
   ⚠⚠ **Disable the old box's backup timer now.** Otherwise it keeps uploading dumps of the frozen
   database every night into the **same bucket prefix** as the new box, under the same key pattern —
   and the newest-looking dump can be the stale one. Re-enable it only if you roll back.
   ```bash
   ssh root@<old> 'systemctl disable --now tenshu-commerce-backup.timer'
   ```
   ⭐ The 72-hour token TTL is what makes this affordable: devices keep writing to the relay
   throughout, so "commerce stopped" is not a realm outage.
5. **Restore through the container** — Postgres is an internal-only Compose service on the target and
   the host has no `pg_restore`:
   ```bash
   DC='cd /opt/tenshu/repo && docker compose -f commerce-service/docker-compose.yml -f /opt/tenshu/commerce.override.yml --env-file /opt/tenshu/deploy.env'
   # ⚠ restore only under a stopped service: --clean drops objects a running commerce may be using
   # ⚠ TS is step 4's timestamp — in a new shell, set it again from `ssh root@<new> 'ls /root/final-*.dump'`
   restored=
   [ -n "$TS" ] && ssh root@<new> "test -s /root/final-$TS.dump" &&
     ssh root@<new> "$DC stop commerce" &&
     ssh root@<new> '[ -z "$(docker ps -q --filter name=^commerce-service-commerce-1$ --filter status=running)" ]' &&
     ssh root@<new> "docker exec -i commerce-service-db-1 pg_restore -U commerce -d commerce --clean --if-exists < /root/final-$TS.dump" &&
     restored=1
   # ⚠⚠ the start is INSIDE the check, and the check includes the restore: pasted as separate lines, a
   #    failed step does not stop the next one. ⚠ pg_restore exits non-zero on ANY error, even one it
   #    reports as ignored — read its output before overriding this.
   if [ "$restored" = 1 ] &&
      ssh root@<new> "$DC config" | grep -qE '^\s+ISSUER_SEED: .{20,}' &&
      ssh root@<new> "$DC config --services" | grep -qx db; then
     ssh root@<new> "$DC up -d commerce"
   else
     echo 'STOP: restore not confirmed, or no seed / no db service in the rendered config — commerce NOT started' >&2
   fi
   ```
6. ⚠⚠ **Verify the issuer before any DNS change**, because this is the failure with no other symptom:
   ```bash
   ssh root@<new-box> 'docker logs commerce-service-commerce-1 2>&1 | grep "issuer public key"'
   # sha256 of that value MUST equal the relay's ENTITLEMENT_ISSUER_PUBKEY sha256
   ```
7. Compare row counts against the table above — `account`, `session`, `acceptance`, `redemption_code`
   are the ones that matter.
8. Flip DNS (`doctl`, while the zone is at DigitalOcean — same as the relay and apex).
   ⚠ Then re-run `scripts/dns-export.sh` and commit `deploy/dns/records.json`: the inventory the
   Cloudflare zone is built from still carries the **old** IP until you do, and applying it would move
   commerce back ([`move-dns-to-cloudflare.md`](move-dns-to-cloudflare.md)).
9. Verify: `/health`, a sign-in, and a write from a device whose token has expired (the only path that
   actually exercises the issuer end to end).
10. ⚠ Keep the old box until the new one has served real sign-ins. **Do not wait for a Stripe webhook**:
    Stripe is not configured in production (`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` and
    `STRIPE_PRICE_IDS` all empty, `stripe_event` 0 rows on 2026-09-14), so none will ever arrive. When it
    is configured, its endpoint is registered by hostname
    (`https://commerce.service.tenshu.app/commerce/webhook/stripe`) and follows the DNS flip.
11. ⚠⚠ **Reinstall the nightly backup on the new box.** `deploy/scaleway-commerce` cannot express the
    `BACKUP_S3_*` settings and does not install the script or its timer, so a box built from it has
    **no backups** until [`back-up-the-commerce-database.md`](back-up-the-commerce-database.md)'s
    install step is run there. ⚠ That starts by **carrying the five `BACKUP_S3_*` lines over from the
    old box's `deploy.env`**, since nothing on the new box writes them, and its enable is gated on
    them. Verify with `systemctl list-timers tenshu-commerce-backup.timer` and the log of one manual run.

### As run, 2026-09-14

| | |
|---|---|
| old commerce stopped | 15:37:33Z |
| DNS flipped | 15:38:27Z — 1.1.1.1, 8.8.8.8 and 9.9.9.9 all answered the new IP by 15:39:22Z |
| dump | 17,151 bytes, box to box; all 12 tables with identical row counts after restore |
| issuer | sha256 `19f87eb3e757cd4e` on the new box, equal to the relay's |
| certificate | seeded serial `068B4732…` served by both boxes, zero ACME orders |
| first backup | `commerce-20260914T153902Z.dump.gz`, 3,973 bytes; old box's timer disabled |

## Still open

- ⚠⚠ **No published image for the running tag.** `2757b533` exists on the two boxes and nowhere else,
  so a rebuild from `deploy/scaleway-commerce` fails at the pull exactly as the cutover did — until
  an image is pushed to GHCR, step 3a (from a box that has it) is the only route.
- ⚠ The data volume is mounted by **device name** (`/dev/sdb`) in `/etc/fstab`, not by filesystem
  UUID — the same as the relay module. SCSI names can reorder across reboots with more disks attached.
- Rollback is DNS back to the old box plus `$DC start commerce` there, and it only makes sense before
  the new box has taken writes: anything written after the flip exists only on the new database.
