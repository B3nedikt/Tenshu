# Sync relay on Scaleway

**This is the production relay** (`relay.tenshu.app`). [`deploy/digitalocean/`](../digitalocean/) is
the box it replaced and is being retired. ⚠⚠ **Never `terraform apply` that module:** its state still
owns the `relay` A record at the old reserved IP, so an apply "corrects" production DNS back to the dead
box. Tear it down with `terraform state rm` on the record and zone first (see its runbook), then destroy.

## ⚠⚠ It was built beside the old relay, not converted in place — keep it that way next time

Converting a live module in place would orphan its state and leave a `terraform apply` aimed at
production with a provider that no longer matches it. The cutover ran in this order, and a future
move should too:

1. stand up the new relay as a **separate** Terraform root with **its own state** and a **temporary**
   hostname (`relay-scw.…` was this move's). ⚠⚠ Not `apply` in this directory: its state is the
   production instance, so an apply here changes production instead of creating a candidate, and its
   `terraform.tfvars.example` now carries the production hostname for rebuilds. Copy the module to a
   new directory, or target a new backend or workspace, then `apply` that.
2. verify (below)
3. ⚠⚠ **move the data** — [`docs/how-to/move-the-relay-to-another-provider.md`](../../docs/how-to/move-the-relay-to-another-provider.md)
4. **then** cut DNS over, and only then destroy the old one

⚠⚠ **Step 3 is not optional.** Steps 1, 2 and 4 are the whole story for
**apex**, which is stateless. The relay is the durable home of the sync log and every content blob, so
applying → verifying → cutting DNS points every client at an **empty realm**, and destroying the old
box then makes that permanent. Verified on this instance, which answered
`{"status":"ok","durable":true}` for five days holding zero rows and zero blobs.

## Use

```bash
cp terraform.tfvars.example terraform.tfvars   # git-ignored; fill in
terraform init
terraform apply
terraform output verify_hint
```

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

## ⚠⚠ What is different from DigitalOcean, and will bite

**`user_data` is a map, not a string.** Scaleway takes cloud-init under a `cloud-init` key;
DigitalOcean takes the document directly. Passing a bare string here is the nastiest failure in this
module because it does **not** error — the box boots clean and nothing is installed.

**Security groups default to ACCEPT.** `inbound_default_policy = "drop"` is set explicitly. Omit it
and you get a box with every port open and no error anywhere.

**`iops` is required on the volume**, and its accepted values are tier-dependent — a rejected number
fails at *create*, not at plan. DigitalOcean has no such knob.

**⚠⚠ SSH keys come from the PROJECT, at first boot only.** `scaleway_instance_server` has no
SSH-key argument — Scaleway injects every key registered on the Project via the metadata service
when the instance first boots. So:

- register the key **before** `terraform apply`, in the console (Project → SSH Keys)
- adding it afterwards does **not** reach a running instance — you get
  `Permission denied (publickey)` on a box that is otherwise perfectly healthy
- recover by rebuilding (cheap for a stateless service) or through the console's **VNC**, which
  needs no SSH and is also how you read `/var/log/cloud-init-output.log` when SSH is the thing that
  is broken

⚠ This one bit on the first real deploy. The module previously had an `ssh_key_ids` variable that
nothing referenced — it looked like the knob and was not.

**The instance and its volume must share a zone.** A volume in `fr-par-2` will not attach to a
server in `fr-par-1`.

**DNS is off, and stays off.** `scaleway_domain_record` only works if the zone is *hosted* at Scaleway,
and `tenshu.app` moves from DigitalOcean to Cloudflare DNS instead
([`move-dns-to-cloudflare.md`](../../docs/how-to/move-dns-to-cloudflare.md)). Leave
`manage_dns = false` and create the A record where the zone is served — DigitalOcean until the move,
[`deploy/dns/records.json`](../dns/records.json) after it.

## ⚠⚠ Verify in this order — a green health check is not enough

```bash
ssh root@<ip> 'cloud-init status --long'      # 1. bootstrap finished
ssh root@<ip> 'findmnt /mnt/relay_data'       # 2. the DURABLE volume is mounted
curl -fsS https://<domain>/health             # 3. the relay answers
```

⚠⚠ **Those three pass on a relay holding nothing**, which is exactly what this box did for five days.
`durable:true` means *a database is configured and its volume is writable* — not that your data is
there. Before a cutover, verify the checks in the migration runbook instead: row counts against the
source, blob **file count** as well as size, `restarts=0` (the relay refuses to boot with in-memory
content stores, so a clean start is evidence), and the `426` gate matching production.

⚠ Two things that bit this box and are not in any `terraform plan`: the **root volume is 8.9 G**
against DigitalOcean's 24 G and filled to 100 %, failing an image pull with
`no space left on device` while the 46 G data volume sat at 1 %; and **`sqlite3` is not installed**,
so verification commands return `command not found`, which reads as an empty schema if stderr is
discarded.

Step 2 is the one that matters and the one that is easy to skip. The cloud-init **warns and carries
on** if it cannot find the data volume, putting relay state on the root disk — where an instance
rebuild silently destroys it. A relay that answers `/health` from the wrong disk looks perfectly
healthy right up until it does not.

⚠ Volume detection is now **provider-neutral**: it derives the root disk and takes the first disk
that is not it, rather than reading DigitalOcean's `scsi-0DO_Volume_…` by-id symlink. That change is
in this module's copy of the cloud-init only; the DO module still has the original.

## ⚠⚠ Rebuilding this instance is an outage procedure, not an `apply`

cloud-init writes `/opt/tenshu/deploy.env` from this module's variables, which cover nine keys, and
**starts the relay on the reused IP as soon as it boots**. The live file carries more, added by hand,
that the module cannot express — eleven lines at the last count: the content plane (`S3_ENDPOINT`,
`S3_BUCKET`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`), the nightly backup (`BACKUP_S3_*`, five, and `BACKUP_DR_S3_*`, three),
the mirrors (`BLOB_MIRROR_BUCKET`, `BLOB_MIRROR_DR_*`, `MIRROR_S3_*`, `MIRROR_STORAGE_CLASS`, and
`MIRROR_PRUNE` / `MIRROR_DR_PRUNE` / `MIRROR_RETENTION_DAYS`, if set), plus
any optional relay setting added since (`S3_KEY_PREFIX`, for one).
cloud-init also does not install the backup and mirror scripts or their timers
([`back-up-the-relay-database.md`](../../docs/how-to/back-up-the-relay-database.md) does, by hand).

A replacement therefore comes up healthy on the **empty volume** instead of the bucket, so every client
sees an empty realm, and it has no backups. ⚠ There is no window to copy anything in first. There is no
`create_before_destroy`, and the IP and volume attach to one server, so the old instance is gone before
the new one exists. Until the module can express this (`docs/tech-debt.md`, *"A rebuilt relay instance
serves an empty realm"*), a rebuild is a maintenance window:

1. **Before** triggering it, copy **every line cloud-init does not render** box to box to a host that
   survives the rebuild. ⚠ Not a fixed list: optional settings such as `S3_KEY_PREFIX` (which the
   relay's compose passes to the blob store) change where existing blobs are read, and a list that
   omits one points the rebuilt relay at the wrong namespace. They exist nowhere else.
   ```bash
   # ⚠⚠ pipefail: without it the pipeline reports the SURVIVOR's status, so a failed read on the box
   #    still "succeeds" and leaves an empty copy. The survivor writes a temp file and replaces the
   #    saved copy only if it holds the content plane and the backup settings.
   set -o pipefail
   RENDERED='^(ACCOUNT_CLOSURE_RECLAIM|ACME_EMAIL|CORS_ALLOWED_ORIGINS|ENTITLEMENT_ENFORCE|ENTITLEMENT_ISSUER_KEY_ID|ENTITLEMENT_ISSUER_PUBKEY|GENESIS_RATE_LIMIT_PER_HOUR|RELAY_DOMAIN|RELAY_IMAGE_TAG)='
   ssh root@<box> "set -o pipefail; grep -E '^[A-Z][A-Z0-9_]*=' /opt/tenshu/deploy.env | grep -v -E '$RENDERED'" \
     | ssh root@<survivor> 'umask 077; f=/root/relay-env-extra; cat > $f.tmp &&
         grep -q "^S3_BUCKET=." $f.tmp && grep -q "^BACKUP_S3_BUCKET=." $f.tmp &&
         mv $f.tmp $f && wc -l < $f' \
     || echo 'STOP: env not preserved, or it lacks S3_BUCKET / BACKUP_S3_BUCKET. Do NOT rebuild.' >&2
   ```
   ⚠ `RENDERED` mirrors the keys `cloud-init.yaml.tftpl` writes; update both together.
2. Wait for cloud-init to **finish**, then stop the relay. ⚠ Not "as soon as SSH answers": SSH is up
   before cloud-init reaches `compose up`, so an early stop finds no container, and the relay starts
   afterwards anyway.
   `ssh root@<box> 'cloud-init status --wait; cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env stop relay'`
3. Restore the lines, and **verify them before anything else runs**. The append is skipped if they
   are already there, and a missing or incomplete copy stops here rather than recreating the relay
   without its bucket:
   ```bash
   set -o pipefail
   ssh root@<survivor> 'set -e; grep -q "^S3_BUCKET=." /root/relay-env-extra; cat /root/relay-env-extra' \
     | ssh root@<box> 'grep -q "^S3_BUCKET=" /opt/tenshu/deploy.env || cat >> /opt/tenshu/deploy.env
         grep -q "^S3_BUCKET=." /opt/tenshu/deploy.env && grep -q "^BACKUP_S3_BUCKET=." /opt/tenshu/deploy.env' \
     && echo 'restored' || echo 'STOP: S3_* / BACKUP_S3_* not in the new deploy.env. Do NOT recreate the relay.' >&2
   ```
   Then reinstall both jobs:
   - the backup, per [`back-up-the-relay-database.md`](../../docs/how-to/back-up-the-relay-database.md)'s
     install step;
   - the mirrors: copy `deploy/scaleway-relay-backups/mirror-blobs.py` to `/opt/tenshu/`, and
     `tenshu-blob-mirror{,-dr}.{service,timer}` from this directory to `/etc/systemd/system/`, then
     `systemctl daemon-reload && systemctl enable --now tenshu-blob-mirror.timer tenshu-blob-mirror-dr.timer`;
   - box health (disk check + the backup's ping): `deploy/box-health/install.sh root@<box> relay`, per
     [`box-health.md`](../../docs/how-to/box-health.md). cloud-init does not install it either.

   Then **recreate** the relay, don't just start it. ⚠⚠ `docker compose start` reuses the container
   cloud-init created from the nine-key env, so it would serve the empty volume again:
   `ssh root@<box> 'cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml --env-file /opt/tenshu/deploy.env up -d --force-recreate relay'`.
   Check that `docker inspect` shows `S3_*`, and that `systemctl list-timers 'tenshu-*'` lists all
   three timers.

Keep `relay_image_tag`, the `entitlement_*` variables and `account_closure_reclaim` in
`terraform.tfvars` current, so that cloud-init's nine keys are at least right. ⚠⚠ Production runs
`account_closure_reclaim = "true"`: a rebuild from a tfvars that omits it comes up
with reclamation silently **off**, and closed accounts' content is then kept indefinitely.

## ⚠ What `terraform validate` does not tell you

It checks schema against the provider. It does **not** check that `ubuntu_jammy` is the right image
label, that `DEV1-M` exists in your zone, that the volume attaches, or that cloud-init succeeds.
This module runs production, and its first apply did surface failures of exactly that kind. Each is
now a plan-time guard at the top of `main.tf`. ⚠ A rebuild can still hit what a guard cannot see at
plan time, such as the instance type's zone availability or the volume's `iops` tier.
